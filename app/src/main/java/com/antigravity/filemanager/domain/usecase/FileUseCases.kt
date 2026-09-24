package com.antigravity.filemanager.domain.usecase

import com.antigravity.filemanager.domain.model.*
import com.antigravity.filemanager.domain.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
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
            //
            // Files on a USB drive or SD card are always deleted permanently: the Recycle Bin is
            // on main storage, so "moving" them there meant copying every byte across, which
            // failed (and left the file in place) as soon as main storage was nearly full.
            val (external, onMain) = paths.partition {
                com.antigravity.filemanager.data.local.storage.isOutsidePrimaryStorage(it)
            }
            val trashed = if (onMain.isEmpty()) Result.success(0) else recycleBinRepository.moveToTrash(onMain, onProgress)
            if (external.isEmpty()) trashed else {
                val removed = deletePermanently(external, onProgress)
                when {
                    trashed.isFailure -> trashed
                    removed.isFailure -> removed
                    else -> Result.success(trashed.getOrThrow() + removed.getOrThrow())
                }
            }
        } else deletePermanently(paths, onProgress)
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

    private suspend fun deletePermanently(
        paths: List<String>,
        onProgress: ((currentName: String, currentIndex: Int, total: Int) -> Unit)?
    ): Result<Int> = withContext(Dispatchers.IO) {
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

    suspend fun listArchiveEntries(archivePath: String, password: String? = null) =
        fileRepository.listArchiveEntries(archivePath, password)

    /** Extracts just the chosen entries (see FileOperationsHelper.extractArchiveEntries). */
    suspend fun extractArchiveEntries(
        archivePath: String,
        selectedPaths: List<String>,
        baseDir: String,
        targetDir: String,
        password: String? = null,
        notifyMediaChange: Boolean = true
    ): Result<List<java.io.File>> =
        fileRepository.extractArchiveEntries(archivePath, selectedPaths, baseDir, targetDir, password).also {
            if (notifyMediaChange && it.getOrNull()?.isNotEmpty() == true) {
                folderCacheManager.invalidateLocal(targetDir)
                folderCacheManager.invalidateMediaFolders()
                mediaChangeSignal.notifyChanged()
            }
        }

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

/** A cloud folder's display path in one spelling: root is "/", no trailing slash. */
private fun normalizeCloudDir(path: String): String = path.trimEnd('/').ifEmpty { "/" }

/** True when the cloud item at [path] sits directly inside the cloud folder [dir]. */
fun isInCloudFolder(path: String, dir: String): Boolean =
    normalizeCloudDir(path.substringBeforeLast('/', "")) == normalizeCloudDir(dir)

/** True when the cloud folder [dir] is [folder] itself or anywhere below it. */
fun isCloudFolderOrInside(dir: String, folder: String): Boolean {
    val d = normalizeCloudDir(dir)
    val f = normalizeCloudDir(folder)
    return d == f || d.startsWith("$f/")
}

/** Google Drive's real top-level folder, listed under the account's virtual root menu. */
const val GOOGLE_DRIVE_MY_DRIVE = "/My Drive"

/**
 * Where a write aimed at the cloud folder [dir] really lands. A Google Drive account's root is a
 * virtual menu (My Drive, Starred, Shared with me, ...) while uploads and new folders there go
 * into My Drive, so name clashes were checked against the menu entries instead of the files
 * actually sitting there, and same-named uploads were silently duplicated.
 */
fun cloudWriteDir(provider: CloudProvider?, dir: String): String =
    if (provider == CloudProvider.GOOGLE_DRIVE && (dir == "/" || dir.isBlank())) GOOGLE_DRIVE_MY_DRIVE else dir

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

    /** [cloudWriteDir] for an account known only by id. */
    suspend fun resolveWriteDir(accountId: String, dir: String): String {
        if (dir != "/" && dir.isNotBlank()) return dir
        val provider = cloudRepository.getConnectedAccounts().find { it.id == accountId }?.provider
        return cloudWriteDir(provider, dir)
    }

    /** Result of [copyBetweenClouds]. */
    data class CloudCopyResult(val transferred: Int, val failures: Int, val lastError: String?)

    /**
     * Copies, or with [isMove] moves, cloud files and folders from [sourceCloudAccountId] into
     * [targetPath] of [accountId] (another account or provider, or the same one for a copy) via a
     * local temp round trip. Shared by the cloud explorer's paste and dual-panel drops.
     */
    suspend fun copyBetweenClouds(
        context: android.content.Context,
        sourceCloudAccountId: String,
        sources: List<String>,
        isDirectoryByPath: Map<String, Boolean>,
        accountId: String,
        targetDir: String,
        isMove: Boolean,
        overwriteNames: Set<String>,
        skipNames: Set<String>,
        onProgress: (CloudTransferProgress) -> Unit
    ): CloudCopyResult {
        val targetPath = resolveWriteDir(accountId, targetDir)
        // Copying a folder into itself kept finding the copy it had just made inside the source
        // and descending into it: /X/X, /X/X/X, ... without end.
        if (sourceCloudAccountId == accountId) {
            sources.firstOrNull { isCloudFolderOrInside(targetPath, it) }?.let {
                return CloudCopyResult(0, 1, "Cannot ${if (isMove) "move" else "copy"} a folder into itself: ${File(it).name}")
            }
        }
        var failures = 0
        var lastErrorMessage: String? = null
        var transferredCount = 0
        fun setTransferProgress(
            currentFileName: String,
            currentIndex: Int,
            totalFiles: Int,
            isUpload: Boolean,
            bytesTransferred: Long = 0L,
            totalBytes: Long = 0L,
            operationLabel: String? = null
        ) = onProgress(
            CloudTransferProgress(
                currentFileName = currentFileName,
                currentIndex = currentIndex,
                totalFiles = totalFiles,
                bytesTransferred = bytesTransferred,
                totalBytes = totalBytes,
                isIndeterminate = false,
                isUpload = isUpload,
                operationLabel = operationLabel
            )
        )
        // Cloud file(s)/folder(s) (possibly a different account/provider) -> this
        // cloud folder, via a local temp round-trip since there is no cross-provider
        // server-side move/copy. A source folder has no single "download" call, so
        // first flatten it: recreate the matching folder tree at the destination and
        // collect every real file underneath (recursively) into (remoteFilePath,
        // itsResolvedTargetDir) pairs, same strategy FileUseCases.uploadFiles already
        // uses for local folders. Without this, a folder in the clipboard was handed
        // straight to downloadFile() as if it were a single file, which always failed
        // and silently dropped every file inside it.
        val tempDir = File(context.cacheDir, "cloud_transfer_${System.nanoTime()}").apply { mkdirs() }
        data class FlatEntry(val remoteFilePath: String, val targetDir: String, val topSource: String)
        val flat = mutableListOf<FlatEntry>()
        // Tracks whether every file under a given top-level source transferred
        // successfully, so a move only deletes that source once nothing was lost.
        val topLevelSucceeded = sources.associateWith { true }.toMutableMap()

        // Same-shape "(1)" suffixing as FileUseCases.uniqueCloudName, used below to
        // give a top-level "Keep Both" folder its own new name at the destination
        // instead of merging its contents into the identically-named folder already
        // there.
        fun uniqueCloudName(existingNames: Set<String>, name: String): String {
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

        suspend fun flatten(remotePath: String, isDir: Boolean, targetDir: String, topSource: String, nameOverride: String? = null) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val name = nameOverride ?: File(remotePath).name
            if (isDir) {
                // Reuse an existing same-name folder at the destination instead of
                // always creating a new one — MEGA in particular has no problem
                // creating a second folder with an identical name (it dedupes nothing),
                // so blindly calling createFolder on every retry/overwrite left
                // duplicate "same name" folders behind instead of merging into the one
                // already there. (A top-level "Keep Both" folder was already given a
                // fresh unique `name` above, so this lookup naturally finds nothing for
                // it and creates a real duplicate instead of merging into the original.)
                val existingFolder = getFiles(accountId, targetDir).getOrDefault(emptyList())
                    .find { it.isDirectory && it.name == name }
                if (existingFolder == null) {
                    val createResult = createFolder(accountId, name, targetDir)
                    if (createResult.isFailure) {
                        // Real failure — still try to copy its children; any file that
                        // can't actually land will fail on its own upload below.
                    }
                }
                val childTargetDir = if (targetDir == "/" || targetDir.isBlank()) "/$name" else "${targetDir.trimEnd('/')}/$name"
                val children = getFiles(sourceCloudAccountId, remotePath).getOrElse {
                    topLevelSucceeded[topSource] = false
                    lastErrorMessage = it.message
                    emptyList()
                }
                for (child in children) {
                    flatten(child.path, child.isDirectory, childTargetDir, topSource)
                }
            } else {
                flat.add(FlatEntry(remotePath, targetDir, topSource))
            }
        }
        // Names already present at the destination, used to give each top-level
        // "Keep Both" item (neither skipped nor chosen to overwrite) its own unique
        // name up front — otherwise a same-name folder silently merged its contents
        // into the existing one instead of landing as a real duplicate.
        val destExistingNames = getFiles(accountId, targetPath).getOrDefault(emptyList())
            .map { it.name }.toMutableSet()
        for (remotePath in sources) {
            val name = File(remotePath).name
            if (name in skipNames) continue
            val effectiveName = if (name in overwriteNames || name !in destExistingNames) {
                name
            } else {
                uniqueCloudName(destExistingNames, name).also { destExistingNames.add(it) }
            }
            flatten(remotePath, isDirectoryByPath[remotePath] == true, targetPath, remotePath, effectiveName)
        }

        val totalCount = flat.size
        transferredCount = totalCount
        val downloadThrottler = com.antigravity.filemanager.utils.ProgressThrottler()
        val uploadThrottler = com.antigravity.filemanager.utils.ProgressThrottler()
        // Each file's round trip (download then upload) is dominated by per-request
        // network latency, not local CPU/bandwidth — doing them one at a time is why
        // 438 small files felt like it crawled. Running several in flight at once
        // overlaps that latency instead of paying it 438 times in a row. Concurrency
        // is capped (not unbounded) because MEGA in particular rate-limits bursts of
        // parallel requests (see the comment on listCloudFiles' offline fallback).
        val failuresCounter = java.util.concurrent.atomic.AtomicInteger(0)
        val lastErrorRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val completedCounter = java.util.concurrent.atomic.AtomicInteger(0)
        val semaphore = kotlinx.coroutines.sync.Semaphore(8)
        kotlinx.coroutines.coroutineScope {
            flat.forEachIndexed { index, entry ->
                launch {
                    semaphore.withPermit {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val remotePath = entry.remoteFilePath
                        // Unique per-entry download dir — concurrent downloads can
                        // otherwise collide when two source files share a name (e.g.
                        // "readme.txt" in two different subfolders).
                        val entryDir = File(tempDir, index.toString()).apply { mkdirs() }
                        val dlResult = downloadFile(sourceCloudAccountId, remotePath, entryDir.absolutePath) { bytesRead, totalBytes ->
                            if (downloadThrottler.shouldEmit(bytesRead, totalBytes)) {
                                setTransferProgress(currentFileName = File(remotePath).name, currentIndex = completedCounter.get() + 1, totalFiles = totalCount, isUpload = false, bytesTransferred = bytesRead, totalBytes = totalBytes)
                            }
                        }
                        val localFile = dlResult.getOrNull()
                        if (localFile != null) {
                            // overwriteNames/skipNames here are TOP-LEVEL clipboard
                            // item names (e.g. the folder "MP3 Tones" itself), decided
                            // once by the user in the conflict dialog — they were never
                            // going to match a nested file's own name (e.g.
                            // "Urgent2.mp3"). Passing them through unchanged meant every
                            // file inside an "Overwrite"-d folder found no name match at
                            // its own upload call and fell back to "keep both" (a "(1)"
                            // suffix), instead of actually overwriting. Propagate the
                            // top-level folder's decision down to each file under it.
                            val topName = File(entry.topSource).name
                            val effectiveOverwriteNames = if (topName in overwriteNames) {
                                setOf(localFile.name)
                            } else {
                                emptySet()
                            }
                            val upResult = uploadFiles(
                                accountId = accountId,
                                localPaths = listOf(localFile.absolutePath),
                                remoteDir = entry.targetDir,
                                overwriteNames = effectiveOverwriteNames,
                                skipNames = emptySet()
                            ) { currentFile, _, _, bytesSent, totalBytes ->
                              if (uploadThrottler.shouldEmit(bytesSent, totalBytes)) {
                                setTransferProgress(currentFileName = currentFile, currentIndex = completedCounter.get() + 1, totalFiles = totalCount, isUpload = true, bytesTransferred = bytesSent, totalBytes = totalBytes)
                              }
                            }
                            entryDir.deleteRecursively()
                            if (upResult.isFailure) {
                                failuresCounter.incrementAndGet()
                                lastErrorRef.set(upResult.exceptionOrNull()?.message)
                                topLevelSucceeded[entry.topSource] = false
                            }
                        } else {
                            entryDir.deleteRecursively()
                            failuresCounter.incrementAndGet()
                            topLevelSucceeded[entry.topSource] = false
                        }
                        completedCounter.incrementAndGet()
                    }
                }
            }
        }
        failures += failuresCounter.get()
        // Only what actually arrived: this used to stay at the total even when files failed.
        transferredCount = totalCount - failuresCounter.get()
        lastErrorRef.get()?.let { lastErrorMessage = it }
        if (isMove) {
            // Delete each top-level source (file or folder) as one unit once its whole
            // subtree copied cleanly — deleting the folder node removes everything
            // under it remotely, so there's no need to delete descendants one by one.
            // Was a plain sequential forEach — each delete is its own network round
            // trip (~1-1.5s), so a multi-file selection (e.g. 26 individual files cut
            // at once, not a single folder) paid that one at a time with the progress
            // bar showing nothing for this phase, which looked exactly like a hang on
            // a large selection. Run it the same bounded-parallel way as the uploads
            // above, and keep the progress bar reporting during it.
            val toDelete = sources.filter { topLevelSucceeded[it] == true && File(it).name !in skipNames }
            val deleteCompleted = java.util.concurrent.atomic.AtomicInteger(0)
            val deleteSemaphore = kotlinx.coroutines.sync.Semaphore(8)
            kotlinx.coroutines.coroutineScope {
                toDelete.forEach { sourcePath ->
                    launch {
                        deleteSemaphore.withPermit {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            setTransferProgress(currentFileName = File(sourcePath).name, currentIndex = deleteCompleted.get() + 1, totalFiles = toDelete.size, isUpload = false, operationLabel = "Removing source")
                            deleteItem(sourceCloudAccountId, sourcePath)
                            deleteCompleted.incrementAndGet()
                        }
                    }
                }
            }
        }
        tempDir.deleteRecursively()
        return CloudCopyResult(transferredCount, failures, lastErrorMessage)
    }

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
    suspend fun findConflicts(accountId: String, targetDir: String, items: List<Pair<String, Long>>): List<com.antigravity.filemanager.domain.model.OverwriteConflict> {
        val remoteDir = resolveWriteDir(accountId, targetDir)
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
            val targetRoot = resolveWriteDir(accountId, remoteDir)
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

            val flatFiles = mutableListOf<UploadItem>()
            for (path in localPaths) {
                val entry = File(path)
                if (entry.name in skipNames) continue
                val overwrite = entry.name in overwriteNames
                if (entry.isDirectory) {
                    // "Keep both" for a folder uploads it as "Name (1)", like a local copy does;
                    // it used to be merged into the existing folder, with "(1)" copies of any
                    // clashing files scattered inside it.
                    val topNames = existingNamesFor(targetRoot)
                    val folderName = if (!overwrite && entry.name in topNames) uniqueCloudName(topNames, entry.name) else entry.name
                    topNames.add(folderName)
                    val nested = mutableListOf<Pair<File, String>>()
                    flattenDirectoryForUpload(accountId, entry, targetRoot, nested, folderName)
                    nested.mapTo(flatFiles) { (file, dir) -> UploadItem(file, dir, overwrite) }
                } else if (entry.isFile) {
                    flatFiles.add(UploadItem(entry, targetRoot, overwrite))
                }
            }

            val totalFiles = flatFiles.size
            // Was Result<Unit> — every caller's "success" toast just repeated the ORIGINAL
            // selection count regardless of how many were actually skipped here, so picking
            // "Skip" on every conflict still reported "Transferred N file(s) successfully" for
            // zero real uploads. Track what actually went out and hand that back instead.
            var uploadedCount = 0
            val provider = if (flatFiles.any { it.overwrite }) {
                cloudRepository.getConnectedAccounts().find { it.id == accountId }?.provider
            } else null
            flatFiles.forEachIndexed { index, (file, targetDir, overwrite) ->
                val existingNames = existingNamesFor(targetDir)
                val conflictItem = existingItemsByDir[targetDir]?.find { it.name == file.name }
                var uploadSource = file
                var tempDir: File? = null
                // Overwrite used to delete the existing remote item first, so an upload that then
                // failed (lost connection, full quota) left neither copy in place. The old item is
                // now removed only once the new one is up, in whatever way the provider allows.
                var replaceAfterUpload: com.antigravity.filemanager.domain.model.FileItem? = null
                var renameAfterUpload = false
                if (conflictItem != null) {
                    if (overwrite) {
                        when {
                            // A file replacing a folder: nothing can be swapped in one step.
                            conflictItem.isDirectory -> cloudRepository.deleteCloudFile(accountId, conflictItem.path)
                                .getOrElse { throw java.io.IOException("Couldn't replace \"${conflictItem.name}\": ${it.message}", it) }
                            // Uploads use WriteMode.OVERWRITE: the new file replaces the old in one step.
                            provider == CloudProvider.DROPBOX -> Unit
                            // Same-named siblings are allowed: upload next to it, then remove the
                            // old one by its id (its path now also names the new file). A cached
                            // entry may carry a path instead of a real id; fall back for those.
                            (provider == CloudProvider.GOOGLE_DRIVE || provider == CloudProvider.MEGA) &&
                                !conflictItem.id.startsWith("/") -> replaceAfterUpload = conflictItem
                            // Otherwise upload under a temporary name, then remove the old item and
                            // give the new one its real name.
                            else -> {
                                val dir = File(System.getProperty("java.io.tmpdir"), "upload_${System.nanoTime()}").apply { mkdirs() }
                                tempDir = dir
                                uploadSource = file.copyTo(File(dir, uniqueCloudName(existingNames, "${file.name}.uploading")), overwrite = true)
                                replaceAfterUpload = conflictItem
                                renameAfterUpload = true
                            }
                        }
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
                replaceAfterUpload?.let { old ->
                    val target = if (renameAfterUpload) old.path else old.id
                    cloudRepository.deleteCloudFile(accountId, target).getOrElse {
                        throw java.io.IOException("Uploaded \"${uploadSource.name}\" but couldn't remove the old \"${old.name}\": ${it.message}", it)
                    }
                    if (renameAfterUpload) {
                        val tempPath = if (targetDir == "/" || targetDir.isBlank()) "/${uploadSource.name}" else "${targetDir.trimEnd('/')}/${uploadSource.name}"
                        cloudRepository.renameCloudFile(accountId, tempPath, file.name).getOrElse {
                            throw java.io.IOException("Uploaded as \"${uploadSource.name}\" but couldn't rename it to \"${file.name}\": ${it.message}", it)
                        }
                    }
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
        out: MutableList<Pair<File, String>>,
        /** The folder's name at the destination; a top-level "Keep both" passes a new one. */
        remoteName: String = dir.name
    ) {
        val existing = cloudRepository.getCloudFiles(accountId, parentRemoteDir).getOrDefault(emptyList())
        val existingFolder = existing.find { it.isDirectory && it.name == remoteName }
        val targetDir = if (existingFolder != null) {
            existingFolder.path
        } else {
            val created = cloudRepository.createFolder(accountId, remoteName, parentRemoteDir)
            created.getOrNull()?.path
                ?: throw (created.exceptionOrNull() ?: Exception("Failed to create remote folder '$remoteName'"))
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
        val overwriteTopSources = remotePaths.filter { File(it).name in overwriteNames }.toSet()
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
                        // A Google Docs/Sheets/Slides file is exported as .docx/.xlsx/.pptx/.pdf;
                        // it used to be saved under its bare Drive name, with no extension, so
                        // nothing on the phone could open it. Keep the export's extension.
                        val exportExt = downloaded.extension
                        val finalFile = if (exportExt.isNotEmpty() &&
                            !item.localFile.name.endsWith(".$exportExt", ignoreCase = true) &&
                            downloaded.name.equals("${File(item.remotePath).name}.$exportExt", ignoreCase = true)
                        ) {
                            val withExt = File(item.localFile.parentFile, "${item.localFile.name}.$exportExt")
                            if (withExt.exists() && item.topSourcePath !in overwriteTopSources) {
                                com.antigravity.filemanager.data.local.storage.uniqueFile(withExt.parentFile!!, withExt.name)
                            } else withExt
                        } else item.localFile
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

