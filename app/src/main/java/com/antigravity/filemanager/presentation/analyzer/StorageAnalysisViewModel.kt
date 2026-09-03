package com.antigravity.filemanager.presentation.analyzer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.filemanager.domain.model.CloudTransferProgress
import com.antigravity.filemanager.domain.model.StorageAnalysisData
import com.antigravity.filemanager.domain.model.StorageCategoryBreakdown
import com.antigravity.filemanager.domain.model.StorageVolumeInfo
import com.antigravity.filemanager.domain.usecase.StorageAnalysisUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

import com.antigravity.filemanager.domain.model.Bookmark
import com.antigravity.filemanager.domain.usecase.BookmarkUseCase
import com.antigravity.filemanager.domain.usecase.FileOperationsUseCase
import com.antigravity.filemanager.domain.usecase.GlobalClipboardManager
import kotlinx.coroutines.flow.collectLatest
import java.io.File

data class StorageAnalysisUiState(
    val isLoading: Boolean = true,
    val data: StorageAnalysisData = StorageAnalysisData(
        volumeInfo = StorageVolumeInfo(256L * 1024 * 1024 * 1024, 241L * 1024 * 1024 * 1024, 14_870_000_000L),
        breakdown = StorageCategoryBreakdown(
            imagesBytes = 42L * 1024 * 1024 * 1024,
            audioBytes = 11L * 1024 * 1024 * 1024,
            videosBytes = 48L * 1024 * 1024 * 1024,
            documentsBytes = 79L * 1024 * 1024,
            archivesBytes = 1_200_000L,
            othersBytes = 35L * 1024 * 1024 * 1024
        ),
        largeFiles = emptyList(),
        largeFilesTotalBytes = 89_120_000_000L,
        recycleBinBytes = 20_960_000L
    ),
    val bookmarks: List<Bookmark> = emptyList(),
    // Bumped on every mutation (rename/compress/extract/delete), independent of whether that
    // mutation triggered a full `data` rescan. Screens that list a specific folder locally
    // (e.g. StorageFolderBreakdownScreen) key their own re-listing off this instead of `data`,
    // so they still refresh even when a mutation only patched `data` in place.
    val mutationTick: Int = 0,
    val transferProgress: CloudTransferProgress? = null,
    val pendingOverwriteZipPath: String? = null
)

