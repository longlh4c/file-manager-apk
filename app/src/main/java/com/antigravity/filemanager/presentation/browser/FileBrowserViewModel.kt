package com.antigravity.filemanager.presentation.browser

import android.content.Context
import kotlinx.coroutines.flow.update
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.filemanager.domain.model.CloudAccount
import com.antigravity.filemanager.domain.model.CloudTransferProgress
import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.domain.model.FileSortOption
import com.antigravity.filemanager.domain.usecase.CloudStorageUseCase
import com.antigravity.filemanager.domain.usecase.FileOperationsUseCase
import com.antigravity.filemanager.domain.usecase.GlobalClipboardManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.antigravity.filemanager.utils.observeDirectoryChanges
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import javax.inject.Inject

data class FileBrowserUiState(
    val currentPath: String = "",
    val rootBoundaryPath: String = "",
    val categoryType: com.antigravity.filemanager.domain.model.CategoryType = com.antigravity.filemanager.domain.model.CategoryType.MAIN_STORAGE,
    val title: String = "Main storage",
    val isLoading: Boolean = false,
    val files: List<FileItem> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val clipboardPaths: List<String> = emptyList(),
    val isCutOperation: Boolean = false,
    val clipboardSourceCloudAccountId: String? = null,
    val clipboardItemSizes: Map<String, Long> = emptyMap(),
    val clipboardItemIsDirectory: Map<String, Boolean> = emptyMap(),
    val showHiddenFiles: Boolean = false,
    val sortOption: FileSortOption = FileSortOption.BY_NAME_ASC,
    val showNewFolderDialog: Boolean = false,
    val showRenameDialog: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val showPropertiesDialog: Boolean = false,
    val showCompressDialog: Boolean = false,
    val pendingOverwriteZipPath: String? = null,
    val pendingPasswordArchive: String? = null,
    val passwordError: String? = null,
    val showCloudDestinationDialog: Boolean = false,
    val isCloudMoveOperation: Boolean = false,
    val cloudAccounts: List<CloudAccount> = emptyList(),
    val cloudFolderPickerAccount: CloudAccount? = null,
    val cloudFolderPickerPath: String = "/",
    val cloudFolderPickerSegments: List<String> = listOf("Root"),
    val cloudFolderPickerFolders: List<FileItem> = emptyList(),
    val cloudFolderPickerLoading: Boolean = false,
    val showLocalFolderPicker: Boolean = false,
    val localFolderPickerPath: String = "",
    val localFolderPickerSegments: List<String> = listOf("Root"),
    val localFolderPickerFolders: List<FileItem> = emptyList(),
    val localFolderPickerLoading: Boolean = false,
    val itemToRename: FileItem? = null,
    val itemForProperties: FileItem? = null,
    // Same idea as CloudExplorerViewModel's — one or many items (files and/or folders), "Size"
    // always the real total byte count including recursively-summed folder contents, computed in
    // the background so a folder with a lot inside it doesn't block the dialog from opening.
    val propertiesItems: List<FileItem> = emptyList(),
    val propertiesTotalSize: Long = 0L,
    val propertiesIsComputing: Boolean = false,
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    // See the matching fields in CloudExplorerUiState — recursive search results (this folder +
    // every subfolder underneath it), populated by onSearchQueryChanged.
    val searchResults: List<FileItem> = emptyList(),
    val isSearching: Boolean = false,
    val toastMessage: String? = null,
    val bookmarks: List<com.antigravity.filemanager.domain.model.Bookmark> = emptyList(),
    val bookmarkConfirmationMessage: String? = null,
    val overwriteConflicts: List<com.antigravity.filemanager.domain.model.OverwriteConflict> = emptyList(),
    val downloadProgress: CloudTransferProgress? = null,
    val storageUsedPercent: Int? = null,
    val viewMode: com.antigravity.filemanager.presentation.components.ViewMode = com.antigravity.filemanager.presentation.components.ViewMode.LIST,
    // See the matching field in CloudExplorerUiState for why this exists.
    val transferCancelledByUser: Boolean = false,
    val shouldNavigateBackOnUsbDisconnect: Boolean = false
)

