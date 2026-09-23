package com.antigravity.filemanager.presentation.navigation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.filemanager.data.local.cache.FolderCacheManager
import com.antigravity.filemanager.domain.model.CloudTransferProgress
import com.antigravity.filemanager.domain.model.OverwriteConflict
import com.antigravity.filemanager.domain.usecase.CloudStorageUseCase
import com.antigravity.filemanager.domain.usecase.FileOperationsUseCase
import com.antigravity.filemanager.domain.usecase.GlobalClipboardState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class DualPaneDropUiState(
    val progress: CloudTransferProgress? = null,
    val conflicts: List<OverwriteConflict> = emptyList(),
    val message: String? = null
)

/**
 * Runs dual-panel drops whose destination isn't the folder a pane is showing (a dashboard card,
 * a cloud account, the Recycle Bin) in place, without opening it: the same conflict check,
 * progress and copy/move/upload/download steps a paste there would take.
 */
@HiltViewModel
class DualPaneDropViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileOperationsUseCase: FileOperationsUseCase,
    private val cloudUseCase: CloudStorageUseCase,
    private val folderCacheManager: FolderCacheManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(DualPaneDropUiState())
    val uiState: StateFlow<DualPaneDropUiState> = _uiState.asStateFlow()

    private var job: Job? = null
    private var pendingRun: (suspend (Set<String>, Set<String>) -> Unit)? = null

    /** A drop location as built by DualPaneDrag: a local folder path or "cloud:<account>:<path>". */
    private data class Destination(val accountId: String?, val path: String)

    private fun parse(location: String): Destination {
        if (!location.startsWith("cloud:")) return Destination(null, location)
        val rest = location.removePrefix("cloud:")
        val split = rest.indexOf(":/")
        return Destination(rest.substring(0, split), rest.substring(split + 1))
    }

    fun dropInto(location: String, items: GlobalClipboardState) {
        if (items.paths.isEmpty()) return
        val dest = parse(location)
        job?.cancel()
        job = viewModelScope.launch {
            val source = items.sourceCloudAccountId
            val conflicts = when {
                dest.accountId != null -> cloudUseCase.findConflicts(
                    dest.accountId, dest.path,
                    items.paths.map { File(it).name to (items.itemSizes[it] ?: if (source == null) File(it).length() else 0L) }
                )
                source == null -> fileOperationsUseCase.findConflicts(items.paths, dest.path)
                else -> cloudUseCase.findLocalConflicts(items.paths, dest.path, items.itemSizes, items.itemIsDirectory)
            }
            if (conflicts.isEmpty()) {
                transfer(dest, items, emptySet(), emptySet())
            } else {
                pendingRun = { overwrite, skip -> transfer(dest, items, overwrite, skip) }
                _uiState.update { it.copy(conflicts = conflicts) }
            }
        }
    }

    fun resolveConflicts(overwriteNames: Set<String>, skipNames: Set<String>) {
        val run = pendingRun ?: return
        pendingRun = null
        _uiState.update { it.copy(conflicts = emptyList()) }
        job = viewModelScope.launch { run(overwriteNames, skipNames) }
    }

    fun cancelConflicts() {
        pendingRun = null
        _uiState.update { it.copy(conflicts = emptyList()) }
    }

    fun cancelTransfer() {
        job?.cancel()
    }

    fun trash(items: GlobalClipboardState) {
        if (items.paths.isEmpty()) return
        job = viewModelScope.launch {
            val accountId = items.sourceCloudAccountId
            val trashed = if (accountId == null) {
                val count = fileOperationsUseCase.delete(items.paths, moveToRecycleBin = true).getOrDefault(0)
                items.paths.mapNotNull { File(it).parent }.distinct().forEach { folderCacheManager.invalidateLocal(it) }
                count
            } else {
                val removed = items.paths.filter { cloudUseCase.deleteItem(accountId, it, moveToTrash = true).isSuccess }
                notifyCloudRemoved(accountId, removed)
                removed.size
            }
            _uiState.update {
                it.copy(
                    message = if (trashed == items.paths.size) "Moved $trashed item(s) to trash"
                    else "Moved $trashed of ${items.paths.size} item(s) to trash"
                )
            }
        }
    }

    private suspend fun transfer(dest: Destination, items: GlobalClipboardState, overwrite: Set<String>, skip: Set<String>) {
        val source = items.sourceCloudAccountId
        val targetAccount = dest.accountId
        val paths = items.paths
        val isMove = items.isCut
        val verb = if (isMove) "Moved" else "Copied"
        try {
            val summary: String = if (targetAccount == null && source == null) {
                // Local -> local
                val label = if (isMove) "Moving" else "Copying"
                val onProgress: (String, Int, Int) -> Unit = { name, index, total ->
                    _uiState.update { it.copy(progress = CloudTransferProgress.forItemCount(name, index, total, isUpload = true, operationLabel = label)) }
                }
                val result = if (isMove) fileOperationsUseCase.move(paths, dest.path, overwrite, skip, onProgress)
                else fileOperationsUseCase.copy(paths, dest.path, overwrite, skip, onProgress)
                folderCacheManager.invalidateLocal(dest.path)
                if (isMove) paths.mapNotNull { File(it).parent }.distinct().forEach { folderCacheManager.invalidateLocal(it) }
                result.fold({ "$verb ${paths.count { File(it).name !in skip }} item(s)" }, { "Couldn't finish: ${it.message}" })
            } else if (targetAccount == null && source != null) {
                // Cloud -> local
                val result = cloudUseCase.downloadFilesToLocal(
                    context, source, paths, dest.path, items.itemSizes, isMove, overwrite, skip, items.itemIsDirectory
                ) { p -> _uiState.update { it.copy(progress = p) } }
                folderCacheManager.invalidateLocal(dest.path)
                if (isMove) notifyCloudRemoved(source, paths.filter { File(it).name !in skip && File(it).name !in result.failedNames })
                if (result.failedNames.isEmpty()) "$verb ${result.scannedPaths.size} item(s)"
                else "Finished with ${result.failedNames.size} failure(s)"
            } else if (targetAccount != null && source == null) {
                // Local -> cloud
                val result = cloudUseCase.uploadFiles(targetAccount, paths, dest.path, overwrite, skip) { name, index, total, sent, totalBytes ->
                    _uiState.update {
                        it.copy(progress = CloudTransferProgress(
                            currentFileName = name, currentIndex = index, totalFiles = total,
                            bytesTransferred = sent, totalBytes = totalBytes, isIndeterminate = totalBytes <= 0, isUpload = true
                        ))
                    }
                }
                if (isMove && result.isSuccess) {
                    // Only what actually went up; a skipped conflict never left the device.
                    fileOperationsUseCase.delete(paths.filter { File(it).name !in skip }, moveToRecycleBin = false)
                    paths.mapNotNull { File(it).parent }.distinct().forEach { folderCacheManager.invalidateLocal(it) }
                }
                folderCacheManager.invalidateCloud(targetAccount, dest.path)
                result.fold({ "$verb $it item(s)" }, { "Couldn't finish: ${it.message}" })
            } else if (isMove && source == targetAccount) {
                // Cloud -> same account, move: server-side, nothing downloaded
                val account = targetAccount!!
                val moved = mutableListOf<String>()
                for ((index, path) in paths.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val name = File(path).name
                    if (name in skip) continue
                    if (name in overwrite) cloudUseCase.deleteItem(account, childPath(dest.path, name))
                    _uiState.update { it.copy(progress = CloudTransferProgress.forItemCount(name, index + 1, paths.size, isUpload = true, operationLabel = "Moving")) }
                    if (cloudUseCase.moveWithinAccount(account, path, dest.path).isSuccess) moved += path
                }
                notifyCloudRemoved(account, moved)
                folderCacheManager.invalidateCloud(account, dest.path)
                "Moved ${moved.size} item(s)"
            } else {
                // Cloud -> cloud: another account, or a copy within one
                val result = cloudUseCase.copyBetweenClouds(
                    context, source!!, paths, items.itemIsDirectory, targetAccount!!, dest.path, isMove, overwrite, skip
                ) { p -> _uiState.update { it.copy(progress = p) } }
                if (isMove) notifyCloudRemoved(source, paths.filter { File(it).name !in skip })
                folderCacheManager.invalidateCloud(targetAccount, dest.path)
                if (result.failures == 0) "$verb ${result.transferred} item(s)" else "Finished with ${result.failures} failure(s)"
            }
            _uiState.update { it.copy(progress = null, message = summary) }
        } catch (e: CancellationException) {
            _uiState.update { it.copy(progress = null, message = "Transfer cancelled") }
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(progress = null, message = "Couldn't finish: ${e.message}") }
        }
    }

    private fun childPath(dir: String, name: String) = if (dir == "/" || dir.isBlank()) "/$name" else "${dir.trimEnd('/')}/$name"

    /** Lets a pane still showing moved or trashed cloud items drop them without a re-list. */
    private suspend fun notifyCloudRemoved(accountId: String, paths: List<String>) {
        paths.groupBy { it.substringBeforeLast('/', "/").ifEmpty { "/" } }.forEach { (parent, removed) ->
            folderCacheManager.notifyCloudFilesRemoved(accountId, parent, removed.toSet())
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
