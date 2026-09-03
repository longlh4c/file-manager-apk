package com.antigravity.filemanager.data.local.storage

import android.content.Context
import android.database.Cursor
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.antigravity.filemanager.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalFileScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun getStorageVolume(): StorageVolumeInfo {
        return try {
            val root = Environment.getExternalStorageDirectory()
            val stat = StatFs(root.path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong

            val totalBytes = totalBlocks * blockSize
            val freeBytes = availableBlocks * blockSize
            val usedBytes = (totalBytes - freeBytes).coerceAtLeast(0L)

            StorageVolumeInfo(totalBytes, usedBytes, freeBytes)
        } catch (e: Exception) {
            StorageVolumeInfo(256L * 1024 * 1024 * 1024, 241L * 1024 * 1024 * 1024, 15L * 1024 * 1024 * 1024)
        }
    }

    fun getFolderEffectiveLastModified(folder: File, maxDepth: Int = 2): Long {
        var maxTime = folder.lastModified()
        try {
            if (folder.name == "Android" || folder.name.startsWith(".")) return maxTime
            val children = folder.listFiles() ?: return maxTime
            for (child in children) {
                if (child.isDirectory) {
                    if (maxDepth > 0 && child.name != "Android" && !child.name.startsWith(".")) {
                        val subMax = getFolderEffectiveLastModified(child, maxDepth - 1)
                        if (subMax > maxTime) maxTime = subMax
                    }
                } else {
                    val childTime = child.lastModified()
                    if (childTime > maxTime) maxTime = childTime
                }
            }
        } catch (e: Exception) {}
        return maxTime
    }

    fun getFolderTotalSize(folder: File, maxDepth: Int = 2): Long {
        var total = 0L
        try {
            if (folder.name == "Android" || folder.name.startsWith(".")) return 0L
            val children = folder.listFiles() ?: return 0L
            for (child in children) {
                if (child.isDirectory) {
                    if (maxDepth > 0 && child.name != "Android" && !child.name.startsWith(".")) {
                        total += getFolderTotalSize(child, maxDepth - 1)
                    }
                } else {
                    total += child.length()
                }
            }
        } catch (e: Exception) {}
        return total
    }

    suspend fun listFilesInDir(
        dirPath: String,
        sortOption: FileSortOption = FileSortOption.BY_NAME_ASC,
        showHidden: Boolean = false
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val dir = File(dirPath)
        if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()

        val files = dir.listFiles() ?: return@withContext emptyList()
        val isDateSort = sortOption == FileSortOption.BY_DATE_DESC || sortOption == FileSortOption.BY_DATE_ASC
        val isSizeSort = sortOption == FileSortOption.BY_SIZE_DESC || sortOption == FileSortOption.BY_SIZE_ASC

        // Sorting by date or size needs a recursive scan of every subfolder (to find its most
        // recently modified file, or its total size) — expensive once there are many subfolders.
        // Running one entry at a time made that cost add up sequentially; doing them concurrently
        // lets the IO dispatcher's thread pool overlap those scans instead.
        val list = files.filter { showHidden || !it.name.startsWith(".") }
            .map { file ->
                async {
                    val isDir = file.isDirectory
                    val ext = if (!isDir) file.extension.lowercase(Locale.getDefault()) else ""
                    val isVideo = ext in videoExtensions
                    val isImage = ext in imageExtensions
                    val isAudio = ext in audioExtensions
                    val mime = if (!isDir) getMimeType(file) else "resource/folder"
                    val count = if (isDir) file.list()?.size ?: 0 else 0
                    val size = if (isDir) {
                        if (isSizeSort) getFolderTotalSize(file, maxDepth = 2) else 0L
                    } else file.length()
                    val badge = detectBadgeFromPath(file.absolutePath, isVideo = isVideo)
                    val folderBadge = if (isDir) detectFolderBadge(file.name) else FolderBadgeType.STANDARD
                    val effectiveTime = if (isDir) {
                        if (isDateSort) getFolderEffectiveLastModified(file, maxDepth = 2) else file.lastModified()
                    } else file.lastModified()

                    FileItem(
                        id = file.absolutePath,
                        name = file.name,
                        path = file.absolutePath,
                        size = size,
                        lastModified = effectiveTime,
                        isDirectory = isDir,
                        mimeType = mime,
                        extension = ext,
                        itemCount = count,
                        thumbnailUri = if (!isDir && (isVideo || isImage || isAudio || mime.startsWith("image/") || mime.startsWith("video/"))) file.absolutePath else null,
                        appSourceBadge = badge,
                        folderBadgeType = folderBadge,
                        isHidden = file.name.startsWith(".")
                    )
                }
            }.awaitAll()

        sortFileList(list, sortOption)
    }

    fun detectFolderBadge(folderName: String): FolderBadgeType {
        val lower = folderName.lowercase(Locale.getDefault())
        return when {
            lower == "dcim" || lower.contains("camera") || lower.contains("photos") -> FolderBadgeType.CAMERA
            lower == "documents" || lower.contains("document") || lower == "docs" -> FolderBadgeType.DOCUMENTS
            lower == "download" || lower == "downloads" -> FolderBadgeType.DOWNLOAD
            lower == "movies" || lower == "movie" || lower == "videos" || lower == "video" -> FolderBadgeType.MOVIES
            lower == "music" || lower.contains("audio") || lower == "songs" -> FolderBadgeType.MUSIC
            else -> FolderBadgeType.STANDARD
        }
    }

    suspend fun getMediaFolders(categoryType: CategoryType, sortOption: FileSortOption = FileSortOption.BY_NAME_ASC): List<MediaFolder> = withContext(Dispatchers.IO) {
        val list = when (categoryType) {
            CategoryType.IMAGES -> queryImageFolders()
            CategoryType.AUDIO -> queryAudioFolders()
            CategoryType.VIDEOS -> queryVideoFolders()
            CategoryType.DOCUMENTS -> queryDocumentFolders()
            CategoryType.DOWNLOADS -> queryDownloadsFolder()
            else -> emptyList()
        }
        sortMediaFolders(list, sortOption)
    }

    // Files past this size are skipped for duplicate hashing (still counted everywhere else) —
    // reading and hashing a huge video/archive on every scan would make an otherwise cheap
    // metadata walk noticeably slow for a rare payoff.
    private val maxDuplicateHashBytes = 200L * 1024 * 1024

    suspend fun scanStorageAnalysis(): StorageAnalysisData = withContext(Dispatchers.IO) {
        val volume = getStorageVolume()
        val root = Environment.getExternalStorageDirectory()

        var imgBytes = 0L
        var audioBytes = 0L
        var videoBytes = 0L
        var docBytes = 0L
        var archiveBytes = 0L
        var otherBytes = 0L

        val largeFiles = mutableListOf<LargeFileItem>()
        val docExts = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub")
        val archiveExts = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "iso", "apk")
        val imgExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "svg")
        val audioExts = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "wma", "opus")
        val videoExts = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp")

        // Cheap first pass for duplicate detection: bucket every non-empty file by its exact
        // byte size. Hashing (the expensive part) only ever runs on files that already share a
        // size with at least one other file, so a device with zero true duplicates pays almost
        // nothing beyond the walk it was already doing for the breakdown/large-files above.
        val sizeBuckets = HashMap<Long, MutableList<File>>()

        fun scanRecursive(dir: File) {
            val list = dir.listFiles() ?: return
            for (f in list) {
                if (f.isDirectory) {
                    if (!f.name.startsWith(".")) scanRecursive(f)
                } else {
                    val length = f.length()
                    val ext = f.extension.lowercase(Locale.getDefault())
                    when {
                        imgExts.contains(ext) -> imgBytes += length
                        audioExts.contains(ext) -> audioBytes += length
                        videoExts.contains(ext) -> videoBytes += length
                        docExts.contains(ext) -> docBytes += length
                        archiveExts.contains(ext) -> archiveBytes += length
                        else -> otherBytes += length
                    }

                    // Large file threshold: > 10 MB (10 * 1024 * 1024)
                    if (length >= 10L * 1024 * 1024) {
                        val relDir = f.parentFile?.absolutePath?.removePrefix(root.absolutePath)?.ifEmpty { "/" } ?: "/"
                        largeFiles.add(
                            LargeFileItem(
                                id = f.absolutePath,
                                name = f.name,
                                path = f.absolutePath,
                                relativeDir = relDir,
                                sizeBytes = length,
                                extension = ext,
                                lastModified = f.lastModified()
                            )
                        )
                    }

                    if (length in 1..maxDuplicateHashBytes) {
                        sizeBuckets.getOrPut(length) { mutableListOf() }.add(f)
                    }
                }
            }
        }

        try {
            scanRecursive(root)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        largeFiles.sortByDescending { it.sizeBytes }
        val largeTotal = largeFiles.sumOf { it.sizeBytes }

        val duplicateGroups = findDuplicateGroups(sizeBuckets, root)

        StorageAnalysisData(
            volumeInfo = volume,
            breakdown = StorageCategoryBreakdown(
                imagesBytes = imgBytes,
                audioBytes = audioBytes,
                videosBytes = videoBytes,
                documentsBytes = docBytes,
                archivesBytes = archiveBytes,
                othersBytes = otherBytes
            ),
            largeFiles = largeFiles,
            largeFilesTotalBytes = largeTotal,
            recycleBinBytes = 0L,
            recycleBinSampleItem = null,
            duplicateFileGroups = duplicateGroups,
            duplicateFilesBytes = duplicateGroups.sumOf { it.wastedBytes }
        )
    }

    /** Groups files that share a size bucket by SHA-256 content hash, so only genuinely
     * byte-identical files end up together (same size alone isn't proof of duplicate content). */
    private fun findDuplicateGroups(sizeBuckets: Map<Long, List<File>>, root: File): List<DuplicateGroup> {
        val groups = mutableListOf<DuplicateGroup>()
        for ((size, files) in sizeBuckets) {
            if (files.size < 2) continue
            val byHash = HashMap<String, MutableList<File>>()
            for (f in files) {
                val hash = hashFile(f) ?: continue
                byHash.getOrPut(hash) { mutableListOf() }.add(f)
            }
            for ((hash, matches) in byHash) {
                if (matches.size < 2) continue
                val original = matches.minByOrNull { it.lastModified() }
                val entries = matches.map { f ->
                    val relDir = f.parentFile?.absolutePath?.removePrefix(root.absolutePath)?.ifEmpty { "/" } ?: "/"
                    DuplicateFileEntry(
                        id = f.absolutePath,
                        name = f.name,
                        path = f.absolutePath,
                        relativeDir = relDir,
                        sizeBytes = size,
                        lastModified = f.lastModified(),
                        isOriginal = f == original
                    )
                }
                groups.add(DuplicateGroup(key = "${hash}_$size", items = entries, wastedBytes = size * (entries.size - 1)))
            }
        }
        return groups.sortedByDescending { it.wastedBytes }
    }

    private fun hashFile(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } > 0) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun queryImageFolders(): List<MediaFolder> {
        val folderMap = mutableMapOf<String, MutableList<Triple<String, Long, Long>>>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED
        )

        try {
            val cursor: Cursor? = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            )

            cursor?.use {
                val dataCol = it.getColumnIndex(MediaStore.Images.Media.DATA)
                val sizeCol = it.getColumnIndex(MediaStore.Images.Media.SIZE)
                val dateCol = it.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)

                while (it.moveToNext()) {
                    val path = if (dataCol >= 0) it.getString(dataCol) else null ?: continue
                    val size = if (sizeCol >= 0) it.getLong(sizeCol) else 0L
                    val dateSec = if (dateCol >= 0) it.getLong(dateCol) else 0L
                    val file = File(path)
                    val ext = file.extension.lowercase(Locale.getDefault())
                    // isFile() already implies the path exists (returns false otherwise), so a
                    // separate exists() call here would just be a second redundant stat() syscall.
                    if (ext in imageExtensions && file.isFile && file.length() > 0) {
                        val parentFile = file.parentFile ?: continue
                        val folderPath = parentFile.absolutePath
                        val dateMs = if (dateSec > 0) dateSec * 1000L else file.lastModified()

                        val list = folderMap.getOrPut(folderPath) { mutableListOf() }
                        list.add(Triple(path, if (size > 0) size else file.length(), dateMs))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return folderMap.mapNotNull { (path, rawItems) ->
            val folderFile = File(path)
            if (!folderFile.exists() || !folderFile.isDirectory) return@mapNotNull null

            // rawItems already passed this exact check (isFile + length + extension) while the
            // MediaStore cursor was being read above — re-stat()'ing every file a second time here
            // was pure duplicated I/O for the same answer.
            if (rawItems.isEmpty()) return@mapNotNull null
            val validItems = rawItems

            val name = if (path == Environment.getExternalStorageDirectory().absolutePath) {
                "Internal storage"
            } else {
                folderFile.name.ifEmpty { "Images" }
            }
            val badge = detectBadgeFromPath(path)
            val totalSize = validItems.sumOf { it.second }
            val latestThumb = validItems.firstOrNull()?.first
            val effectiveTime = validItems.maxOfOrNull { it.third } ?: folderFile.lastModified()

            MediaFolder(
                id = path,
                name = name,
                path = path,
                itemCount = validItems.size,
                latestThumbnailUri = latestThumb,
                appSourceBadge = badge,
                totalSizeBytes = totalSize,
                lastModified = effectiveTime
            )
        }
    }

    private fun queryAudioFolders(): List<MediaFolder> {
        val folderMap = mutableMapOf<String, MutableList<Triple<String, Long, Long>>>()
        // Tracks paths already added (from the MediaStore query below) so the recursive
        // fallback scan can dedup in O(1) instead of doing a linear list.none {} per file.
        val seenPaths = HashSet<String>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED
        )

        try {
            val cursor: Cursor? = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
            )

            cursor?.use {
                val dataCol = it.getColumnIndex(MediaStore.Audio.Media.DATA)
                val sizeCol = it.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val dateCol = it.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)

                while (it.moveToNext()) {
                    val path = if (dataCol >= 0) it.getString(dataCol) else null ?: continue
                    val size = if (sizeCol >= 0) it.getLong(sizeCol) else 0L
                    val dateSec = if (dateCol >= 0) it.getLong(dateCol) else 0L
                    val file = File(path)
                    val ext = file.extension.lowercase(Locale.getDefault())
                    if (ext in audioExtensions && file.isFile && file.length() > 0) {
                        val parentFile = file.parentFile ?: continue
                        val folderPath = parentFile.absolutePath
                        val dateMs = if (dateSec > 0) dateSec * 1000L else file.lastModified()

                        val list = folderMap.getOrPut(folderPath) { mutableListOf() }
                        list.add(Triple(path, if (size > 0) size else file.length(), dateMs))
                        seenPaths.add(path)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Direct scan of standard audio directories
        val standardDirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RINGTONES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_NOTIFICATIONS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_ALARMS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File(Environment.getExternalStorageDirectory(), "Music"),
            File(Environment.getExternalStorageDirectory(), "Download"),
            File(Environment.getExternalStorageDirectory(), "Audiobooks"),
            File(Environment.getExternalStorageDirectory(), "Recordings"),
            File(Environment.getExternalStorageDirectory(), "Zalo"),
            File(Environment.getExternalStorageDirectory(), "Telegram")
        )

        fun scanAudioFast(dir: File, depth: Int = 2) {
            if (!dir.exists() || !dir.isDirectory || depth < 0) return
            val files = dir.listFiles() ?: return
            for (f in files) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (f.name != "Android") {
                        scanAudioFast(f, depth - 1)
                    }
                } else {
                    val ext = f.extension.lowercase(Locale.getDefault())
                    if (ext in audioExtensions && f.length() > 0) {
                        val parent = f.parentFile?.absolutePath ?: continue
                        if (seenPaths.add(f.absolutePath)) {
                            folderMap.getOrPut(parent) { mutableListOf() }.add(Triple(f.absolutePath, f.length(), f.lastModified()))
                        }
                    }
                }
            }
        }

        for (d in standardDirs) {
            scanAudioFast(d, depth = 2)
        }

        return folderMap.mapNotNull { (path, rawItems) ->
            val folderFile = File(path)
            if (!folderFile.exists() || !folderFile.isDirectory) return@mapNotNull null

            // Already verified (isFile + length + extension) once above — see the image-folder
            // query's identical comment for why a second pass here was wasted stat() I/O.
            if (rawItems.isEmpty()) return@mapNotNull null
            val validItems = rawItems

            val name = if (path == Environment.getExternalStorageDirectory().absolutePath) {
                "Internal storage"
            } else {
                folderFile.name.ifEmpty { "Audio" }
            }
            val badge = AppSourceBadge.GENERIC_AUDIO
            val totalSize = validItems.sumOf { it.second }
            val effectiveTime = validItems.maxOfOrNull { it.third } ?: folderFile.lastModified()

            MediaFolder(
                id = path,
                name = name,
                path = path,
                itemCount = validItems.size,
                latestThumbnailUri = validItems.firstOrNull()?.first,
                appSourceBadge = badge,
                totalSizeBytes = totalSize,
                lastModified = effectiveTime
            )
        }
    }

    private fun queryVideoFolders(): List<MediaFolder> {
        val folderMap = mutableMapOf<String, MutableList<Triple<String, Long, Long>>>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED
        )

        try {
            val cursor: Cursor? = context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
            )

            cursor?.use {
                val dataCol = it.getColumnIndex(MediaStore.Video.Media.DATA)
                val sizeCol = it.getColumnIndex(MediaStore.Video.Media.SIZE)
                val dateCol = it.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)

                while (it.moveToNext()) {
                    val path = if (dataCol >= 0) it.getString(dataCol) else null ?: continue
                    val size = if (sizeCol >= 0) it.getLong(sizeCol) else 0L
                    val dateSec = if (dateCol >= 0) it.getLong(dateCol) else 0L
                    val file = File(path)
                    val ext = file.extension.lowercase(Locale.getDefault())
                    if (ext in videoExtensions && file.isFile && file.length() > 0) {
                        val parentFile = file.parentFile ?: continue
                        val folderPath = parentFile.absolutePath
                        val dateMs = if (dateSec > 0) dateSec * 1000L else file.lastModified()

                        val list = folderMap.getOrPut(folderPath) { mutableListOf() }
                        list.add(Triple(path, if (size > 0) size else file.length(), dateMs))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return folderMap.mapNotNull { (path, rawItems) ->
            val folderFile = File(path)
            if (!folderFile.exists() || !folderFile.isDirectory) return@mapNotNull null

            // Already verified (isFile + length + extension) once above — see the image-folder
            // query's identical comment for why a second pass here was wasted stat() I/O.
            if (rawItems.isEmpty()) return@mapNotNull null
            val validItems = rawItems

            val name = if (path == Environment.getExternalStorageDirectory().absolutePath) {
                "Internal storage"
            } else {
                folderFile.name.ifEmpty { "Videos" }
            }
            val badge = detectBadgeFromPath(path, isVideo = true)
            val totalSize = validItems.sumOf { it.second }
            val effectiveTime = validItems.maxOfOrNull { it.third } ?: folderFile.lastModified()

            MediaFolder(
                id = path,
                name = name,
                path = path,
                itemCount = validItems.size,
                latestThumbnailUri = validItems.firstOrNull()?.first,
                appSourceBadge = badge,
                totalSizeBytes = totalSize,
                lastModified = effectiveTime
            )
        }
    }

    val documentExtensions = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub",
        "rtf", "csv", "mobi", "azw", "azw3", "prc", "odt", "ods", "odp", "wps"
    )

    suspend fun getAllDocumentFiles(sortOption: FileSortOption = FileSortOption.BY_DATE_DESC): List<FileItem> = withContext(Dispatchers.IO) {
        val fileMap = mutableMapOf<String, FileItem>()

        // 1. Instant MediaStore.Files index query
        try {
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.MIME_TYPE
            )

            val mimeTypes = listOf(
                "application/pdf",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-powerpoint",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "text/plain",
                "text/csv",
                "text/rtf",
                "application/rtf",
                "application/epub+zip"
            )
            val selection = mimeTypes.joinToString(" OR ") { "${MediaStore.Files.FileColumns.MIME_TYPE} = '$it'" } +
                    " OR ${MediaStore.Files.FileColumns.DATA} LIKE '%.pdf'" +
                    " OR ${MediaStore.Files.FileColumns.DATA} LIKE '%.doc%'" +
                    " OR ${MediaStore.Files.FileColumns.DATA} LIKE '%.xls%'" +
                    " OR ${MediaStore.Files.FileColumns.DATA} LIKE '%.ppt%'" +
                    " OR ${MediaStore.Files.FileColumns.DATA} LIKE '%.txt'" +
                    " OR ${MediaStore.Files.FileColumns.DATA} LIKE '%.epub'"

            val cursor: Cursor? = context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                null,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )

            cursor?.use {
                val dataCol = it.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val nameCol = it.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeCol = it.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val dateCol = it.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val mimeCol = it.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)

                while (it.moveToNext()) {
                    val path = if (dataCol >= 0) it.getString(dataCol) else null ?: continue
                    val file = File(path)
                    if (!file.isFile) continue
                    val ext = file.extension.lowercase(Locale.getDefault())
                    if (ext in documentExtensions) {
                        val name = (if (nameCol >= 0) it.getString(nameCol) else null)?.ifBlank { file.name } ?: file.name
                        val size = if (sizeCol >= 0) it.getLong(sizeCol) else file.length()
                        val modified = if (dateCol >= 0) it.getLong(dateCol) * 1000L else file.lastModified()
                        val mime = if (mimeCol >= 0) it.getString(mimeCol) else getMimeType(file)

                        fileMap[path] = FileItem(
                            id = path,
                            name = name,
                            path = path,
                            size = size,
                            lastModified = modified,
                            isDirectory = false,
                            mimeType = mime,
                            extension = ext,
                            itemCount = 0,
                            thumbnailUri = null,
                            appSourceBadge = detectBadgeFromPath(path),
                            folderBadgeType = FolderBadgeType.STANDARD,
                            isHidden = file.name.startsWith(".")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Direct scan of standard public Document & Download directories to ensure 100% complete coverage
        val standardDirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File(Environment.getExternalStorageDirectory(), "Documents"),
            File(Environment.getExternalStorageDirectory(), "Download"),
            File(Environment.getExternalStorageDirectory(), "Books"),
            File(Environment.getExternalStorageDirectory(), "Zalo"),
            File(Environment.getExternalStorageDirectory(), "Telegram")
        )

        fun scanRecursive(dir: File, depth: Int = 4) {
            if (!dir.exists() || !dir.isDirectory || depth < 0) return
            val files = dir.listFiles() ?: return
            for (f in files) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (f.name != "Android") scanRecursive(f, depth - 1)
                } else {
                    val ext = f.extension.lowercase(Locale.getDefault())
                    if (ext in documentExtensions && !fileMap.containsKey(f.absolutePath)) {
                        fileMap[f.absolutePath] = FileItem(
                            id = f.absolutePath,
                            name = f.name,
                            path = f.absolutePath,
                            size = f.length(),
                            lastModified = f.lastModified(),
                            isDirectory = false,
                            mimeType = getMimeType(f),
                            extension = ext,
                            itemCount = 0,
                            thumbnailUri = null,
                            appSourceBadge = detectBadgeFromPath(f.absolutePath),
                            folderBadgeType = FolderBadgeType.STANDARD,
                            isHidden = f.name.startsWith(".")
                        )
                    }
                }
            }
        }

        for (d in standardDirs) {
            scanRecursive(d)
        }

        sortFileList(fileMap.values.toList(), sortOption)
    }

    private fun queryDocumentFolders(): List<MediaFolder> {
        val folderMap = mutableMapOf<String, MutableList<Triple<String, Long, Long>>>()
        // Tracks paths already added (from the MediaStore query below) so the recursive
        // fallback scan can dedup in O(1) instead of doing a linear list.none {} per file.
        val seenPaths = HashSet<String>()
        val docExts = documentExtensions

        // 1. Instant MediaStore.Files index query
        try {
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.DATE_MODIFIED
            )

            val mimeTypes = listOf(
                "application/pdf",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-powerpoint",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "text/plain",
                "text/csv",
                "text/rtf",
                "application/rtf",
                "application/epub+zip"
            )
            val selection = mimeTypes.joinToString(" OR ") { "${MediaStore.Files.FileColumns.MIME_TYPE} = '$it'" } +
                    " OR ${MediaStore.Files.FileColumns.DATA} LIKE '%.pdf'" +
                    " OR ${MediaStore.Files.FileColumns.DATA} LIKE '%.doc%'" +
                    " OR ${MediaStore.Files.FileColumns.DATA} LIKE '%.xls%'" +
                    " OR ${MediaStore.Files.FileColumns.DATA} LIKE '%.ppt%'" +
                    " OR ${MediaStore.Files.FileColumns.DATA} LIKE '%.txt'" +
                    " OR ${MediaStore.Files.FileColumns.DATA} LIKE '%.epub'"

            val cursor: Cursor? = context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                null,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )

            cursor?.use {
                val dataCol = it.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val sizeCol = it.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val dateCol = it.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)

                while (it.moveToNext()) {
                    val path = if (dataCol >= 0) it.getString(dataCol) else null ?: continue
                    val size = if (sizeCol >= 0) it.getLong(sizeCol) else 0L
                    val dateSec = if (dateCol >= 0) it.getLong(dateCol) else 0L
                    val file = File(path)
                    val ext = file.extension.lowercase(Locale.getDefault())
                    if (ext in docExts && file.isFile && file.length() > 0) {
                        val parentFile = file.parentFile ?: continue
                        val folderPath = parentFile.absolutePath
                        val dateMs = if (dateSec > 0) dateSec * 1000L else file.lastModified()
                        val list = folderMap.getOrPut(folderPath) { mutableListOf() }
                        list.add(Triple(path, if (size > 0) size else file.length(), dateMs))
                        seenPaths.add(path)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Direct scan of standard public Document & Download directories to ensure 100% complete coverage
        val standardDirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File(Environment.getExternalStorageDirectory(), "Documents"),
            File(Environment.getExternalStorageDirectory(), "Download"),
            File(Environment.getExternalStorageDirectory(), "Books"),
            File(Environment.getExternalStorageDirectory(), "Zalo"),
            File(Environment.getExternalStorageDirectory(), "Telegram")
        )

        fun scanFolderFast(dir: File, depth: Int = 2) {
            if (!dir.exists() || !dir.isDirectory || depth < 0) return
            val files = dir.listFiles() ?: return
            for (f in files) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (f.name != "Android") {
                        scanFolderFast(f, depth - 1)
                    }
                } else {
                    val ext = f.extension.lowercase(Locale.getDefault())
                    if (ext in docExts && f.length() > 0) {
                        val parent = f.parentFile?.absolutePath ?: continue
                        if (seenPaths.add(f.absolutePath)) {
                            folderMap.getOrPut(parent) { mutableListOf() }.add(Triple(f.absolutePath, f.length(), f.lastModified()))
                        }
                    }
                }
            }
        }

        for (d in standardDirs) {
            scanFolderFast(d, depth = 2)
        }

        return folderMap.mapNotNull { (path, rawItems) ->
            val folderFile = File(path)
            if (!folderFile.exists() || !folderFile.isDirectory) return@mapNotNull null

            // Already verified (isFile + length + extension) once above — see the image-folder
            // query's identical comment for why a second pass here was wasted stat() I/O.
            if (rawItems.isEmpty()) return@mapNotNull null
            val validItems = rawItems

            val name = if (path == Environment.getExternalStorageDirectory().absolutePath) {
                "Internal storage"
            } else {
                folderFile.name.ifEmpty { "Documents" }
            }
            val badge = detectBadgeFromPath(path)
            val totalSize = validItems.sumOf { it.second }
            val effectiveTime = validItems.maxOfOrNull { it.third } ?: folderFile.lastModified()

            MediaFolder(
                id = path,
                name = name,
                path = path,
                itemCount = validItems.size,
                latestThumbnailUri = validItems.firstOrNull()?.first,
                appSourceBadge = badge,
                totalSizeBytes = totalSize,
                lastModified = effectiveTime
            )
        }
    }

    private fun queryDownloadsFolder(): List<MediaFolder> {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val files = downloadDir.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()
        val totalSize = files.sumOf { if (it.isDirectory) 0L else it.length() }
        val effectiveTime = getFolderEffectiveLastModified(downloadDir)

        return listOf(
            MediaFolder(
                id = downloadDir.absolutePath,
                name = "Downloads",
                path = downloadDir.absolutePath,
                itemCount = files.size,
                latestThumbnailUri = files.firstOrNull { it.isFile }?.absolutePath,
                appSourceBadge = AppSourceBadge.DOWNLOAD,
                totalSizeBytes = totalSize,
                lastModified = effectiveTime
            )
        )
    }

    fun sortMediaFolders(list: List<MediaFolder>, sortOption: FileSortOption): List<MediaFolder> {
        return when (sortOption) {
            FileSortOption.BY_NAME_ASC -> list.sortedBy { it.name.lowercase(Locale.getDefault()) }
            FileSortOption.BY_NAME_DESC -> list.sortedByDescending { it.name.lowercase(Locale.getDefault()) }
            FileSortOption.BY_DATE_DESC -> list.sortedByDescending { it.lastModified }
            FileSortOption.BY_DATE_ASC -> list.sortedBy { it.lastModified }
            FileSortOption.BY_SIZE_DESC -> list.sortedByDescending { it.totalSizeBytes }
            FileSortOption.BY_SIZE_ASC -> list.sortedBy { it.totalSizeBytes }
            FileSortOption.BY_TYPE -> list.sortedBy { it.name.lowercase(Locale.getDefault()) }
        }
    }

    fun detectBadgeFromPath(path: String, isVideo: Boolean = false): AppSourceBadge {
        val lower = path.lowercase(Locale.getDefault())
        return when {
            lower.contains("messenger") || lower.contains("facebook") -> AppSourceBadge.MESSENGER
            lower.contains("zalo") -> AppSourceBadge.ZALO
            lower.contains("download") -> AppSourceBadge.DOWNLOAD
            lower.contains("screenshot") -> AppSourceBadge.SCREENSHOT
            lower.contains("instagram") -> AppSourceBadge.INSTAGRAM
            lower.contains("reddit") -> AppSourceBadge.REDDIT
            lower.contains("9gag") -> AppSourceBadge.NINE_GAG
            lower.contains("office lens") || lower.contains("lens") -> AppSourceBadge.OFFICE_LENS
            lower.contains("dcim") || lower.contains("camera") -> AppSourceBadge.CAMERA
            isVideo -> AppSourceBadge.GENERIC_VIDEO
            else -> AppSourceBadge.GENERIC_IMAGE
        }
    }

    private val audioExtensions = setOf("mp3", "m4a", "wav", "flac", "aac", "ogg", "wma", "opus", "amr", "mid", "midi")
    private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "ts", "m4v", "mpg", "mpeg", "vob", "ogv", "f4v")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "svg", "raw", "dng", "cr2", "nef")

    private fun getMimeType(file: File): String {
        val ext = file.extension.lowercase(Locale.getDefault())
        if (ext in videoExtensions) return "video/$ext"
        if (ext in imageExtensions) return "image/$ext"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    private fun sortFileList(list: List<FileItem>, sortOption: FileSortOption): List<FileItem> {
        val (dirs, files) = list.partition { it.isDirectory }
        val sortedDirs = when (sortOption) {
            FileSortOption.BY_NAME_ASC -> dirs.sortedBy { it.name.lowercase(Locale.getDefault()) }
            FileSortOption.BY_NAME_DESC -> dirs.sortedByDescending { it.name.lowercase(Locale.getDefault()) }
            FileSortOption.BY_DATE_DESC -> dirs.sortedByDescending { it.lastModified }
            FileSortOption.BY_DATE_ASC -> dirs.sortedBy { it.lastModified }
            FileSortOption.BY_SIZE_DESC -> dirs.sortedByDescending { it.size }
            FileSortOption.BY_SIZE_ASC -> dirs.sortedBy { it.size }
            FileSortOption.BY_TYPE -> dirs.sortedBy { it.name.lowercase(Locale.getDefault()) }
        }

        val sortedFiles = when (sortOption) {
            FileSortOption.BY_NAME_ASC -> files.sortedBy { it.name.lowercase(Locale.getDefault()) }
            FileSortOption.BY_NAME_DESC -> files.sortedByDescending { it.name.lowercase(Locale.getDefault()) }
            FileSortOption.BY_DATE_DESC -> files.sortedByDescending { it.lastModified }
            FileSortOption.BY_DATE_ASC -> files.sortedBy { it.lastModified }
            FileSortOption.BY_SIZE_DESC -> files.sortedByDescending { it.size }
            FileSortOption.BY_SIZE_ASC -> files.sortedBy { it.size }
            FileSortOption.BY_TYPE -> files.sortedBy { it.extension }
        }

        return sortedDirs + sortedFiles
    }
}
