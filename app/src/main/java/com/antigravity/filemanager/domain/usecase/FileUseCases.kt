package com.antigravity.filemanager.domain.usecase

import com.antigravity.filemanager.domain.model.*
import com.antigravity.filemanager.domain.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class GetDashboardDataUseCase @Inject constructor(
    private val storageRepository: IStorageRepository
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
    private val folderCacheManager: com.antigravity.filemanager.data.local.cache.FolderCacheManager,
    private val mediaChangeSignal: com.antigravity.filemanager.data.local.observer.MediaChangeSignal
) {
    suspend fun getFiles(directoryPath: String, sort: FileSortOption, showHidden: Boolean): List<FileItem> =
        fileRepository.getFilesInDirectory(directoryPath, sort, showHidden)

    suspend fun copy(
        sourcePaths: List<String>,
        targetDir: String,
        overwriteNames: Set<String> = emptySet(),
        skipNames: Set<String> = emptySet(),
        onProgress: ((currentFile: String, currentIndex: Int, totalFiles: Int) -> Unit)? = null
    ): Result<Unit> {
        transferGuard.begin(initialLabel = "Copying")
        try {
            val result = fileRepository.copyFiles(sourcePaths, targetDir, overwriteNames, skipNames) { currentFile, currentIndex, totalFiles ->
                onProgress?.invoke(currentFile, currentIndex, totalFiles)
                transferGuard.updateProgress(
                    com.antigravity.filemanager.data.service.TransferProgressInfo(
                        currentFileName = currentFile,
                        currentIndex = currentIndex,
                        totalFiles = totalFiles,
                        bytesTransferred = 0L,
                        totalBytes = 0L,
                        isUpload = true,
                        operationLabel = "Copying"
                    )
                )
            }
            // Even a failed copy/move may have written part of the files.
            folderCacheManager.invalidateMediaFolders()
            mediaChangeSignal.notifyChanged()
            return result
        } finally {
            transferGuard.end()
        }
    }

    suspend fun move(
        sourcePaths: List<String>,
        targetDir: String,
        overwriteNames: Set<String> = emptySet(),
        skipNames: Set<String> = emptySet(),
        onProgress: ((currentFile: String, currentIndex: Int, totalFiles: Int) -> Unit)? = null
    ): Result<Unit> {
        transferGuard.begin(initialLabel = "Moving")
        try {
            val result = fileRepository.moveFiles(sourcePaths, targetDir, overwriteNames, skipNames) { currentFile, currentIndex, totalFiles ->
                onProgress?.invoke(currentFile, currentIndex, totalFiles)
                transferGuard.updateProgress(
                    com.antigravity.filemanager.data.service.TransferProgressInfo(
                        currentFileName = currentFile,
                        currentIndex = currentIndex,
                        totalFiles = totalFiles,
                        bytesTransferred = 0L,
                        totalBytes = 0L,
                        isUpload = true,
                        operationLabel = "Moving"
                    )
                )
            }
            // Even a failed copy/move may have written part of the files.
            folderCacheManager.invalidateMediaFolders()
            mediaChangeSignal.notifyChanged()
            return result
        } finally {
            transferGuard.end()
        }
    }

    suspend fun findConflicts(sourcePaths: List<String>, targetDir: String): List<com.antigravity.filemanager.domain.model.OverwriteConflict> =
        fileRepository.findCopyConflicts(sourcePaths, targetDir)

    suspend fun rename(filePath: String, newName: String): Result<FileItem> =
        fileRepository.renameFile(filePath, newName).also {
            if (it.isSuccess) {
                folderCacheManager.invalidateMediaFolders()
                mediaChangeSignal.notifyChanged()
            }
        }

    suspend fun createFolder(parentPath: String, name: String): Result<FileItem> =
        fileRepository.createDirectory(parentPath, name).also {
            if (it.isSuccess) {
                mediaChangeSignal.notifyChanged()
            }
        }

    suspend fun delete(
        paths: List<String>,
        moveToRecycleBin: Boolean = true,
        onProgress: ((currentName: String, currentIndex: Int, total: Int) -> Unit)? = null
    ): Result<Int> {
        val result = if (moveToRecycleBin) {
            // Moving to the Recycle Bin is (usually) a single atomic renameTo() per top-level
            // path even for a folder with many files inside — same-partition renames don't walk
            // the tree at all, so per-top-level-item progress is already the real granularity
            // here; it only falls back to a real recursive copy+delete on a renameTo() failure
            // (cross-filesystem, permission issue), same unavoidable gap as extracting a single
            // zip archive.
            recycleBinRepository.moveToTrash(paths, onProgress)
        } else withContext(Dispatchers.IO) {
            // Permanent delete has no such shortcut — File.deleteRecursively() really does walk
            // the whole tree for each top-level path, so a single large folder used to report
            // "1/1" for the entire operation (see zipFiles' addFolder() replacement above for the
            // identical bug and why). Flattened into one bottom-up (files, then their now-empty
            // parent directories) delete order across every selected path, so progress reflects
            // every real file/folder actually being removed, not just how many top-level items
            // were selected.
            //
            // withContext(Dispatchers.IO) matters here for more than just "don't block the UI
            // thread with disk I/O" — this whole function is called directly from
            // viewModelScope.launch { }, which defaults to Dispatchers.Main.immediate. Without
            // switching dispatcher, every onProgress state update below would be written from,
            // and this entire synchronous loop would run on, the main thread with nothing to ever
            // yield it back to Compose in between — so no frame showing the progress dialog could
            // ever actually get drawn until the whole delete had already finished, making a
            // real, working progress mechanism look like it wasn't there at all.
            data class WorkItem(val file: java.io.File, val topLevelIndex: Int)
            val work = mutableListOf<WorkItem>()
            fun collect(f: java.io.File, topLevelIndex: Int) {
                if (f.isDirectory) {
                    f.listFiles()?.forEach { collect(it, topLevelIndex) }
                }
                work.add(WorkItem(f, topLevelIndex)) // post-order: contents before the folder itself
            }
            paths.forEachIndexed { index, path -> collect(java.io.File(path), index) }

            val total = work.size
            val topLevelFailed = BooleanArray(paths.size)
            work.forEachIndexed { index, item ->
                ensureActive()
                onProgress?.invoke(item.file.name, index + 1, total)
                if (!item.file.delete() && item.file.exists()) topLevelFailed[item.topLevelIndex] = true
            }
            Result.success(paths.indices.count { !topLevelFailed[it] })
        }
        // A delete only ever shrinks folders that are already cached — unlike copy/move/rename,
        // it can't land a file in a folder the cache doesn't know about yet — so it can patch
        // just the affected bucket(s) instead of invalidateMediaFolders()'s blanket "drop every
        // category" (which otherwise forced Images AND Videos AND Audio AND Documents to all redo
        // a full MediaStore rescan on their next open just because one photo was deleted).
        if (result.isSuccess) {
            folderCacheManager.removeFromMediaFolders(paths)
            mediaChangeSignal.notifyChanged()
        }
        return result
    }

    suspend fun compress(
        sourcePaths: List<String>,
        targetArchivePath: String,
        onProgress: ((currentFile: String, currentIndex: Int, totalFiles: Int, bytesProcessed: Long, totalBytes: Long) -> Unit)? = null
    ): Result<FileItem> =
        fileRepository.compressFiles(sourcePaths, targetArchivePath, onProgress).also {
            if (it.isSuccess) {
                mediaChangeSignal.notifyChanged()
            }
        }

    suspend fun getArchiveConflicts(
        archivePath: String,
        targetDir: String,
        password: String? = null
    ): List<com.antigravity.filemanager.domain.model.OverwriteConflict> =
        fileRepository.getArchiveConflicts(archivePath, targetDir, password)

    suspend fun extract(
        archivePath: String,
        targetDir: String,
        password: String? = null,
        overwriteNames: Set<String> = emptySet(),
        skipNames: Set<String> = emptySet(),
        onProgress: ((currentEntry: String, currentIndex: Int, totalEntries: Int, bytesProcessed: Long, totalBytes: Long) -> Unit)? = null
    ): Result<com.antigravity.filemanager.domain.model.ExtractResult> =
        fileRepository.extractArchive(archivePath, targetDir, password, overwriteNames, skipNames, onProgress).also {
            if (it.isSuccess) {
                folderCacheManager.invalidateMediaFolders()
                mediaChangeSignal.notifyChanged()
            }
        }

    fun isArchiveEncrypted(archivePath: String): Boolean =
        fileRepository.isArchiveEncrypted(archivePath)

    suspend fun search(query: String, rootPath: String? = null, category: CategoryType? = null): List<FileItem> =
        fileRepository.searchFiles(query, rootPath, category)

    suspend fun getDetails(path: String): FileItem? = fileRepository.getFileDetails(path)
}

