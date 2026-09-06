package com.antigravity.filemanager.data.repository

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import com.antigravity.filemanager.data.local.db.AppDatabase
import com.antigravity.filemanager.data.local.db.CloudEntity
import com.antigravity.filemanager.data.local.db.TrashEntity
import com.antigravity.filemanager.data.local.preferences.PreferenceManager
import com.antigravity.filemanager.data.local.storage.FileOperationsHelper
import com.antigravity.filemanager.data.local.storage.LocalFileScanner
import com.antigravity.filemanager.data.remote.cloud.CloudManager
import com.antigravity.filemanager.data.remote.ftp.FtpServerService
import com.antigravity.filemanager.domain.model.*
import com.antigravity.filemanager.domain.repository.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageRepositoryImpl @Inject constructor(
    private val scanner: LocalFileScanner,
    private val database: AppDatabase,
    private val folderCacheManager: com.antigravity.filemanager.data.local.cache.FolderCacheManager
) : IStorageRepository {

    override suspend fun getStorageVolumeInfo(): StorageVolumeInfo = withContext(Dispatchers.IO) {
        scanner.getStorageVolume()
    }

    override suspend fun getCategorySummaries(): List<CategorySummary> = withContext(Dispatchers.IO) {
        coroutineScope {
            val volume = scanner.getStorageVolume()
            val trashDeferred = async { database.trashDao().getTotalTrashSize() ?: 0L }
            val cloudDeferred = async { database.cloudDao().getAll().size }
            val imgDeferred = async { scanner.getMediaFolders(CategoryType.IMAGES) }
            val audioDeferred = async { scanner.getMediaFolders(CategoryType.AUDIO) }
            val videoDeferred = async { scanner.getMediaFolders(CategoryType.VIDEOS) }
            val docDeferred = async { scanner.getAllDocumentFiles() }
            val dlDeferred = async { scanner.getMediaFolders(CategoryType.DOWNLOADS) }

            val trashSize = trashDeferred.await()
            val cloudCount = cloudDeferred.await()
            val imgFolders = imgDeferred.await()
            val audioFolders = audioDeferred.await()
            val videoFolders = videoDeferred.await()
            val docFiles = docDeferred.await()
            val dlFolders = dlDeferred.await()

            val imgCount = imgFolders.sumOf { it.itemCount }
            val imgSize = imgFolders.sumOf { it.totalSizeBytes }
            val audioCount = audioFolders.sumOf { it.itemCount }
            val audioSize = audioFolders.sumOf { it.totalSizeBytes }
            val videoCount = videoFolders.sumOf { it.itemCount }
            val videoSize = videoFolders.sumOf { it.totalSizeBytes }
            val docCount = docFiles.size
            val docSize = docFiles.sumOf { it.size }
            val dlCount = dlFolders.sumOf { it.itemCount }
            val dlSize = dlFolders.sumOf { it.totalSizeBytes }

            listOf(
                CategorySummary(
                    type = CategoryType.MAIN_STORAGE,
                    title = "Main storage",
                    totalSizeBytes = volume.totalBytes,
                    itemCount = 0,
                    subtitle = "${volume.formattedUsed} / ${volume.formattedTotal}"
                ),
                CategorySummary(
                    type = CategoryType.DOWNLOADS,
                    title = "Downloads",
                    totalSizeBytes = dlSize,
                    itemCount = dlCount
                ),
                CategorySummary(
                    type = CategoryType.STORAGE_ANALYSIS,
                    title = "Storage Anal…",
                    totalSizeBytes = volume.usedBytes,
                    subtitle = "${volume.usedPercentageInt}% used"
                ),
                CategorySummary(
                    type = CategoryType.IMAGES,
                    title = "Images",
                    totalSizeBytes = imgSize,
                    itemCount = imgCount
                ),
                CategorySummary(
                    type = CategoryType.AUDIO,
                    title = "Audio",
                    totalSizeBytes = audioSize,
                    itemCount = audioCount
                ),
                CategorySummary(
                    type = CategoryType.VIDEOS,
                    title = "Videos",
                    totalSizeBytes = videoSize,
                    itemCount = videoCount
                ),
                CategorySummary(
                    type = CategoryType.DOCUMENTS,
                    title = "Documents",
                    totalSizeBytes = docSize,
                    itemCount = docCount
                ),
                CategorySummary(
                    type = CategoryType.CLOUD,
                    title = "Cloud",
                    itemCount = cloudCount
                ),
                CategorySummary(
                    type = CategoryType.ACCESS_FROM_NETWORK,
                    title = "FTP"
                ),
                CategorySummary(
                    type = CategoryType.RECYCLE_BIN,
                    title = "Recycle Bin",
                    totalSizeBytes = trashSize
                )
            )
        }
    }

    override fun observeCategorySummaries(): Flow<List<CategorySummary>> = flow {
        // Step 1: instantly show last session's numbers (persisted to disk) if we have them,
        // instead of blank placeholder cards — a cold app start used to always show 0/empty
        // cards until the full scan below finished.
        val cached = folderCacheManager.getDashboardSummaries()
        if (cached != null) {
            emit(cached)
        } else {
            val volume = scanner.getStorageVolume()
            val baseline = listOf(
                CategorySummary(
                    type = CategoryType.MAIN_STORAGE,
                    title = "Main storage",
                    totalSizeBytes = volume.totalBytes,
                    subtitle = "${volume.formattedUsed} / ${volume.formattedTotal}"
                ),
                CategorySummary(type = CategoryType.DOWNLOADS, title = "Downloads"),
                CategorySummary(
                    type = CategoryType.STORAGE_ANALYSIS,
                    title = "Storage Anal…",
                    totalSizeBytes = volume.usedBytes,
                    subtitle = "${volume.usedPercentageInt}% used"
                ),
                CategorySummary(type = CategoryType.IMAGES, title = "Images"),
                CategorySummary(type = CategoryType.AUDIO, title = "Audio"),
                CategorySummary(type = CategoryType.VIDEOS, title = "Videos"),
                CategorySummary(type = CategoryType.DOCUMENTS, title = "Documents"),
                CategorySummary(type = CategoryType.CLOUD, title = "Cloud"),
                CategorySummary(type = CategoryType.ACCESS_FROM_NETWORK, title = "FTP"),
                CategorySummary(type = CategoryType.RECYCLE_BIN, title = "Recycle Bin")
            )
            emit(baseline)
        }

        // Step 2: compute the real, current summaries in the background and update the cache.
        val fresh = getCategorySummaries()
        folderCacheManager.putDashboardSummaries(fresh)
        emit(fresh)
    }
}

