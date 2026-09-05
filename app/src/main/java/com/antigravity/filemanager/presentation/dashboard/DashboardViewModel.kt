package com.antigravity.filemanager.presentation.dashboard

import android.content.Context
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.filemanager.domain.model.Bookmark
import com.antigravity.filemanager.domain.model.CategorySummary
import com.antigravity.filemanager.domain.model.CategoryType
import com.antigravity.filemanager.domain.model.CloudTransferProgress
import com.antigravity.filemanager.domain.model.StorageVolumeInfo
import com.antigravity.filemanager.domain.usecase.BookmarkUseCase
import com.antigravity.filemanager.domain.usecase.FileOperationsUseCase
import com.antigravity.filemanager.domain.usecase.GetCategorizedMediaUseCase
import com.antigravity.filemanager.domain.usecase.GetDashboardDataUseCase
import com.antigravity.filemanager.domain.usecase.GlobalClipboardManager
import com.antigravity.filemanager.domain.usecase.GlobalClipboardState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

private val INITIAL_DASHBOARD_CATEGORIES = listOf(
    CategorySummary(type = CategoryType.MAIN_STORAGE, title = "Main storage"),
    CategorySummary(type = CategoryType.DOWNLOADS, title = "Downloads"),
    CategorySummary(type = CategoryType.STORAGE_ANALYSIS, title = "Storage Analysis"),
    CategorySummary(type = CategoryType.IMAGES, title = "Images"),
    CategorySummary(type = CategoryType.AUDIO, title = "Audio"),
    CategorySummary(type = CategoryType.VIDEOS, title = "Videos"),
    CategorySummary(type = CategoryType.DOCUMENTS, title = "Documents"),
    CategorySummary(type = CategoryType.NEW_FILES, title = "New Files"),
    CategorySummary(type = CategoryType.CLOUD, title = "Cloud"),
    CategorySummary(type = CategoryType.ACCESS_FROM_NETWORK, title = "FTP"),
    CategorySummary(type = CategoryType.RECYCLE_BIN, title = "Recycle Bin")
)