class RecycleBinUseCase @Inject constructor(
    private val recycleBinRepository: IRecycleBinRepository,
    private val folderCacheManager: com.antigravity.filemanager.data.local.cache.FolderCacheManager,
    private val mediaChangeSignal: com.antigravity.filemanager.data.local.observer.MediaChangeSignal
) {
    fun observeTrash(): Flow<List<TrashItem>> = recycleBinRepository.observeTrashItems()
    suspend fun getTrash(): List<TrashItem> = recycleBinRepository.getTrashItems()
    suspend fun restore(ids: List<Long>, onProgress: ((currentName: String, currentIndex: Int, total: Int) -> Unit)? = null): Result<Int> =
        recycleBinRepository.restoreFromTrash(ids, onProgress).also {
            if (it.isSuccess) {
                folderCacheManager.invalidateMediaFolders()
                mediaChangeSignal.notifyChanged()
            }
        }
    suspend fun deletePermanently(ids: List<Long>, onProgress: ((currentName: String, currentIndex: Int, total: Int) -> Unit)? = null): Result<Int> =
        recycleBinRepository.deletePermanently(ids, onProgress).also {
            if (it.isSuccess) {
                mediaChangeSignal.notifyChanged()
            }
        }
    suspend fun empty(onProgress: ((currentName: String, currentIndex: Int, total: Int) -> Unit)? = null): Result<Unit> =
        recycleBinRepository.emptyTrash(onProgress).also {
            if (it.isSuccess) {
                mediaChangeSignal.notifyChanged()
            }
        }
    suspend fun getTotalSize(): Long = recycleBinRepository.getTrashTotalSize()
}