@HiltViewModel
class StorageAnalysisViewModel @Inject constructor(
    private val storageAnalysisUseCase: StorageAnalysisUseCase,
    private val fileOperationsUseCase: FileOperationsUseCase,
    private val globalClipboardManager: GlobalClipboardManager,
    private val bookmarkUseCase: BookmarkUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(StorageAnalysisUiState())
    val uiState: StateFlow<StorageAnalysisUiState> = _uiState.asStateFlow()

    private var activeTransferJob: kotlinx.coroutines.Job? = null

    init {
        loadData()
        observeBookmarks()
    }

    fun cancelTransfer() {
        activeTransferJob?.cancel()
        activeTransferJob = null
        _uiState.value = _uiState.value.copy(transferProgress = null)
    }

    private fun observeBookmarks() {
        viewModelScope.launch {
            bookmarkUseCase.observeBookmarks().collectLatest { list ->
                _uiState.value = _uiState.value.copy(bookmarks = list)
            }
        }
    }

    fun loadData() {
        viewModelScope.launch { loadDataInternal() }
    }

    /** Suspending body of [loadData], so mutation handlers can await a full rescan before continuing. */
    private suspend fun loadDataInternal() {
        _uiState.value = _uiState.value.copy(isLoading = true)
        val result = storageAnalysisUseCase.getAnalysisData()
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            data = result
        )
    }

    fun refresh() {
        loadData()
    }

    fun copySelected(paths: List<String>) {
        globalClipboardManager.copy(paths, paths.associateWith { File(it).length() })
    }

    fun cutSelected(paths: List<String>) {
        globalClipboardManager.cut(paths, paths.associateWith { File(it).length() })
    }

    fun rename(path: String, newName: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            // Renaming a folder can invalidate the path of every large file nested inside it
            // (if any are in the current list), so only fast-path plain file renames — the
            // common case — and fall back to a full rescan for folders to stay correct.
            val isDirectory = File(path).isDirectory
            fileOperationsUseCase.rename(path, newName)

            if (isDirectory) {
                loadDataInternal()
                _uiState.value = _uiState.value.copy(mutationTick = _uiState.value.mutationTick + 1)
            } else {
                // Renaming a file doesn't change any byte totals — patch the affected entry
                // in place instead of re-walking the entire device just to reflect a name change.
                val parentDir = File(path).parent
                val newPath = if (parentDir != null) File(parentDir, newName).absolutePath else newName
                val currentData = _uiState.value.data
                _uiState.value = _uiState.value.copy(
                    data = currentData.copy(
                        largeFiles = currentData.largeFiles.map {
                            if (it.path == path) it.copy(path = newPath, name = newName) else it
                        }
                    ),
                    mutationTick = _uiState.value.mutationTick + 1
                )
            }
            onComplete()
        }
    }

    private var pendingCompress: Triple<List<String>, String, () -> Unit>? = null

    fun compress(paths: List<String>, zipName: String, targetDir: String, onComplete: () -> Unit = {}) {
        val name = if (zipName.endsWith(".zip")) zipName else "$zipName.zip"
        val targetZip = "$targetDir/$name"
        if (File(targetZip).exists()) {
            pendingCompress = Triple(paths, targetZip, onComplete)
            _uiState.value = _uiState.value.copy(pendingOverwriteZipPath = targetZip)
            return
        }
        runCompress(paths, targetZip, onComplete)
    }

    fun confirmCompressOverwrite() {
        val (paths, targetZip, onComplete) = pendingCompress ?: return
        pendingCompress = null
        _uiState.value = _uiState.value.copy(pendingOverwriteZipPath = null)
        // zip4j appends into an existing archive rather than replacing it — delete the old one
        // first so "Replace" actually replaces instead of silently merging into stale contents.
        File(targetZip).delete()
        runCompress(paths, targetZip, onComplete)
    }

    fun cancelCompressOverwrite() {
        pendingCompress = null
        _uiState.value = _uiState.value.copy(pendingOverwriteZipPath = null)
    }

    private fun runCompress(paths: List<String>, targetZip: String, onComplete: () -> Unit) {
        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            fileOperationsUseCase.zip(paths, targetZip) { currentFile, currentIndex, totalFiles ->
                _uiState.value = _uiState.value.copy(
                    transferProgress = CloudTransferProgress(
                        currentFileName = currentFile,
                        currentIndex = currentIndex,
                        totalFiles = totalFiles,
                        isIndeterminate = true,
                        isUpload = true,
                        operationLabel = "Compressing"
                    )
                )
            }
            _uiState.value = _uiState.value.copy(transferProgress = null)
            // The archive is a single new file — a cheap stat is enough to keep totals
            // accurate without a full-device rescan (source files are left in place by zip).
            val zipFile = File(targetZip)
            if (zipFile.exists()) {
                val currentData = _uiState.value.data
                val zipSize = zipFile.length()
                _uiState.value = _uiState.value.copy(
                    data = currentData.copy(
                        volumeInfo = currentData.volumeInfo.copy(
                            usedBytes = currentData.volumeInfo.usedBytes + zipSize,
                            freeBytes = (currentData.volumeInfo.freeBytes - zipSize).coerceAtLeast(0L)
                        )
                    ),
                    mutationTick = _uiState.value.mutationTick + 1
                )
            }
            onComplete()
        }
    }

    fun extract(paths: List<String>, targetDir: String, onComplete: () -> Unit = {}) {
        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            var anySucceeded = false
            paths.forEachIndexed { index, p ->
                val archiveName = File(p).name
                _uiState.value = _uiState.value.copy(
                    transferProgress = CloudTransferProgress(
                        currentFileName = archiveName,
                        currentIndex = index + 1,
                        totalFiles = paths.size,
                        isIndeterminate = true,
                        isUpload = false,
                        operationLabel = "Extracting"
                    )
                )
                val res = fileOperationsUseCase.unzip(p, targetDir)
                if (res.isSuccess) anySucceeded = true
            }
            _uiState.value = _uiState.value.copy(transferProgress = null)
            // Extraction can add an unknown number/size of files, so an accurate total
            // genuinely requires a rescan — but only pay for it if something actually extracted.
            if (anySucceeded) {
                loadDataInternal()
                _uiState.value = _uiState.value.copy(mutationTick = _uiState.value.mutationTick + 1)
            }
            onComplete()
        }
    }

    fun deleteSelected(paths: List<String>, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            val currentData = _uiState.value.data
            // This screen always deletes via the recycle bin (moveToRecycleBin = true below):
            // the files are relocated within the same volume, not freed, so volumeInfo/used
            // space is unaffected and must NOT be adjusted here. Only the "large files" view
            // needs patching, since the moved items (including any nested under a deleted
            // folder) should no longer show up in it.
            val deletedDirPrefixes = paths.filter { File(it).isDirectory }.map { if (it.endsWith("/")) it else "$it/" }
            fun isRemoved(itemPath: String) = itemPath in paths || deletedDirPrefixes.any { itemPath.startsWith(it) }

            val matchedLargeBytes = currentData.largeFiles.filter { isRemoved(it.path) }.sumOf { it.sizeBytes }

            fileOperationsUseCase.delete(paths, moveToRecycleBin = true)

            _uiState.value = _uiState.value.copy(
                data = currentData.copy(
                    largeFiles = currentData.largeFiles.filterNot { isRemoved(it.path) },
                    largeFilesTotalBytes = (currentData.largeFilesTotalBytes - matchedLargeBytes).coerceAtLeast(0L)
                ),
                mutationTick = _uiState.value.mutationTick + 1
            )
            onComplete()
        }
    }

    fun deleteDuplicates(paths: List<String>, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            fileOperationsUseCase.delete(paths, moveToRecycleBin = true)
            // Removing a duplicate can change which copy is now the "earliest" survivor in its
            // group, and affects both the Downloads-scoped and full-storage totals — cheapest
            // correct option is the same full rescan the other mutations above already pay for.
            loadDataInternal()
            _uiState.value = _uiState.value.copy(mutationTick = _uiState.value.mutationTick + 1)
            onComplete()
        }
    }

    fun addBookmark(path: String, name: String) {
        viewModelScope.launch {
            bookmarkUseCase.addBookmark(path, name)
        }
    }

    fun removeBookmark(path: String) {
        viewModelScope.launch {
            bookmarkUseCase.removeBookmark(path)
        }
    }
}
