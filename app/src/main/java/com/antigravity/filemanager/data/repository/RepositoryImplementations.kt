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
import com.antigravity.filemanager.data.local.storage.FileCopyEntry
import com.antigravity.filemanager.data.local.storage.collectFileCopyEntries
import com.antigravity.filemanager.data.local.storage.directorySize
import com.antigravity.filemanager.data.local.storage.uniqueFile
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

internal suspend fun healZeroTrashFolders(database: AppDatabase) {
    try {
        val items = database.trashDao().getAll()
        val missingIds = mutableListOf<Long>()
        for (item in items) {
            val file = File(item.trashPath)
            if (!file.exists()) {
                missingIds.add(item.id)
                continue
            }
            if (item.isDirectory && item.fileSize <= 0L) {
                val computedSize = directorySize(file)
                if (computedSize > 0L) {
                    database.trashDao().updateFileSize(item.id, computedSize)
                }
            }
        }
        if (missingIds.isNotEmpty()) {
            database.trashDao().deleteByIds(missingIds)
        }
    } catch (e: Exception) {
        // ignore
    }
}

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
            val trashDeferred = async {
                healZeroTrashFolders(database)
                val size = database.trashDao().getTotalTrashSize() ?: 0L
                val count = database.trashDao().getTrashCount()
                Pair(size, count)
            }
            val cloudDeferred = async { database.cloudDao().getAll().size }
            val imgDeferred = async { scanner.getMediaFolders(CategoryType.IMAGES) }
            val audioDeferred = async { scanner.getMediaFolders(CategoryType.AUDIO) }
            val videoDeferred = async { scanner.getMediaFolders(CategoryType.VIDEOS) }
            val docDeferred = async { scanner.getAllDocumentFiles() }
            val dlDeferred = async { scanner.getMediaFolders(CategoryType.DOWNLOADS) }

            val (trashSize, trashCount) = trashDeferred.await()
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
                    totalSizeBytes = trashSize,
                    itemCount = trashCount
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
        showHidden: Boolean,
        mergeCloneDownloads: Boolean
    ): List<FileItem> = scanner.listFilesInDir(directoryPath, sortOption, showHidden, mergeCloneDownloads)

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
    private val searchDocExts = setOf(
        "pdf", "rtf", "wps", "wpd", "ps",
        "doc", "docx", "docm", "dot", "dotx",
        "xls", "xlsx", "xlsm", "xlt", "xltx", "csv", "tsv",
        "ppt", "pptx", "pptm", "pps", "ppsx", "pot", "potx",
        "odt", "ods", "odp", "ott", "ots", "otp", "sxw", "sxc", "sxi",
        "pages", "numbers", "key", "keynote",
        "txt", "text", "log", "md", "markdown", "rst", "tex", "latex", "note", "nfo", "diz",
        "json", "xml", "yaml", "yml", "ini", "conf", "properties", "html", "htm", "msg", "eml", "vcf",
        "epub", "mobi", "azw", "azw3", "prc", "fb2", "djvu", "chm", "lit"
    )

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
                if (f.name.startsWith(".")) continue
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

    override suspend fun copyFiles(
        sourcePaths: List<String>,
        targetDirectory: String,
        overwriteNames: Set<String>,
        skipNames: Set<String>,
        onProgress: ((currentFile: String, currentIndex: Int, totalFiles: Int) -> Unit)?
    ): Result<Unit> =
        operationsHelper.copy(sourcePaths, targetDirectory, overwriteNames, skipNames, onProgress)

    override suspend fun moveFiles(
        sourcePaths: List<String>,
        targetDirectory: String,
        overwriteNames: Set<String>,
        skipNames: Set<String>,
        onProgress: ((currentFile: String, currentIndex: Int, totalFiles: Int) -> Unit)?
    ): Result<Unit> =
        operationsHelper.move(sourcePaths, targetDirectory, overwriteNames, skipNames, onProgress)

    override suspend fun findCopyConflicts(sourcePaths: List<String>, targetDirectory: String): List<com.antigravity.filemanager.domain.model.OverwriteConflict> =
        operationsHelper.findConflicts(sourcePaths, targetDirectory)

    override suspend fun renameFile(filePath: String, newName: String): Result<FileItem> =
        operationsHelper.rename(filePath, newName)

    override suspend fun createDirectory(parentPath: String, directoryName: String): Result<FileItem> =
        operationsHelper.createDirectory(parentPath, directoryName)

    override suspend fun compressFiles(
        sourcePaths: List<String>,
        targetArchivePath: String,
        onProgress: ((currentFile: String, currentIndex: Int, totalFiles: Int, bytesProcessed: Long, totalBytes: Long) -> Unit)?
    ): Result<FileItem> =
        operationsHelper.compressFiles(sourcePaths, targetArchivePath, onProgress)

    override suspend fun extractArchive(
        archiveFilePath: String,
        targetDirectory: String,
        password: String?,
        overwriteNames: Set<String>,
        skipNames: Set<String>,
        onProgress: ((currentEntry: String, currentIndex: Int, totalEntries: Int, bytesProcessed: Long, totalBytes: Long) -> Unit)?
    ): Result<com.antigravity.filemanager.domain.model.ExtractResult> =
        operationsHelper.extractArchive(archiveFilePath, targetDirectory, password, overwriteNames, skipNames, onProgress)

    override suspend fun getArchiveConflicts(
        archiveFilePath: String,
        targetDirectory: String,
        password: String?
    ): List<com.antigravity.filemanager.domain.model.OverwriteConflict> =
        operationsHelper.getArchiveConflicts(archiveFilePath, targetDirectory, password)

    override fun isArchiveEncrypted(archiveFilePath: String): Boolean =
        operationsHelper.isArchiveEncrypted(archiveFilePath)

    override suspend fun listArchiveEntries(archiveFilePath: String, password: String?) =
        operationsHelper.listArchiveEntries(archiveFilePath, password)

    override suspend fun extractArchiveEntries(
        archiveFilePath: String,
        selectedPaths: List<String>,
        baseDir: String,
        targetDirectory: String,
        password: String?
    ): Result<List<java.io.File>> =
        operationsHelper.extractArchiveEntries(archiveFilePath, selectedPaths, baseDir, targetDirectory, password)

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
    private val database: AppDatabase,
    private val mediaChangeSignal: com.antigravity.filemanager.data.local.observer.MediaChangeSignal
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
    }

    override fun observeTrashItems(): Flow<List<TrashItem>> =
        database.trashDao().observeAll().map { list ->
            val (validList, missing) = list.partition { File(it.trashPath).exists() }
            if (missing.isNotEmpty()) {
                try { database.trashDao().deleteByIds(missing.map { it.id }) } catch (e: Exception) {}
            }
            validList.map { entity ->
                var displayEntity = entity
                if (entity.isDirectory && entity.fileSize <= 0L) {
                    val computed = directorySize(File(entity.trashPath))
                    if (computed > 0L) {
                        database.trashDao().updateFileSize(entity.id, computed)
                        displayEntity = entity.copy(fileSize = computed)
                    }
                }
                displayEntity.toDomain()
            }
        }.flowOn(Dispatchers.IO) // exists()/directory walks per emission must stay off the main thread

    override suspend fun getTrashItems(): List<TrashItem> = withContext(Dispatchers.IO) {
        healZeroTrashFolders(database)
        database.trashDao().getAll().filter { File(it.trashPath).exists() }.map { it.toDomain() }
    }

    override suspend fun moveToTrash(
        filePaths: List<String>,
        onProgress: ((currentName: String, currentIndex: Int, total: Int) -> Unit)?
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            var count = 0
            suspend fun record(source: File, trashFile: File, size: Long, isDir: Boolean) {
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

            // Phase 1: renameTo() is atomic and effectively instant for a same-filesystem move —
            // even a folder with thousands of files inside moves to trash in one O(1) call, so
            // there's nothing meaningful to report progress on for these. Only a genuine
            // cross-filesystem case (trash root lives on internal storage; a folder being deleted
            // from an SD card can't renameTo() there) falls through to the per-file fallback in
            // phase 2 below.
            data class PendingItem(val source: File, val trashFile: File, val size: Long, val isDir: Boolean)
            val fallbacks = mutableListOf<PendingItem>()
            for (path in filePaths) {
                val source = File(path)
                if (!source.exists()) continue
                // Unique per item: two same-named files trashed within the same millisecond used
                // to share one trash path, and renameTo() silently replaced the first one.
                val trashFile = uniqueFile(trashRoot, "${System.currentTimeMillis()}_${source.name}")
                val isDir = source.isDirectory
                val size = if (isDir) directorySize(source) else source.length()
                if (source.renameTo(trashFile)) {
                    record(source, trashFile, size, isDir)
                } else {
                    fallbacks.add(PendingItem(source, trashFile, size, isDir))
                }
            }

            // Phase 2: genuine cross-filesystem fallback.
            val moved = moveTreesByCopy(fallbacks.map { it.source to it.trashFile }, onProgress)
            fallbacks.forEachIndexed { index, item ->
                if (moved[index]) record(item.source, item.trashFile, item.size, item.isDir)
            }
            if (count > 0) mediaChangeSignal.notifyChanged()
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun restoreFromTrash(
        trashIds: List<Long>,
        onProgress: ((currentName: String, currentIndex: Int, total: Int) -> Unit)?
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val entities = database.trashDao().getByIds(trashIds)
            var restored = 0
            val scannedPaths = mutableListOf<String>()

            // renameTo() alone silently fails on a lot of real devices/paths (cross-filesystem,
            // permissions), so anything it can't handle goes through the same per-file
            // copy+delete fallback, with progress, as moveToTrash.
            data class PendingItem(val entity: TrashEntity, val trashFile: File, val originalFile: File)
            val fallbacks = mutableListOf<PendingItem>()
            for (entity in entities) {
                val trashFile = File(entity.trashPath)
                if (!trashFile.exists()) continue
                val original = File(entity.originalPath)
                val parent = original.parentFile
                parent?.mkdirs()
                // Something new may have been created at the original path since the delete —
                // renameTo() would silently replace it, so restore next to it instead.
                val originalFile = if (parent != null) uniqueFile(parent, original.name) else original
                if (trashFile.renameTo(originalFile)) {
                    database.trashDao().deleteByIds(listOf(entity.id))
                    scannedPaths.add(originalFile.absolutePath)
                    restored++
                } else {
                    fallbacks.add(PendingItem(entity, trashFile, originalFile))
                }
            }

            val moved = moveTreesByCopy(fallbacks.map { it.trashFile to it.originalFile }, onProgress)
            fallbacks.forEachIndexed { index, item ->
                if (moved[index]) {
                    database.trashDao().deleteByIds(listOf(item.entity.id))
                    scannedPaths.add(item.originalFile.absolutePath)
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
            if (restored > 0) mediaChangeSignal.notifyChanged()
            Result.success(restored)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Copy+delete fallback for moves renameTo() can't do. Each (source, dest) pair is flattened
     * into per-file work sharing one progress counter; a pair that fails partway has its partial
     * destination removed and its source left untouched. Returns per-pair success. */
    private fun moveTreesByCopy(
        pairs: List<Pair<File, File>>,
        onProgress: ((currentName: String, currentIndex: Int, total: Int) -> Unit)?
    ): List<Boolean> {
        val perItemEntries = pairs.map { (source, dest) ->
            val fileEntries = mutableListOf<FileCopyEntry>()
            val dirEntries = mutableListOf<FileCopyEntry>()
            collectFileCopyEntries(source, dest, fileEntries, dirEntries)
            fileEntries to dirEntries
        }
        val total = perItemEntries.sumOf { it.first.size + it.second.size }
        var current = 0
        return pairs.mapIndexed { index, (source, dest) ->
            val (fileEntries, dirEntries) = perItemEntries[index]
            try {
                for (entry in dirEntries) {
                    current++
                    onProgress?.invoke(entry.source.name, current, total)
                    if (!entry.dest.exists()) entry.dest.mkdirs()
                }
                for (entry in fileEntries) {
                    current++
                    onProgress?.invoke(entry.source.name, current, total)
                    entry.dest.parentFile?.mkdirs()
                    entry.source.copyTo(entry.dest, overwrite = true)
                }
                // A source that can't be removed at all (read-only storage, such as another
                // profile's) was still counted as moved, leaving it in place plus a copy in the
                // trash; it now fails and its copy is dropped. Once any original is gone the copy
                // is kept whatever else happens, so nothing is lost.
                val removed = fileEntries.count { it.source.delete() || !it.source.exists() }
                if (removed == 0 && fileEntries.isNotEmpty()) {
                    throw java.io.IOException("Couldn't remove ${source.absolutePath}")
                }
                source.deleteRecursively()
                true
            } catch (e: Exception) {
                android.util.Log.e("RecycleBinRepository", "Copy fallback failed: ${source.absolutePath} -> ${dest.absolutePath}", e)
                try { dest.deleteRecursively() } catch (_: Exception) {}
                false
            }
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
                // A row whose file couldn't be removed is kept: dropping it anyway left the file
                // in the hidden trash folder, using space and never shown again.
                val trashFile = File(entity.trashPath)
                if (deleteTrashFile(trashFile)) {
                    database.trashDao().deleteByIds(listOf(entity.id))
                    deleted++
                } else if (trashFile.isDirectory) {
                    // Partly deleted: show what is actually left.
                    database.trashDao().updateFileSize(entity.id, directorySize(trashFile))
                }
            }
            if (deleted > 0) mediaChangeSignal.notifyChanged()
            Result.success(deleted)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun emptyTrash(onProgress: ((currentName: String, currentIndex: Int, total: Int) -> Unit)?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val all = database.trashDao().getAll()
            val removedIds = mutableListOf<Long>()
            for ((index, item) in all.withIndex()) {
                onProgress?.invoke(item.fileName, index + 1, all.size)
                val trashFile = File(item.trashPath)
                if (deleteTrashFile(trashFile)) removedIds += item.id
                else if (trashFile.isDirectory) database.trashDao().updateFileSize(item.id, directorySize(trashFile))
            }
            // Same as deletePermanently: only rows whose file is really gone are dropped.
            if (removedIds.isNotEmpty()) database.trashDao().deleteByIds(removedIds)
            mediaChangeSignal.notifyChanged()
            val failed = all.size - removedIds.size
            if (failed > 0) Result.failure(java.io.IOException("$failed item(s) couldn't be deleted"))
            else Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Deletes a trashed file or folder; true once nothing is left at [file]. */
    private fun deleteTrashFile(file: File): Boolean {
        if (file.exists()) {
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
        return !file.exists()
    }

    override suspend fun getTrashTotalSize(): Long = withContext(Dispatchers.IO) {
        healZeroTrashFolders(database)
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
        httpPort: Int
    ): Result<Unit> {
        val intent = Intent(context, FtpServerService::class.java).apply {
            action = FtpServerService.ACTION_START
            putExtra(FtpServerService.EXTRA_PORT, port)
            putExtra(FtpServerService.EXTRA_HTTP_PORT, httpPort)
            putExtra(FtpServerService.EXTRA_PASSWORD, password)
            putExtra(FtpServerService.EXTRA_RANDOM_PASS, isRandomPassword)
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
        httpPort: Int
    ) {
        prefManager.saveNetworkConfig(port, httpPort, password, isRandomPassword)
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
            // Keep an existing account's position: this is also used to update one in place
            // (e.g. once its real email is resolved), which must not push it to the end.
            val count = database.cloudDao().getById(account.id)?.displayOrder
                ?: database.cloudDao().getAll().size
            val rawSession = account.sessionHandle ?: ""
            // Offload large JSON payload to disk to prevent SQLiteBlobTooBigException (SQLite CursorWindow limit)
            // A TeraBox session is the cookie string itself (API credentials, not a bulky node
            // tree), so keep it in the row — offloading it left only "session_active" behind.
            val lightAccount = if (account.provider != CloudProvider.TERABOX &&
                (rawSession.length > 500 || rawSession.contains("\"folders\":") || rawSession.contains("\"files\":"))) {
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

    override suspend fun openCloudThumbnailDataSource(accountId: String, nodeId: String, forPlayback: Boolean): Result<android.media.MediaDataSource> = withContext(Dispatchers.IO) {
        val account = database.cloudDao().getById(accountId)?.toDomain()
            ?: return@withContext Result.failure(Exception("Account not found"))
        cloudManager.openThumbnailDataSource(account, nodeId, forPlayback)
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