class FtpServerUseCase @Inject constructor(
    private val ftpRepository: IFtpServerRepository
) {
    fun observeState(): Flow<FtpServerState> = ftpRepository.observeFtpServerState()
    suspend fun getState(): FtpServerState = ftpRepository.getFtpServerState()
    suspend fun start(port: Int, pass: String, random: Boolean, httpPort: Int = 8080): Result<Unit> =
        ftpRepository.startFtpServer(port, pass, random, httpPort)
    suspend fun stop(): Result<Unit> = ftpRepository.stopFtpServer()
    suspend fun saveConfig(port: Int, pass: String, random: Boolean, httpPort: Int = 8080) =
        ftpRepository.updateConfig(port, pass, random, httpPort)
}

class CloudStorageUseCase @Inject constructor(
    private val cloudRepository: ICloudRepository,
    private val transferGuard: com.antigravity.filemanager.data.service.TransferGuard,
    private val folderCacheManager: com.antigravity.filemanager.data.local.cache.FolderCacheManager
) {
    fun observeAccounts(): Flow<List<CloudAccount>> = cloudRepository.observeConnectedAccounts()
    suspend fun getAccounts(): List<CloudAccount> = cloudRepository.getConnectedAccounts()
    suspend fun addAccount(account: CloudAccount): Result<Unit> = cloudRepository.addAccount(account)
    suspend fun removeAccount(id: String): Result<Unit> =
        cloudRepository.removeAccount(id).also { folderCacheManager.invalidateCloud(id, notify = false) }
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
            // A failed listing must not be cached as "empty" — it would stick for the whole session.
            // uploadFiles() re-checks and refuses to upload blind if it still can't list.
            val fetched = cloudRepository.getCloudFiles(accountId, remoteDir).getOrNull()
            if (fetched != null) folderCacheManager.putCloudFolder(accountId, remoteDir, fetched)
            fetched ?: emptyList()
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
            // Conflict choices only ever resolve top-level names (same as local copy/move): a
            // skipped folder is left out entirely, and every file inside an overwritten folder
            // overwrites its remote counterpart.
            val flatFiles = mutableListOf<UploadItem>()
            for (path in localPaths) {
                val entry = File(path)
                if (entry.name in skipNames) continue
                val overwrite = entry.name in overwriteNames
                if (entry.isDirectory) {
                    val nested = mutableListOf<Pair<File, String>>()
                    flattenDirectoryForUpload(accountId, entry, remoteDir, nested)
                    nested.mapTo(flatFiles) { (file, dir) -> UploadItem(file, dir, overwrite) }
                } else if (entry.isFile) {
                    flatFiles.add(UploadItem(entry, remoteDir, overwrite))
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
                    // Uploading without knowing what is already there would skip the keep-both rename,
                    // and Dropbox (WriteMode.OVERWRITE) would silently replace a same-named file.
                    val fetched = listResult.getOrElse {
                        throw java.io.IOException("Couldn't check the destination folder for existing files: ${it.message}", it)
                    }
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
            flatFiles.forEachIndexed { index, (file, targetDir, overwrite) ->
                val existingNames = existingNamesFor(targetDir)
                val conflictItem = existingItemsByDir[targetDir]?.find { it.name == file.name }
                var uploadSource = file
                var tempDir: File? = null
                if (conflictItem != null) {
                    if (overwrite) {
                        cloudRepository.deleteCloudFile(accountId, conflictItem.path)
                        existingNames.remove(file.name)
                    } else {
                        // "Keep both": the providers name the upload after the local file, so it
                        // needs a renamed copy — made in app cache, never next to the user's own
                        // file (where a same-named local file made copyTo() throw, and a crash
                        // mid-upload left the copy behind in their folder).
                        val uniqueName = uniqueCloudName(existingNames, file.name)
                        val dir = File(System.getProperty("java.io.tmpdir"), "upload_${System.nanoTime()}").apply { mkdirs() }
                        tempDir = dir
                        uploadSource = file.copyTo(File(dir, uniqueName), overwrite = true)
                        existingNames.add(uniqueName)
                    }
                } else {
                    existingNames.add(file.name)
                }

                val fileSize = uploadSource.length()
                onFileProgress?.invoke(uploadSource.name, index + 1, totalFiles, 0L, fileSize)
                val uploadResult = try {
                    cloudRepository.uploadCloudFile(accountId, uploadSource.absolutePath, targetDir) { sent, total ->
                        val effTotal = if (total > 0) total else fileSize
                        onFileProgress?.invoke(uploadSource.name, index + 1, totalFiles, sent, effTotal)
                    }
                } finally {
                    tempDir?.deleteRecursively()
                }
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

    private data class UploadItem(val file: File, val targetDir: String, val overwrite: Boolean)

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

    /** Name clashes a cloud-to-local paste into [targetDir] would hit. Shared by every screen that
     * pastes from the cloud; they each used to size an existing folder by its directory inode
     * (a few KB) and always label it "File already exists". */
    suspend fun findLocalConflicts(
        remotePaths: List<String>,
        targetDir: String,
        itemSizes: Map<String, Long>,
        itemIsDirectory: Map<String, Boolean>
    ): List<com.antigravity.filemanager.domain.model.OverwriteConflict> = withContext(Dispatchers.IO) {
        remotePaths.mapNotNull { remotePath ->
            val name = File(remotePath).name
            val destFile = File(targetDir, name)
            if (!destFile.exists()) return@mapNotNull null
            com.antigravity.filemanager.domain.model.OverwriteConflict(
                name = name,
                existingSize = com.antigravity.filemanager.data.local.storage.directorySize(destFile),
                newSize = itemSizes[remotePath] ?: 0L,
                isDirectory = destFile.isDirectory || itemIsDirectory[remotePath] == true
            )
        }
    }

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
        itemIsDirectory: Map<String, Boolean> = emptyMap(),
        onProgress: (com.antigravity.filemanager.domain.model.CloudTransferProgress) -> Unit
    ): CloudDownloadToLocalResult {
        val targetFolder = File(targetDir)
        val progressThrottler = com.antigravity.filemanager.utils.ProgressThrottler()
        val scannedPaths = mutableListOf<String>()
        val failedNames = mutableListOf<String>()
        val movedFromFolders = mutableMapOf<String, MutableSet<String>>()

        data class FileDownloadItem(
            val remotePath: String,
            val localFile: File,
            val expectedSize: Long,
            val topSourcePath: String
        )
        val filesToDownload = mutableListOf<FileDownloadItem>()
        val emptyFolders = mutableListOf<Pair<File, String>>()
        val failedTopSources = mutableSetOf<String>()

        suspend fun crawlFolder(currentRemoteDir: String, currentLocalDir: File, topSource: String) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            currentLocalDir.mkdirs()
            val childrenResult = cloudRepository.getCloudFiles(accountId, currentRemoteDir)
            val children = childrenResult.getOrNull()
            if (children == null) {
                // An unlistable folder was previously treated like an empty one — and a move then
                // deleted the whole source folder from the cloud without having downloaded it.
                android.util.Log.e("CloudStorageUseCase", "downloadFilesToLocal: listing failed for '$currentRemoteDir'", childrenResult.exceptionOrNull())
                failedNames.add(File(currentRemoteDir).name)
                failedTopSources.add(topSource)
                return
            }
            if (children.isEmpty()) {
                emptyFolders.add(currentLocalDir to topSource)
                return
            }
            for (child in children) {
                val childDest = File(currentLocalDir, child.name)
                if (child.isDirectory) {
                    crawlFolder(child.path, childDest, topSource)
                } else {
                    filesToDownload.add(FileDownloadItem(child.path, childDest, child.size, topSource))
                }
            }
        }

        // 1. Resolve destination targets and recursively flatten any directories
        val validTopSources = mutableListOf<String>()
        for (remotePath in remotePaths) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val name = File(remotePath).name
            if (name in skipNames) continue
            validTopSources.add(remotePath)

            val destFile = if (name in overwriteNames) {
                File(targetFolder, name)
            } else if (File(targetFolder, name).exists()) {
                com.antigravity.filemanager.data.local.storage.uniqueFile(targetFolder, name)
            } else {
                File(targetFolder, name)
            }

            var isDir = itemIsDirectory[remotePath] ?: false
            if (!isDir && itemIsDirectory.isEmpty()) {
                // Fallback directory detection if itemIsDirectory wasn't supplied
                isDir = runCatching { cloudRepository.getCloudFiles(accountId, remotePath).isSuccess }.getOrDefault(false)
            }

            if (isDir) {
                destFile.mkdirs()
                crawlFolder(remotePath, destFile, remotePath)
            } else {
                filesToDownload.add(FileDownloadItem(remotePath, destFile, itemSizes[remotePath] ?: 0L, remotePath))
            }
        }

        val totalCount = filesToDownload.size

        // 2. Download all files
        filesToDownload.forEachIndexed { index, item ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val name = item.localFile.name
            val expectedSize = item.expectedSize

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
                val result = downloadFile(accountId, item.remotePath, tempDir.absolutePath) { bytesRead, totalBytes ->
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
                        val finalFile = item.localFile
                        finalFile.parentFile?.mkdirs()
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
                    } else {
                        failedNames.add(name)
                        failedTopSources.add(item.topSourcePath)
                    }
                } else {
                    android.util.Log.e("CloudStorageUseCase", "downloadFilesToLocal: download failed for '${item.remotePath}'", result.exceptionOrNull())
                    failedNames.add(name)
                    failedTopSources.add(item.topSourcePath)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("CloudStorageUseCase", "downloadFilesToLocal: failed for '${item.remotePath}'", e)
                failedNames.add(name)
                failedTopSources.add(item.topSourcePath)
            } finally {
                tempDir.deleteRecursively()
            }
        }

        // 3. Track any empty folders created
        for ((emptyFolder, topSource) in emptyFolders) {
            if (topSource !in failedTopSources) {
                scannedPaths.add(emptyFolder.absolutePath)
            }
        }

        // 4. If move operation, delete successfully transferred top-level sources
        if (isMove) {
            for (topSource in validTopSources) {
                if (topSource !in failedTopSources) {
                    deleteItem(accountId, topSource)
                    val parentPath = topSource.substringBeforeLast('/', "/").ifEmpty { "/" }
                    movedFromFolders.getOrPut(parentPath) { mutableSetOf() }.add(topSource)
                }
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

    suspend fun downloadThumbnail(accountId: String, nodeId: String): Result<ByteArray> =
        cloudRepository.downloadCloudThumbnail(accountId, nodeId)

    /** MEGA-only on-demand decrypting data source — see [CloudManager.openThumbnailDataSource]. */
    suspend fun openThumbnailDataSource(accountId: String, nodeId: String): Result<android.media.MediaDataSource> =
        cloudRepository.openCloudThumbnailDataSource(accountId, nodeId)

    /** MEGA-only: same on-demand decrypting source, tuned for sequential video playback. */
    suspend fun openVideoDataSource(accountId: String, nodeId: String): Result<android.media.MediaDataSource> =
        cloudRepository.openCloudThumbnailDataSource(accountId, nodeId, forPlayback = true)

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

