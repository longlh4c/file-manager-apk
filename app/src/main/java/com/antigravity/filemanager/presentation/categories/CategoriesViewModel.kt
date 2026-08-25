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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val sortOption: FileSortOption = FileSortOption.BY_NAME_ASC,
    val showHiddenFiles: Boolean = false,
    val viewMode: com.antigravity.filemanager.presentation.components.ViewMode = com.antigravity.filemanager.presentation.components.ViewMode.LIST,
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
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
    private val mediaUseCase: GetCategorizedMediaUseCase,
    private val fileOperationsUseCase: FileOperationsUseCase,
    private val cloudStorageUseCase: CloudStorageUseCase,
    private val globalClipboardManager: GlobalClipboardManager,
    private val folderPreferencesRepository: com.antigravity.filemanager.data.repository.FolderPreferencesRepository,
    private val folderCacheManager: com.antigravity.filemanager.data.local.cache.FolderCacheManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val MEDIA_FOLDERS_FRESH_TTL_MS = 20_000L
    }

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
    }

    private fun observeGlobalClipboard() {
        viewModelScope.launch {
            globalClipboardManager.state.collect { clip ->
                _uiState.value = _uiState.value.copy(
                    clipboardPaths = clip.paths,
                    isCutOperation = clip.isCut
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
            if (categoryType == CategoryType.DOCUMENTS) {
                // Stale-while-revalidate: show the cached list instantly if we have one, and only
                // re-run the (MediaStore query + filesystem walk) scan when it's actually stale —
                // re-opening "Documents" a few seconds after the last visit used to always redo
                // the full scan from scratch.
                val cached = folderCacheManager.getDocuments(savedSort, MEDIA_FOLDERS_FRESH_TTL_MS)
                if (cached != null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, subfolderFiles = cached.files, folders = emptyList())
                    if (cached.isFresh) return@launch
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = true)
                }
                val docs = mediaUseCase.getAllDocuments(savedSort)
                folderCacheManager.putDocuments(savedSort, docs)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    subfolderFiles = docs,
                    folders = emptyList()
                )
            } else {
                val cached = folderCacheManager.getMediaFolders(categoryType, savedSort, MEDIA_FOLDERS_FRESH_TTL_MS)
                if (cached != null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, folders = cached.folders)
                    if (cached.isFresh) return@launch
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = true)
                }
                val folders = mediaUseCase.getFolders(categoryType, savedSort)
                folderCacheManager.putMediaFolders(categoryType, savedSort, folders)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    folders = folders
                )
            }
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

            _uiState.value = _uiState.value.copy(
                isLoading = true,
                folderHistory = history,
                selectedPaths = emptySet(),
                isSelectionMode = false,
                sortOption = savedSort,
                showHiddenFiles = savedHidden,
                viewMode = savedViewMode
            )
            val allFiles = fileOperationsUseCase.getFiles(
                folderPath,
                savedSort,
                showHidden = savedHidden
            )
            val filteredFiles = filterFilesForCategory(allFiles)
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

    private fun folderContainsExtensions(folderPath: String, extensions: Set<String>, maxDepth: Int = 3): Boolean {
        val dir = File(folderPath)
        if (!dir.exists() || !dir.isDirectory) return false
        val files = dir.listFiles() ?: return false
        for (f in files) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                if (f.name != "Android" && maxDepth > 0) {
                    if (folderContainsExtensions(f.absolutePath, extensions, maxDepth - 1)) return true
                }
            } else {
                if (f.length() > 0 && f.extension.lowercase(Locale.getDefault()) in extensions) {
                    return true
                }
            }
        }
        return false
    }

    private suspend fun filterFilesForCategory(allFiles: List<FileItem>): List<FileItem> = withContext(Dispatchers.IO) {
        when (categoryType) {
            CategoryType.IMAGES -> allFiles.filter {
                if (it.isDirectory) {
                    folderContainsExtensions(it.path, imageExts)
                } else {
                    it.extension.lowercase(Locale.getDefault()) in imageExts || it.mimeType.startsWith("image/")
                }
            }
            CategoryType.VIDEOS -> allFiles.filter {
                if (it.isDirectory) {
                    folderContainsExtensions(it.path, videoExts)
                } else {
                    it.extension.lowercase(Locale.getDefault()) in videoExts || it.mimeType.startsWith("video/")
                }
            }
            CategoryType.AUDIO -> allFiles.filter {
                if (it.isDirectory) {
                    folderContainsExtensions(it.path, audioExts)
                } else {
                    it.extension.lowercase(Locale.getDefault()) in audioExts || it.mimeType.startsWith("audio/")
                }
            }
            CategoryType.DOCUMENTS -> allFiles.filter {
                if (it.isDirectory) {
                    folderContainsExtensions(it.path, docExts)
                } else {
                    it.extension.lowercase(Locale.getDefault()) in docExts
                }
            }
            CategoryType.DOWNLOADS -> allFiles
            else -> allFiles
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

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun setSearchActive(active: Boolean) {
        _uiState.value = _uiState.value.copy(
            isSearchActive = active,
            searchQuery = if (!active) "" else _uiState.value.searchQuery
        )
    }

    fun createFolder(name: String) {
        val currentDir = _uiState.value.currentSubfolderPath ?: return
        viewModelScope.launch {
            fileOperationsUseCase.createFolder(currentDir, name)
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
                    folderCacheManager.invalidateCloud(account.id, destPath)
                    if (isMove) {
                        fileOperationsUseCase.delete(selected, moveToRecycleBin = false)
                        if (currentDir != null) {
                            openSubfolder(currentDir, _uiState.value.currentSubfolderName)
                        } else {
                            loadFolders()
                        }
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
        viewModelScope.launch {
            fileOperationsUseCase.delete(_uiState.value.selectedPaths.toList(), moveToRecycleBin)
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