@Singleton
class StorageAnalysisRepositoryImpl @Inject constructor(
    private val scanner: LocalFileScanner,
    private val recycleBinRepository: IRecycleBinRepository
) : IStorageAnalysisRepository {

    private val mutex = Mutex()
    private var inFlight: kotlinx.coroutines.Deferred<StorageAnalysisData>? = null

    // Single-flight: if a screen asks for both the full breakdown and the large-files list
    // around the same time, the second caller joins the first's in-progress scan instead of
    // starting its own full-device walk. Unlike a time-based cache, this never returns a stale
    // result once the scan has finished — the next call always triggers a fresh one.
    private suspend fun scanWithCache(): StorageAnalysisData = coroutineScope {
        val joined = mutex.withLock { inFlight }
        if (joined != null) return@coroutineScope joined.await()

        val deferred = async {
            // scanner.scanStorageAnalysis() always hardcoded recycleBinBytes to 0 — it has no
            // knowledge of the app's own recycle bin (a Room-backed table of moved-not-deleted
            // files under .filemanager_trash, tracked separately from the raw filesystem walk).
            // Fetch the real total from there instead so this card reflects what Recycle Bin
            // actually shows.
            val trashDeferred = async { recycleBinRepository.getTrashTotalSize() }
            val sampleDeferred = async { recycleBinRepository.getTrashItems().firstOrNull() }
            val analysis = scanner.scanStorageAnalysis()
            val sample = sampleDeferred.await()
            analysis.copy(
                recycleBinBytes = trashDeferred.await(),
                recycleBinSampleItem = sample?.let {
                    FileItem(
                        id = it.trashPath,
                        name = it.fileName,
                        // originalPath (not trashPath) so the UI can show where the item came
                        // from — the physical file itself now lives under .filemanager_trash,
                        // which isn't meaningful to show the user.
                        path = it.originalPath,
                        size = it.fileSize,
                        lastModified = it.deletedTimestamp,
                        isDirectory = it.isDirectory,
                        extension = if (it.isDirectory) "" else File(it.fileName).extension
                    )
                }
            )
        }
        mutex.withLock { inFlight = deferred }
        try {
            deferred.await()
        } finally {
            mutex.withLock { if (inFlight === deferred) inFlight = null }
        }
    }

    override suspend fun getStorageAnalysisData(): StorageAnalysisData = scanWithCache()

    override suspend fun getAllLargeFiles(): List<LargeFileItem> = scanWithCache().largeFiles
}

