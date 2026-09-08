package com.antigravity.filemanager.presentation.categories

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.filemanager.domain.model.CategoryType
import com.antigravity.filemanager.domain.model.CloudAccount
import com.antigravity.filemanager.domain.model.CloudTransferProgress
import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.domain.model.FileSortOption
import com.antigravity.filemanager.domain.model.MediaFolder
import com.antigravity.filemanager.domain.usecase.CloudStorageUseCase
import com.antigravity.filemanager.domain.usecase.FileOperationsUseCase
import com.antigravity.filemanager.domain.usecase.GetCategorizedMediaUseCase
import com.antigravity.filemanager.domain.usecase.GlobalClipboardManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject

data class CategoryUiState(
    val categoryType: CategoryType = CategoryType.IMAGES,
    val isLoading: Boolean = true,
    val folders: List<MediaFolder> = emptyList(),
    val folderHistory: List<Pair<String, String>> = emptyList(), // Stack of (path, name)
    val subfolderFiles: List<FileItem> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val showPropertiesDialog: Boolean = false,
    val itemForProperties: FileItem? = null,
    // Same idea as CloudExplorerViewModel/FileBrowserViewModel's — one or many selected items.
    // No background computation needed here: a category subfolder listing is flat (files only,
    // see filterFilesForCategory) and a root-level MediaFolder bucket already carries its own
    // precomputed totalSizeBytes, so every size is already known up front.
    val propertiesItems: List<FileItem> = emptyList(),
    val propertiesTotalSize: Long = 0L,
    val showDeleteDialog: Boolean = false,
    val showRenameDialog: Boolean = false,
    val itemForRename: FileItem? = null,
    val showCompressDialog: Boolean = false,
    val pendingOverwriteZipPath: String? = null,
    val showNewFolderDialog: Boolean = false,
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
    val clipboardPaths: List<String> = emptyList(),
    val isCutOperation: Boolean = false,
    // Was missing entirely — paste() had no idea a clipboard entry could be a cloud file (only
    // ever built for local-to-local copy/move), so pasting something Copied from a Cloud account
    // into a category subfolder (Images > Pictures, say) silently treated the remote path as if
    // it were a local one and did nothing.
    val clipboardSourceCloudAccountId: String? = null,
    val clipboardItemSizes: Map<String, Long> = emptyMap(),
    val sortOption: FileSortOption = FileSortOption.BY_NAME_ASC,
    val showHiddenFiles: Boolean = false,
    val viewMode: com.antigravity.filemanager.presentation.components.ViewMode = com.antigravity.filemanager.presentation.components.ViewMode.LIST,
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    // A blank query means "not searching" — the screen falls back to whatever's already loaded
    // (folders/subfolderFiles) in that case, same convention as Cloud/FileBrowser's own search.
    // Populated by onSearchQueryChanged with a real recursive, whole-device search matching this
    // category's file types — searching used to just filter the folder-name grid you happened to
    // already be looking at, so typing an actual file name at the category root (not a folder
    // name) always came back with nothing, regardless of whether that file existed.
    val searchResults: List<FileItem> = emptyList(),
    val isSearching: Boolean = false,
    val toastMessage: String? = null,
    val overwriteConflicts: List<com.antigravity.filemanager.domain.model.OverwriteConflict> = emptyList(),
    val downloadProgress: CloudTransferProgress? = null
) {
    val currentSubfolderPath: String?
        get() = folderHistory.lastOrNull()?.first

    val currentSubfolderName: String
        get() = folderHistory.lastOrNull()?.second ?: ""

    val pathSegments: List<String>
        get() = folderHistory.map { it.second }
}

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val mediaUseCase: GetCategorizedMediaUseCase,
    private val fileOperationsUseCase: FileOperationsUseCase,
    private val cloudStorageUseCase: CloudStorageUseCase,
    private val globalClipboardManager: GlobalClipboardManager,
    private val folderPreferencesRepository: com.antigravity.filemanager.data.repository.FolderPreferencesRepository,
    private val folderCacheManager: com.antigravity.filemanager.data.local.cache.FolderCacheManager,
    private val mediaChangeSignal: com.antigravity.filemanager.data.local.observer.MediaChangeSignal,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val categoryTypeName: String = savedStateHandle.get<String>("categoryType") ?: CategoryType.IMAGES.name
    val categoryType: CategoryType = try {
        CategoryType.valueOf(categoryTypeName)
    } catch (e: Exception) {
        CategoryType.IMAGES
    }

    private val _uiState = MutableStateFlow(CategoryUiState(categoryType = categoryType))
    val uiState: StateFlow<CategoryUiState> = _uiState.asStateFlow()

    init {
        loadFolders()
        loadCloudAccounts()
        observeGlobalClipboard()
        observeMediaChanges()
    }

    /** Quietly re-fetches whatever's currently shown (root folders, or the open subfolder)
     * whenever MediaStore reports a change anywhere — a new photo, a completed download, a
     * deleted file — so the user doesn't have to pull-to-refresh to see it. Unlike loadFolders()/
     * openSubfolder(), this never touches folderHistory/selectedPaths/isLoading: it's meant to be
     * invisible while the user is actively browsing, not to reset their place or selection. */
    private fun observeMediaChanges() {
        viewModelScope.launch {
            // Short debounce just to coalesce a burst of MediaStore change notifications from a
            // single file write into one rescan, not to delay the update.
            mediaChangeSignal.changes.debounce(150).collect {
                val subfolderPath = _uiState.value.currentSubfolderPath
                val sort = _uiState.value.sortOption
                val hidden = _uiState.value.showHiddenFiles
                if (subfolderPath != null) {
                    val allFiles = fileOperationsUseCase.getFiles(subfolderPath, sort, showHidden = hidden)
                    val filtered = filterFilesForCategory(allFiles)
                    folderCacheManager.putCategorySubfolder(categoryType, subfolderPath, sort, hidden, filtered)
                    if (_uiState.value.currentSubfolderPath == subfolderPath) {
                        _uiState.value = _uiState.value.copy(subfolderFiles = filtered)
                    }
                } else {
                    val folders = mediaUseCase.getFolders(categoryType, sort)
                    folderCacheManager.putMediaFolders(categoryType, sort, folders)
                    if (_uiState.value.currentSubfolderPath == null) {
                        _uiState.value = _uiState.value.copy(folders = folders)
                    }
                }
            }
        }
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
        val currentPath = _uiState.value.currentSubfolderPath
        if (currentPath != null) {
            openSubfolder(currentPath, _uiState.value.currentSubfolderName)
        } else {
            loadFolders()
        }
    }

    fun loadFolders() {
        val rootKey = "category_${categoryType.name}"
        viewModelScope.launch {
            val savedSort = folderPreferencesRepository.getSortOption(rootKey)
            val savedHidden = folderPreferencesRepository.getShowHidden(rootKey)
            val savedViewMode = folderPreferencesRepository.getViewMode(rootKey)

            _uiState.value = _uiState.value.copy(
                folderHistory = emptyList(),
                subfolderFiles = emptyList(),
                selectedPaths = emptySet(),
                isSelectionMode = false,
                sortOption = savedSort,
                showHiddenFiles = savedHidden,
                viewMode = savedViewMode
            )
            // Documents used to always show a flat list of every document file on the device at
            // the root level, unlike Images/Audio/Videos/Downloads which show a bucket-folder
            // grid first — now it follows the same pattern as the others (a folder like
            // /Zalo/Documents shows up as one card, not every file inside it dumped at the top).
            //
            // isFresh here means "already reconciled once this process" (see
            // FolderCacheManager.reconciledOnceKeys), not "cached within the last N seconds" — so
            // a cache hit paints instantly and, after the first open per process, never bounces
            // back into a background rescan at all. Anything that could make it wrong is already
            // pushed to the cache directly: invalidateMediaFolders() on any in-app mutation, and
            // observeMediaChanges() on any external one.
            val cached = folderCacheManager.getMediaFolders(categoryType, savedSort)
            if (cached != null) {
                _uiState.value = _uiState.value.copy(isLoading = false, folders = cached.folders)
                if (cached.isFresh) return@launch
            } else {
                _uiState.value = _uiState.value.copy(isLoading = true)
            }
            // Coalesced with DashboardViewModel's warm-up: if that background reconcile for this
            // exact category is already running (a very likely race right after cold start — the
            // dashboard kicks it off before the user can possibly have tapped in yet), this awaits
            // that same scan instead of running a second one alongside it and fighting it for CPU.
            val folders = folderCacheManager.reconcileMediaFolders(categoryType, savedSort) {
                mediaUseCase.getFolders(categoryType, savedSort)
            }
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                folders = folders
            )
        }
    }

    fun openSubfolder(folderPath: String, folderName: String) {
        viewModelScope.launch {
            val savedSort = folderPreferencesRepository.getSortOption(folderPath)
            val savedHidden = folderPreferencesRepository.getShowHidden(folderPath)
            val savedViewMode = folderPreferencesRepository.getViewMode(folderPath)

            val history = _uiState.value.folderHistory.toMutableList()
            val existingIndex = history.indexOfFirst { it.first == folderPath }
            if (existingIndex >= 0) {
                while (history.size > existingIndex + 1) {
                    history.removeAt(history.size - 1)
                }
            } else {
                history.add(Pair(folderPath, folderName))
            }

            // Stale-while-revalidate, same pattern as loadFolders(): paint the cached (already
            // category-filtered) result instantly if we have one, and only redo the recursive
            // filesystem scan (getFiles + filterFilesForCategory, both of which walk subfolders
            // looking for matching extensions — the actual slow part) once per process for this
            // exact folder+sort+hidden combination. Previously this ran that full scan
            // unconditionally on every open, even ones visited seconds ago.
            val cached = folderCacheManager.getCategorySubfolder(categoryType, folderPath, savedSort, savedHidden)
            // On a cache miss (e.g. right after a paste, whose download step wipes the whole
            // catsub cache via invalidateMediaFolders()), don't blank subfolderFiles to empty —
            // that produced a visible "all files hide, then reappear" flash once the fresh scan
            // finished. Keep whatever was already on screen (stale-but-non-empty) until the fresh
            // list is ready, same as the stale-while-revalidate pattern already used when a cache
            // entry does exist.
            _uiState.value = _uiState.value.copy(
                isLoading = cached == null,
                folderHistory = history,
                selectedPaths = emptySet(),
                isSelectionMode = false,
                sortOption = savedSort,
                showHiddenFiles = savedHidden,
                viewMode = savedViewMode,
                subfolderFiles = cached?.files ?: _uiState.value.subfolderFiles
            )
            if (cached != null && cached.isFresh) return@launch

            val allFiles = fileOperationsUseCase.getFiles(
                folderPath,
                savedSort,
                showHidden = savedHidden
            )
            val filteredFiles = filterFilesForCategory(allFiles)
            folderCacheManager.putCategorySubfolder(categoryType, folderPath, savedSort, savedHidden, filteredFiles)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                subfolderFiles = filteredFiles
            )
        }
    }

    private val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "svg", "raw", "dng")
    private val videoExts = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "ts", "m4v", "mpg", "mpeg", "vob", "ogv", "f4v")
    private val audioExts = setOf("mp3", "m4a", "wav", "flac", "aac", "ogg", "wma", "opus", "amr", "mid", "midi")
    private val docExts = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "epub")

    private suspend fun filterFilesForCategory(allFiles: List<FileItem>): List<FileItem> = withContext(Dispatchers.IO) {
        val exts = when (categoryType) {
            CategoryType.IMAGES -> imageExts
            CategoryType.VIDEOS -> videoExts
            CategoryType.AUDIO -> audioExts
            CategoryType.DOCUMENTS -> docExts
            else -> return@withContext allFiles
        }
        val mimePrefix = when (categoryType) {
            CategoryType.IMAGES -> "image/"
            CategoryType.VIDEOS -> "video/"
            CategoryType.AUDIO -> "audio/"
            else -> null
        }
        // A category bucket (e.g. Images > Pictures) is meant to be a flat view of the files
        // directly inside it. MediaStore already groups every *subfolder's* images/videos/etc.
        // into its own separate top-level bucket (Images > Wallpapers, Images > Screenshots are
        // their own cards on the root grid even though they live inside Pictures/) — so also
        // listing Pictures' subfolders here just repeated content the user can already reach as
        // its own card, nested one level deeper for no reason. Used to keep a subfolder whenever
        // folderContainsExtensions() found a matching file anywhere underneath it (a recursive
        // filesystem walk per subfolder); dropping directories outright instead is both the fix
        // and, incidentally, no longer touches the filesystem at all beyond what's already in
        // `allFiles`.
        allFiles.filter { item ->
            !item.isDirectory && (
                item.extension.lowercase(Locale.getDefault()) in exts ||
                    (mimePrefix != null && item.mimeType.startsWith(mimePrefix))
            )
        }
    }

    fun navigateBack(): Boolean {
        val history = _uiState.value.folderHistory
        if (history.size > 1) {
            val newHistory = history.dropLast(1)
            val prev = newHistory.last()
            viewModelScope.launch {
                val savedSort = folderPreferencesRepository.getSortOption(prev.first)
                val savedHidden = folderPreferencesRepository.getShowHidden(prev.first)
                val savedViewMode = folderPreferencesRepository.getViewMode(prev.first)
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    folderHistory = newHistory,
                    selectedPaths = emptySet(),
                    isSelectionMode = false,
                    sortOption = savedSort,
                    showHiddenFiles = savedHidden,
                    viewMode = savedViewMode
                )
                val files = fileOperationsUseCase.getFiles(
                    prev.first,
                    savedSort,
                    showHidden = savedHidden
                )
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    subfolderFiles = filterFilesForCategory(files)
                )
            }
            return true
        } else if (history.size == 1) {
            loadFolders()
            return true
        }
        return false
    }

    fun navigateToSegment(index: Int) {
        val history = _uiState.value.folderHistory
        if (index < 0 || index >= history.size) return
        val target = history[index]
        openSubfolder(target.first, target.second)
    }

    fun onSortChanged(sort: FileSortOption, applyToAll: Boolean = false) {
        val currentPath = _uiState.value.currentSubfolderPath
        val targetKey = currentPath ?: "category_${categoryType.name}"
        viewModelScope.launch {
            folderPreferencesRepository.saveSortOption(targetKey, sort, applyToAll)
            _uiState.value = _uiState.value.copy(sortOption = sort)
            if (currentPath != null) {
                openSubfolder(currentPath, _uiState.value.currentSubfolderName)
            } else {
                loadFolders()
            }
        }
    }

    fun onViewModeChanged(mode: com.antigravity.filemanager.presentation.components.ViewMode, applyToAll: Boolean = false) {
        val currentPath = _uiState.value.currentSubfolderPath
        val targetKey = currentPath ?: "category_${categoryType.name}"
        viewModelScope.launch {
            folderPreferencesRepository.saveViewMode(targetKey, mode, applyToAll)
            _uiState.value = _uiState.value.copy(viewMode = mode)
        }
    }

    fun onShowHiddenChanged(show: Boolean, applyToAll: Boolean = false) {
        val currentPath = _uiState.value.currentSubfolderPath
        val targetKey = currentPath ?: "category_${categoryType.name}"
        viewModelScope.launch {
            folderPreferencesRepository.saveShowHidden(targetKey, show, applyToAll)
            _uiState.value = _uiState.value.copy(showHiddenFiles = show)
            if (currentPath != null) {
                openSubfolder(currentPath, _uiState.value.currentSubfolderName)
            } else {
                loadFolders()
            }
        }
    }

    private var searchJob: kotlinx.coroutines.Job? = null

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(searchResults = emptyList(), isSearching = false)
            return
        }
        searchJob = viewModelScope.launch {
            // Debounce so fast typing doesn't kick off a new device-wide walk per keystroke.
            delay(350)
            _uiState.value = _uiState.value.copy(searchResults = emptyList(), isSearching = true)
            try {
                // fileOperationsUseCase.search() itself already existed (bounded: 500 results,
                // depth 12) but nothing in the app ever actually called it — search here just
                // filtered whatever was already on screen (a folder-name grid at the category
                // root, or one subfolder's own file list), which only ever matched a query typed
                // for a folder name, never an actual file living anywhere else in the category.
                // Scope to the folder the user is currently inside (e.g. searching from within
                // Camera only searches Camera), same as browsing already only shows that folder's
                // own files — falls back to the whole category when at the root grid.
                val currentFolder = _uiState.value.currentSubfolderPath
                val allMatches = fileOperationsUseCase.search(query, rootPath = currentFolder, category = categoryType)
                val filtered = filterFilesForCategory(allMatches.filterNot { it.isDirectory })
                    .sortedByDescending { it.lastModified }
                _uiState.value = _uiState.value.copy(searchResults = filtered)
            } finally {
                if (searchJob === kotlinx.coroutines.currentCoroutineContext().job) {
                    _uiState.value = _uiState.value.copy(isSearching = false)
                }
            }
        }
    }

    fun setSearchActive(active: Boolean) {
        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isSearchActive = active,
            searchQuery = if (!active) "" else _uiState.value.searchQuery,
            searchResults = emptyList(),
            isSearching = false
        )
    }

    fun createFolder(name: String) {
        val currentDir = _uiState.value.currentSubfolderPath ?: return
        viewModelScope.launch {
            fileOperationsUseCase.createFolder(currentDir, name)
            // getCategorySubfolder's reconcile-once cache doesn't know anything changed on its
            // own — without this, openSubfolder() below just re-painted the same
            // already-"reconciled" cached list, and the new folder never appeared until something
            // else happened to invalidate it (e.g. the app process restarting).
            folderCacheManager.invalidateCategorySubfolder(categoryType, currentDir)
            _uiState.value = _uiState.value.copy(showNewFolderDialog = false)
            openSubfolder(currentDir, _uiState.value.currentSubfolderName)
        }
    }

    fun toggleFileSelection(path: String) {
        val current = _uiState.value.selectedPaths.toMutableSet()
        if (current.contains(path)) current.remove(path) else current.add(path)
        _uiState.value = _uiState.value.copy(
            selectedPaths = current,
            isSelectionMode = current.isNotEmpty()
        )
    }

    fun selectAll() {
        val all = _uiState.value.subfolderFiles.map { it.path }.toSet()
        _uiState.value = _uiState.value.copy(
            selectedPaths = all,
            isSelectionMode = true
        )
    }

    fun invertSelection() {
        val all = _uiState.value.subfolderFiles.map { it.path }.toSet()
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
        globalClipboardManager.copy(selected, selected.associateWith { java.io.File(it).length() })
        _uiState.value = _uiState.value.copy(
            selectedPaths = emptySet(),
            isSelectionMode = false
        )
    }

    fun cutSelected() {
        val selected = _uiState.value.selectedPaths.toList()
        globalClipboardManager.cut(selected, selected.associateWith { java.io.File(it).length() })
        _uiState.value = _uiState.value.copy(
            selectedPaths = emptySet(),
            isSelectionMode = false
        )
    }

    private var pendingOverwriteAction: (suspend (overwriteNames: Set<String>, skipNames: Set<String>) -> Unit)? = null

    fun paste() {
        val targetDir = _uiState.value.currentSubfolderPath ?: return
        val cloudAccountId = _uiState.value.clipboardSourceCloudAccountId
        if (cloudAccountId != null) {
            // Was always calling pasteFromCloud() straight away regardless of name clashes —
            // unlike FileBrowserViewModel's own cloud-source paste (which already checks this),
            // a name collision here silently auto-renamed to "name (1)" instead of ever asking,
            // since pasteFromCloud only auto-renames when a name isn't in overwriteNames.
            val sources = _uiState.value.clipboardPaths
            val itemSizes = _uiState.value.clipboardItemSizes
            val conflicts = sources.mapNotNull { remotePath ->
                val name = File(remotePath).name
                val destFile = File(targetDir, name)
                if (destFile.exists()) {
                    com.antigravity.filemanager.domain.model.OverwriteConflict(name = name, existingSize = destFile.length(), newSize = itemSizes[remotePath] ?: 0L)
                } else null
            }
            activeTransferJob?.cancel()
            if (conflicts.isNotEmpty()) {
                pendingOverwriteAction = { overwriteNames, skipNames ->
                    pasteFromCloud(cloudAccountId, targetDir, overwriteNames, skipNames)
                }
                _uiState.value = _uiState.value.copy(overwriteConflicts = conflicts)
            } else {
                activeTransferJob = viewModelScope.launch { pasteFromCloud(cloudAccountId, targetDir) }
            }
            return
        }
        viewModelScope.launch {
            val sources = _uiState.value.clipboardPaths
            val isMove = _uiState.value.isCutOperation

            suspend fun doPaste(overwriteNames: Set<String>, skipNames: Set<String>) {
                if (isMove) {
                    fileOperationsUseCase.move(sources, targetDir, overwriteNames, skipNames)
                } else {
                    fileOperationsUseCase.copy(sources, targetDir, overwriteNames, skipNames)
                }
                globalClipboardManager.clear()
                val currentName = _uiState.value.currentSubfolderName
                openSubfolder(targetDir, currentName)
            }

            val conflicts = fileOperationsUseCase.findConflicts(sources, targetDir)
            if (conflicts.isNotEmpty()) {
                pendingOverwriteAction = { overwriteNames, skipNames -> doPaste(overwriteNames, skipNames) }
                _uiState.value = _uiState.value.copy(overwriteConflicts = conflicts)
            } else {
                doPaste(emptySet(), emptySet())
            }
        }
    }

    /**
     * paste() used to have no cloud branch at all — a clipboard entry Copied from a Cloud account
     * (a remote path like "/CS/photo.jpg") went straight into fileOperationsUseCase.copy/move,
     * which only ever knows how to operate on real local java.io.File paths. The remote "file"
     * simply doesn't exist locally, so copyFiles/findConflicts silently found nothing to do and
     * returned — no exception, no toast, no file, matching exactly "pressed Paste, nothing
     * happened" for a Dropbox/MEGA/Drive source pasted into a category subfolder (Images >
     * Pictures, say), while the same paste worked fine from the plain local file browser (Local >
     * Downloads), which already had this branch. Mirrors FileBrowserViewModel.pasteFromCloud.
     */
    private suspend fun pasteFromCloud(accountId: String, targetDir: String, overwriteNames: Set<String> = emptySet(), skipNames: Set<String> = emptySet()) {
        val sources = _uiState.value.clipboardPaths
        val isMove = _uiState.value.isCutOperation
        val itemSizes = _uiState.value.clipboardItemSizes
        try {
            val result = cloudStorageUseCase.downloadFilesToLocal(
                context = context,
                accountId = accountId,
                remotePaths = sources,
                targetDir = targetDir,
                itemSizes = itemSizes,
                isMove = isMove,
                overwriteNames = overwriteNames,
                skipNames = skipNames
            ) { progress -> _uiState.value = _uiState.value.copy(downloadProgress = progress) }

            globalClipboardManager.clear()
            // result.scannedPaths.size is how many actually got written — was always "Pasted
            // ${sources.size}" regardless of skipNames, so skipping every conflict still reported
            // success for files that never actually landed.
            _uiState.value = _uiState.value.copy(
                downloadProgress = null,
                toastMessage = when {
                    result.failedNames.isNotEmpty() -> "Pasted with ${result.failedNames.size} failure(s)"
                    result.scannedPaths.isEmpty() -> "No files pasted (all skipped)"
                    else -> "Pasted ${result.scannedPaths.size} item(s)"
                }
            )
            val currentName = _uiState.value.currentSubfolderName
            openSubfolder(targetDir, currentName)
        } catch (e: kotlinx.coroutines.CancellationException) {
            _uiState.value = _uiState.value.copy(downloadProgress = null, toastMessage = "Transfer cancelled")
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

    fun openCopyToCloudDialog(isMove: Boolean = false) {
        _uiState.value = _uiState.value.copy(
            showCloudDestinationDialog = true,
            isCloudMoveOperation = isMove
        )
    }

    fun dismissCloudDestinationDialog() {
        _uiState.value = _uiState.value.copy(showCloudDestinationDialog = false)
    }

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
        val selected = _uiState.value.selectedPaths.toList()
        val isMove = _uiState.value.isCloudMoveOperation
        val count = selected.size
        val currentDir = _uiState.value.currentSubfolderPath
        viewModelScope.launch {
            suspend fun doTransfer(overwriteNames: Set<String>, skipNames: Set<String>) {
                if (isMove) {
                    fileOperationsUseCase.move(selected, destPath, overwriteNames, skipNames)
                } else {
                    fileOperationsUseCase.copy(selected, destPath, overwriteNames, skipNames)
                }
                if (currentDir != null) {
                    openSubfolder(currentDir, _uiState.value.currentSubfolderName)
                } else {
                    loadFolders()
                }
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
            _uiState.value = _uiState.value.copy(cloudFolderPickerPath = path)
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
                _uiState.value = _uiState.value.copy(
                    cloudFolderPickerLoading = false,
                    cloudFolderPickerFolders = cached.files.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
                )
                if (cached.isFresh) return@launch
            } else {
                _uiState.value = _uiState.value.copy(cloudFolderPickerLoading = true)
            }
            val result = cloudStorageUseCase.getFiles(account.id, path)
            val files = result.getOrDefault(emptyList())
            folderCacheManager.putCloudFolder(account.id, path, files)
            _uiState.value = _uiState.value.copy(
                cloudFolderPickerLoading = false,
                cloudFolderPickerFolders = files.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
            )
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

    fun transferToCloud(account: CloudAccount, destPath: String = "/") {
        val selected = _uiState.value.selectedPaths.toList()
        val isMove = _uiState.value.isCloudMoveOperation
        val count = selected.size
        val currentDir = _uiState.value.currentSubfolderPath
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
                        if (currentDir != null) {
                            openSubfolder(currentDir, _uiState.value.currentSubfolderName)
                        } else {
                            loadFolders()
                        }
                    }
                    _uiState.value = _uiState.value.copy(
                        selectedPaths = emptySet(),
                        isSelectionMode = false,
                        toastMessage = if (uploadedCount > 0) {
                            "Transferred $uploadedCount file(s) to ${account.accountName} successfully!"
                        } else {
                            "No files transferred (all skipped)"
                        }
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    _uiState.value = _uiState.value.copy(downloadProgress = null, toastMessage = "Transfer cancelled")
                }
            }

            val items = selected.map { java.io.File(it).name to java.io.File(it).length() }
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

    fun renameFile(newName: String) {
        val item = _uiState.value.itemForRename ?: return
        viewModelScope.launch {
            fileOperationsUseCase.rename(item.path, newName)
            _uiState.value = _uiState.value.copy(showRenameDialog = false, itemForRename = null)
            val folderPath = _uiState.value.currentSubfolderPath
            if (folderPath != null) {
                openSubfolder(folderPath, _uiState.value.currentSubfolderName)
            } else {
                loadFolders()
            }
        }
    }

    fun deleteSelected(moveToRecycleBin: Boolean) {
        val paths = _uiState.value.selectedPaths.toList()
        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            // Same fix as FileBrowserViewModel.deleteSelected — a large batch delete with no
            // progress feedback used to just leave the screen looking hung until it finished.
            fileOperationsUseCase.delete(paths, moveToRecycleBin) { currentName, currentIndex, total ->
                _uiState.value = _uiState.value.copy(
                    downloadProgress = CloudTransferProgress(
                        currentFileName = currentName,
                        currentIndex = currentIndex,
                        totalFiles = total,
                        isIndeterminate = false,
                        isUpload = false,
                        operationLabel = if (moveToRecycleBin) "Deleting" else "Deleting permanently"
                    )
                )
            }
            _uiState.value = _uiState.value.copy(downloadProgress = null)
            val folderPath = _uiState.value.currentSubfolderPath
            if (folderPath != null) {
                openSubfolder(folderPath, _uiState.value.currentSubfolderName)
            } else {
                loadFolders()
            }
        }
    }

    private var pendingCompressSources: List<String>? = null

    fun compressSelected(zipName: String) {
        val targetDir = _uiState.value.currentSubfolderPath ?: return
        val name = if (zipName.endsWith(".zip")) zipName else "$zipName.zip"
        val zipPath = "$targetDir/$name"
        val sources = _uiState.value.selectedPaths.toList()
        _uiState.value = _uiState.value.copy(showCompressDialog = false)
        if (File(zipPath).exists()) {
            pendingCompressSources = sources
            _uiState.value = _uiState.value.copy(pendingOverwriteZipPath = zipPath)
            return
        }
        runCompress(sources, zipPath, targetDir)
    }

    fun confirmCompressOverwrite() {
        val targetDir = _uiState.value.currentSubfolderPath ?: return
        val zipPath = _uiState.value.pendingOverwriteZipPath ?: return
        val sources = pendingCompressSources ?: return
        pendingCompressSources = null
        _uiState.value = _uiState.value.copy(pendingOverwriteZipPath = null)
        // zip4j appends into an existing archive rather than replacing it — delete the old one
        // first so "Replace" actually replaces instead of silently merging into stale contents.
        File(zipPath).delete()
        runCompress(sources, zipPath, targetDir)
    }

    fun cancelCompressOverwrite() {
        pendingCompressSources = null
        _uiState.value = _uiState.value.copy(pendingOverwriteZipPath = null)
    }

    private fun runCompress(sources: List<String>, zipPath: String, targetDir: String) {
        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            fileOperationsUseCase.zip(sources, zipPath) { currentFile, currentIndex, totalFiles ->
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
            openSubfolder(targetDir, _uiState.value.currentSubfolderName)
        }
    }

    fun extractSelected() {
        val selected = _uiState.value.selectedPaths.toList()
        val targetDir = _uiState.value.currentSubfolderPath ?: return
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
            openSubfolder(targetDir, _uiState.value.currentSubfolderName)
        }
    }

    fun showProperties(item: FileItem?) {
        _uiState.value = _uiState.value.copy(
            showPropertiesDialog = item != null,
            itemForProperties = item
        )
    }

    // Entry point for the selection bar's Properties action — one or many items. Was only ever
    // showing the FIRST selected item (see the "More > Properties" call site), silently ignoring
    // the rest of a multi-selection and never summing their sizes.
    fun showPropertiesForSelection(items: List<FileItem>) {
        if (items.isEmpty()) return
        _uiState.value = _uiState.value.copy(
            showPropertiesDialog = true,
            propertiesItems = items,
            propertiesTotalSize = items.sumOf { it.size }
        )
    }

    fun dismissPropertiesDialog() {
        _uiState.value = _uiState.value.copy(
            showPropertiesDialog = false,
            propertiesItems = emptyList(),
            propertiesTotalSize = 0L
        )
    }

    fun setShowRenameDialog(item: FileItem?) {
        _uiState.value = _uiState.value.copy(
            showRenameDialog = item != null,
            itemForRename = item
        )
    }

    fun setShowNewFolderDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showNewFolderDialog = show)
    }

    fun setShowCompressDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showCompressDialog = show)
    }

    fun setShowDeleteDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showDeleteDialog = show)
    }
}
