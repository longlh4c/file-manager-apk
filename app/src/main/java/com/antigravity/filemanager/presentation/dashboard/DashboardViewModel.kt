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
import com.antigravity.filemanager.domain.usecase.GetDashboardDataUseCase
import com.antigravity.filemanager.domain.usecase.GlobalClipboardManager
import com.antigravity.filemanager.domain.usecase.GlobalClipboardState
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

private val INITIAL_DASHBOARD_CATEGORIES = listOf(
    CategorySummary(type = CategoryType.MAIN_STORAGE, title = "Main storage"),
    CategorySummary(type = CategoryType.DOWNLOADS, title = "Downloads"),
    CategorySummary(type = CategoryType.STORAGE_ANALYSIS, title = "Storage Analysis"),
    CategorySummary(type = CategoryType.IMAGES, title = "Images"),
    CategorySummary(type = CategoryType.AUDIO, title = "Audio"),
    CategorySummary(type = CategoryType.VIDEOS, title = "Videos"),
    CategorySummary(type = CategoryType.DOCUMENTS, title = "Documents"),
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
    val downloadProgress: CloudTransferProgress? = null
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val getDashboardDataUseCase: GetDashboardDataUseCase,
    private val fileOperationsUseCase: FileOperationsUseCase,
    private val globalClipboardManager: GlobalClipboardManager,
    private val bookmarkUseCase: BookmarkUseCase,
    private val cloudStorageUseCase: com.antigravity.filemanager.domain.usecase.CloudStorageUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()
    private var activeTransferJob: kotlinx.coroutines.Job? = null
    private var loadDataJob: kotlinx.coroutines.Job? = null

    init {
        loadData()
        observeClipboard()
        observeBookmarks()
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
                        val totalCount = sources.size
                        val progressThrottler = com.antigravity.filemanager.utils.ProgressThrottler()
                        sources.forEachIndexed { index, remotePath ->
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
                            val result = cloudStorageUseCase.downloadFile(cloudAccountId, remotePath, tempDir.absolutePath) { bytesRead, totalBytes ->
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
                                        var candidate = File(targetFolder, name)
                                        val dotIndex = name.lastIndexOf('.')
                                        val base = if (dotIndex > 0) name.substring(0, dotIndex) else name
                                        val ext = if (dotIndex > 0) name.substring(dotIndex) else ""
                                        var counter = 1
                                        while (candidate.exists()) {
                                            candidate = File(targetFolder, "$base ($counter)$ext")
                                            counter++
                                        }
                                        candidate
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
                                        cloudStorageUseCase.deleteItem(cloudAccountId, remotePath)
                                    }
                                }
                            }
                            tempDir.deleteRecursively()
                        }
                        _uiState.value = _uiState.value.copy(downloadProgress = null)
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