@Singleton
class FileRepositoryImpl @Inject constructor(
    private val scanner: LocalFileScanner,
    private val operationsHelper: FileOperationsHelper
) : IFileRepository {

    override suspend fun getFilesInDirectory(
        directoryPath: String,
        sortOption: FileSortOption,
        showHidden: Boolean
    ): List<FileItem> = scanner.listFilesInDir(directoryPath, sortOption, showHidden)

    override suspend fun getMediaFolders(categoryType: CategoryType, sortOption: FileSortOption): List<MediaFolder> =
        scanner.getMediaFolders(categoryType, sortOption)

    override suspend fun getMediaFilesInFolder(
        folderPath: String,
        categoryType: CategoryType,
        sortOption: FileSortOption
    ): List<FileItem> = scanner.listFilesInDir(folderPath, sortOption, showHidden = false)

    override suspend fun getAllDocuments(sortOption: FileSortOption): List<FileItem> =
        scanner.getAllDocumentFiles(sortOption)

    private val searchImageExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "svg", "raw", "dng")
    private val searchVideoExts = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "ts", "m4v", "mpg", "mpeg", "vob", "ogv", "f4v")
    private val searchAudioExts = setOf("mp3", "m4a", "wav", "flac", "aac", "ogg", "wma", "opus", "amr", "mid", "midi")
    private val searchDocExts = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "epub")

    override suspend fun searchFiles(query: String, rootPath: String?, categoryType: CategoryType?): List<FileItem> = withContext(Dispatchers.IO) {
        // Category-scoped search (Images/Videos/Audio/Documents) goes through MediaStore instead
        // of a raw java.io.File recursion — see searchMediaByCategory's comment for why: some OEM
        // ROMs restrict/virtualize direct filesystem access to camera/media folders in ways that
        // silently made a plain dir.listFiles() walk skip DCIM/Camera entirely, while MediaStore
        // (the same index the category grids themselves are built from) always sees it correctly.
        // rootPath, when set, scopes results to that one folder (the user searching from inside a
        // specific bucket like Camera) instead of the whole category.
        if (categoryType != null) {
            return@withContext scanner.searchMediaByCategory(query, categoryType, folderPath = rootPath)
        }

        val root = if (rootPath != null) File(rootPath) else Environment.getExternalStorageDirectory()
        val results = mutableListOf<FileItem>()

        // categoryType-specific extension filter, applied *during* the walk rather than after —
        // previously categoryType was accepted but never actually used here, so the 500-match
        // budget below was spent on every filename match anywhere on the device (docs, apks,
        // videos...) regardless of the category being searched. On a device with many
        // same-named/non-matching-type files, that budget could be exhausted before the walk
        // ever reached folders like DCIM/Camera, making genuine image matches there silently
        // disappear even though the device had plenty of storage left to search. Filtering by
        // extension up front means the cap only ever counts files that could actually show up
        // in this category's results.
        val allowedExts: Set<String>? = when (categoryType) {
            CategoryType.IMAGES -> searchImageExts
            CategoryType.VIDEOS -> searchVideoExts
            CategoryType.AUDIO -> searchAudioExts
            CategoryType.DOCUMENTS -> searchDocExts
            else -> null
        }

        // Bounded so a broad query on a huge device can't turn into an unlimited-depth,
        // unlimited-result full-storage walk — stop once we have enough matches to show.
        val maxResults = 500
        val maxDepth = 12

        fun searchRecursive(dir: File, depth: Int) {
            if (results.size >= maxResults || depth > maxDepth) return
            val list = dir.listFiles() ?: return
            for (f in list) {
                if (results.size >= maxResults) return
                val matchesType = f.isDirectory || allowedExts == null || f.extension.lowercase() in allowedExts
                if (matchesType && f.name.contains(query, ignoreCase = true)) {
                    val isDir = f.isDirectory
                    results.add(
                        FileItem(
                            id = f.absolutePath,
                            name = f.name,
                            path = f.absolutePath,
                            size = if (isDir) 0L else f.length(),
                            lastModified = f.lastModified(),
                            isDirectory = isDir,
                            extension = if (isDir) "" else f.extension,
                            // Was never set here — every search result showed only its generic
                            // type icon, never a real thumbnail. Coil's registered fetchers
                            // (image decoding, VideoThumbnailFetcher, PdfThumbnailFetcher,
                            // ApkIconFetcher, AudioArtFetcher) already handle "not actually one of
                            // my types" by producing nothing, which FileListItem's `error` painter
                            // falls back from — safe to just point every file at its own path.
                            thumbnailUri = if (isDir) null else f.absolutePath
                        )
                    )
                }
                if (f.isDirectory && !f.name.startsWith(".")) {
                    searchRecursive(f, depth + 1)
                }
            }
        }

        searchRecursive(root, 0)
        results
    }

    override suspend fun copyFiles(sourcePaths: List<String>, targetDirectory: String, overwriteNames: Set<String>, skipNames: Set<String>): Result<Unit> =
        operationsHelper.copy(sourcePaths, targetDirectory, overwriteNames, skipNames)

    override suspend fun moveFiles(sourcePaths: List<String>, targetDirectory: String, overwriteNames: Set<String>, skipNames: Set<String>): Result<Unit> =
        operationsHelper.move(sourcePaths, targetDirectory, overwriteNames, skipNames)

    override suspend fun findCopyConflicts(sourcePaths: List<String>, targetDirectory: String): List<com.antigravity.filemanager.domain.model.OverwriteConflict> =
        operationsHelper.findConflicts(sourcePaths, targetDirectory)

    override suspend fun renameFile(filePath: String, newName: String): Result<FileItem> =
        operationsHelper.rename(filePath, newName)

    override suspend fun createDirectory(parentPath: String, directoryName: String): Result<FileItem> =
        operationsHelper.createDirectory(parentPath, directoryName)

    override suspend fun zipFiles(
        sourcePaths: List<String>,
        targetZipPath: String,
        onProgress: ((currentFile: String, currentIndex: Int, totalFiles: Int) -> Unit)?
    ): Result<FileItem> =
        operationsHelper.zipFiles(sourcePaths, targetZipPath, onProgress)

    override suspend fun extractZip(zipFilePath: String, targetDirectory: String): Result<Unit> =
        operationsHelper.extractZip(zipFilePath, targetDirectory)

    override suspend fun getFileDetails(filePath: String): FileItem? = withContext(Dispatchers.IO) {
        val f = File(filePath)
        if (!f.exists()) return@withContext null
        val isDir = f.isDirectory
        FileItem(
            id = f.absolutePath,
            name = f.name,
            path = f.absolutePath,
            size = if (isDir) 0L else f.length(),
            lastModified = f.lastModified(),
            isDirectory = isDir,
            extension = if (isDir) "" else f.extension
        )
    }
}