@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileOperationsUseCase: FileOperationsUseCase,
    private val cloudStorageUseCase: CloudStorageUseCase,
    private val globalClipboardManager: GlobalClipboardManager,
    private val folderPreferencesRepository: com.antigravity.filemanager.data.repository.FolderPreferencesRepository,
    private val bookmarkUseCase: com.antigravity.filemanager.domain.usecase.BookmarkUseCase,
    private val getDashboardDataUseCase: com.antigravity.filemanager.domain.usecase.GetDashboardDataUseCase,
    private val folderCacheManager: com.antigravity.filemanager.data.local.cache.FolderCacheManager,
    private val transferGuard: com.antigravity.filemanager.data.service.TransferGuard,
    private val usbOtgManager: com.antigravity.filemanager.utils.UsbOtgManager,
    private val mediaChangeSignal: com.antigravity.filemanager.data.local.observer.MediaChangeSignal,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val LOCAL_CACHE_FRESH_TTL_MS = 15_000L
    }

    private val initialPath: String = savedStateHandle.get<String>("path") ?: android.os.Environment.getExternalStorageDirectory().absolutePath
    private val initialTitle: String = savedStateHandle.get<String>("title") ?: "Main storage"
    private val determinedCategory: com.antigravity.filemanager.domain.model.CategoryType =
        if (initialTitle.equals("Download", ignoreCase = true) || initialTitle.equals("Downloads", ignoreCase = true) || initialPath.endsWith("Download")) {
            com.antigravity.filemanager.domain.model.CategoryType.DOWNLOADS
        } else if (initialTitle.contains("USB", ignoreCase = true) || (initialPath.startsWith("/storage/") && !initialPath.startsWith("/storage/emulated"))) {
            com.antigravity.filemanager.domain.model.CategoryType.USB_OTG
        } else {
            com.antigravity.filemanager.domain.model.CategoryType.MAIN_STORAGE
        }

    private val _uiState = MutableStateFlow(
        FileBrowserUiState(
            currentPath = initialPath,
            rootBoundaryPath = initialPath,
            categoryType = determinedCategory,
            title = initialTitle
        )
    )
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()


    init {
        loadDirectory(initialPath)
        loadCloudAccounts()
        observeGlobalClipboard()
        observeBookmarks()
        loadStorageUsage()
        observeUsbConnection()
        observeMediaChanges()
    }

    private fun observeUsbConnection() {
        viewModelScope.launch {
            usbOtgManager.connectedUsbDrives.collect { drives ->
                val current = _uiState.value.currentPath
                val isUsbPath = _uiState.value.categoryType == com.antigravity.filemanager.domain.model.CategoryType.USB_OTG ||
                        (current.startsWith("/storage/") && !current.startsWith("/storage/emulated"))
                if (isUsbPath) {
                    val isDriveStillConnected = drives.any { current.startsWith(it.rootPath) }
                    if (!isDriveStillConnected) {
                        _uiState.update { old -> old.copy(
                            shouldNavigateBackOnUsbDisconnect = true
                        ) }
                    }
                }
            }
        }
    }

    fun onUsbDisconnectHandled() {
        _uiState.update { old -> old.copy(shouldNavigateBackOnUsbDisconnect = false) }
    }

    private fun loadStorageUsage() {
        viewModelScope.launch {
            val volume = getDashboardDataUseCase.getStorageInfo()
            _uiState.update { old -> old.copy(storageUsedPercent = volume.usedPercentageInt) }
        }
    }

    private fun observeBookmarks() {
        viewModelScope.launch {
            bookmarkUseCase.observeBookmarks().collect { bookmarks ->
                _uiState.update { old -> old.copy(bookmarks = bookmarks) }
            }
        }
    }

    fun dismissBookmarkConfirmation() {
        _uiState.update { old -> old.copy(bookmarkConfirmationMessage = null) }
    }

    private fun observeGlobalClipboard() {
        viewModelScope.launch {
            globalClipboardManager.state.collect { clip ->
                _uiState.update { old -> old.copy(
                    clipboardPaths = clip.paths,
                    isCutOperation = clip.isCut,
                    clipboardSourceCloudAccountId = clip.sourceCloudAccountId,
                    clipboardItemSizes = clip.itemSizes,
                    clipboardItemIsDirectory = clip.itemIsDirectory
                ) }
            }
        }
        // See the matching comment in CloudExplorerViewModel: TransferGuard.progress survives
        // this ViewModel getting recreated (e.g. backgrounding the app mid-transfer and the OS
        // reclaiming the Activity), unlike the local downloadProgress set by this screen's own
        // throttled callbacks — so the notification kept showing progress while the in-app bar
        // came back blank after reopening. Keep this in-app bar mirrored to that shared source too.
        viewModelScope.launch {
            transferGuard.progress.collect { info ->
                if (info != null && _uiState.value.transferCancelledByUser) {
                    return@collect
                }
                _uiState.update { old -> old.copy(
                    downloadProgress = info?.let {
                        CloudTransferProgress(
                            currentFileName = it.currentFileName,
                            currentIndex = it.currentIndex,
                            totalFiles = it.totalFiles,
                            bytesTransferred = it.bytesTransferred,
                            totalBytes = it.totalBytes,
                            isIndeterminate = it.totalBytes <= 0,
                            isUpload = it.isUpload,
                            operationLabel = it.operationLabel
                        )
                    },
                    transferCancelledByUser = if (info == null) false else _uiState.value.transferCancelledByUser
                ) }
            }
        }
    }

    private fun loadCloudAccounts() {
        viewModelScope.launch {
            cloudStorageUseCase.observeAccounts().collect { accounts ->
                _uiState.update { old -> old.copy(cloudAccounts = accounts) }
            }
        }
    }

    fun refresh() {
        folderCacheManager.invalidateLocal(_uiState.value.currentPath)
        loadDirectory(_uiState.value.currentPath)
    }

    /** Per-folder scroll positions, so going back lands where the parent was left (see FolderScrollMemory). */
    val scrollMemory = com.antigravity.filemanager.presentation.components.FolderScrollMemory()

    fun loadDirectory(path: String) {
        // Going into a subfolder starts at its top; going up (or reloading) keeps remembered positions.
        val here = _uiState.value.currentPath.trimEnd('/')
        if (here.isNotEmpty() && path.startsWith("$here/")) scrollMemory.forget(path)

        // Set synchronously (before the coroutine below even starts) rather than inside it —
        // the coroutine's first line is a suspend call (folderPreferencesRepository.getSortOption),
        // so there's a real gap between this function returning and isLoading actually flipping
        // to true. Compose's first frame can render in that gap with the state's default
        // isLoading=false + files=emptyList(), which briefly paints the "empty folder" icon right
        // before real content (or even cached content) replaces it. Most noticeable on a folder
        // like Downloads that's opened straight from the dashboard with nothing pre-rendered yet.
        _uiState.update { old -> old.copy(isLoading = true) }

        // Navigating anywhere (including tapping a folder found via recursive search) must leave
        // search mode — otherwise this correctly loads the target folder's real contents into
        // `files`, but the screen keeps rendering the stale `searchResults` list on top of it
        // (filteredFiles prefers searchResults whenever searchQuery is non-blank), so opening a
        // search result folder looked like it did nothing. See the matching fix in
        // CloudExplorerViewModel.openFolder.
        searchJob?.cancel()
        if (_uiState.value.isSearchActive || _uiState.value.searchQuery.isNotEmpty()) {
            _uiState.update { old -> old.copy(
                isSearchActive = false,
                searchQuery = "",
                searchResults = emptyList(),
                isSearching = false
            ) }
        }
        // Only the latest navigation may land: an older load finishing later used to set
        // currentPath back to the folder the user had already left.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val savedSort = folderPreferencesRepository.getSortOption(path)
            val savedHidden = folderPreferencesRepository.getShowHidden(path)
            val savedViewMode = folderPreferencesRepository.getViewMode(path)

            // 1. Try to show cached folder contents immediately (Stale phase)
            val cached = folderCacheManager.getLocalFolder(path, savedSort, savedHidden, LOCAL_CACHE_FRESH_TTL_MS)
            if (cached != null && cached.files.isNotEmpty()) {
                _uiState.update { old -> old.copy(
                    isLoading = false,
                    currentPath = path,
                    files = cached.files,
                    selectedPaths = emptySet(),
                    isSelectionMode = false,
                    sortOption = savedSort,
                    showHiddenFiles = savedHidden,
                    viewMode = savedViewMode
                ) }
            } else {
                _uiState.update { old -> old.copy(
                    isLoading = true,
                    currentPath = path,
                    selectedPaths = emptySet(),
                    isSelectionMode = false,
                    sortOption = savedSort,
                    showHiddenFiles = savedHidden,
                    viewMode = savedViewMode
                ) }
            }

            watchDirectory(path)

            // 2. Revalidate: fetch fresh data from filesystem
            val files = fileOperationsUseCase.getFiles(
                directoryPath = path,
                sort = savedSort,
                showHidden = savedHidden
            )

            // Save to cache for next time
            folderCacheManager.putLocalFolder(path, savedSort, savedHidden, files)

            if (_uiState.value.currentPath == path) {
                _uiState.update { old -> old.copy(
                    isLoading = false,
                    files = files
                ) }
            }
        }
    }

    private fun observeMediaChanges() {
        viewModelScope.launch {
            mediaChangeSignal.changes.debounce(300).collect {
                if (_uiState.value.currentPath.isNotEmpty()) {
                    loadDirectory(_uiState.value.currentPath)
                }
            }
        }
    }

    private var watchJob: Job? = null
    private var loadJob: Job? = null

    /** Restarted on every loadDirectory — silently re-fetches this exact folder whenever a file
     * is added/removed/renamed inside it while it's on screen, so the user doesn't have to
     * pull-to-refresh to see something another app just wrote there. */
    private fun watchDirectory(path: String) {
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            // Short debounce, not a "wait and see" delay — a single file write still fires
            // CREATE followed by MODIFY a moment later, so this only exists to coalesce that
            // pair into one rescan rather than two, not to sit on the update.
            observeDirectoryChanges(path).debounce(150).collect {
                // The user may have navigated elsewhere by the time this fires (or several
                // change events piled up) — only apply the result if still viewing this path.
                if (_uiState.value.currentPath != path) return@collect
                val sort = _uiState.value.sortOption
                val hidden = _uiState.value.showHiddenFiles
                val files = fileOperationsUseCase.getFiles(path, sort, showHidden = hidden)
                folderCacheManager.putLocalFolder(path, sort, hidden, files)
                if (_uiState.value.currentPath == path) {
                    _uiState.update { old -> old.copy(files = files) }
                }
            }
        }
    }

    override fun onCleared() {
        watchJob?.cancel()
        super.onCleared()
    }

    fun onSortChanged(sort: FileSortOption, applyToAll: Boolean = false) {
        val current = _uiState.value.currentPath
        viewModelScope.launch {
            folderPreferencesRepository.saveSortOption(current, sort, applyToAll)
            _uiState.update { old -> old.copy(sortOption = sort) }
            loadDirectory(current)
        }
    }

    fun onShowHiddenChanged(show: Boolean, applyToAll: Boolean = false) {
        val current = _uiState.value.currentPath
        viewModelScope.launch {
            folderPreferencesRepository.saveShowHidden(current, show, applyToAll)
            _uiState.update { old -> old.copy(showHiddenFiles = show) }
            loadDirectory(current)
        }
    }

    fun onViewModeChanged(viewMode: com.antigravity.filemanager.presentation.components.ViewMode, applyToAll: Boolean = false) {
        val current = _uiState.value.currentPath
        _uiState.update { old -> old.copy(viewMode = viewMode) }
        viewModelScope.launch {
            folderPreferencesRepository.saveViewMode(current, viewMode, applyToAll)
        }
    }

    private var searchJob: Job? = null

    fun onSearchQueryChanged(query: String) {
        _uiState.update { old -> old.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { old -> old.copy(searchResults = emptyList(), isSearching = false) }
            return
        }
        val basePath = _uiState.value.currentPath
        val showHidden = _uiState.value.showHiddenFiles
        val thisJob = viewModelScope.launch(Dispatchers.IO) {
            // Debounce so fast typing doesn't kick off a new tree walk per keystroke — only the
            // settled query actually searches.
            delay(350)
            _uiState.update { old -> old.copy(searchResults = emptyList(), isSearching = true) }
            val found = mutableListOf<FileItem>()
            val resultsMutex = Mutex()
            // Bounded fan-out, same shape as CloudExplorerViewModel's recursive search — reading
            // every subfolder one at a time would make searching a large tree feel like it hung.
            val semaphore = Semaphore(4)

            suspend fun walk(path: String) {
                currentCoroutineContext().ensureActive()
                // Permit held only around the actual directory read below, never across the
                // recursive descent into subfolders — see the matching comment in
                // CloudExplorerViewModel.onSearchQueryChanged for why holding it across the whole
                // call (including waiting on this folder's own children) can deadlock a wide/deep
                // tree with only 4 permits.
                val children = semaphore.withPermit { fileOperationsUseCase.getFiles(path, FileSortOption.BY_NAME_ASC, showHidden) }
                val matches = children.filter { it.name.contains(query, ignoreCase = true) }
                if (matches.isNotEmpty()) {
                    resultsMutex.withLock {
                        found.addAll(matches)
                        // Default sort: newest first, matching Cloud/Media category search.
                        _uiState.update { old -> old.copy(searchResults = found.sortedByDescending { it.lastModified }) }
                    }
                }
                val subfolders = children.filter { it.isDirectory }
                coroutineScope {
                    subfolders.forEach { sub ->
                        launch { walk(sub.path) }
                    }
                }
            }

            try {
                walk(basePath)
            } finally {
                // A newer search may have already started and replaced searchJob by the time this
                // one unwinds (from cancellation) — only clear isSearching if this is still the
                // active search, so its finally block doesn't stomp on the newer one's state.
                if (searchJob === currentCoroutineContext().job) {
                    _uiState.update { old -> old.copy(isSearching = false) }
                }
            }
        }
        searchJob = thisJob
    }

    fun setSearchActive(active: Boolean) {
        searchJob?.cancel()
        _uiState.update { old -> old.copy(
            isSearchActive = active,
            searchQuery = if (!active) "" else _uiState.value.searchQuery,
            searchResults = emptyList(),
            isSearching = false
        ) }
    }

    fun toggleFileSelection(path: String) {
        val currentSelected = _uiState.value.selectedPaths.toMutableSet()
        if (currentSelected.contains(path)) {
            currentSelected.remove(path)
        } else {
            currentSelected.add(path)
        }
        _uiState.update { old -> old.copy(
            selectedPaths = currentSelected,
            isSelectionMode = currentSelected.isNotEmpty()
        ) }
    }

    // Select All / Invert must operate on what's actually visible — the recursive search results
    // while searching, or the plain folder listing otherwise — same fix as CloudExplorerViewModel.
    private fun visibleFiles(): List<FileItem> =
        if (_uiState.value.searchQuery.isBlank()) _uiState.value.files else _uiState.value.searchResults

    fun selectAll() {
        val allPaths = visibleFiles().map { it.path }.toSet()
        _uiState.update { old -> old.copy(
            selectedPaths = allPaths,
            isSelectionMode = true
        ) }
    }

    fun invertSelection() {
        val all = visibleFiles().map { it.path }.toSet()
        val current = _uiState.value.selectedPaths
        val inverted = all - current
        _uiState.update { old -> old.copy(
            selectedPaths = inverted,
            isSelectionMode = inverted.isNotEmpty()
        ) }
    }

    fun clearSelection() {
        _uiState.update { old -> old.copy(
            selectedPaths = emptySet(),
            isSelectionMode = false
        ) }
    }

    fun clearClipboard() {
        globalClipboardManager.clear()
    }

    fun copySelected() {
        val selected = _uiState.value.selectedPaths.toList()
        globalClipboardManager.copy(selected, selected.associateWith { File(it).length() })
        _uiState.update { old -> old.copy(
            isSelectionMode = false,
            selectedPaths = emptySet()
        ) }
    }

    fun cutSelected() {
        val selected = _uiState.value.selectedPaths.toList()
        globalClipboardManager.cut(selected, selected.associateWith { File(it).length() })
        _uiState.update { old -> old.copy(
            isSelectionMode = false,
            selectedPaths = emptySet()
        ) }
    }

    fun addBookmark(path: String, name: String) {
        viewModelScope.launch {
            bookmarkUseCase.addBookmark(path, name)
            _uiState.update { old -> old.copy(
                isSelectionMode = false,
                selectedPaths = emptySet(),
                bookmarkConfirmationMessage = "\"$name\" has been added to Bookmarks"
            ) }
        }
    }

    fun removeBookmark(path: String) {
        viewModelScope.launch {
            bookmarkUseCase.removeBookmark(path)
            _uiState.update { old -> old.copy(
                isSelectionMode = false,
                selectedPaths = emptySet()
            ) }
        }
    }

    private var pendingOverwriteAction: (suspend (overwriteNames: Set<String>, skipNames: Set<String>) -> Unit)? = null

    fun paste() {
        val sources = _uiState.value.clipboardPaths
        val target = _uiState.value.currentPath
        val cloudAccountId = _uiState.value.clipboardSourceCloudAccountId
        if (sources.isEmpty()) return

        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            if (cloudAccountId != null) {
                val isMove = _uiState.value.isCutOperation
                val itemSizes = _uiState.value.clipboardItemSizes
                val conflicts = sources.mapNotNull { remotePath ->
                    val name = File(remotePath).name
                    val destFile = File(target, name)
                    if (destFile.exists()) {
                        com.antigravity.filemanager.domain.model.OverwriteConflict(
                            name = name,
                            existingSize = destFile.length(),
                            newSize = itemSizes[remotePath] ?: 0L
                        )
                    } else null
                }
                if (conflicts.isNotEmpty()) {
                    pendingOverwriteAction = { overwriteNames, skipNames ->
                        pasteFromCloud(cloudAccountId, sources, target, isMove, overwriteNames, skipNames)
                        globalClipboardManager.clear()
                        folderCacheManager.invalidateLocal(target)
                        loadDirectory(target)
                    }
                    _uiState.update { old -> old.copy(overwriteConflicts = conflicts) }
                } else {
                    pasteFromCloud(cloudAccountId, sources, target, isMove)
                    globalClipboardManager.clear()
                    folderCacheManager.invalidateLocal(target)
                    loadDirectory(target)
                }
                return@launch
            }

            val isMove = _uiState.value.isCutOperation
            val conflicts = fileOperationsUseCase.findConflicts(sources, target)
            if (conflicts.isNotEmpty()) {
                pendingOverwriteAction = { overwriteNames, skipNames ->
                    runLocalCopyOrMove(sources, target, isMove, overwriteNames, skipNames)
                    globalClipboardManager.clear()
                    invalidateLocalCacheForPaste(sources, target, isMove)
                    loadDirectory(target)
                }
                _uiState.update { old -> old.copy(overwriteConflicts = conflicts) }
            } else {
                runLocalCopyOrMove(sources, target, isMove)
                globalClipboardManager.clear()
                invalidateLocalCacheForPaste(sources, target, isMove)
                loadDirectory(target)
            }
        }
    }

    /** Local-to-local copy/move with the same progress dialog compress/extract/delete already
     * show — this used to run silently with no feedback at all for however long it took, which
     * for a large batch looked exactly like the app hanging. */
    private suspend fun runLocalCopyOrMove(
        sources: List<String>,
        target: String,
        isMove: Boolean,
        overwriteNames: Set<String> = emptySet(),
        skipNames: Set<String> = emptySet()
    ): Result<Unit> {
        val operationLabel = if (isMove) "Moving" else "Copying"
        val onProgress: (String, Int, Int) -> Unit = { currentFile, currentIndex, totalFiles ->
            _uiState.update { old -> old.copy(
                downloadProgress = CloudTransferProgress.forItemCount(currentFile, currentIndex, totalFiles, isUpload = true, operationLabel = operationLabel)
            ) }
        }
        val result = if (isMove) {
            fileOperationsUseCase.move(sources, target, overwriteNames, skipNames, onProgress)
        } else {
            fileOperationsUseCase.copy(sources, target, overwriteNames, skipNames, onProgress)
        }
        _uiState.update { old -> old.copy(downloadProgress = null) }
        if (result.isFailure) {
            _uiState.update { old -> old.copy(toastMessage = "Error during $operationLabel: ${result.exceptionOrNull()?.message}") }
        }
        return result
    }

    /** After a local copy/move, both the destination and (on move) each source's parent may have stale cache entries. */
    private fun invalidateLocalCacheForPaste(sources: List<String>, target: String, isMove: Boolean) {
        folderCacheManager.invalidateLocal(target)
        sources.forEach { sourcePath ->
            val name = File(sourcePath).name
            folderCacheManager.invalidateLocal(File(target, name).absolutePath)
        }
        if (isMove) {
            sources.mapNotNull { File(it).parent }.distinct().forEach { folderCacheManager.invalidateLocal(it) }
            sources.forEach { folderCacheManager.invalidateLocal(it) }
        }
    }

    private var activeTransferJob: kotlinx.coroutines.Job? = null

    fun cancelTransfer() {
        val toastMessage = when (_uiState.value.downloadProgress?.operationLabel) {
            "Compressing" -> "Compress cancelled"
            "Extracting" -> "Extract cancelled"
            else -> "Transfer cancelled"
        }
        _uiState.update { old -> old.copy(
            downloadProgress = null,
            toastMessage = toastMessage,
            transferCancelledByUser = true
        ) }
        activeTransferJob?.cancel()
        activeTransferJob = null
    }

    fun resolveOverwriteConflict(overwriteNames: Set<String>, skipNames: Set<String>) {
        val action = pendingOverwriteAction
        pendingOverwriteAction = null
        _uiState.update { old -> old.copy(overwriteConflicts = emptyList()) }
        if (action != null) {
            activeTransferJob?.cancel()
            activeTransferJob = viewModelScope.launch { action(overwriteNames, skipNames) }
        }
    }

    fun cancelOverwriteConflict() {
        pendingOverwriteAction = null
        _uiState.update { old -> old.copy(overwriteConflicts = emptyList()) }
    }

    private suspend fun pasteFromCloud(accountId: String, remotePaths: List<String>, targetDir: String, isMove: Boolean, overwriteNames: Set<String> = emptySet(), skipNames: Set<String> = emptySet()) {
        try {
            val itemSizes = _uiState.value.clipboardItemSizes
            val result = cloudStorageUseCase.downloadFilesToLocal(
                context = context,
                accountId = accountId,
                remotePaths = remotePaths,
                targetDir = targetDir,
                itemSizes = itemSizes,
                isMove = isMove,
                overwriteNames = overwriteNames,
                skipNames = skipNames,
                itemIsDirectory = _uiState.value.clipboardItemIsDirectory
            ) { progress -> _uiState.update { old -> old.copy(downloadProgress = progress) } }

            // result.scannedPaths.size is how many actually got written — was always "Pasted
            // ${remotePaths.size}" regardless of skipNames, so skipping every conflict still
            // reported success for files that never actually landed.
            _uiState.update { old -> old.copy(
                downloadProgress = null,
                toastMessage = when {
                    result.failedNames.isNotEmpty() -> "Pasted with ${result.failedNames.size} failure(s)"
                    result.scannedPaths.isEmpty() -> "No files pasted (all skipped)"
                    else -> "Pasted ${result.scannedPaths.size} item(s)"
                }
            ) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            _uiState.update { old -> old.copy(downloadProgress = null, toastMessage = "Transfer cancelled") }
        }
    }

    fun openCopyToCloudDialog(isMove: Boolean = false) {
        _uiState.update { old -> old.copy(
            showCloudDestinationDialog = true,
            isCloudMoveOperation = isMove
        ) }
    }

    fun dismissCloudDestinationDialog() {
        _uiState.update { old -> old.copy(showCloudDestinationDialog = false) }
    }

    /** User picked which cloud account; now let them pick a destination folder inside it. */
    fun onSelectCloudAccountForTransfer(account: CloudAccount) {
        _uiState.update { old -> old.copy(
            showCloudDestinationDialog = false,
            cloudFolderPickerAccount = account,
            cloudFolderPickerPath = "/",
            cloudFolderPickerSegments = listOf("Root"),
            cloudFolderPickerFolders = emptyList()
        ) }
        loadCloudFolderPickerFolders("/")
    }

    fun dismissCloudFolderPicker() {
        _uiState.update { old -> old.copy(cloudFolderPickerAccount = null) }
    }

    private fun loadCloudFolderPickerFolders(path: String) {
        val account = _uiState.value.cloudFolderPickerAccount ?: return
        viewModelScope.launch {
            _uiState.update { old -> old.copy(cloudFolderPickerPath = path) }
            // Was always a live network round-trip (for MEGA in particular, a full account-tree
            // fetch+decrypt) on every folder tapped in this "pick a destination" picker, even
            // though the Cloud tab right next to it (CloudExplorerViewModel) already caches the
            // exact same folder via FolderCacheManager — so browsing here after already having
            // browsed there in the Cloud tab was needlessly slow for data already sitting in
            // cache. Same key ("cloud_<accountId>_<path>"), reconciled at most once per process
            // (see FolderCacheManager.getCloudFolder) — so this picker now shares that cache
            // instead of bypassing it, and a folder either screen has already fetched this
            // session stays instant on the other for the rest of it.
            val cached = folderCacheManager.getCloudFolder(account.id, path)
            if (cached != null) {
                _uiState.update { old -> old.copy(
                    cloudFolderPickerLoading = false,
                    cloudFolderPickerFolders = cached.files.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
                ) }
                if (cached.isFresh) return@launch
            } else {
                _uiState.update { old -> old.copy(cloudFolderPickerLoading = true) }
            }
            val result = cloudStorageUseCase.getFiles(account.id, path)
            val files = result.getOrDefault(emptyList())
            folderCacheManager.putCloudFolder(account.id, path, files)
            _uiState.update { old -> old.copy(
                cloudFolderPickerLoading = false,
                cloudFolderPickerFolders = files.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
            ) }
        }
    }

    fun openCloudFolderPickerFolder(folder: FileItem) {
        val newPath = folder.path
        _uiState.update { old -> old.copy(
            cloudFolderPickerSegments = _uiState.value.cloudFolderPickerSegments + folder.name
        ) }
        loadCloudFolderPickerFolders(newPath)
    }

    fun navigateCloudFolderPickerToSegment(index: Int) {
        val segments = _uiState.value.cloudFolderPickerSegments
        if (index >= segments.size - 1) return
        val newSegments = segments.subList(0, index + 1)
        val newPath = if (index == 0) "/" else "/" + segments.subList(1, index + 1).joinToString("/")
        _uiState.update { old -> old.copy(cloudFolderPickerSegments = newSegments) }
        loadCloudFolderPickerFolders(newPath)
    }

    fun confirmCloudFolderPickerDestination() {
        val account = _uiState.value.cloudFolderPickerAccount ?: return
        val destPath = _uiState.value.cloudFolderPickerPath
        _uiState.update { old -> old.copy(cloudFolderPickerAccount = null) }
        transferToCloud(account, destPath)
    }

    /** User picked "This device"; now let them pick a destination folder in local storage. */
    fun onSelectLocalDestination() {
        val root = android.os.Environment.getExternalStorageDirectory().absolutePath
        _uiState.update { old -> old.copy(
            showCloudDestinationDialog = false,
            showLocalFolderPicker = true,
            localFolderPickerPath = root,
            localFolderPickerSegments = listOf("Root"),
            localFolderPickerFolders = emptyList()
        ) }
        loadLocalFolderPickerFolders(root)
    }

    fun dismissLocalFolderPicker() {
        _uiState.update { old -> old.copy(showLocalFolderPicker = false) }
    }

    private fun loadLocalFolderPickerFolders(path: String) {
        viewModelScope.launch {
            _uiState.update { old -> old.copy(localFolderPickerLoading = true, localFolderPickerPath = path) }
            val folders = fileOperationsUseCase.getFiles(path, FileSortOption.BY_NAME_ASC, showHidden = false)
                .filter { it.isDirectory }
            _uiState.update { old -> old.copy(localFolderPickerLoading = false, localFolderPickerFolders = folders) }
        }
    }

    fun openLocalFolderPickerFolder(folder: FileItem) {
        _uiState.update { old -> old.copy(
            localFolderPickerSegments = _uiState.value.localFolderPickerSegments + folder.name
        ) }
        loadLocalFolderPickerFolders(folder.path)
    }

    fun navigateLocalFolderPickerToSegment(index: Int) {
        val segments = _uiState.value.localFolderPickerSegments
        if (index >= segments.size - 1) return
        val root = android.os.Environment.getExternalStorageDirectory().absolutePath
        val newSegments = segments.subList(0, index + 1)
        val newPath = if (index == 0) root else "$root/" + segments.subList(1, index + 1).joinToString("/")
        _uiState.update { old -> old.copy(localFolderPickerSegments = newSegments) }
        loadLocalFolderPickerFolders(newPath)
    }

    fun confirmLocalFolderPickerDestination() {
        val destPath = _uiState.value.localFolderPickerPath
        _uiState.update { old -> old.copy(showLocalFolderPicker = false) }
        transferToLocal(destPath)
    }

    private fun transferToLocal(destPath: String) {
        val selected = _uiState.value.selectedPaths.toList()
        val isMove = _uiState.value.isCloudMoveOperation
        val count = selected.size
        viewModelScope.launch {
            suspend fun doTransfer(overwriteNames: Set<String>, skipNames: Set<String>) {
                val result = runLocalCopyOrMove(selected, destPath, isMove, overwriteNames, skipNames)
                folderCacheManager.invalidateLocal(destPath)
                if (isMove) {
                    folderCacheManager.invalidateLocal(_uiState.value.currentPath)
                }
                loadDirectory(_uiState.value.currentPath)
                _uiState.update { old -> old.copy(
                    selectedPaths = emptySet(),
                    isSelectionMode = false,
                    toastMessage = if (result.isSuccess) "Transferred $count file(s) successfully!" else old.toastMessage
                ) }
            }

            val conflicts = fileOperationsUseCase.findConflicts(selected, destPath)
            if (conflicts.isNotEmpty()) {
                pendingOverwriteAction = { overwriteNames, skipNames -> doTransfer(overwriteNames, skipNames) }
                _uiState.update { old -> old.copy(overwriteConflicts = conflicts) }
            } else {
                doTransfer(emptySet(), emptySet())
            }
        }
    }

    fun transferToCloud(account: CloudAccount, destPath: String = "/") {
        val selected = _uiState.value.selectedPaths.toList()
        val isMove = _uiState.value.isCloudMoveOperation
        val count = selected.size
        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            _uiState.update { old -> old.copy(showCloudDestinationDialog = false) }

            val progressThrottler = com.antigravity.filemanager.utils.ProgressThrottler()
            suspend fun doTransfer(overwriteNames: Set<String>, skipNames: Set<String>) {
                try {
                    val result = cloudStorageUseCase.uploadFiles(
                        accountId = account.id,
                        localPaths = selected,
                        remoteDir = destPath,
                        overwriteNames = overwriteNames,
                        skipNames = skipNames
                    ) { currentFile, currentIndex, totalFiles, bytesSent, totalBytes ->
                      if (progressThrottler.shouldEmit(bytesSent, totalBytes)) {
                        _uiState.update { old -> old.copy(
                            downloadProgress = CloudTransferProgress(
                                currentFileName = currentFile,
                                currentIndex = currentIndex,
                                totalFiles = totalFiles,
                                bytesTransferred = bytesSent,
                                totalBytes = totalBytes,
                                isIndeterminate = totalBytes <= 0,
                                isUpload = true
                            )
                        ) }
                        transferGuard.updateProgress(
                            com.antigravity.filemanager.data.service.TransferProgressInfo(
                                currentFileName = currentFile,
                                currentIndex = currentIndex,
                                totalFiles = totalFiles,
                                bytesTransferred = bytesSent,
                                totalBytes = totalBytes,
                                isUpload = true
                            )
                        )
                      }
                    }
                    _uiState.update { old -> old.copy(downloadProgress = null) }
                    if (result.isFailure) {
                        _uiState.update { old -> old.copy(
                            selectedPaths = emptySet(),
                            isSelectionMode = false,
                            toastMessage = "Upload to ${account.accountName} failed: ${result.exceptionOrNull()?.message ?: "unknown error"}"
                        ) }
                        return
                    }
                    // uploadFiles() now returns how many files actually went out — was always
                    // reporting the ORIGINAL selection count here regardless of skipNames, so
                    // choosing "Skip" on every conflict still said "Transferred N successfully"
                    // for zero real uploads.
                    val uploadedCount = result.getOrDefault(0)
                    // A plain flat-file upload with no rename/merge decision (no overwrite
                    // conflict, nothing in the selection was a folder) means we already know
                    // exactly what landed under exactly what name — hand that straight to
                    // FolderCacheManager so a Cloud tab already open on this folder can splice it
                    // in live instead of paying for a refetch. Anything less certain (a folder in
                    // the selection, or an overwrite that deleted+replaced a remote item) falls
                    // back to the generic invalidate, which only triggers a real refresh().
                    if (overwriteNames.isEmpty() && selected.none { File(it).isDirectory }) {
                        val addedFiles = folderCacheManager.buildUploadedFileItems(selected, skipNames, destPath)
                        folderCacheManager.notifyCloudFilesAdded(account.id, destPath, addedFiles)
                    } else {
                        folderCacheManager.invalidateCloud(account.id, destPath)
                    }
                    if (isMove) {
                        // Only delete sources that actually transferred — a skipped conflict never
                        // left this device, so deleting it here would just lose the file outright.
                        val movedSources = selected.filter { File(it).name !in skipNames }
                        fileOperationsUseCase.delete(movedSources, moveToRecycleBin = false)
                        folderCacheManager.invalidateLocal(_uiState.value.currentPath)
                        loadDirectory(_uiState.value.currentPath)
                    }
                    _uiState.update { old -> old.copy(
                        selectedPaths = emptySet(),
                        isSelectionMode = false,
                        toastMessage = if (uploadedCount > 0) {
                            "Transferred $uploadedCount file(s) to ${account.accountName} successfully!"
                        } else {
                            "No files transferred (all skipped)"
                        }
                    ) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    _uiState.update { old -> old.copy(downloadProgress = null, toastMessage = "Transfer cancelled") }
                }
            }

            val items = selected.map { File(it).name to File(it).length() }
            val conflicts = cloudStorageUseCase.findConflicts(account.id, destPath, items)
            if (conflicts.isNotEmpty()) {
                pendingOverwriteAction = { overwriteNames, skipNames -> doTransfer(overwriteNames, skipNames) }
                _uiState.update { old -> old.copy(overwriteConflicts = conflicts) }
            } else {
                doTransfer(emptySet(), emptySet())
            }
        }
    }

    fun clearToast() {
        _uiState.update { old -> old.copy(toastMessage = null) }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            val result = fileOperationsUseCase.createFolder(_uiState.value.currentPath, name)
            _uiState.update { old -> old.copy(showNewFolderDialog = false, toastMessage = result.exceptionOrNull()?.let { it.message ?: "Could not create folder" }) }
            folderCacheManager.invalidateLocal(_uiState.value.currentPath)
            loadDirectory(_uiState.value.currentPath)
        }
    }

    fun renameItem(newName: String) {
        viewModelScope.launch {
            val item = _uiState.value.itemToRename ?: return@launch
            val result = fileOperationsUseCase.rename(item.path, newName)
            _uiState.update { old -> old.copy(showRenameDialog = false, itemToRename = null, toastMessage = result.exceptionOrNull()?.let { it.message ?: "Rename failed" }) }
            folderCacheManager.invalidateLocal(_uiState.value.currentPath)
            loadDirectory(_uiState.value.currentPath)
        }
    }

    fun deleteSelected(moveToRecycleBin: Boolean) {
        val paths = _uiState.value.selectedPaths.toList()
        _uiState.update { old -> old.copy(showDeleteDialog = false) }
        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            // Shown immediately, before delete() even starts: moving to Recycle Bin is (usually)
            // a quick per-item renameTo() with no progress ticks in between at all — for several
            // items that's still enough small synchronous work (rename + a DB insert, each) to
            // take a moment with nothing on screen the whole time, which read as the app hanging
            // even though nothing was actually blocked. Replaced by real per-file progress the
            // moment (if ever) the slow copy fallback below actually kicks in.
            _uiState.update { old -> old.copy(
                downloadProgress = CloudTransferProgress(
                    isUpload = false,
                    isIndeterminate = true,
                    operationLabel = if (moveToRecycleBin) "Deleting" else "Deleting permanently"
                )
            ) }
            val deleteResult = fileOperationsUseCase.delete(paths, moveToRecycleBin) { currentName, currentIndex, total ->
                _uiState.update { old -> old.copy(
                    downloadProgress = CloudTransferProgress.forItemCount(
                        currentName, currentIndex, total, isUpload = false,
                        operationLabel = if (moveToRecycleBin) "Deleting" else "Deleting permanently"
                    )
                ) }
            }
            val deletedCount = deleteResult.getOrNull()
            if (deletedCount == null || deletedCount < paths.size) {
                _uiState.update { old -> old.copy(toastMessage = deleteResult.exceptionOrNull()?.let { "Delete failed: ${it.message}" }
                    ?: "$deletedCount of ${paths.size} item(s) deleted") }
            }
            _uiState.update { old -> old.copy(
                downloadProgress = null,
                selectedPaths = emptySet(),
                isSelectionMode = false
            ) }
            folderCacheManager.invalidateLocal(_uiState.value.currentPath)
            loadDirectory(_uiState.value.currentPath)
        }
    }

    private var pendingCompressSources: List<String>? = null

    fun compressSelected(archiveName: String) {
        val name = if (archiveName.endsWith(".7z", ignoreCase = true) || archiveName.endsWith(".zip", ignoreCase = true)) {
            archiveName
        } else {
            "$archiveName.zip"
        }
        val targetArchive = File(_uiState.value.currentPath, name).absolutePath
        val sources = _uiState.value.selectedPaths.toList()
        _uiState.update { old -> old.copy(showCompressDialog = false) }
        if (File(targetArchive).exists()) {
            pendingCompressSources = sources
            _uiState.update { old -> old.copy(pendingOverwriteZipPath = targetArchive) }
            return
        }
        runCompress(sources, targetArchive)
    }

    fun zipSelected(zipName: String) = compressSelected(zipName)

    fun confirmCompressOverwrite() {
        val targetArchive = _uiState.value.pendingOverwriteZipPath ?: return
        val sources = pendingCompressSources ?: return
        pendingCompressSources = null
        _uiState.update { old -> old.copy(pendingOverwriteZipPath = null) }
        File(targetArchive).delete()
        runCompress(sources, targetArchive)
    }

    fun cancelCompressOverwrite() {
        pendingCompressSources = null
        _uiState.update { old -> old.copy(pendingOverwriteZipPath = null) }
    }

    private fun runCompress(sources: List<String>, targetArchive: String) {
        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            try {
                fileOperationsUseCase.compress(sources, targetArchive) { currentFile, currentIndex, totalFiles, bytesProcessed, totalBytes ->
                    if (!this@launch.isActive || _uiState.value.transferCancelledByUser) return@compress
                    val p = if (totalBytes > 0L) {
                        ((bytesProcessed.toDouble() / totalBytes.toDouble()) * 100).toInt().coerceIn(0, 100)
                    } else if (totalFiles > 0) {
                        ((currentIndex.toFloat() / totalFiles.toFloat()) * 100).toInt().coerceIn(0, 100)
                    } else 0
                    _uiState.update { old -> old.copy(
                        downloadProgress = CloudTransferProgress(
                            currentFileName = currentFile.ifEmpty { File(targetArchive).name },
                            currentIndex = currentIndex,
                            totalFiles = totalFiles,
                            bytesTransferred = bytesProcessed,
                            totalBytes = totalBytes,
                            isIndeterminate = false,
                            isUpload = true,
                            operationLabel = "Compressing",
                            percent = p
                        )
                    ) }
                }.onFailure { e ->
                    _uiState.update { old -> old.copy(toastMessage = "Compress failed: ${e.message}") }
                }
            } finally {
                withContext(NonCancellable) {
                    _uiState.update { old -> old.copy(
                        downloadProgress = null,
                        transferCancelledByUser = false
                    ) }
                    clearSelection()
                    folderCacheManager.invalidateLocal(_uiState.value.currentPath)
                    loadDirectory(_uiState.value.currentPath)
                }
            }
        }
    }

    fun extractSelected() {
        val selected = _uiState.value.selectedPaths.toList()
        val targetDir = _uiState.value.currentPath
        if (selected.isEmpty()) return

        // If single archive selected and encrypted, prompt immediately for password
        if (selected.size == 1 && fileOperationsUseCase.isArchiveEncrypted(selected[0])) {
            _uiState.update { old -> old.copy(
                pendingPasswordArchive = selected[0],
                passwordError = null
            ) }
            return
        }

        checkExtractConflictsAndRun(selected, targetDir)
    }

    private fun checkExtractConflictsAndRun(
        selected: List<String>,
        targetDir: String,
        password: String? = null
    ) {
        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            val allConflicts = mutableListOf<com.antigravity.filemanager.domain.model.OverwriteConflict>()
            for (path in selected) {
                try {
                    val conflicts = fileOperationsUseCase.getArchiveConflicts(path, targetDir, password)
                    allConflicts.addAll(conflicts)
                } catch (e: com.antigravity.filemanager.data.local.storage.ArchivePasswordRequiredException) {
                    _uiState.update { old -> old.copy(
                        isLoading = false,
                        downloadProgress = null,
                        pendingPasswordArchive = path,
                        passwordError = null
                    ) }
                    return@launch
                } catch (e: com.antigravity.filemanager.data.local.storage.ArchiveInvalidPasswordException) {
                    _uiState.update { old -> old.copy(
                        isLoading = false,
                        downloadProgress = null,
                        pendingPasswordArchive = path,
                        passwordError = "Incorrect password. Please try again."
                    ) }
                    return@launch
                } catch (e: Exception) {
                    val errorMsg = e.localizedMessage?.takeIf { it.isNotBlank() }
                        ?: e.message?.takeIf { it.isNotBlank() }
                        ?: e.javaClass.simpleName
                    _uiState.update { old -> old.copy(
                        isLoading = false,
                        toastMessage = "Failed to inspect archive: $errorMsg"
                    ) }
                    return@launch
                }
            }

            if (allConflicts.isNotEmpty()) {
                pendingOverwriteAction = { overwriteNames, skipNames ->
                    runExtract(selected, targetDir, password, overwriteNames, skipNames)
                }
                _uiState.update { old -> old.copy(overwriteConflicts = allConflicts) }
            } else {
                runExtract(selected, targetDir, password)
            }
        }
    }

    private fun runExtract(
        selected: List<String>,
        targetDir: String,
        password: String? = null,
        overwriteNames: Set<String> = emptySet(),
        skipNames: Set<String> = emptySet()
    ) {
        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            try {
                _uiState.update { old -> old.copy(isLoading = true) }
                var totalExtracted = 0
                var totalSkipped = 0
                var successfulArchives = 0
                for ((index, path) in selected.withIndex()) {
                    if (!isActive || _uiState.value.transferCancelledByUser) break
                    val archiveName = File(path).name
                    _uiState.update { old -> old.copy(
                        downloadProgress = CloudTransferProgress(
                            currentFileName = archiveName,
                            currentIndex = index + 1,
                            totalFiles = selected.size,
                            isIndeterminate = false,
                            isUpload = false,
                            operationLabel = if (selected.size > 1) "Extracting (${index + 1}/${selected.size})" else "Extracting",
                            percent = 0
                        )
                    ) }
                    val res = fileOperationsUseCase.extract(
                        archivePath = path,
                        targetDir = targetDir,
                        password = password,
                        overwriteNames = overwriteNames,
                        skipNames = skipNames
                    ) { currentEntry, currentIndex, totalEntries, bytesProcessed, totalBytes ->
                        if (!this@launch.isActive || _uiState.value.transferCancelledByUser) return@extract
                        val p = if (totalBytes > 0L) {
                            ((bytesProcessed.toDouble() / totalBytes.toDouble()) * 100).toInt().coerceIn(0, 100)
                        } else if (totalEntries > 0) {
                            ((currentIndex.toFloat() / totalEntries.toFloat()) * 100).toInt().coerceIn(0, 100)
                        } else 0
                        _uiState.update { old -> old.copy(
                            downloadProgress = CloudTransferProgress(
                                currentFileName = currentEntry.ifEmpty { archiveName },
                                currentIndex = currentIndex,
                                totalFiles = totalEntries,
                                bytesTransferred = bytesProcessed,
                                totalBytes = totalBytes,
                                isIndeterminate = false,
                                isUpload = false,
                                operationLabel = if (selected.size > 1) "Extracting (${index + 1}/${selected.size})" else "Extracting",
                                percent = p
                            )
                        ) }
                    }
                    if (res.isSuccess) {
                        successfulArchives++
                        val extractResult = res.getOrNull()
                        if (extractResult != null) {
                            totalExtracted += extractResult.extractedCount
                            totalSkipped += extractResult.skippedCount
                        }
                    } else {
                        val ex = res.exceptionOrNull()
                        if (ex is CancellationException) {
                            break
                        }
                        if (ex is com.antigravity.filemanager.data.local.storage.ArchivePasswordRequiredException) {
                            _uiState.update { old -> old.copy(
                                isLoading = false,
                                downloadProgress = null,
                                pendingPasswordArchive = path,
                                passwordError = null
                            ) }
                            return@launch
                        } else if (ex is com.antigravity.filemanager.data.local.storage.ArchiveInvalidPasswordException) {
                            _uiState.update { old -> old.copy(
                                isLoading = false,
                                downloadProgress = null,
                                pendingPasswordArchive = path,
                                passwordError = "Incorrect password. Please try again."
                            ) }
                            return@launch
                        } else {
                            _uiState.update { old -> old.copy(
                                isLoading = false,
                                downloadProgress = null,
                                toastMessage = "Extraction failed: ${ex?.message ?: "Unknown error"}"
                            ) }
                            return@launch
                        }
                    }
                }
                if (!_uiState.value.transferCancelledByUser) {
                    val toastMessage = when {
                        totalExtracted == 0 && totalSkipped > 0 -> "Extraction skipped (file(s) already exist)"
                        totalExtracted > 0 && totalSkipped > 0 -> "Extracted $totalExtracted file(s) ($totalSkipped skipped)"
                        totalExtracted > 0 -> if (selected.size > 1) "Extracted $successfulArchives archive(s) ($totalExtracted files)" else "Extracted $totalExtracted file(s)"
                        successfulArchives > 0 -> "Extracted $successfulArchives archive(s)"
                        else -> null
                    }
                    _uiState.update { old -> old.copy(
                        selectedPaths = emptySet(),
                        isSelectionMode = false,
                        toastMessage = toastMessage
                    ) }
                }
            } finally {
                withContext(NonCancellable) {
                    _uiState.update { old -> old.copy(
                        downloadProgress = null,
                        isLoading = false,
                        transferCancelledByUser = false
                    ) }
                    folderCacheManager.invalidateLocal(targetDir)
                    loadDirectory(targetDir)
                }
            }
        }
    }

    fun submitArchivePassword(password: String) {
        val archivePath = _uiState.value.pendingPasswordArchive ?: return
        val targetDir = _uiState.value.currentPath
        // Dismiss password dialog immediately
        _uiState.update { old -> old.copy(pendingPasswordArchive = null, passwordError = null) }
        checkExtractConflictsAndRun(listOf(archivePath), targetDir, password)
    }

    fun dismissPasswordDialog() {
        _uiState.update { old -> old.copy(pendingPasswordArchive = null, passwordError = null) }
    }

    fun setShowNewFolderDialog(show: Boolean) { _uiState.update { old -> old.copy(showNewFolderDialog = show) } }
    fun setShowRenameDialog(item: FileItem?) { _uiState.update { old -> old.copy(showRenameDialog = item != null, itemToRename = item) } }
    fun setShowDeleteDialog(show: Boolean) { _uiState.update { old -> old.copy(showDeleteDialog = show) } }
    fun setShowPropertiesDialog(item: FileItem?) { _uiState.update { old -> old.copy(showPropertiesDialog = item != null, itemForProperties = item) } }
    fun setShowCompressDialog(show: Boolean) { _uiState.update { old -> old.copy(showCompressDialog = show) } }

    private var propertiesJob: kotlinx.coroutines.Job? = null

    // Entry point for the selection bar's Properties action — one or many items, files and/or
    // folders. Was only ever able to show a single item (the "More > Properties" menu just looked
    // up uiState.selectedPaths.firstOrNull(), silently ignoring the rest of a multi-selection).
    // Shows the dialog immediately with what's already known (file sizes) while a background job
    // walks any folder(s) in the selection on disk to add up their real total size.
    fun showPropertiesForSelection(items: List<FileItem>) {
        if (items.isEmpty()) return
        propertiesJob?.cancel()
        val knownSize = items.filterNot { it.isDirectory }.sumOf { it.size }
        val folders = items.filter { it.isDirectory }
        _uiState.update { old -> old.copy(
            showPropertiesDialog = true,
            propertiesItems = items,
            propertiesTotalSize = knownSize,
            propertiesIsComputing = folders.isNotEmpty()
        ) }
        if (folders.isEmpty()) return
        propertiesJob = viewModelScope.launch(Dispatchers.IO) {
            var total = knownSize
            for (folder in folders) {
                ensureActive()
                total += try {
                    File(folder.path).walkTopDown().filter { it.isFile }.sumOf { it.length() }
                } catch (e: Exception) {
                    0L
                }
                _uiState.update { old -> old.copy(propertiesTotalSize = total) }
            }
            _uiState.update { old -> old.copy(propertiesIsComputing = false) }
        }
    }

    fun dismissPropertiesDialog() {
        propertiesJob?.cancel()
        _uiState.update { old -> old.copy(
            showPropertiesDialog = false,
            propertiesItems = emptyList(),
            propertiesTotalSize = 0L,
            propertiesIsComputing = false
        ) }
    }
}
