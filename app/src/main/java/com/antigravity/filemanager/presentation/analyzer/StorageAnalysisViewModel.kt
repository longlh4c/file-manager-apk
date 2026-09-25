package com.antigravity.filemanager.presentation.analyzer

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.update
import androidx.lifecycle.viewModelScope
import com.antigravity.filemanager.domain.model.CloudTransferProgress
import com.antigravity.filemanager.domain.model.StorageAnalysisData
import com.antigravity.filemanager.domain.model.StorageCategoryBreakdown
import com.antigravity.filemanager.domain.model.StorageVolumeInfo
import com.antigravity.filemanager.domain.usecase.StorageAnalysisUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val pendingOverwriteZipPath: String? = null,
    val pendingPasswordArchive: String? = null,
    val passwordError: String? = null,
    val overwriteConflicts: List<com.antigravity.filemanager.domain.model.OverwriteConflict> = emptyList(),
    val transferCancelledByUser: Boolean = false,
    val toastMessage: String? = null
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
        _uiState.update { old -> old.copy(isLoading = true) }
        loadData(isInitial = true)
        observeBookmarks()
    }

    fun clearToast() {
        _uiState.update { old -> old.copy(toastMessage = null) }
    }

    fun cancelTransfer() {
        _uiState.update { old -> old.copy(
            transferProgress = null,
            transferCancelledByUser = true
        ) }
        activeTransferJob?.cancel()
        activeTransferJob = null
    }

    private fun observeBookmarks() {
        viewModelScope.launch {
            bookmarkUseCase.observeBookmarks().collectLatest { list ->
                _uiState.update { old -> old.copy(bookmarks = list) }
            }
        }
    }

    fun loadData(isInitial: Boolean = false) {
        viewModelScope.launch { loadDataInternal(isInitial) }
    }

    /** Suspending body of [loadData], so mutation handlers can await a full rescan before continuing. */
    private suspend fun loadDataInternal(isInitial: Boolean = false) {
        if (isInitial) {
            _uiState.update { old -> old.copy(isLoading = true) }
        }
        val result = storageAnalysisUseCase.getAnalysisData()
        _uiState.update { old -> old.copy(
            isLoading = false,
            data = result
        ) }
    }

    fun refresh() {
        loadData(isInitial = false)
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
            val result = fileOperationsUseCase.rename(path, newName)
            if (result.isFailure) {
                // Used to patch the list as if the rename had happened even when it failed.
                _uiState.update { old -> old.copy(toastMessage = result.exceptionOrNull()?.message ?: "Rename failed") }
                return@launch
            }

            if (isDirectory) {
                loadDataInternal()
                _uiState.update { old -> old.copy(mutationTick = _uiState.value.mutationTick + 1) }
            } else {
                // Renaming a file doesn't change any byte totals — patch the affected entry
                // in place instead of re-walking the entire device just to reflect a name change.
                val parentDir = File(path).parent
                val newPath = if (parentDir != null) File(parentDir, newName).absolutePath else newName
                val currentData = _uiState.value.data
                _uiState.update { old -> old.copy(
                    data = currentData.copy(
                        largeFiles = currentData.largeFiles.map {
                            if (it.path == path) it.copy(path = newPath, name = newName) else it
                        }
                    ),
                    mutationTick = _uiState.value.mutationTick + 1
                ) }
            }
            onComplete()
        }
    }

    private var pendingCompress: Triple<List<String>, String, () -> Unit>? = null

    fun compress(paths: List<String>, archiveName: String, targetDir: String, onComplete: () -> Unit = {}) {
        val name = if (archiveName.endsWith(".7z", ignoreCase = true) || archiveName.endsWith(".zip", ignoreCase = true)) {
            archiveName
        } else {
            "$archiveName.zip"
        }
        val targetArchive = "$targetDir/$name"
        // Checked before the overwrite prompt: the archive would replace one of its own sources.
        com.antigravity.filemanager.data.local.storage.archiveTargetConflictReason(targetArchive, paths)?.let { reason ->
            _uiState.update { old -> old.copy(toastMessage = reason) }
            return
        }
        if (File(targetArchive).exists()) {
            pendingCompress = Triple(paths, targetArchive, onComplete)
            _uiState.update { old -> old.copy(pendingOverwriteZipPath = targetArchive) }
            return
        }
        runCompress(paths, targetArchive, onComplete)
    }

    fun confirmCompressOverwrite() {
        val (paths, targetArchive, onComplete) = pendingCompress ?: return
        pendingCompress = null
        _uiState.update { old -> old.copy(pendingOverwriteZipPath = null) }
        // The old archive is replaced only once the new one is complete (see compressFiles).
        runCompress(paths, targetArchive, onComplete)
    }

    fun cancelCompressOverwrite() {
        pendingCompress = null
        _uiState.update { old -> old.copy(pendingOverwriteZipPath = null) }
    }

    private fun runCompress(paths: List<String>, targetArchive: String, onComplete: () -> Unit) {
        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            try {
                // An archive being overwritten already takes up its old size.
                val previousSize = File(targetArchive).takeIf { it.isFile }?.length() ?: 0L
                val result = fileOperationsUseCase.compress(paths, targetArchive) { currentFile, currentIndex, totalFiles, bytesProcessed, totalBytes ->
                    if (!this@launch.isActive || _uiState.value.transferCancelledByUser) return@compress
                    val p = if (totalBytes > 0L) {
                        ((bytesProcessed.toDouble() / totalBytes.toDouble()) * 100).toInt().coerceIn(0, 100)
                    } else if (totalFiles > 0) {
                        ((currentIndex.toFloat() / totalFiles.toFloat()) * 100).toInt().coerceIn(0, 100)
                    } else 0
                    _uiState.update { old -> old.copy(
                        transferProgress = CloudTransferProgress(
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
                val archiveFile = File(targetArchive)
                // A failed overwrite leaves the old archive in place, which adds nothing.
                if (result.isSuccess && archiveFile.exists()) {
                    val currentData = _uiState.value.data
                    val archiveSize = archiveFile.length() - previousSize
                    _uiState.update { old -> old.copy(
                        data = currentData.copy(
                            volumeInfo = currentData.volumeInfo.copy(
                                usedBytes = currentData.volumeInfo.usedBytes + archiveSize,
                                freeBytes = (currentData.volumeInfo.freeBytes - archiveSize).coerceAtLeast(0L)
                            )
                        ),
                        mutationTick = _uiState.value.mutationTick + 1
                    ) }
                }
                onComplete()
            } finally {
                withContext(NonCancellable) {
                    _uiState.update { old -> old.copy(
                        transferProgress = null,
                        transferCancelledByUser = false
                    ) }
                }
            }
        }
    }

    private var pendingExtractDir: String? = null
    private var pendingExtractOnComplete: (() -> Unit)? = null
    // Archives of an extraction waiting on a password, and the passwords entered so far (per
    // archive). Entering one used to extract only that archive and drop the rest of the selection.
    private var pendingExtractBatch: List<String>? = null
    private var extractPasswords: Map<String, String> = emptyMap()

    private fun awaitPassword(batch: List<String>, targetDir: String, passwords: Map<String, String>, onComplete: () -> Unit) {
        pendingExtractBatch = batch
        pendingExtractDir = targetDir
        extractPasswords = passwords
        pendingExtractOnComplete = onComplete
    }
    private var pendingOverwriteAction: (suspend (overwriteNames: Set<String>, skipNames: Set<String>) -> Unit)? = null

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

    fun extract(paths: List<String>, targetDir: String, onComplete: () -> Unit = {}) {
        if (paths.isEmpty()) return
        awaitPassword(paths, targetDir, emptyMap(), onComplete)
        if (paths.size == 1 && fileOperationsUseCase.isArchiveEncrypted(paths[0])) {
            _uiState.update { old -> old.copy(
                pendingPasswordArchive = paths[0],
                passwordError = null
            ) }
            return
        }
        checkExtractConflictsAndRun(paths, targetDir, emptyMap(), onComplete)
    }

    private fun checkExtractConflictsAndRun(
        paths: List<String>,
        targetDir: String,
        passwords: Map<String, String>,
        onComplete: () -> Unit
    ) {
        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            val allConflicts = mutableListOf<com.antigravity.filemanager.domain.model.OverwriteConflict>()
            for (path in paths) {
                try {
                    val conflicts = fileOperationsUseCase.getArchiveConflicts(path, targetDir, passwords[path])
                    allConflicts.addAll(conflicts)
                } catch (e: com.antigravity.filemanager.data.local.storage.ArchivePasswordRequiredException) {
                    awaitPassword(paths, targetDir, passwords, onComplete)
                    _uiState.update { old -> old.copy(
                        transferProgress = null,
                        pendingPasswordArchive = path,
                        passwordError = null
                    ) }
                    return@launch
                } catch (e: com.antigravity.filemanager.data.local.storage.ArchiveInvalidPasswordException) {
                    awaitPassword(paths, targetDir, passwords, onComplete)
                    _uiState.update { old -> old.copy(
                        transferProgress = null,
                        pendingPasswordArchive = path,
                        passwordError = "Incorrect password. Please try again."
                    ) }
                    return@launch
                } catch (e: Exception) {
                    return@launch
                }
            }

            if (allConflicts.isNotEmpty()) {
                pendingOverwriteAction = { overwriteNames, skipNames ->
                    runExtract(paths, targetDir, passwords, overwriteNames, skipNames, onComplete)
                }
                _uiState.update { old -> old.copy(overwriteConflicts = allConflicts) }
            } else {
                runExtract(paths, targetDir, passwords, emptySet(), emptySet(), onComplete)
            }
        }
    }

    private fun runExtract(
        paths: List<String>,
        targetDir: String,
        passwords: Map<String, String>,
        overwriteNames: Set<String> = emptySet(),
        skipNames: Set<String> = emptySet(),
        onComplete: () -> Unit
    ) {
        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            try {
                var anySucceeded = false
                for ((index, p) in paths.withIndex()) {
                    if (!isActive || _uiState.value.transferCancelledByUser) break
                    val archiveName = File(p).name
                    _uiState.update { old -> old.copy(
                        transferProgress = CloudTransferProgress(
                            currentFileName = archiveName,
                            currentIndex = index + 1,
                            totalFiles = paths.size,
                            isIndeterminate = false,
                            isUpload = false,
                            operationLabel = if (paths.size > 1) "Extracting (${index + 1}/${paths.size})" else "Extracting",
                            percent = 0
                        )
                    ) }
                    val res = fileOperationsUseCase.extract(
                        archivePath = p,
                        targetDir = targetDir,
                        password = passwords[p],
                        overwriteNames = overwriteNames,
                        skipNames = skipNames
                    ) { currentEntry, currentIndex, totalEntries, bytesProcessed, totalBytes ->
                        if (!this@launch.isActive || _uiState.value.transferCancelledByUser) return@extract
                        val pPercent = if (totalBytes > 0L) {
                            ((bytesProcessed.toDouble() / totalBytes.toDouble()) * 100).toInt().coerceIn(0, 100)
                        } else if (totalEntries > 0) {
                            ((currentIndex.toFloat() / totalEntries.toFloat()) * 100).toInt().coerceIn(0, 100)
                        } else 0
                        _uiState.update { old -> old.copy(
                            transferProgress = CloudTransferProgress(
                                currentFileName = currentEntry.ifEmpty { File(p).name },
                                currentIndex = currentIndex,
                                totalFiles = totalEntries,
                                bytesTransferred = bytesProcessed,
                                totalBytes = totalBytes,
                                isIndeterminate = false,
                                isUpload = false,
                                operationLabel = if (paths.size > 1) "Extracting (${index + 1}/${paths.size})" else "Extracting",
                                percent = pPercent
                            )
                        ) }
                    }
                    if (res.isSuccess) {
                        val extractResult = res.getOrNull()
                        if (extractResult != null && extractResult.extractedCount > 0) {
                            anySucceeded = true
                        }
                    } else {
                        val ex = res.exceptionOrNull()
                        if (ex is CancellationException) {
                            break
                        }
                        if (ex is com.antigravity.filemanager.data.local.storage.ArchivePasswordRequiredException) {
                            // The archives already extracted aren't redone after the password.
                            awaitPassword(paths.drop(index), targetDir, passwords, onComplete)
                            _uiState.update { old -> old.copy(
                                transferProgress = null,
                                pendingPasswordArchive = p,
                                passwordError = null
                            ) }
                            return@launch
                        } else if (ex is com.antigravity.filemanager.data.local.storage.ArchiveInvalidPasswordException) {
                            awaitPassword(paths.drop(index), targetDir, passwords, onComplete)
                            _uiState.update { old -> old.copy(
                                transferProgress = null,
                                pendingPasswordArchive = p,
                                passwordError = "Incorrect password. Please try again."
                            ) }
                            return@launch
                        }
                    }
                }
                _uiState.update { old -> old.copy(
                    transferProgress = null,
                    pendingPasswordArchive = null,
                    passwordError = null
                ) }
                if (anySucceeded && !_uiState.value.transferCancelledByUser) {
                    loadDataInternal()
                    _uiState.update { old -> old.copy(mutationTick = _uiState.value.mutationTick + 1) }
                }
                onComplete()
            } finally {
                withContext(NonCancellable) {
                    _uiState.update { old -> old.copy(
                        transferProgress = null,
                        transferCancelledByUser = false
                    ) }
                }
            }
        }
    }

    fun submitArchivePassword(password: String) {
        val archivePath = _uiState.value.pendingPasswordArchive ?: return
        val targetDir = pendingExtractDir ?: return
        val onComplete = pendingExtractOnComplete ?: {}
        val batch = pendingExtractBatch ?: listOf(archivePath)
        val passwords = extractPasswords + (archivePath to password)
        pendingExtractBatch = null
        extractPasswords = emptyMap()
        // Dismiss password dialog immediately
        _uiState.update { old -> old.copy(pendingPasswordArchive = null, passwordError = null) }
        checkExtractConflictsAndRun(batch, targetDir, passwords, onComplete)
    }

    fun dismissPasswordDialog() {
        _uiState.update { old -> old.copy(pendingPasswordArchive = null, passwordError = null) }
        pendingExtractDir = null
        pendingExtractOnComplete = null
        pendingExtractBatch = null
        extractPasswords = emptyMap()
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

            fileOperationsUseCase.delete(paths, moveToRecycleBin = true).let { result ->
                val deleted = result.getOrNull()
                if (deleted == null || deleted < paths.size) {
                    _uiState.update { old -> old.copy(toastMessage = result.exceptionOrNull()?.let { "Delete failed: ${it.message}" } ?: "$deleted of ${paths.size} item(s) deleted") }
                }
            }
            // Checked on disk after the delete: a partly failed delete used to drop every selected
            // item from the list, so files that were never deleted vanished from the screen.
            fun isRemoved(itemPath: String) =
                (itemPath in paths || deletedDirPrefixes.any { itemPath.startsWith(it) }) && !File(itemPath).exists()
            val matchedLargeBytes = currentData.largeFiles.filter { isRemoved(it.path) }.sumOf { it.sizeBytes }

            _uiState.update { old -> old.copy(
                data = currentData.copy(
                    largeFiles = currentData.largeFiles.filterNot { isRemoved(it.path) },
                    largeFilesTotalBytes = (currentData.largeFilesTotalBytes - matchedLargeBytes).coerceAtLeast(0L)
                ),
                mutationTick = _uiState.value.mutationTick + 1
            ) }
            onComplete()
        }
    }

    fun deleteDuplicates(paths: List<String>, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            fileOperationsUseCase.delete(paths, moveToRecycleBin = true).let { result ->
                val deleted = result.getOrNull()
                if (deleted == null || deleted < paths.size) {
                    _uiState.update { old -> old.copy(toastMessage = result.exceptionOrNull()?.let { "Delete failed: ${it.message}" } ?: "$deleted of ${paths.size} item(s) deleted") }
                }
            }
            // Removing a duplicate can change which copy is now the "earliest" survivor in its
            // group, and affects both the Downloads-scoped and full-storage totals — cheapest
            // correct option is the same full rescan the other mutations above already pay for.
            loadDataInternal()
            _uiState.update { old -> old.copy(mutationTick = _uiState.value.mutationTick + 1) }
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