@Singleton
class RecycleBinRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase
) : IRecycleBinRepository {

    private val trashRoot = File(Environment.getExternalStorageDirectory(), ".filemanager_trash")

    init {
        if (!trashRoot.exists()) trashRoot.mkdirs()
        // A leading-dot directory name only hides it from plain file listings (this app's own
        // recursive scans already skip those) — MediaStore's own indexer doesn't honor that
        // convention at all, only an actual .nomedia marker file does. Without one, every file
        // moved here still gets indexed and its folder shows up as a bucket in Images/Audio/
        // Videos/Documents, exactly as if it were a normal visible folder.
        val noMedia = File(trashRoot, ".nomedia")
        if (!noMedia.exists()) {
            try { noMedia.createNewFile() } catch (e: Exception) {}
        }
        // Rows for files that landed here before the .nomedia marker existed (or that MediaStore
        // indexed in the gap before it noticed the marker) would otherwise sit stale in the index
        // until a full device rescan — purge them explicitly so this takes effect immediately.
        try {
            context.contentResolver.delete(
                MediaStore.Files.getContentUri("external"),
                "${MediaStore.Files.FileColumns.DATA} LIKE ?",
                arrayOf("${trashRoot.absolutePath}/%")
            )
        } catch (e: Exception) {}
    }

    override fun observeTrashItems(): Flow<List<TrashItem>> =
        database.trashDao().observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getTrashItems(): List<TrashItem> = withContext(Dispatchers.IO) {
        database.trashDao().getAll().map { it.toDomain() }
    }

    override suspend fun moveToTrash(
        filePaths: List<String>,
        onProgress: ((currentName: String, currentIndex: Int, total: Int) -> Unit)?
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            var count = 0
            for ((index, path) in filePaths.withIndex()) {
                val source = File(path)
                onProgress?.invoke(source.name, index + 1, filePaths.size)
                if (!source.exists()) continue

                val trashName = "${System.currentTimeMillis()}_${source.name}"
                val trashFile = File(trashRoot, trashName)
                val size = if (source.isDirectory) 0L else source.length()
                val isDir = source.isDirectory

                // Same renameTo()-alone-isn't-reliable-enough fix as restoreFromTrash below —
                // fall back to copy+delete instead of just skipping the file when it fails.
                val moved = try {
                    if (source.renameTo(trashFile)) {
                        true
                    } else if (isDir) {
                        source.copyRecursively(trashFile, overwrite = true)
                        source.deleteRecursively()
                        true
                    } else {
                        source.copyTo(trashFile, overwrite = true)
                        source.delete()
                        true
                    }
                } catch (e: Exception) {
                    false
                }

                if (moved) {
                    database.trashDao().insert(
                        TrashEntity(
                            originalPath = source.absolutePath,
                            trashPath = trashFile.absolutePath,
                            fileName = source.name,
                            fileSize = size,
                            deletedTimestamp = System.currentTimeMillis(),
                            isDirectory = isDir
                        )
                    )
                    count++
                }
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun restoreFromTrash(trashIds: List<Long>): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val entities = database.trashDao().getByIds(trashIds)
            var restored = 0
            val scannedPaths = mutableListOf<String>()
            for (entity in entities) {
                val trashFile = File(entity.trashPath)
                val originalFile = File(entity.originalPath)
                if (!trashFile.exists()) continue
                originalFile.parentFile?.mkdirs()

                // renameTo() alone silently fails on a lot of real devices/paths — same reason
                // FileOperationsHelper.move() below already falls back to copy+delete instead of
                // trusting it outright. Restore had no such fallback, so on any device/path where
                // renameTo() just returns false, the file quietly never came back (still sitting
                // in .filemanager_trash) with the DB row untouched — restored never incremented,
                // no error surfaced anywhere, reading as "Restore doesn't do anything."
                val moved = try {
                    if (trashFile.renameTo(originalFile)) {
                        true
                    } else if (entity.isDirectory) {
                        trashFile.copyRecursively(originalFile, overwrite = true)
                        trashFile.deleteRecursively()
                        true
                    } else {
                        trashFile.copyTo(originalFile, overwrite = true)
                        trashFile.delete()
                        true
                    }
                } catch (e: Exception) {
                    false
                }

                if (moved) {
                    database.trashDao().deleteByIds(listOf(entity.id))
                    scannedPaths.add(originalFile.absolutePath)
                    restored++
                }
            }
            // Writing straight to a java.io.File never tells MediaStore anything happened — a
            // restored photo/video/etc. showed back up fine navigating to its folder directly, but
            // never updated the folder's thumbnail/count on the category root grid without this.
            if (scannedPaths.isNotEmpty()) {
                try {
                    android.media.MediaScannerConnection.scanFile(context, scannedPaths.toTypedArray(), null, null)
                } catch (e: Exception) {}
            }
            Result.success(restored)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deletePermanently(
        trashIds: List<Long>,
        onProgress: ((currentName: String, currentIndex: Int, total: Int) -> Unit)?
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val entities = database.trashDao().getByIds(trashIds)
            var deleted = 0
            for ((index, entity) in entities.withIndex()) {
                onProgress?.invoke(entity.fileName, index + 1, entities.size)
                val trashFile = File(entity.trashPath)
                if (trashFile.exists()) {
                    if (trashFile.isDirectory) trashFile.deleteRecursively() else trashFile.delete()
                }
                database.trashDao().deleteByIds(listOf(entity.id))
                deleted++
            }
            Result.success(deleted)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun emptyTrash(onProgress: ((currentName: String, currentIndex: Int, total: Int) -> Unit)?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val all = database.trashDao().getAll()
            for ((index, item) in all.withIndex()) {
                onProgress?.invoke(item.fileName, index + 1, all.size)
                val f = File(item.trashPath)
                if (f.exists()) {
                    if (f.isDirectory) f.deleteRecursively() else f.delete()
                }
            }
            database.trashDao().clearAll()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getTrashTotalSize(): Long = withContext(Dispatchers.IO) {
        database.trashDao().getTotalTrashSize() ?: 0L
    }
}

@Singleton
class FtpServerRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefManager: PreferenceManager
) : IFtpServerRepository {

    override fun observeFtpServerState(): Flow<FtpServerState> = FtpServerService.ftpState

    override suspend fun getFtpServerState(): FtpServerState = FtpServerService.ftpState.value

    override suspend fun startFtpServer(
        port: Int,
        password: String,
        isRandomPassword: Boolean,
        showHidden: Boolean
    ): Result<Unit> {
        val intent = Intent(context, FtpServerService::class.java).apply {
            action = FtpServerService.ACTION_START
            putExtra(FtpServerService.EXTRA_PORT, port)
            putExtra(FtpServerService.EXTRA_PASSWORD, password)
            putExtra(FtpServerService.EXTRA_RANDOM_PASS, isRandomPassword)
            putExtra(FtpServerService.EXTRA_SHOW_HIDDEN, showHidden)
        }
        context.startService(intent)
        return Result.success(Unit)
    }

    override suspend fun stopFtpServer(): Result<Unit> {
        val intent = Intent(context, FtpServerService::class.java).apply {
            action = FtpServerService.ACTION_STOP
        }
        context.startService(intent)
        return Result.success(Unit)
    }

    override suspend fun updateConfig(
        port: Int,
        password: String,
        isRandomPassword: Boolean,
        showHidden: Boolean
    ) {
        prefManager.saveFtpConfig(port, password, isRandomPassword, showHidden)
    }
}

@Singleton
class CloudRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val cloudManager: CloudManager
) : ICloudRepository {

    override fun observeConnectedAccounts(): Flow<List<CloudAccount>> =
        database.cloudDao().observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getConnectedAccounts(): List<CloudAccount> = withContext(Dispatchers.IO) {
        database.cloudDao().getAll().map { it.toDomain() }
    }

    override suspend fun addAccount(account: CloudAccount): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val count = database.cloudDao().getAll().size
            val rawSession = account.sessionHandle ?: ""
            // Offload large JSON payload to disk to prevent SQLiteBlobTooBigException (SQLite CursorWindow limit)
            val lightAccount = if (rawSession.length > 500 || rawSession.contains("\"folders\":") || rawSession.contains("\"files\":")) {
                cloudManager.saveSessionPayload(account.id, rawSession)
                val lightSession = if (rawSession.contains("\"sid\":")) {
                    try {
                        org.json.JSONObject(rawSession).optString("sid", "session_active")
                    } catch (e: Exception) { "session_active" }
                } else "session_active"
                account.copy(displayOrder = count, sessionHandle = lightSession)
            } else {
                account.copy(displayOrder = count)
            }
            database.cloudDao().insert(CloudEntity.fromDomain(lightAccount))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun removeAccount(accountId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            cloudManager.deleteSessionPayload(accountId)
            database.cloudDao().deleteById(accountId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateAccountsOrder(accounts: List<CloudAccount>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val entities = accounts.mapIndexed { index, acc ->
                CloudEntity.fromDomain(acc.copy(displayOrder = index))
            }
            database.cloudDao().insertAll(entities)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getCloudFiles(accountId: String, remotePath: String, forceFullRefresh: Boolean): Result<List<FileItem>> =
        withContext(Dispatchers.IO) {
            val account = database.cloudDao().getById(accountId)?.toDomain()
                ?: return@withContext Result.failure(Exception("Account not found"))
            cloudManager.listCloudFiles(account, remotePath, forceFullRefresh)
        }

    override suspend fun downloadCloudFile(
        accountId: String,
        remotePath: String,
        localTargetDir: String,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)?
    ): Result<File> = withContext(Dispatchers.IO) {
        val account = database.cloudDao().getById(accountId)?.toDomain()
            ?: return@withContext Result.failure(Exception("Account not found"))
        cloudManager.downloadFile(account, remotePath, localTargetDir, onProgress)
    }

    override suspend fun uploadCloudFile(
        accountId: String,
        localFilePath: String,
        remoteTargetDir: String,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val account = database.cloudDao().getById(accountId)?.toDomain()
            ?: return@withContext Result.failure(Exception("Account not found"))
        cloudManager.uploadFile(account, localFilePath, remoteTargetDir, onProgress)
    }

    override suspend fun createFolder(
        accountId: String,
        folderName: String,
        parentPath: String
    ): Result<FileItem> = withContext(Dispatchers.IO) {
        val account = database.cloudDao().getById(accountId)?.toDomain()
            ?: return@withContext Result.failure(Exception("Account not found"))
        cloudManager.createFolder(account, folderName, parentPath)
    }

    override suspend fun deleteCloudFile(
        accountId: String,
        remotePath: String,
        moveToTrash: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val account = database.cloudDao().getById(accountId)?.toDomain()
            ?: return@withContext Result.failure(Exception("Account not found"))
        cloudManager.deleteItem(account, remotePath, moveToTrash)
    }

    override suspend fun deletePermanentlyBatchMega(accountId: String, remotePaths: List<String>): Map<String, Result<Unit>> = withContext(Dispatchers.IO) {
        val account = database.cloudDao().getById(accountId)?.toDomain()
            ?: return@withContext remotePaths.associateWith { Result.failure(Exception("Account not found")) }
        if (account.provider != com.antigravity.filemanager.domain.model.CloudProvider.MEGA) return@withContext emptyMap()
        cloudManager.deletePermanentlyBatchMega(account, remotePaths)
    }

    override suspend fun restoreCloudFile(accountId: String, remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        val account = database.cloudDao().getById(accountId)?.toDomain()
            ?: return@withContext Result.failure(Exception("Account not found"))
        cloudManager.restoreItem(account, remotePath)
    }

    override suspend fun getCloudQuota(accountId: String): Result<Pair<Long, Long>> = withContext(Dispatchers.IO) {
        val account = database.cloudDao().getById(accountId)?.toDomain()
            ?: return@withContext Result.failure(Exception("Account not found"))
        cloudManager.getAccountQuota(account)
    }

    override suspend fun renameCloudFile(
        accountId: String,
        remotePath: String,
        newName: String
    ): Result<FileItem> = withContext(Dispatchers.IO) {
        val account = database.cloudDao().getById(accountId)?.toDomain()
            ?: return@withContext Result.failure(Exception("Account not found"))
        cloudManager.renameItem(account, remotePath, newName)
    }

    override suspend fun moveCloudFileWithinAccount(
        accountId: String,
        sourcePath: String,
        targetDir: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val account = database.cloudDao().getById(accountId)?.toDomain()
            ?: return@withContext Result.failure(Exception("Account not found"))
        cloudManager.moveItemWithinAccount(account, sourcePath, targetDir)
    }

    override suspend fun downloadCloudThumbnail(accountId: String, nodeId: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        val account = database.cloudDao().getById(accountId)?.toDomain()
            ?: return@withContext Result.failure(Exception("Account not found"))
        cloudManager.downloadThumbnail(account, nodeId)
    }

    override suspend fun downloadCloudFilePartial(accountId: String, nodeId: String, localTargetFile: java.io.File, maxBytes: Long): Result<java.io.File> = withContext(Dispatchers.IO) {
        val account = database.cloudDao().getById(accountId)?.toDomain()
            ?: return@withContext Result.failure(Exception("Account not found"))
        cloudManager.downloadFilePartial(account, nodeId, localTargetFile, maxBytes)
    }

    override suspend fun openCloudThumbnailDataSource(accountId: String, nodeId: String): Result<android.media.MediaDataSource> = withContext(Dispatchers.IO) {
        val account = database.cloudDao().getById(accountId)?.toDomain()
            ?: return@withContext Result.failure(Exception("Account not found"))
        cloudManager.openThumbnailDataSource(account, nodeId)
    }

    override suspend fun getCloudStreamableLink(accountId: String, remotePath: String): Result<String> = withContext(Dispatchers.IO) {
        val account = database.cloudDao().getById(accountId)?.toDomain()
            ?: return@withContext Result.failure(Exception("Account not found"))
        cloudManager.getStreamableLink(account, remotePath)
    }

    override suspend fun getCloudStreamSource(accountId: String, remotePath: String): Result<com.antigravity.filemanager.domain.model.CloudStreamSource> = withContext(Dispatchers.IO) {
        val account = database.cloudDao().getById(accountId)?.toDomain()
            ?: return@withContext Result.failure(Exception("Account not found"))
        cloudManager.getStreamSource(account, remotePath)
    }
}

