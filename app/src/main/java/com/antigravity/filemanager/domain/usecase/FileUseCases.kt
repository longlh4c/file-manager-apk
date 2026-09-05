package com.antigravity.filemanager.domain.usecase

import com.antigravity.filemanager.domain.model.*
import com.antigravity.filemanager.domain.repository.*
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Inject

class GetDashboardDataUseCase @Inject constructor(
    private val storageRepository: IStorageRepository,
    private val recycleBinRepository: IRecycleBinRepository,
    private val cloudRepository: ICloudRepository
) {
    suspend fun getStorageInfo(): StorageVolumeInfo = storageRepository.getStorageVolumeInfo()
    suspend fun getSummaries(): List<CategorySummary> = storageRepository.getCategorySummaries()
    fun observeSummaries(): Flow<List<CategorySummary>> = storageRepository.observeCategorySummaries()
}

class StorageAnalysisUseCase @Inject constructor(
    private val analysisRepository: IStorageAnalysisRepository
) {
    suspend fun getAnalysisData(): StorageAnalysisData = analysisRepository.getStorageAnalysisData()
    suspend fun getLargeFiles(): List<LargeFileItem> = analysisRepository.getAllLargeFiles()
}

class GetCategorizedMediaUseCase @Inject constructor(
    private val fileRepository: IFileRepository
) {
    suspend fun getFolders(category: CategoryType, sortOption: FileSortOption = FileSortOption.BY_NAME_ASC): List<MediaFolder> =
        fileRepository.getMediaFolders(category, sortOption)

    suspend fun getFilesInFolder(
        folderPath: String,
        category: CategoryType,
        sortOption: FileSortOption = FileSortOption.BY_DATE_DESC
    ): List<FileItem> = fileRepository.getMediaFilesInFolder(folderPath, category, sortOption)

    suspend fun getAllDocuments(
        sortOption: FileSortOption = FileSortOption.BY_DATE_DESC
    ): List<FileItem> = fileRepository.getAllDocuments(sortOption)
}

class FileOperationsUseCase @Inject constructor(
    private val fileRepository: IFileRepository,
    private val recycleBinRepository: IRecycleBinRepository,
    private val transferGuard: com.antigravity.filemanager.data.service.TransferGuard,
    private val folderCacheManager: com.antigravity.filemanager.data.local.cache.FolderCacheManager
) {
    suspend fun getFiles(directoryPath: String, sort: FileSortOption, showHidden: Boolean): List<FileItem> =
        fileRepository.getFilesInDirectory(directoryPath, sort, showHidden)

    suspend fun copy(sourcePaths: List<String>, targetDir: String, overwriteNames: Set<String> = emptySet(), skipNames: Set<String> = emptySet()): Result<Unit> {
        transferGuard.begin(initialLabel = "Copying")
        try {
            val result = fileRepository.copyFiles(sourcePaths, targetDir, overwriteNames, skipNames)
            if (result.isSuccess) folderCacheManager.invalidateMediaFolders()
            return result
        } finally {
            transferGuard.end()
        }
    }

    suspend fun move(sourcePaths: List<String>, targetDir: String, overwriteNames: Set<String> = emptySet(), skipNames: Set<String> = emptySet()): Result<Unit> {
        transferGuard.begin(initialLabel = "Moving")
        try {
            val result = fileRepository.moveFiles(sourcePaths, targetDir, overwriteNames, skipNames)
            if (result.isSuccess) folderCacheManager.invalidateMediaFolders()
            return result
        } finally {
            transferGuard.end()
        }
    }

    suspend fun findConflicts(sourcePaths: List<String>, targetDir: String): List<com.antigravity.filemanager.domain.model.OverwriteConflict> =
        fileRepository.findCopyConflicts(sourcePaths, targetDir)

    suspend fun rename(filePath: String, newName: String): Result<FileItem> =
        fileRepository.renameFile(filePath, newName).also { if (it.isSuccess) folderCacheManager.invalidateMediaFolders() }

    suspend fun createFolder(parentPath: String, name: String): Result<FileItem> =
        fileRepository.createDirectory(parentPath, name)

    suspend fun delete(
        paths: List<String>,
        moveToRecycleBin: Boolean = true,
        onProgress: ((currentName: String, currentIndex: Int, total: Int) -> Unit)? = null
    ): Result<Int> {
        val result = if (moveToRecycleBin) {
            recycleBinRepository.moveToTrash(paths, onProgress)
        } else {
            var count = 0
            paths.forEachIndexed { index, path ->
                val f = java.io.File(path)
                onProgress?.invoke(f.name, index + 1, paths.size)
                if (f.deleteRecursively()) count++
            }
            Result.success(count)
        }
        // A delete only ever shrinks folders that are already cached — unlike copy/move/rename,
        // it can't land a file in a folder the cache doesn't know about yet — so it can patch
        // just the affected bucket(s) instead of invalidateMediaFolders()'s blanket "drop every
        // category" (which otherwise forced Images AND Videos AND Audio AND Documents to all redo
        // a full MediaStore rescan on their next open just because one photo was deleted).
        if (result.isSuccess) folderCacheManager.removeFromMediaFolders(paths)
        return result
    }

    suspend fun zip(
        sourcePaths: List<String>,
        targetZipPath: String,
        onProgress: ((currentFile: String, currentIndex: Int, totalFiles: Int) -> Unit)? = null
    ): Result<FileItem> =
        fileRepository.zipFiles(sourcePaths, targetZipPath, onProgress)

    suspend fun unzip(zipPath: String, targetDir: String): Result<Unit> =
        fileRepository.extractZip(zipPath, targetDir)

    suspend fun search(query: String, rootPath: String? = null, category: CategoryType? = null): List<FileItem> =
        fileRepository.searchFiles(query, rootPath, category)

    suspend fun getDetails(path: String): FileItem? = fileRepository.getFileDetails(path)
}