data class DashboardUiState(
    val isLoading: Boolean = false,
    val storageVolume: StorageVolumeInfo = StorageVolumeInfo(256L * 1024 * 1024 * 1024, 241L * 1024 * 1024 * 1024, 15L * 1024 * 1024 * 1024),
    val categories: List<CategorySummary> = INITIAL_DASHBOARD_CATEGORIES,
    val clipboardState: GlobalClipboardState = GlobalClipboardState(),
    val showBookmarksDialog: Boolean = false,
    val bookmarks: List<Bookmark> = emptyList(),
    val overwriteConflicts: List<com.antigravity.filemanager.domain.model.OverwriteConflict> = emptyList(),
    val downloadProgress: CloudTransferProgress? = null,
    val toastMessage: String? = null
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val getDashboardDataUseCase: GetDashboardDataUseCase,
    private val fileOperationsUseCase: FileOperationsUseCase,
    private val globalClipboardManager: GlobalClipboardManager,
    private val bookmarkUseCase: BookmarkUseCase,
    private val cloudStorageUseCase: com.antigravity.filemanager.domain.usecase.CloudStorageUseCase,
    private val mediaUseCase: GetCategorizedMediaUseCase,
    private val folderCacheManager: com.antigravity.filemanager.data.local.cache.FolderCacheManager,
    private val folderPreferencesRepository: com.antigravity.filemanager.data.repository.FolderPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()
    private var activeTransferJob: kotlinx.coroutines.Job? = null
    private var loadDataJob: kotlinx.coroutines.Job? = null

    init {
        // Only flipped true here, for the cold-boot load — refresh() below re-triggers the exact
        // same loadData() but must NOT set this back to true, since a manual pull-to-refresh
        // already shows its own spinner (PullToRefreshContainer in DashboardScreen) and having
        // both on screen at once would look like two competing loading indicators.
        _uiState.value = _uiState.value.copy(isLoading = true)
        loadData()
        observeClipboard()
        observeBookmarks()
        warmMediaFolderCaches()
    }

    /**
     * The dashboard is where every session starts, so it's the cheapest place to pay for each
     * category's one-per-process MediaStore reconcile (see FolderCacheManager.reconciledOnceKeys)
     * — doing it here, quietly, while the user is still looking at the dashboard, means by the
     * time they actually tap into Images/Videos/Audio/Documents/Downloads that reconcile is
     * already done and the tap just paints the (now-fresh) cache with no rescan left to run.
     * Previously that same rescan only ever started on the tap itself, so the very first category
     * opened after a cold start always paid for it visibly (see the jank measured opening Images/
     * Videos right after launch). Sequential rather than parallel, and off the main dispatcher, so
     * this never competes with the dashboard's own first frame for CPU.
     *
     * Beyond the folder LIST, each folder card also shows a thumbnail — decoding that (via Coil)
     * is a separate cost the list cache above doesn't touch, and was the remaining source of jank
     * measured opening a category for the first time even after the list itself was pre-warmed.
     * Prefetching each folder's thumbnail here too, at the same fixed size the card itself
     * requests (MEDIA_FOLDER_THUMB_PX — see FileItemViews.kt), means Coil's memory/disk cache
     * already has the decoded bitmap ready by the time the card actually asks for it.
     */
    private fun warmMediaFolderCaches() {
        viewModelScope.launch(Dispatchers.Default) {
            val imageLoader = coil.Coil.imageLoader(context)
            val categories = listOf(
                CategoryType.IMAGES,
                CategoryType.VIDEOS,
                CategoryType.AUDIO,
                CategoryType.DOWNLOADS,
                CategoryType.DOCUMENTS
            )
            for (categoryType in categories) {
                currentCoroutineContext().ensureActive()
                val sort = folderPreferencesRepository.getSortOption("category_${categoryType.name}")
                val cached = folderCacheManager.getMediaFolders(categoryType, sort)
                val folders = if (cached == null || !cached.isFresh) {
                    // Coalesced with CategoriesViewModel.loadFolders(): if the user taps into this
                    // exact category before this warm-up pass reaches it, both sides await the
                    // same scan instead of running two full MediaStore scans of the same category
                    // side by side — which used to make the tap slower than if this warm-up didn't
                    // exist, since it was competing with itself for CPU/IO on the one category the
                    // user was actually staring at.
                    folderCacheManager.reconcileMediaFolders(categoryType, sort) {
                        mediaUseCase.getFolders(categoryType, sort)
                    }
                } else {
                    cached.folders
                }

                for (folder in folders) {
                    currentCoroutineContext().ensureActive()
                    val uri = folder.latestThumbnailUri ?: continue
                    val request = coil.request.ImageRequest.Builder(context)
                        .data(uri)
                        .size(com.antigravity.filemanager.presentation.components.MEDIA_FOLDER_THUMB_PX)
                        .build()
                    // execute() (not enqueue()) so this loop naturally throttles itself to one
                    // decode at a time instead of firing dozens of concurrent requests at the
                    // categories' combined folder count.
                    imageLoader.execute(request)
                }
            }
        }
    }

    private fun observeBookmarks() {
        viewModelScope.launch {
            bookmarkUseCase.observeBookmarks().collect { bookmarks ->
                _uiState.value = _uiState.value.copy(bookmarks = bookmarks)
            }
        }
    }

    fun setShowBookmarksDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showBookmarksDialog = show)
    }

    fun removeBookmark(path: String) {
        viewModelScope.launch {
            bookmarkUseCase.removeBookmark(path)
        }
    }

    private fun observeClipboard() {
        viewModelScope.launch {
            globalClipboardManager.state.collect { clip ->
                _uiState.value = _uiState.value.copy(clipboardState = clip)
            }
        }
    }

    fun refresh() {
        loadData()
    }

    fun clearClipboard() {
        globalClipboardManager.clear()
    }

    fun cancelTransfer() {
        activeTransferJob?.cancel()
        activeTransferJob = null
        _uiState.value = _uiState.value.copy(downloadProgress = null)
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }

    private var pendingOverwriteAction: (suspend (overwriteNames: Set<String>, skipNames: Set<String>) -> Unit)? = null

    fun pasteToMainStorage() {
        val clip = _uiState.value.clipboardState
        if (clip.paths.isEmpty()) return

        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            val target = Environment.getExternalStorageDirectory().absolutePath
            val cloudAccountId = clip.sourceCloudAccountId

            if (cloudAccountId != null) {
                val sources = clip.paths
                val isMove = clip.isCut
                val itemSizes = clip.itemSizes
                val targetFolder = File(target)

                suspend fun doPasteCloud(overwriteNames: Set<String>, skipNames: Set<String>) {
                    try {
                        val result = cloudStorageUseCase.downloadFilesToLocal(
                            context = context,
                            accountId = cloudAccountId,
                            remotePaths = sources,
                            targetDir = target,
                            itemSizes = itemSizes,
                            isMove = isMove,
                            overwriteNames = overwriteNames,
                            skipNames = skipNames
                        ) { progress -> _uiState.value = _uiState.value.copy(downloadProgress = progress) }

                        _uiState.value = _uiState.value.copy(
                            downloadProgress = null,
                            toastMessage = when {
                                result.failedNames.isEmpty() -> _uiState.value.toastMessage
                                result.failedNames.size == 1 -> "Failed to copy \"${result.failedNames.first()}\""
                                else -> "Failed to copy ${result.failedNames.size} file(s)"
                            }
                        )
                        globalClipboardManager.clear()
                        refresh()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        _uiState.value = _uiState.value.copy(downloadProgress = null)
                    }
                }

                val conflicts = sources.mapNotNull { remotePath ->
                    val name = File(remotePath).name
                    val destFile = File(targetFolder, name)
                    if (destFile.exists()) {
                        com.antigravity.filemanager.domain.model.OverwriteConflict(
                            name = name,
                            existingSize = destFile.length(),
                            newSize = itemSizes[remotePath] ?: 0L
                        )
                    } else null
                }

                if (conflicts.isNotEmpty()) {
                    pendingOverwriteAction = { overwriteNames, skipNames -> doPasteCloud(overwriteNames, skipNames) }
                    _uiState.value = _uiState.value.copy(overwriteConflicts = conflicts)
                } else {
                    doPasteCloud(emptySet(), emptySet())
                }
                return@launch
            }

            suspend fun doPaste(overwriteNames: Set<String>, skipNames: Set<String>) {
                if (clip.isCut) {
                    fileOperationsUseCase.move(clip.paths, target, overwriteNames, skipNames)
                } else {
                    fileOperationsUseCase.copy(clip.paths, target, overwriteNames, skipNames)
                }
                globalClipboardManager.clear()
                refresh()
            }

            val conflicts = fileOperationsUseCase.findConflicts(clip.paths, target)
            if (conflicts.isNotEmpty()) {
                pendingOverwriteAction = { overwriteNames, skipNames -> doPaste(overwriteNames, skipNames) }
                _uiState.value = _uiState.value.copy(overwriteConflicts = conflicts)
            } else {
                doPaste(emptySet(), emptySet())
            }
        }
    }

    fun resolveOverwriteConflict(overwriteNames: Set<String>, skipNames: Set<String>) {
        val action = pendingOverwriteAction
        pendingOverwriteAction = null
        _uiState.value = _uiState.value.copy(overwriteConflicts = emptyList())
        if (action != null) {
            viewModelScope.launch { action(overwriteNames, skipNames) }
        }
    }

    fun cancelOverwriteConflict() {
        pendingOverwriteAction = null
        _uiState.value = _uiState.value.copy(overwriteConflicts = emptyList())
    }

    private fun loadData() {
        loadDataJob?.cancel()
        loadDataJob = viewModelScope.launch {
            getDashboardDataUseCase.observeSummaries().collect { categories ->
                val volume = getDashboardDataUseCase.getStorageInfo()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    storageVolume = volume,
                    categories = categories
                )
            }
        }
    }
}
