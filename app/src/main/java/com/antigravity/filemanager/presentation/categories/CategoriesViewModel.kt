package com.antigravity.filemanager.presentation.categories

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.update
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
import java.io.File
import java.util.Locale
import javax.inject.Inject
import com.antigravity.filemanager.data.local.storage.isInsideHiddenOrSystemFolder

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
    val pendingPasswordArchive: String? = null,
    val passwordError: String? = null,
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
    val clipboardItemIsDirectory: Map<String, Boolean> = emptyMap(),
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
    val downloadProgress: CloudTransferProgress? = null,
    val transferCancelledByUser: Boolean = false
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
                        _uiState.update { old -> old.copy(subfolderFiles = filtered) }
                    }
                } else {
                    val rawFolders = mediaUseCase.getFolders(categoryType, sort)
                    val folders = if (categoryType == CategoryType.DOCUMENTS) {
                        rawFolders.filterNot { it.name.startsWith(".") || isInsideHiddenOrSystemFolder(it.path, isFolder = true) }
                    } else rawFolders
                    folderCacheManager.putMediaFolders(categoryType, sort, folders)
                    if (_uiState.value.currentSubfolderPath == null) {
                        _uiState.update { old -> old.copy(folders = folders) }
                    }
                }
            }
        }
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
    }

    private fun loadCloudAccounts() {
        viewModelScope.launch {
            cloudStorageUseCase.observeAccounts().collect { accounts ->
                _uiState.update { old -> old.copy(cloudAccounts = accounts) }
            }
        }
    }

    fun refresh() {
        val currentPath = _uiState.value.currentSubfolderPath
        if (currentPath != null) {
            folderCacheManager.invalidateCategorySubfolder(categoryType, currentPath)
            openSubfolder(currentPath, _uiState.value.currentSubfolderName)
        } else {
            folderCacheManager.invalidateMediaFolders()
            loadFolders()
        }
    }

    private var navigationJob: kotlinx.coroutines.Job? = null

    // Loading the root grid, opening a subfolder and going back all replace what is on screen;
    // only the latest may land, or a slower earlier load pulls the user back to where they left.
    private fun launchNavigation(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        navigationJob?.cancel()
        navigationJob = viewModelScope.launch(block = block)
    }

    fun loadFolders() {
        val rootKey = "category_${categoryType.name}"
        launchNavigation {
            val savedSort = folderPreferencesRepository.getSortOption(rootKey)
            val savedHidden = folderPreferencesRepository.getShowHidden(rootKey)
            val savedViewMode = folderPreferencesRepository.getViewMode(rootKey)

            _uiState.update { old -> old.copy(
                folderHistory = emptyList(),
                subfolderFiles = emptyList(),
                selectedPaths = emptySet(),
                isSelectionMode = false,
                sortOption = savedSort,
                showHiddenFiles = savedHidden,
                viewMode = savedViewMode
            ) }
            val cached = folderCacheManager.getMediaFolders(categoryType, savedSort)
            val filteredCached = if (categoryType == CategoryType.DOCUMENTS) {
                cached?.folders?.filterNot { it.name.startsWith(".") || isInsideHiddenOrSystemFolder(it.path, isFolder = true) }
            } else cached?.folders

            if (filteredCached != null) {
                _uiState.update { old -> old.copy(isLoading = false, folders = filteredCached) }
                if (cached?.isFresh == true) return@launchNavigation
            } else {
                _uiState.update { old -> old.copy(isLoading = true) }
            }
            val rawFolders = folderCacheManager.reconcileMediaFolders(categoryType, savedSort) {
                mediaUseCase.getFolders(categoryType, savedSort)
            }
            val folders = if (categoryType == CategoryType.DOCUMENTS) {
                rawFolders.filterNot { it.name.startsWith(".") || isInsideHiddenOrSystemFolder(it.path, isFolder = true) }
            } else rawFolders
            _uiState.update { old -> old.copy(
                isLoading = false,
                folders = folders
            ) }
        }
    }

    fun openSubfolder(folderPath: String, folderName: String) {
        launchNavigation {
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
            val isSameFolder = _uiState.value.currentSubfolderPath == folderPath
            val fallbackFiles = if (isSameFolder) _uiState.value.subfolderFiles else emptyList()
            _uiState.update { old -> old.copy(
                isLoading = cached == null,
                folderHistory = history,
                selectedPaths = emptySet(),
                isSelectionMode = false,
                sortOption = savedSort,
                showHiddenFiles = savedHidden,
                viewMode = savedViewMode,
                subfolderFiles = cached?.files ?: fallbackFiles
            ) }
            if (cached != null && cached.isFresh) return@launchNavigation

            val allFiles = fileOperationsUseCase.getFiles(
                folderPath,
                savedSort,
                showHidden = savedHidden
            )
            val filteredFiles = filterFilesForCategory(allFiles)
            folderCacheManager.putCategorySubfolder(categoryType, folderPath, savedSort, savedHidden, filteredFiles)
            _uiState.update { old -> old.copy(
                isLoading = false,
                subfolderFiles = filteredFiles
            ) }
        }
    }

    private val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "svg", "raw", "dng")
    private val videoExts = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "ts", "m4v", "mpg", "mpeg", "vob", "ogv", "f4v")
    private val audioExts = setOf("mp3", "m4a", "wav", "flac", "aac", "ogg", "wma", "opus", "amr", "mid", "midi")
    private val docExts = setOf(
        "pdf", "rtf", "wps", "wpd", "ps",
        "doc", "docx", "docm", "dot", "dotx",
        "xls", "xlsx", "xlsm", "xlt", "xltx", "csv", "tsv",
        "ppt", "pptx", "pptm", "pps", "ppsx", "pot", "potx",
        "odt", "ods", "odp", "ott", "ots", "otp", "sxw", "sxc", "sxi",
        "pages", "numbers", "key", "keynote",
        "txt", "text", "log", "md", "markdown", "rst", "tex", "latex", "note", "nfo", "diz",
        "json", "xml", "yaml", "yml", "ini", "conf", "properties", "html", "htm", "msg", "eml", "vcf",
        "epub", "mobi", "azw", "azw3", "prc", "fb2", "djvu", "chm", "lit"
    )

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
            CategoryType.DOCUMENTS -> "text/"
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
            !item.isDirectory && !item.name.startsWith(".") &&
                (categoryType != CategoryType.DOCUMENTS || !isInsideHiddenOrSystemFolder(item.path, isFolder = false)) && (
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
            launchNavigation {
                val savedSort = folderPreferencesRepository.getSortOption(prev.first)
                val savedHidden = folderPreferencesRepository.getShowHidden(prev.first)
                val savedViewMode = folderPreferencesRepository.getViewMode(prev.first)
                _uiState.update { old -> old.copy(
                    isLoading = true,
                    folderHistory = newHistory,
                    selectedPaths = emptySet(),
                    isSelectionMode = false,
                    sortOption = savedSort,
                    showHiddenFiles = savedHidden,
                    viewMode = savedViewMode
                ) }
                val files = fileOperationsUseCase.getFiles(
                    prev.first,
                    savedSort,
                    showHidden = savedHidden
                )
                _uiState.update { old -> old.copy(
                    isLoading = false,
                    subfolderFiles = filterFilesForCategory(files)
                ) }
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
            _uiState.update { old -> old.copy(sortOption = sort) }
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
            _uiState.update { old -> old.copy(viewMode = mode) }
        }
    }

    fun onShowHiddenChanged(show: Boolean, applyToAll: Boolean = false) {
        val currentPath = _uiState.value.currentSubfolderPath
        val targetKey = currentPath ?: "category_${categoryType.name}"
        viewModelScope.launch {
            folderPreferencesRepository.saveShowHidden(targetKey, show, applyToAll)
            _uiState.update { old -> old.copy(showHiddenFiles = show) }
            if (currentPath != null) {
                openSubfolder(currentPath, _uiState.value.currentSubfolderName)
            } else {
                loadFolders()
            }
        }
    }

    private var searchJob: kotlinx.coroutines.Job? = null

    fun onSearchQueryChanged(query: String) {
        _uiState.update { old -> old.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { old -> old.copy(searchResults = emptyList(), isSearching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            // Debounce so fast typing doesn't kick off a new device-wide walk per keystroke.
            delay(350)
            _uiState.update { old -> old.copy(searchResults = emptyList(), isSearching = true) }
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
                _uiState.update { old -> old.copy(searchResults = filtered) }
            } finally {
                if (searchJob === kotlinx.coroutines.currentCoroutineContext().job) {
                    _uiState.update { old -> old.copy(isSearching = false) }
                }
            }
        }
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

    fun createFolder(name: String) {
        val currentDir = _uiState.value.currentSubfolderPath ?: return
        viewModelScope.launch {
            val result = fileOperationsUseCase.createFolder(currentDir, name)
            result.exceptionOrNull()?.let { e -> _uiState.update { old -> old.copy(toastMessage = e.message ?: "Could not create folder") } }
            // getCategorySubfolder's reconcile-once cache doesn't know anything changed on its
            // own — without this, openSubfolder() below just re-painted the same
            // already-"reconciled" cached list, and the new folder never appeared until something
            // else happened to invalidate it (e.g. the app process restarting).
            folderCacheManager.invalidateCategorySubfolder(categoryType, currentDir)
            _uiState.update { old -> old.copy(showNewFolderDialog = false) }
            openSubfolder(currentDir, _uiState.value.currentSubfolderName)
        }
    }

    fun toggleFileSelection(path: String) {
        val current = _uiState.value.selectedPaths.toMutableSet()
        if (current.contains(path)) current.remove(path) else current.add(path)
        _uiState.update { old -> old.copy(
            selectedPaths = current,
            isSelectionMode = current.isNotEmpty()
        ) }
    }

    fun selectAll() {
        val all = _uiState.value.subfolderFiles.map { it.path }.toSet()
        _uiState.update { old -> old.copy(
            selectedPaths = all,
            isSelectionMode = true
        ) }
    }

    fun invertSelection() {
        val all = _uiState.value.subfolderFiles.map { it.path }.toSet()
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
        globalClipboardManager.copy(selected, selected.associateWith { java.io.File(it).length() })
        _uiState.update { old -> old.copy(
            selectedPaths = emptySet(),
            isSelectionMode = false
        ) }
    }

    fun cutSelected() {
        val selected = _uiState.value.selectedPaths.toList()
        globalClipboardManager.cut(selected, selected.associateWith { java.io.File(it).length() })
        _uiState.update { old -> old.copy(
            selectedPaths = emptySet(),
            isSelectionMode = false
        ) }
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
                _uiState.update { old -> old.copy(overwriteConflicts = conflicts) }
            } else {
                activeTransferJob = viewModelScope.launch { pasteFromCloud(cloudAccountId, targetDir) }
            }
            return
        }
        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            val sources = _uiState.value.clipboardPaths
            val isMove = _uiState.value.isCutOperation

            suspend fun doPaste(overwriteNames: Set<String>, skipNames: Set<String>) {
                runLocalCopyOrMove(sources, targetDir, isMove, overwriteNames, skipNames)
                globalClipboardManager.clear()
                val currentName = _uiState.value.currentSubfolderName
                openSubfolder(targetDir, currentName)
            }

            val conflicts = fileOperationsUseCase.findConflicts(sources, targetDir)
            if (conflicts.isNotEmpty()) {
                pendingOverwriteAction = { overwriteNames, skipNames -> doPaste(overwriteNames, skipNames) }
                _uiState.update { old -> old.copy(overwriteConflicts = conflicts) }
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
                skipNames = skipNames,
                itemIsDirectory = _uiState.value.clipboardItemIsDirectory
            ) { progress -> _uiState.update { old -> old.copy(downloadProgress = progress) } }

            globalClipboardManager.clear()
            // result.scannedPaths.size is how many actually got written — was always "Pasted
            // ${sources.size}" regardless of skipNames, so skipping every conflict still reported
            // success for files that never actually landed.
            _uiState.update { old -> old.copy(
                downloadProgress = null,
                toastMessage = when {
                    result.failedNames.isNotEmpty() -> "Pasted with ${result.failedNames.size} failure(s)"
                    result.scannedPaths.isEmpty() -> "No files pasted (all skipped)"
                    else -> "Pasted ${result.scannedPaths.size} item(s)"
                }
            ) }
            val currentName = _uiState.value.currentSubfolderName
            openSubfolder(targetDir, currentName)
        } catch (e: kotlinx.coroutines.CancellationException) {
            _uiState.update { old -> old.copy(downloadProgress = null, toastMessage = "Transfer cancelled") }
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
        _uiState.update { old -> old.copy(
            downloadProgress = null,
            toastMessage = result.exceptionOrNull()?.let { "Error during $operationLabel: ${it.message}" } ?: old.toastMessage
        ) }
        return result
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

    fun openCopyToCloudDialog(isMove: Boolean = false) {
        _uiState.update { old -> old.copy(
            showCloudDestinationDialog = true,
            isCloudMoveOperation = isMove
        ) }
    }

    fun dismissCloudDestinationDialog() {
        _uiState.update { old -> old.copy(showCloudDestinationDialog = false) }
    }

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
        val selected = _uiState.value.selectedPaths.toList()
        val isMove = _uiState.value.isCloudMoveOperation
        val count = selected.size
        val currentDir = _uiState.value.currentSubfolderPath
        viewModelScope.launch {
            suspend fun doTransfer(overwriteNames: Set<String>, skipNames: Set<String>) {
                val result = runLocalCopyOrMove(selected, destPath, isMove, overwriteNames, skipNames)
                if (currentDir != null) {
                    openSubfolder(currentDir, _uiState.value.currentSubfolderName)
                } else {
                    loadFolders()
                }
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

    fun transferToCloud(account: CloudAccount, destPath: String = "/") {
        val selected = _uiState.value.selectedPaths.toList()
        val isMove = _uiState.value.isCloudMoveOperation
        val count = selected.size
        val currentDir = _uiState.value.currentSubfolderPath
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
                        if (currentDir != null) {
                            openSubfolder(currentDir, _uiState.value.currentSubfolderName)
                        } else {
                            loadFolders()
                        }
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

            val items = selected.map { java.io.File(it).name to java.io.File(it).length() }
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

    fun renameFile(newName: String) {
        val item = _uiState.value.itemForRename ?: return
        viewModelScope.launch {
            val result = fileOperationsUseCase.rename(item.path, newName)
            _uiState.update { old -> old.copy(showRenameDialog = false, itemForRename = null, toastMessage = result.exceptionOrNull()?.let { it.message ?: "Rename failed" }) }
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
            // Same fix as FileBrowserViewModel.deleteSelected — shown immediately, before
            // delete() even starts, since a quick per-item renameTo() with no progress ticks in
            // between can still take a moment for several items with nothing on screen at all,
            // which read as the app hanging.
            _uiState.update { old -> old.copy(
                downloadProgress = CloudTransferProgress(
                    isUpload = false,
                    isIndeterminate = true,
                    operationLabel = if (moveToRecycleBin) "Deleting" else "Deleting permanently"
                )
            ) }
            fileOperationsUseCase.delete(paths, moveToRecycleBin) { currentName, currentIndex, total ->
                _uiState.update { old -> old.copy(
                    downloadProgress = CloudTransferProgress.forItemCount(
                        currentName, currentIndex, total, isUpload = false,
                        operationLabel = if (moveToRecycleBin) "Deleting" else "Deleting permanently"
                    )
                ) }
            }
            _uiState.update { old -> old.copy(downloadProgress = null) }
            val folderPath = _uiState.value.currentSubfolderPath
            if (folderPath != null) {
                openSubfolder(folderPath, _uiState.value.currentSubfolderName)
            } else {
                loadFolders()
            }
        }
    }

    private var pendingCompressSources: List<String>? = null

    fun compressSelected(archiveName: String) {
        val targetDir = _uiState.value.currentSubfolderPath ?: return
        val name = if (archiveName.endsWith(".7z", ignoreCase = true) || archiveName.endsWith(".zip", ignoreCase = true)) {
            archiveName
        } else {
            "$archiveName.zip"
        }
        val archivePath = "$targetDir/$name"
        val sources = _uiState.value.selectedPaths.toList()
        _uiState.update { old -> old.copy(showCompressDialog = false) }
        if (File(archivePath).exists()) {
            pendingCompressSources = sources
            _uiState.update { old -> old.copy(pendingOverwriteZipPath = archivePath) }
            return
        }
        runCompress(sources, archivePath, targetDir)
    }

    fun confirmCompressOverwrite() {
        val targetDir = _uiState.value.currentSubfolderPath ?: return
        val archivePath = _uiState.value.pendingOverwriteZipPath ?: return
        val sources = pendingCompressSources ?: return
        pendingCompressSources = null
        _uiState.update { old -> old.copy(pendingOverwriteZipPath = null) }
        File(archivePath).delete()
        runCompress(sources, archivePath, targetDir)
    }

    fun cancelCompressOverwrite() {
        pendingCompressSources = null
        _uiState.update { old -> old.copy(pendingOverwriteZipPath = null) }
    }

    private fun runCompress(sources: List<String>, archivePath: String, targetDir: String) {
        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            try {
                fileOperationsUseCase.compress(sources, archivePath) { currentFile, currentIndex, totalFiles, bytesProcessed, totalBytes ->
                    if (!this@launch.isActive || _uiState.value.transferCancelledByUser) return@compress
                    val p = if (totalBytes > 0L) {
                        ((bytesProcessed.toDouble() / totalBytes.toDouble()) * 100).toInt().coerceIn(0, 100)
                    } else if (totalFiles > 0) {
                        ((currentIndex.toFloat() / totalFiles.toFloat()) * 100).toInt().coerceIn(0, 100)
                    } else 0
                    _uiState.update { old -> old.copy(
                        downloadProgress = CloudTransferProgress(
                            currentFileName = currentFile.ifEmpty { File(archivePath).name },
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
                }
            } finally {
                withContext(NonCancellable) {
                    _uiState.update { old -> old.copy(
                        downloadProgress = null,
                        transferCancelledByUser = false
                    ) }
                    openSubfolder(targetDir, _uiState.value.currentSubfolderName)
                }
            }
        }
    }

    fun extractSelected() {
        val selected = _uiState.value.selectedPaths.toList()
        val targetDir = _uiState.value.currentSubfolderPath ?: return
        if (selected.isEmpty()) return

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
                    openSubfolder(targetDir, _uiState.value.currentSubfolderName)
                }
            }
        }
    }

    fun submitArchivePassword(password: String) {
        val archivePath = _uiState.value.pendingPasswordArchive ?: return
        val targetDir = _uiState.value.currentSubfolderPath ?: return
        // Dismiss password dialog immediately
        _uiState.update { old -> old.copy(pendingPasswordArchive = null, passwordError = null) }
        checkExtractConflictsAndRun(listOf(archivePath), targetDir, password)
    }

    fun dismissPasswordDialog() {
        _uiState.update { old -> old.copy(pendingPasswordArchive = null, passwordError = null) }
    }

    fun showProperties(item: FileItem?) {
        _uiState.update { old -> old.copy(
            showPropertiesDialog = item != null,
            itemForProperties = item
        ) }
    }

    // Entry point for the selection bar's Properties action — one or many items. Was only ever
    // showing the FIRST selected item (see the "More > Properties" call site), silently ignoring
    // the rest of a multi-selection and never summing their sizes.
    fun showPropertiesForSelection(items: List<FileItem>) {
        if (items.isEmpty()) return
        _uiState.update { old -> old.copy(
            showPropertiesDialog = true,
            propertiesItems = items,
            propertiesTotalSize = items.sumOf { it.size }
        ) }
    }

    fun dismissPropertiesDialog() {
        _uiState.update { old -> old.copy(
            showPropertiesDialog = false,
            propertiesItems = emptyList(),
            propertiesTotalSize = 0L
        ) }
    }

    fun setShowRenameDialog(item: FileItem?) {
        _uiState.update { old -> old.copy(
            showRenameDialog = item != null,
            itemForRename = item
        ) }
    }

    fun setShowNewFolderDialog(show: Boolean) {
        _uiState.update { old -> old.copy(showNewFolderDialog = show) }
    }

    fun setShowCompressDialog(show: Boolean) {
        _uiState.update { old -> old.copy(showCompressDialog = show) }
    }

    fun setShowDeleteDialog(show: Boolean) {
        _uiState.update { old -> old.copy(showDeleteDialog = show) }
    }
}