class RecycleBinUseCase @Inject constructor(
    private val recycleBinRepository: IRecycleBinRepository,
    private val folderCacheManager: com.antigravity.filemanager.data.local.cache.FolderCacheManager
) {
    fun observeTrash(): Flow<List<TrashItem>> = recycleBinRepository.observeTrashItems()
    suspend fun getTrash(): List<TrashItem> = recycleBinRepository.getTrashItems()
    suspend fun restore(ids: List<Long>): Result<Int> =
        recycleBinRepository.restoreFromTrash(ids).also { if (it.isSuccess) folderCacheManager.invalidateMediaFolders() }
    suspend fun deletePermanently(ids: List<Long>, onProgress: ((currentName: String, currentIndex: Int, total: Int) -> Unit)? = null): Result<Int> =
        recycleBinRepository.deletePermanently(ids, onProgress)
    suspend fun empty(onProgress: ((currentName: String, currentIndex: Int, total: Int) -> Unit)? = null): Result<Unit> =
        recycleBinRepository.emptyTrash(onProgress)
    suspend fun getTotalSize(): Long = recycleBinRepository.getTrashTotalSize()
}

class FtpServerUseCase @Inject constructor(
    private val ftpRepository: IFtpServerRepository
) {
    fun observeState(): Flow<FtpServerState> = ftpRepository.observeFtpServerState()
    suspend fun getState(): FtpServerState = ftpRepository.getFtpServerState()
    suspend fun start(port: Int, pass: String, random: Boolean, showHidden: Boolean): Result<Unit> =
        ftpRepository.startFtpServer(port, pass, random, showHidden)
    suspend fun stop(): Result<Unit> = ftpRepository.stopFtpServer()
    suspend fun saveConfig(port: Int, pass: String, random: Boolean, showHidden: Boolean) =
        ftpRepository.updateConfig(port, pass, random, showHidden)
}

