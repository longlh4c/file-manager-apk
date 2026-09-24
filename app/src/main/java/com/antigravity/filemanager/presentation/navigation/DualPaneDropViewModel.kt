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
import com.antigravity.filemanager.domain.usecase.isCloudFolderOrInside
import com.antigravity.filemanager.domain.usecase.isInCloudFolder
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

    fun dropInto(location: String, dropped: GlobalClipboardState) {
        if (dropped.paths.isEmpty()) return
        val requested = parse(location)
        if (isBusy()) return
        job = viewModelScope.launch {
            // A Google Drive account's root is a virtual menu; a drop there lands in My Drive.
            val dest = requested.accountId
                ?.let { requested.copy(path = cloudUseCase.resolveWriteDir(it, requested.path)) }
                ?: requested
            // Same guards as a paste in the cloud explorer: never into a dragged folder itself,
            // and items of this account already in the destination clash only with themselves
            // (a move leaves them be, a copy lands under a new name, neither offers "Overwrite").
            val sameAccount = dest.accountId != null && dest.accountId == dropped.sourceCloudAccountId
            if (sameAccount) {
                dropped.paths.firstOrNull { isCloudFolderOrInside(dest.path, it) }?.let {
                    _uiState.update { s -> s.copy(message = "Cannot ${if (dropped.isCut) "move" else "copy"} a folder into itself: ${File(it).name}") }
                    return@launch
                }
            }
            val alreadyHere = if (sameAccount) dropped.paths.filter { isInCloudFolder(it, dest.path) } else emptyList()
            val alreadyHereNames = alreadyHere.map { File(it).name }.toSet()
            val items = if (dropped.isCut && alreadyHere.isNotEmpty()) dropped.copy(paths = dropped.paths - alreadyHere.toSet()) else dropped
            if (items.paths.isEmpty()) {
                _uiState.update { it.copy(message = "Already in this folder") }
                return@launch
            }
            val source = items.sourceCloudAccountId
            val conflicts = when {
                dest.accountId != null -> cloudUseCase.findConflicts(
                    dest.accountId, dest.path,
                    items.paths.map { File(it).name to (items.itemSizes[it] ?: if (source == null) File(it).length() else 0L) }
                )
                source == null -> fileOperationsUseCase.findConflicts(items.paths, dest.path)
                else -> cloudUseCase.findLocalConflicts(items.paths, dest.path, items.itemSizes, items.itemIsDirectory)
            }.filterNot { it.name in alreadyHereNames }
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

    /** A drop while another one is still running (or waiting on its conflict dialog) used to
     * cancel that one silently, cutting a copy or move off halfway. It is refused instead. */
    private fun isBusy(): Boolean {
        if (job?.isActive != true && pendingRun == null) return false
        _uiState.update { it.copy(message = "Another transfer is still running") }
        return true
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
        // Files on a USB drive or SD card can only be deleted permanently; a drag onto the Recycle
        // Bin card shouldn't do that behind a "move to trash" gesture with no permanent-delete
        // confirmation, so point to the folder's own Delete instead.
        if (items.sourceCloudAccountId == null &&
            items.paths.any { com.antigravity.filemanager.data.local.storage.isOutsidePrimaryStorage(it) }
        ) {
            _uiState.update { it.copy(message = "Files on a USB drive or SD card can't go to the Recycle Bin. Delete them from their folder instead.") }
            return
        }
        if (isBusy()) return
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
                // Overwritten items merge into the existing ones and replace a file only once its
                // replacement is up (see copyBetweenClouds); the rest move server-side.
                val overwritten = paths.filter { File(it).name in overwrite && File(it).name !in skip }
                var failures = 0
                if (overwritten.isNotEmpty()) {
                    val merged = cloudUseCase.copyBetweenClouds(
                        context, account, overwritten, items.itemIsDirectory, account, dest.path,
                        isMove = true, overwriteNames = overwrite, skipNames = skip
                    ) { p -> _uiState.update { it.copy(progress = p) } }
                    failures += merged.failures
                    folderCacheManager.invalidateCloud(account, dest.path)
                    overwritten.map { it.substringBeforeLast('/', "/").ifEmpty { "/" } }.distinct()
                        .forEach { folderCacheManager.invalidateCloud(account, it) }
                }
                for ((index, path) in paths.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val name = File(path).name
                    if (name in skip || path in overwritten) continue
                    _uiState.update { it.copy(progress = CloudTransferProgress.forItemCount(name, index + 1, paths.size, isUpload = true, operationLabel = "Moving")) }
                    if (cloudUseCase.moveWithinAccount(account, path, dest.path).isSuccess) moved += path else failures++
                }
                notifyCloudRemoved(account, moved)
                folderCacheManager.invalidateCloud(account, dest.path)
                if (failures == 0) "Moved ${moved.size + overwritten.size} item(s)" else "Finished with $failures failure(s)"
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
