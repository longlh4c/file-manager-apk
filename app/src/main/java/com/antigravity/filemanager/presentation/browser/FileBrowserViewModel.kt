package com.antigravity.filemanager.presentation.browser

import android.content.Context
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    val showHiddenFiles: Boolean = false,
    val sortOption: FileSortOption = FileSortOption.BY_NAME_ASC,
    val showNewFolderDialog: Boolean = false,
    val showRenameDialog: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val showPropertiesDialog: Boolean = false,
    val showCompressDialog: Boolean = false,
    val pendingOverwriteZipPath: String? = null,
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
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val toastMessage: String? = null,
    val bookmarks: List<com.antigravity.filemanager.domain.model.Bookmark> = emptyList(),
    val bookmarkConfirmationMessage: String? = null,
    val overwriteConflicts: List<com.antigravity.filemanager.domain.model.OverwriteConflict> = emptyList(),
    val downloadProgress: CloudTransferProgress? = null,
    val storageUsedPercent: Int? = null,
    val viewMode: com.antigravity.filemanager.presentation.components.ViewMode = com.antigravity.filemanager.presentation.components.ViewMode.LIST
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
    }

    private fun loadStorageUsage() {
        viewModelScope.launch {
            val volume = getDashboardDataUseCase.getStorageInfo()
            _uiState.value = _uiState.value.copy(storageUsedPercent = volume.usedPercentageInt)
        }
    }

    private fun observeBookmarks() {
        viewModelScope.launch {
            bookmarkUseCase.observeBookmarks().collect { bookmarks ->
                _uiState.value = _uiState.value.copy(bookmarks = bookmarks)
            }
        }
    }

    fun dismissBookmarkConfirmation() {
        _uiState.value = _uiState.value.copy(bookmarkConfirmationMessage = null)
    }

    private fun observeGlobalClipboard() {
        viewModelScope.launch {
            globalClipboardManager.state.collect { clip ->
                _uiState.value = _uiState.value.copy(
                    clipboardPaths = clip.paths,
                    isCutOperation = clip.isCut,
                    clipboardSourceCloudAccountId = clip.sourceCloudAccountId,
                    clipboardItemSizes = clip.itemSizes
                )
            }
        }
    }

    private fun loadCloudAccounts() {
        viewModelScope.launch {
            cloudStorageUseCase.observeAccounts().collect { accounts ->
                _uiState.value = _uiState.value.copy(cloudAccounts = accounts)
            }
        }
    }

    fun refresh() {
        folderCacheManager.invalidateLocal(_uiState.value.currentPath)
        loadDirectory(_uiState.value.currentPath)
    }

    fun loadDirectory(path: String) {
        viewModelScope.launch {
            val savedSort = folderPreferencesRepository.getSortOption(path)
            val savedHidden = folderPreferencesRepository.getShowHidden(path)
            val savedViewMode = folderPreferencesRepository.getViewMode(path)

            // 1. Try to show cached folder contents immediately (Stale phase)
            val cached = folderCacheManager.getLocalFolder(path, savedSort, savedHidden, LOCAL_CACHE_FRESH_TTL_MS)
            if (cached != null && cached.files.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentPath = path,
                    files = cached.files,
                    selectedPaths = emptySet(),
                    isSelectionMode = false,
                    sortOption = savedSort,
                    showHiddenFiles = savedHidden,
                    viewMode = savedViewMode
                )
                if (cached.isFresh) {
                    // Cache was written moments ago (and every mutation invalidates it) —
                    // skip the filesystem rescan entirely instead of redoing it unconditionally.
                    return@launch
                }
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    currentPath = path,
                    selectedPaths = emptySet(),
                    isSelectionMode = false,
                    sortOption = savedSort,
                    showHiddenFiles = savedHidden,
                    viewMode = savedViewMode
                )
            }

            // 2. Revalidate: fetch fresh data from filesystem
            val files = fileOperationsUseCase.getFiles(
                directoryPath = path,
                sort = savedSort,
                showHidden = savedHidden
            )

            // Save to cache for next time
            folderCacheManager.putLocalFolder(path, savedSort, savedHidden, files)

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                files = files
            )
        }
    }

    fun onSortChanged(sort: FileSortOption, applyToAll: Boolean = false) {
        val current = _uiState.value.currentPath
        viewModelScope.launch {
            folderPreferencesRepository.saveSortOption(current, sort, applyToAll)
            _uiState.value = _uiState.value.copy(sortOption = sort)
            loadDirectory(current)
        }
    }

    fun onShowHiddenChanged(show: Boolean, applyToAll: Boolean = false) {
        val current = _uiState.value.currentPath
        viewModelScope.launch {
            folderPreferencesRepository.saveShowHidden(current, show, applyToAll)
            _uiState.value = _uiState.value.copy(showHiddenFiles = show)
            loadDirectory(current)
        }
    }

    fun onViewModeChanged(viewMode: com.antigravity.filemanager.presentation.components.ViewMode, applyToAll: Boolean = false) {
        val current = _uiState.value.currentPath
        _uiState.value = _uiState.value.copy(viewMode = viewMode)
        viewModelScope.launch {
            folderPreferencesRepository.saveViewMode(current, viewMode, applyToAll)
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun setSearchActive(active: Boolean) {
        _uiState.value = _uiState.value.copy(
            isSearchActive = active,
            searchQuery = if (!active) "" else _uiState.value.searchQuery
        )
    }

    fun toggleFileSelection(path: String) {
        val currentSelected = _uiState.value.selectedPaths.toMutableSet()
        if (currentSelected.contains(path)) {
            currentSelected.remove(path)
        } else {
            currentSelected.add(path)
        }
        _uiState.value = _uiState.value.copy(
            selectedPaths = currentSelected,
            isSelectionMode = currentSelected.isNotEmpty()
        )
    }

    fun selectAll() {
        val allPaths = _uiState.value.files.map { it.path }.toSet()
        _uiState.value = _uiState.value.copy(
            selectedPaths = allPaths,
            isSelectionMode = true
        )
    }

    fun invertSelection() {
        val all = _uiState.value.files.map { it.path }.toSet()
        val current = _uiState.value.selectedPaths
        val inverted = all - current
        _uiState.value = _uiState.value.copy(
            selectedPaths = inverted,
            isSelectionMode = inverted.isNotEmpty()
        )
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(
            selectedPaths = emptySet(),
            isSelectionMode = false
        )
    }

    fun clearClipboard() {
        globalClipboardManager.clear()
    }

    fun copySelected() {
        val selected = _uiState.value.selectedPaths.toList()
        globalClipboardManager.copy(selected, selected.associateWith { File(it).length() })
        _uiState.value = _uiState.value.copy(
            isSelectionMode = false,
            selectedPaths = emptySet()
        )
    }

    fun cutSelected() {
        val selected = _uiState.value.selectedPaths.toList()
        globalClipboardManager.cut(selected, selected.associateWith { File(it).length() })
        _uiState.value = _uiState.value.copy(
            isSelectionMode = false,
            selectedPaths = emptySet()
        )
    }

    fun addBookmark(path: String, name: String) {
        viewModelScope.launch {
            bookmarkUseCase.addBookmark(path, name)
            _uiState.value = _uiState.value.copy(
                isSelectionMode = false,
                selectedPaths = emptySet(),
                bookmarkConfirmationMessage = "\"$name\" has been added to Bookmarks"
            )
        }
    }

    fun removeBookmark(path: String) {
        viewModelScope.launch {
            bookmarkUseCase.removeBookmark(path)
            _uiState.value = _uiState.value.copy(
                isSelectionMode = false,
                selectedPaths = emptySet()
            )
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
                    _uiState.value = _uiState.value.copy(overwriteConflicts = conflicts)
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
                    if (isMove) fileOperationsUseCase.move(sources, target, overwriteNames, skipNames) else fileOperationsUseCase.copy(sources, target, overwriteNames, skipNames)
                    globalClipboardManager.clear()
                    invalidateLocalCacheForPaste(sources, target, isMove)
                    loadDirectory(target)
                }
                _uiState.value = _uiState.value.copy(overwriteConflicts = conflicts)
            } else {
                if (isMove) fileOperationsUseCase.move(sources, target) else fileOperationsUseCase.copy(sources, target)
                globalClipboardManager.clear()
                invalidateLocalCacheForPaste(sources, target, isMove)
                loadDirectory(target)
            }
        }
    }

    /** After a local copy/move, both the destination and (on move) each source's parent may have stale cache entries. */
    private fun invalidateLocalCacheForPaste(sources: List<String>, target: String, isMove: Boolean) {
        folderCacheManager.invalidateLocal(target)
        if (isMove) {
            sources.mapNotNull { File(it).parent }.distinct().forEach { folderCacheManager.invalidateLocal(it) }
        }
    }

    private var activeTransferJob: kotlinx.coroutines.Job? = null

    fun cancelTransfer() {
        activeTransferJob?.cancel()
        activeTransferJob = null
        val toastMessage = when (_uiState.value.downloadProgress?.operationLabel) {
            "Compressing" -> "Compress cancelled"
            "Extracting" -> "Extract cancelled"
            else -> "Transfer cancelled"
        }
        _uiState.value = _uiState.value.copy(
            downloadProgress = null,
            toastMessage = toastMessage
        )
    }

    fun resolveOverwriteConflict(overwriteNames: Set<String>, skipNames: Set<String>) {
        val action = pendingOverwriteAction
        pendingOverwriteAction = null
        _uiState.value = _uiState.value.copy(overwriteConflicts = emptyList())
        if (action != null) {
            activeTransferJob?.cancel()
            activeTransferJob = viewModelScope.launch { action(overwriteNames, skipNames) }
        }
    }

    fun cancelOverwriteConflict() {
        pendingOverwriteAction = null
        _uiState.value = _uiState.value.copy(overwriteConflicts = emptyList())
    }

    private suspend fun pasteFromCloud(accountId: String, remotePaths: List<String>, targetDir: String, isMove: Boolean, overwriteNames: Set<String> = emptySet(), skipNames: Set<String> = emptySet()) {
        try {
            var failures = 0
            val targetFolder = File(targetDir)
            val totalCount = remotePaths.size
            val itemSizes = _uiState.value.clipboardItemSizes
            val progressThrottler = com.antigravity.filemanager.utils.ProgressThrottler()

            remotePaths.forEachIndexed { index, remotePath ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val name = File(remotePath).name
                if (name in skipNames) return@forEachIndexed
                val destFile = File(targetFolder, name)
                val expectedSize = itemSizes[remotePath] ?: 0L

                _uiState.value = _uiState.value.copy(
                    downloadProgress = CloudTransferProgress(
                        currentFileName = name,
                        currentIndex = index + 1,
                        totalFiles = totalCount,
                        bytesTransferred = 0L,
                        totalBytes = expectedSize,
                        isIndeterminate = expectedSize <= 0,
                        isUpload = false
                    )
                )

                val tempDir = File(context.cacheDir, "cloud_paste_temp_${System.nanoTime()}").apply { mkdirs() }
                val result = cloudStorageUseCase.downloadFile(accountId, remotePath, tempDir.absolutePath) { bytesRead, totalBytes ->
                    val effTotal = if (totalBytes > 0) totalBytes else expectedSize
                    if (progressThrottler.shouldEmit(bytesRead, effTotal)) {
                        _uiState.value = _uiState.value.copy(
                            downloadProgress = CloudTransferProgress(
                                currentFileName = name,
                                currentIndex = index + 1,
                                totalFiles = totalCount,
                                bytesTransferred = bytesRead,
                                totalBytes = effTotal,
                                isIndeterminate = effTotal <= 0,
                                isUpload = false
                            )
                        )
                    }
                }
                if (result.isSuccess) {
                    val downloaded = result.getOrNull()
                    if (downloaded != null && downloaded.exists() && downloaded.isFile) {
                        val finalFile = if (name in overwriteNames) {
                            destFile
                        } else if (destFile.exists()) {
                            uniqueLocalDestination(targetFolder, downloaded.name)
                        } else {
                            destFile
                        }
                        if (finalFile.exists()) {
                            finalFile.delete()
                        }
                        downloaded.inputStream().use { input ->
                            finalFile.outputStream().use { output ->
                                val buf = ByteArray(64 * 1024)
                                var read: Int
                                while (input.read(buf).also { read = it } != -1) {
                                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                    output.write(buf, 0, read)
                                }
                            }
                        }
                        if (isMove) {
                            cloudStorageUseCase.deleteItem(accountId, remotePath)
                        }
                    } else {
                        failures++
                    }
                } else {
                    failures++
                }
                tempDir.deleteRecursively()
            }
            _uiState.value = _uiState.value.copy(
                downloadProgress = null,
                toastMessage = if (failures == 0) "Pasted ${remotePaths.size} item(s)" else "Pasted with $failures failure(s)"
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            _uiState.value = _uiState.value.copy(downloadProgress = null, toastMessage = "Transfer cancelled")
        }
    }

    private fun uniqueLocalDestination(targetFolder: File, name: String): File {
        var candidate = File(targetFolder, name)
        if (!candidate.exists()) return candidate
        val dotIndex = name.lastIndexOf('.')
        val base = if (dotIndex > 0) name.substring(0, dotIndex) else name
        val ext = if (dotIndex > 0) name.substring(dotIndex) else ""
        var counter = 1
        while (candidate.exists()) {
            candidate = File(targetFolder, "$base ($counter)$ext")
            counter++
        }
        return candidate
    }

    fun openCopyToCloudDialog(isMove: Boolean = false) {
        _uiState.value = _uiState.value.copy(
            showCloudDestinationDialog = true,
            isCloudMoveOperation = isMove
        )
    }

    fun dismissCloudDestinationDialog() {
        _uiState.value = _uiState.value.copy(showCloudDestinationDialog = false)
    }

    /** User picked which cloud account; now let them pick a destination folder inside it. */
    fun onSelectCloudAccountForTransfer(account: CloudAccount) {
        _uiState.value = _uiState.value.copy(
            showCloudDestinationDialog = false,
            cloudFolderPickerAccount = account,
            cloudFolderPickerPath = "/",
            cloudFolderPickerSegments = listOf("Root"),
            cloudFolderPickerFolders = emptyList()
        )
        loadCloudFolderPickerFolders("/")
    }

    fun dismissCloudFolderPicker() {
        _uiState.value = _uiState.value.copy(cloudFolderPickerAccount = null)
    }

    private fun loadCloudFolderPickerFolders(path: String) {
        val account = _uiState.value.cloudFolderPickerAccount ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(cloudFolderPickerLoading = true, cloudFolderPickerPath = path)
            val result = cloudStorageUseCase.getFiles(account.id, path)
            val folders = result.getOrDefault(emptyList()).filter { it.isDirectory }
                .sortedBy { it.name.lowercase() }
            _uiState.value = _uiState.value.copy(cloudFolderPickerLoading = false, cloudFolderPickerFolders = folders)
        }
    }

    fun openCloudFolderPickerFolder(folder: FileItem) {
        val newPath = folder.path
        _uiState.value = _uiState.value.copy(
            cloudFolderPickerSegments = _uiState.value.cloudFolderPickerSegments + folder.name
        )
        loadCloudFolderPickerFolders(newPath)
    }

    fun navigateCloudFolderPickerToSegment(index: Int) {
        val segments = _uiState.value.cloudFolderPickerSegments
        if (index >= segments.size - 1) return
        val newSegments = segments.subList(0, index + 1)
        val newPath = if (index == 0) "/" else "/" + segments.subList(1, index + 1).joinToString("/")
        _uiState.value = _uiState.value.copy(cloudFolderPickerSegments = newSegments)
        loadCloudFolderPickerFolders(newPath)
    }

    fun confirmCloudFolderPickerDestination() {
        val account = _uiState.value.cloudFolderPickerAccount ?: return
        val destPath = _uiState.value.cloudFolderPickerPath
        _uiState.value = _uiState.value.copy(cloudFolderPickerAccount = null)
        transferToCloud(account, destPath)
    }

    /** User picked "This device"; now let them pick a destination folder in local storage. */
    fun onSelectLocalDestination() {
        val root = android.os.Environment.getExternalStorageDirectory().absolutePath
        _uiState.value = _uiState.value.copy(
            showCloudDestinationDialog = false,
            showLocalFolderPicker = true,
            localFolderPickerPath = root,
            localFolderPickerSegments = listOf("Root"),
            localFolderPickerFolders = emptyList()
        )
        loadLocalFolderPickerFolders(root)
    }

    fun dismissLocalFolderPicker() {
        _uiState.value = _uiState.value.copy(showLocalFolderPicker = false)
    }

    private fun loadLocalFolderPickerFolders(path: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(localFolderPickerLoading = true, localFolderPickerPath = path)
            val folders = fileOperationsUseCase.getFiles(path, FileSortOption.BY_NAME_ASC, showHidden = false)
                .filter { it.isDirectory }
            _uiState.value = _uiState.value.copy(localFolderPickerLoading = false, localFolderPickerFolders = folders)
        }
    }

    fun openLocalFolderPickerFolder(folder: FileItem) {
        _uiState.value = _uiState.value.copy(
            localFolderPickerSegments = _uiState.value.localFolderPickerSegments + folder.name
        )
        loadLocalFolderPickerFolders(folder.path)
    }

    fun navigateLocalFolderPickerToSegment(index: Int) {
        val segments = _uiState.value.localFolderPickerSegments
        if (index >= segments.size - 1) return
        val root = android.os.Environment.getExternalStorageDirectory().absolutePath
        val newSegments = segments.subList(0, index + 1)
        val newPath = if (index == 0) root else "$root/" + segments.subList(1, index + 1).joinToString("/")
        _uiState.value = _uiState.value.copy(localFolderPickerSegments = newSegments)
        loadLocalFolderPickerFolders(newPath)
    }

    fun confirmLocalFolderPickerDestination() {
        val destPath = _uiState.value.localFolderPickerPath
        _uiState.value = _uiState.value.copy(showLocalFolderPicker = false)
        transferToLocal(destPath)
    }

    private fun transferToLocal(destPath: String) {
        val selected = _uiState.value.selectedPaths.toList()
        val isMove = _uiState.value.isCloudMoveOperation
        val count = selected.size
        viewModelScope.launch {
            suspend fun doTransfer(overwriteNames: Set<String>, skipNames: Set<String>) {
                if (isMove) {
                    fileOperationsUseCase.move(selected, destPath, overwriteNames, skipNames)
                } else {
                    fileOperationsUseCase.copy(selected, destPath, overwriteNames, skipNames)
                }
                folderCacheManager.invalidateLocal(destPath)
                if (isMove) {
                    folderCacheManager.invalidateLocal(_uiState.value.currentPath)
                }
                loadDirectory(_uiState.value.currentPath)
                _uiState.value = _uiState.value.copy(
                    selectedPaths = emptySet(),
                    isSelectionMode = false,
                    toastMessage = "Transferred $count file(s) successfully!"
                )
            }

            val conflicts = fileOperationsUseCase.findConflicts(selected, destPath)
            if (conflicts.isNotEmpty()) {
                pendingOverwriteAction = { overwriteNames, skipNames -> doTransfer(overwriteNames, skipNames) }
                _uiState.value = _uiState.value.copy(overwriteConflicts = conflicts)
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
            _uiState.value = _uiState.value.copy(showCloudDestinationDialog = false)

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
                        _uiState.value = _uiState.value.copy(
                            downloadProgress = CloudTransferProgress(
                                currentFileName = currentFile,
                                currentIndex = currentIndex,
                                totalFiles = totalFiles,
                                bytesTransferred = bytesSent,
                                totalBytes = totalBytes,
                                isIndeterminate = totalBytes <= 0,
                                isUpload = true
                            )
                        )
                      }
                    }
                    _uiState.value = _uiState.value.copy(downloadProgress = null)
                    if (result.isFailure) {
                        _uiState.value = _uiState.value.copy(
                            selectedPaths = emptySet(),
                            isSelectionMode = false,
                            toastMessage = "Upload to ${account.accountName} failed: ${result.exceptionOrNull()?.message ?: "unknown error"}"
                        )
                        return
                    }
                    folderCacheManager.invalidateCloud(account.id, destPath)
                    if (isMove) {
                        fileOperationsUseCase.delete(selected, moveToRecycleBin = false)
                        folderCacheManager.invalidateLocal(_uiState.value.currentPath)
                        loadDirectory(_uiState.value.currentPath)
                    }
                    _uiState.value = _uiState.value.copy(
                        selectedPaths = emptySet(),
                        isSelectionMode = false,
                        toastMessage = "Transferred $count file(s) to ${account.accountName} successfully!"
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    _uiState.value = _uiState.value.copy(downloadProgress = null, toastMessage = "Transfer cancelled")
                }
            }

            val items = selected.map { File(it).name to File(it).length() }
            val conflicts = cloudStorageUseCase.findConflicts(account.id, destPath, items)
            if (conflicts.isNotEmpty()) {
                pendingOverwriteAction = { overwriteNames, skipNames -> doTransfer(overwriteNames, skipNames) }
                _uiState.value = _uiState.value.copy(overwriteConflicts = conflicts)
            } else {
                doTransfer(emptySet(), emptySet())
            }
        }
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            fileOperationsUseCase.createFolder(_uiState.value.currentPath, name)
            _uiState.value = _uiState.value.copy(showNewFolderDialog = false)
            folderCacheManager.invalidateLocal(_uiState.value.currentPath)
            loadDirectory(_uiState.value.currentPath)
        }
    }

    fun renameItem(newName: String) {
        viewModelScope.launch {
            val item = _uiState.value.itemToRename ?: return@launch
            fileOperationsUseCase.rename(item.path, newName)
            _uiState.value = _uiState.value.copy(showRenameDialog = false, itemToRename = null)
            folderCacheManager.invalidateLocal(_uiState.value.currentPath)
            loadDirectory(_uiState.value.currentPath)
        }
    }

    fun deleteSelected(moveToRecycleBin: Boolean) {
        viewModelScope.launch {
            val paths = _uiState.value.selectedPaths.toList()
            fileOperationsUseCase.delete(paths, moveToRecycleBin)
            _uiState.value = _uiState.value.copy(
                showDeleteDialog = false,
                selectedPaths = emptySet(),
                isSelectionMode = false
            )
            folderCacheManager.invalidateLocal(_uiState.value.currentPath)
            loadDirectory(_uiState.value.currentPath)
        }
    }

    private var pendingCompressSources: List<String>? = null

    fun zipSelected(zipName: String) {
        val name = if (zipName.endsWith(".zip")) zipName else "$zipName.zip"
        val targetZip = File(_uiState.value.currentPath, name).absolutePath
        val sources = _uiState.value.selectedPaths.toList()
        _uiState.value = _uiState.value.copy(showCompressDialog = false)
        if (File(targetZip).exists()) {
            pendingCompressSources = sources
            _uiState.value = _uiState.value.copy(pendingOverwriteZipPath = targetZip)
            return
        }
        runCompress(sources, targetZip)
    }

    fun confirmCompressOverwrite() {
        val targetZip = _uiState.value.pendingOverwriteZipPath ?: return
        val sources = pendingCompressSources ?: return
        pendingCompressSources = null
        _uiState.value = _uiState.value.copy(pendingOverwriteZipPath = null)
        // zip4j appends into an existing archive rather than replacing it — delete the old one
        // first so "Replace" actually replaces instead of silently merging into stale contents.
        File(targetZip).delete()
        runCompress(sources, targetZip)
    }

    fun cancelCompressOverwrite() {
        pendingCompressSources = null
        _uiState.value = _uiState.value.copy(pendingOverwriteZipPath = null)
    }

    private fun runCompress(sources: List<String>, targetZip: String) {
        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            fileOperationsUseCase.zip(sources, targetZip) { currentFile, currentIndex, totalFiles ->
                _uiState.value = _uiState.value.copy(
                    downloadProgress = CloudTransferProgress(
                        currentFileName = currentFile,
                        currentIndex = currentIndex,
                        totalFiles = totalFiles,
                        isIndeterminate = true,
                        isUpload = true,
                        operationLabel = "Compressing"
                    )
                )
            }
            _uiState.value = _uiState.value.copy(downloadProgress = null)
            clearSelection()
            folderCacheManager.invalidateLocal(_uiState.value.currentPath)
            loadDirectory(_uiState.value.currentPath)
        }
    }

    fun extractSelected() {
        val selected = _uiState.value.selectedPaths.toList()
        val targetDir = _uiState.value.currentPath
        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            var count = 0
            selected.forEachIndexed { index, path ->
                val archiveName = File(path).name
                _uiState.value = _uiState.value.copy(
                    downloadProgress = CloudTransferProgress(
                        currentFileName = archiveName,
                        currentIndex = index + 1,
                        totalFiles = selected.size,
                        isIndeterminate = true,
                        isUpload = false,
                        operationLabel = "Extracting"
                    )
                )
                val res = fileOperationsUseCase.unzip(path, targetDir)
                if (res.isSuccess) count++
            }
            _uiState.value = _uiState.value.copy(
                selectedPaths = emptySet(),
                isSelectionMode = false,
                toastMessage = "Extracted $count archive(s)",
                downloadProgress = null
            )
            folderCacheManager.invalidateLocal(targetDir)
            loadDirectory(targetDir)
        }
    }

    fun setShowNewFolderDialog(show: Boolean) { _uiState.value = _uiState.value.copy(showNewFolderDialog = show) }
    fun setShowRenameDialog(item: FileItem?) { _uiState.value = _uiState.value.copy(showRenameDialog = item != null, itemToRename = item) }
    fun setShowDeleteDialog(show: Boolean) { _uiState.value = _uiState.value.copy(showDeleteDialog = show) }
    fun setShowPropertiesDialog(item: FileItem?) { _uiState.value = _uiState.value.copy(showPropertiesDialog = item != null, itemForProperties = item) }
    fun setShowCompressDialog(show: Boolean) { _uiState.value = _uiState.value.copy(showCompressDialog = show) }
}