class CloudStorageUseCase @Inject constructor(
    private val cloudRepository: ICloudRepository,
    private val transferGuard: com.antigravity.filemanager.data.service.TransferGuard,
    private val folderCacheManager: com.antigravity.filemanager.data.local.cache.FolderCacheManager
) {
    fun observeAccounts(): Flow<List<CloudAccount>> = cloudRepository.observeConnectedAccounts()
    suspend fun getAccounts(): List<CloudAccount> = cloudRepository.getConnectedAccounts()
    suspend fun addAccount(account: CloudAccount): Result<Unit> = cloudRepository.addAccount(account)
    suspend fun removeAccount(id: String): Result<Unit> = cloudRepository.removeAccount(id)
    suspend fun reorderAccounts(accounts: List<CloudAccount>): Result<Unit> = cloudRepository.updateAccountsOrder(accounts)
    suspend fun getFiles(accountId: String, path: String, forceFullRefresh: Boolean = false): Result<List<FileItem>> =
        cloudRepository.getCloudFiles(accountId, path, forceFullRefresh)

    suspend fun createFolder(accountId: String, folderName: String, parentPath: String): Result<FileItem> =
        cloudRepository.createFolder(accountId, folderName, parentPath)

    suspend fun deleteItem(accountId: String, remotePath: String, moveToTrash: Boolean = true): Result<Unit> =
        cloudRepository.deleteCloudFile(accountId, remotePath, moveToTrash)

    /** MEGA-only fast path for permanently deleting many items at once — see
     * CloudManager.deletePermanentlyBatchMega. Returns an empty map for any other provider. */
    suspend fun deletePermanentlyBatchMega(accountId: String, remotePaths: List<String>): Map<String, Result<Unit>> =
        cloudRepository.deletePermanentlyBatchMega(accountId, remotePaths)

    /** Restores an item from the provider's real trash — Google Drive and MEGA only. */
    suspend fun restoreItem(accountId: String, remotePath: String): Result<Unit> =
        cloudRepository.restoreCloudFile(accountId, remotePath)

    suspend fun renameItem(accountId: String, remotePath: String, newName: String): Result<FileItem> =
        cloudRepository.renameCloudFile(accountId, remotePath, newName)

    /** Relocates an item to a different folder within the SAME cloud account, entirely
     * server-side — the fast path for a "Move" whose source and destination are the same
     * account, instead of the generic cross-provider paste flow's download+reupload round trip. */
    suspend fun moveWithinAccount(accountId: String, sourcePath: String, targetDir: String): Result<Unit> =
        cloudRepository.moveCloudFileWithinAccount(accountId, sourcePath, targetDir)

    // Finds conflicting names in remoteDir on the given cloud account before upload. Used to
    // always call cloudRepository.getCloudFiles() directly — a real network listing every single
    // time, even though the "Copy to > pick a Dropbox folder" flow right before this had just
    // fetched (and cached, via FolderCacheManager) that exact same folder while the user was
    // browsing to it. That made every "SELECT THIS FOLDER" tap pay for a redundant network round
    // trip for data already sitting in cache from seconds earlier. Now it reuses that cache first
    // — same reconcile-once contract as everywhere else this cache is read — and only falls back
    // to a live fetch when there's nothing cached yet for this folder.
    suspend fun findConflicts(accountId: String, remoteDir: String, items: List<Pair<String, Long>>): List<com.antigravity.filemanager.domain.model.OverwriteConflict> {
        val cached = folderCacheManager.getCloudFolder(accountId, remoteDir)
        val existing = if (cached != null && cached.isFresh) {
            cached.files
        } else {
            val fetched = cloudRepository.getCloudFiles(accountId, remoteDir).getOrDefault(emptyList())
            folderCacheManager.putCloudFolder(accountId, remoteDir, fetched)
            fetched
        }
        android.util.Log.d("CloudStorageUseCase", "findConflicts: remoteDir='$remoteDir' fromCache=${cached?.isFresh == true} existing=${existing.map { it.name }} checking=${items.map { it.first }}")
        return items.mapNotNull { (name, size) ->
            val match = existing.find { it.name == name }
            if (match != null) {
                com.antigravity.filemanager.domain.model.OverwriteConflict(
                    name = name,
                    existingSize = match.size,
                    newSize = size,
                    isDirectory = match.isDirectory
                )
            } else null
        }
    }

    suspend fun uploadFiles(
        accountId: String,
        localPaths: List<String>,
        remoteDir: String = "/",
        overwriteNames: Set<String> = emptySet(),
        skipNames: Set<String> = emptySet(),
        onFileProgress: ((currentFile: String, currentIndex: Int, totalFiles: Int, bytesSent: Long, totalBytes: Long) -> Unit)? = null
    ): Result<Int> {
        transferGuard.begin(initialLabel = "Uploading")
        try {
        return try {
            // Directories in localPaths have no single "upload" call — recursively create a
            // matching remote folder tree first, then flatten every real file underneath into
            // (file, itsResolvedTargetDir) pairs. Files passed in directly keep target=remoteDir.
            val flatFiles = mutableListOf<Pair<File, String>>()
            for (path in localPaths) {
                val entry = File(path)
                if (entry.isDirectory) {
                    flattenDirectoryForUpload(accountId, entry, remoteDir, flatFiles)
                } else if (entry.isFile) {
                    flatFiles.add(entry to remoteDir)
                }
            }

            val existingByDir = mutableMapOf<String, MutableSet<String>>()
            val existingItemsByDir = mutableMapOf<String, List<com.antigravity.filemanager.domain.model.FileItem>>()
            // Was an unconditional live getCloudFiles() call per target dir, every single upload —
            // completely redundant right after findConflicts() (see above) had just done the exact
            // same listing (and cached it) moments earlier to build the overwrite-conflict list
            // the caller is now resolving. Reuse that cache first, same as findConflicts does, and
            // only hit the network if nothing's cached for this dir yet.
            suspend fun existingNamesFor(dir: String): MutableSet<String> = existingByDir.getOrPut(dir) {
                val cached = folderCacheManager.getCloudFolder(accountId, dir)
                val items = if (cached != null && cached.isFresh) {
                    cached.files
                } else {
                    val listResult = cloudRepository.getCloudFiles(accountId, dir)
                    android.util.Log.d("CloudStorageUseCase", "uploadFiles: getCloudFiles('$dir') isSuccess=${listResult.isSuccess} error=${listResult.exceptionOrNull()}")
                    val fetched = listResult.getOrDefault(emptyList())
                    folderCacheManager.putCloudFolder(accountId, dir, fetched)
                    fetched
                }
                existingItemsByDir[dir] = items
                items.map { it.name }.toMutableSet()
            }

            val totalFiles = flatFiles.size
            // Was Result<Unit> — every caller's "success" toast just repeated the ORIGINAL
            // selection count regardless of how many were actually skipped here, so picking
            // "Skip" on every conflict still reported "Transferred N file(s) successfully" for
            // zero real uploads. Track what actually went out and hand that back instead.
            var uploadedCount = 0
            flatFiles.forEachIndexed { index, (file, targetDir) ->
                if (file.name in skipNames) return@forEachIndexed

                val existingNames = existingNamesFor(targetDir)
                val conflictItem = existingItemsByDir[targetDir]?.find { it.name == file.name }
                var uploadSource = file
                var tempCopy: File? = null
                if (conflictItem != null) {
                    if (file.name in overwriteNames) {
                        cloudRepository.deleteCloudFile(accountId, conflictItem.path)
                        existingNames.remove(file.name)
                    } else {
                        val uniqueName = uniqueCloudName(existingNames, file.name)
                        val copy = File(file.parentFile, uniqueName)
                        file.copyTo(copy, overwrite = false)
                        uploadSource = copy
                        tempCopy = copy
                        existingNames.add(uniqueName)
                    }
                } else {
                    existingNames.add(file.name)
                }

                val fileSize = uploadSource.length()
                onFileProgress?.invoke(uploadSource.name, index + 1, totalFiles, 0L, fileSize)
                val uploadResult = cloudRepository.uploadCloudFile(accountId, uploadSource.absolutePath, targetDir) { sent, total ->
                    val effTotal = if (total > 0) total else fileSize
                    onFileProgress?.invoke(uploadSource.name, index + 1, totalFiles, sent, effTotal)
                }
                tempCopy?.delete()
                if (uploadResult.isFailure) {
                    // Every remaining file would fail for the same reason (same account/target),
                    // so stop here instead of silently reporting success for a partial batch.
                    throw uploadResult.exceptionOrNull() ?: Exception("Upload failed for ${uploadSource.name}")
                }
                uploadedCount++
            }
            Result.success(uploadedCount)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
        } finally {
            transferGuard.end()
        }
    }

    // Recursively mirrors a local directory tree into the cloud: reuses an existing remote
    // folder of the same name if present (merge), otherwise creates one — then walks children,
    // collecting every real file as (file, itsResolvedParentDir) so uploadFiles' flat loop can
    // upload them. Empty subdirectories still get created even though they add nothing to `out`.
    private suspend fun flattenDirectoryForUpload(
        accountId: String,
        dir: File,
        parentRemoteDir: String,
        out: MutableList<Pair<File, String>>
    ) {
        val existing = cloudRepository.getCloudFiles(accountId, parentRemoteDir).getOrDefault(emptyList())
        val existingFolder = existing.find { it.isDirectory && it.name == dir.name }
        val targetDir = if (existingFolder != null) {
            existingFolder.path
        } else {
            val created = cloudRepository.createFolder(accountId, dir.name, parentRemoteDir)
            created.getOrNull()?.path
                ?: throw (created.exceptionOrNull() ?: Exception("Failed to create remote folder '${dir.name}'"))
        }

        dir.listFiles()?.sortedBy { it.name }?.forEach { child ->
            if (child.isDirectory) {
                flattenDirectoryForUpload(accountId, child, targetDir, out)
            } else if (child.isFile) {
                out.add(child to targetDir)
            }
        }
    }

    private fun uniqueCloudName(existingNames: Set<String>, name: String): String {
        if (name !in existingNames) return name
        val dotIndex = name.lastIndexOf('.')
        val base = if (dotIndex > 0) name.substring(0, dotIndex) else name
        val ext = if (dotIndex > 0) name.substring(dotIndex) else ""
        var counter = 1
        var candidate = "$base ($counter)$ext"
        while (candidate in existingNames) {
            counter++
            candidate = "$base ($counter)$ext"
        }
        return candidate
    }

    suspend fun getQuota(accountId: String): Result<Pair<Long, Long>> =
        cloudRepository.getCloudQuota(accountId)

    suspend fun downloadFile(
        accountId: String,
        remotePath: String,
        localTargetDir: String,
        // false for CloudExplorerViewModel's last-resort thumbnail fetch (small files a provider
        // has no cheap thumbnail endpoint for) — that's an invisible background prefetch the user
        // never asked for, not a transfer worth a persistent notification or (worse, before the
        // fix that added this parameter) a full-screen "Downloading from Cloud" modal on whatever
        // screen happens to be open when it runs. TransferGuard's progress is one global signal
        // every open Cloud-adjacent screen mirrors into its own UI — see CloudMediaViewerViewModel
        // for the other silent caller (fetching a temp local copy to preview/stream), which also
        // passes false for the same reason. Kept before onProgress (rather than after) so existing
        // trailing-lambda call sites for onProgress don't silently bind to the wrong parameter.
        notifyTransfer: Boolean = true,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> {
        if (notifyTransfer) transferGuard.begin()
        try {
            val result = cloudRepository.downloadCloudFile(accountId, remotePath, localTargetDir, onProgress)
            if (result.isSuccess) folderCacheManager.invalidateMediaFolders()
            return result
        } finally {
            if (notifyTransfer) transferGuard.end()
        }
    }

    data class CloudDownloadToLocalResult(val scannedPaths: List<String>, val failedNames: List<String>)

    // Was near-identically duplicated three times (CategoriesViewModel.pasteFromCloud,
    // FileBrowserViewModel.pasteFromCloud, DashboardViewModel's doPasteCloud) — same download loop,
    // same overwrite/skip/unique-name handling, same per-item try/catch/finally hardening (a local
    // write failure after a successful download used to throw uncaught right here and silently
    // kill the whole paste with no toast, no error, nothing), same MediaScannerConnection.scanFile
    // call at the end (writing straight to a java.io.File never tells MediaStore anything happened,
    // so a pasted photo's folder thumbnail/count on the category root grid never updated without
    // this). Each of the three callers keeps its own wrapper for whatever's specific to that screen
    // (its own toast wording, what to reload/clear afterward) but delegates the actual work here.
    suspend fun downloadFilesToLocal(
        context: android.content.Context,
        accountId: String,
        remotePaths: List<String>,
        targetDir: String,
        itemSizes: Map<String, Long>,
        isMove: Boolean,
        overwriteNames: Set<String> = emptySet(),
        skipNames: Set<String> = emptySet(),
        onProgress: (com.antigravity.filemanager.domain.model.CloudTransferProgress) -> Unit
    ): CloudDownloadToLocalResult {
        val targetFolder = File(targetDir)
        val totalCount = remotePaths.size
        val progressThrottler = com.antigravity.filemanager.utils.ProgressThrottler()
        val scannedPaths = mutableListOf<String>()
        val failedNames = mutableListOf<String>()
        // A Move deletes the remote source item below, but that only patches DropboxApiClient's
        // own whole-account tree cache (see patchTreeAfterDelete) — it never touches
        // FolderCacheManager's per-folder listing cache, the one the Cloud tab actually paints
        // from. Without telling it too, a folder open in the Cloud tab kept showing the "moved"
        // file as still there until something else happened to invalidate it. We already know
        // exactly which remote paths just disappeared, grouped by parent folder, so this can drop
        // them straight out of a listening screen's list (see notifyCloudFilesRemoved) rather than
        // forcing a refetch the way a plain invalidate would.
        val movedFromFolders = mutableMapOf<String, MutableSet<String>>()

        remotePaths.forEachIndexed { index, remotePath ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val name = File(remotePath).name
            if (name in skipNames) return@forEachIndexed
            val destFile = File(targetFolder, name)
            val expectedSize = itemSizes[remotePath] ?: 0L

            onProgress(
                com.antigravity.filemanager.domain.model.CloudTransferProgress(
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
            try {
                val result = downloadFile(accountId, remotePath, tempDir.absolutePath) { bytesRead, totalBytes ->
                    val effTotal = if (totalBytes > 0) totalBytes else expectedSize
                    if (progressThrottler.shouldEmit(bytesRead, effTotal)) {
                        onProgress(
                            com.antigravity.filemanager.domain.model.CloudTransferProgress(
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
                            uniqueLocalDestination(targetFolder, name)
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
                        scannedPaths.add(finalFile.absolutePath)
                        if (isMove) {
                            deleteItem(accountId, remotePath)
                            val parentPath = remotePath.substringBeforeLast('/', "/").ifEmpty { "/" }
                            movedFromFolders.getOrPut(parentPath) { mutableSetOf() }.add(remotePath)
                        }
                    } else {
                        failedNames.add(name)
                    }
                } else {
                    android.util.Log.e("CloudStorageUseCase", "downloadFilesToLocal: download failed for '$remotePath'", result.exceptionOrNull())
                    failedNames.add(name)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("CloudStorageUseCase", "downloadFilesToLocal: failed for '$remotePath'", e)
                failedNames.add(name)
            } finally {
                tempDir.deleteRecursively()
            }
        }

        if (scannedPaths.isNotEmpty()) {
            android.media.MediaScannerConnection.scanFile(context, scannedPaths.toTypedArray(), null, null)
        }
        movedFromFolders.forEach { (parentPath, removedPaths) ->
            folderCacheManager.notifyCloudFilesRemoved(accountId, parentPath, removedPaths)
        }
        return CloudDownloadToLocalResult(scannedPaths, failedNames)
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

    suspend fun downloadThumbnail(accountId: String, nodeId: String): Result<ByteArray> =
        cloudRepository.downloadCloudThumbnail(accountId, nodeId)

    /** MEGA-only on-demand decrypting data source — see [CloudManager.openThumbnailDataSource]. */
    suspend fun openThumbnailDataSource(accountId: String, nodeId: String): Result<android.media.MediaDataSource> =
        cloudRepository.openCloudThumbnailDataSource(accountId, nodeId)

    /** Fallback for [openThumbnailDataSource] — see [CloudManager.downloadFilePartial]. */
    suspend fun downloadFilePartial(accountId: String, nodeId: String, localTargetFile: java.io.File, maxBytes: Long): Result<java.io.File> =
        cloudRepository.downloadCloudFilePartial(accountId, nodeId, localTargetFile, maxBytes)

    /** Range-request-capable direct URL for the file — lets a video thumbnail be decoded
     * without downloading the whole file. Only some providers support this (see [CloudManager]). */
    suspend fun getStreamableLink(accountId: String, remotePath: String): Result<String> =
        cloudRepository.getCloudStreamableLink(accountId, remotePath)

    /** Direct playable/decodable source (URL + any required headers) for viewing an image or
     * video without downloading it first. Supported for Dropbox and Google Drive; fails for
     * MEGA (client-side encrypted). */
    suspend fun getStreamSource(accountId: String, remotePath: String): Result<com.antigravity.filemanager.domain.model.CloudStreamSource> =
        cloudRepository.getCloudStreamSource(accountId, remotePath)
}

