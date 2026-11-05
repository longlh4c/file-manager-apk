package com.antigravity.filemanager.core

import android.util.Log
import com.antigravity.filemanager.data.local.storage.ArchiveInvalidPasswordException
import com.antigravity.filemanager.data.local.storage.ArchivePasswordRequiredException
import com.antigravity.filemanager.domain.model.ArchiveEntryInfo
import com.antigravity.filemanager.domain.model.DuplicateFileEntry
import com.antigravity.filemanager.domain.model.DuplicateGroup
import com.antigravity.filemanager.domain.model.LargeFileItem
import com.antigravity.filemanager.domain.model.StorageCategoryBreakdown
import org.json.JSONObject

data class NativeAnalysisData(
    val breakdown: StorageCategoryBreakdown,
    val largeFiles: List<LargeFileItem>,
    val largeFilesTotalBytes: Long,
    val duplicateGroups: List<DuplicateGroup>,
    val duplicateFilesBytes: Long
)

data class NativeDirEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val itemCount: Int,
    val subfolderCount: Int,
    val isHidden: Boolean
)

data class NativeSearchItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val extension: String
)

data class ChecksumResult(
    val md5: String? = null,
    val sha1: String? = null,
    val sha256: String? = null,
    val crc32: String? = null,
    val blake3: String? = null
)

data class ApkInfo(
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?
)

data class NativeMegaNode(
    val handle: String,
    val parentHandle: String,
    val type: Int,
    val size: Long,
    val timestamp: Long,
    val name: String,
    val keyStr: String,
    val fileAttrStr: String
)

data class NativeFolderBreakdownItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val itemCount: Int
)

data class NativeDocumentEntry(
    val path: String,
    val name: String,
    val size: Long,
    val lastModified: Long
)

data class NativeAudioMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val year: String? = null,
    val durationMs: Long? = null,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null
)

data class NativeImageMetadata(
    val width: Int = 0,
    val height: Int = 0,
    val format: String = "",
    val orientation: Int = 1,
    val hasEmbeddedThumbnail: Boolean = false,
    val dateTime: String? = null
)

data class NativeVideoMetadata(
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L,
    val qualityBadge: String = "",
    val hasPoster: Boolean = false
)

const val CHECKSUM_FLAG_MD5 = 1 shl 0
const val CHECKSUM_FLAG_SHA1 = 1 shl 1
const val CHECKSUM_FLAG_SHA256 = 1 shl 2
const val CHECKSUM_FLAG_CRC32 = 1 shl 3
const val CHECKSUM_FLAG_BLAKE3 = 1 shl 4
const val CHECKSUM_FLAG_ALL = CHECKSUM_FLAG_MD5 or CHECKSUM_FLAG_SHA1 or CHECKSUM_FLAG_SHA256 or CHECKSUM_FLAG_CRC32 or CHECKSUM_FLAG_BLAKE3

object NativeEngine {
    private const val TAG = "NativeEngine"
    private var isLoaded: Boolean = false

    init {
        try {
            System.loadLibrary("owlfm_core")
            isLoaded = true
            Log.i(TAG, "Native library libowlfm_core.so loaded successfully. Ping: ${ping()}")
        } catch (t: Throwable) {
            isLoaded = false
            Log.w(TAG, "Native library libowlfm_core.so could not be loaded: ${t.message}")
        }
    }

    val isAvailable: Boolean
        get() = isLoaded

    @JvmStatic
    external fun ping(): String

    @JvmStatic
    external fun resizeImage(
        sourcePath: String,
        targetPath: String,
        targetWidth: Int,
        targetHeight: Int,
        quality: Int,
        formatStr: String,
        orientation: Int
    ): Long

    @JvmStatic
    private external fun scanStorageAnalysis(rootPath: String, maxDuplicateHashBytes: Long): String

    fun analyzeStorage(rootPath: String, maxDuplicateHashBytes: Long): NativeAnalysisData? {
        if (!isLoaded) return null
        return try {
            val rawJson = scanStorageAnalysis(rootPath, maxDuplicateHashBytes)
            val json = JSONObject(rawJson)
            if (json.has("error")) {
                Log.e(TAG, "Rust analyzer error: ${json.optString("error")}")
                return null
            }

            val breakdown = StorageCategoryBreakdown(
                imagesBytes = json.optLong("imgBytes", 0L),
                audioBytes = json.optLong("audioBytes", 0L),
                videosBytes = json.optLong("videoBytes", 0L),
                documentsBytes = json.optLong("docBytes", 0L),
                archivesBytes = json.optLong("archiveBytes", 0L),
                othersBytes = json.optLong("otherBytes", 0L)
            )

            val largeFilesJson = json.optJSONArray("largeFiles")
            val largeFiles = mutableListOf<LargeFileItem>()
            var largeFilesTotal = 0L
            if (largeFilesJson != null) {
                for (i in 0 until largeFilesJson.length()) {
                    val item = largeFilesJson.getJSONObject(i)
                    val size = item.optLong("sizeBytes", 0L)
                    largeFilesTotal += size
                    largeFiles.add(
                        LargeFileItem(
                            id = item.optString("path"),
                            name = item.optString("name"),
                            path = item.optString("path"),
                            relativeDir = item.optString("relativeDir", "/"),
                            sizeBytes = size,
                            extension = item.optString("extension", ""),
                            lastModified = item.optLong("lastModified", 0L)
                        )
                    )
                }
            }

            val dupGroupsJson = json.optJSONArray("duplicateGroups")
            val duplicateGroups = mutableListOf<DuplicateGroup>()
            if (dupGroupsJson != null) {
                for (i in 0 until dupGroupsJson.length()) {
                    val group = dupGroupsJson.getJSONObject(i)
                    val key = group.optString("hash")
                    val wastedBytes = group.optLong("wastedBytes", 0L)
                    val entriesJson = group.optJSONArray("entries")
                    val entries = mutableListOf<DuplicateFileEntry>()

                    var earliestTime = Long.MAX_VALUE
                    var earliestIdx = -1

                    if (entriesJson != null) {
                        for (j in 0 until entriesJson.length()) {
                            val entryObj = entriesJson.getJSONObject(j)
                            val modified = entryObj.optLong("lastModified", 0L)
                            if (modified < earliestTime) {
                                earliestTime = modified
                                earliestIdx = j
                            }
                            entries.add(
                                DuplicateFileEntry(
                                    id = entryObj.optString("path"),
                                    name = entryObj.optString("name"),
                                    path = entryObj.optString("path"),
                                    relativeDir = entryObj.optString("relativeDir", "/"),
                                    sizeBytes = entryObj.optLong("sizeBytes", 0L),
                                    lastModified = modified,
                                    isOriginal = false
                                )
                            )
                        }
                    }

                    // Mark the earliest file as original
                    val mappedEntries = entries.mapIndexed { idx, item ->
                        if (idx == earliestIdx) item.copy(isOriginal = true) else item
                    }

                    duplicateGroups.add(
                        DuplicateGroup(
                            key = key,
                            items = mappedEntries,
                            wastedBytes = wastedBytes
                        )
                    )
                }
            }

            NativeAnalysisData(
                breakdown = breakdown,
                largeFiles = largeFiles,
                largeFilesTotalBytes = largeFilesTotal,
                duplicateGroups = duplicateGroups,
                duplicateFilesBytes = json.optLong("duplicateWastedBytes", 0L)
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Error parsing Rust analyzer response", e)
            null
        }
    }

    @JvmStatic
    external fun listArchiveEntries(archivePath: String, password: String?): String

    @JvmStatic
    external fun extractArchive(
        archivePath: String,
        targetDir: String,
        password: String?,
        selectedPathsJson: String?
    ): String

    @JvmStatic
    external fun compressZip(sourcePathsJson: String, targetZipPath: String): String

    fun listArchive(archivePath: String, password: String?): List<ArchiveEntryInfo>? {
        if (!isLoaded) return null
        return try {
            val resJson = JSONObject(listArchiveEntries(archivePath, password))
            if (!resJson.optBoolean("success", false)) {
                val code = resJson.optString("error_code")
                when (code) {
                    "PASSWORD_REQUIRED" -> throw ArchivePasswordRequiredException(archivePath)
                    "INVALID_PASSWORD" -> throw ArchiveInvalidPasswordException(archivePath)
                    else -> return null // Fallback to Kotlin
                }
            }
            val entriesArray = resJson.optJSONArray("entries") ?: return emptyList()
            val list = ArrayList<ArchiveEntryInfo>(entriesArray.length())
            for (i in 0 until entriesArray.length()) {
                val obj = entriesArray.getJSONObject(i)
                list.add(
                    ArchiveEntryInfo(
                        path = obj.optString("path"),
                        size = obj.optLong("size"),
                        isDirectory = obj.optBoolean("is_directory"),
                        lastModified = obj.optLong("last_modified")
                    )
                )
            }
            list
        } catch (e: ArchivePasswordRequiredException) {
            throw e
        } catch (e: ArchiveInvalidPasswordException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Native listArchive fallback: ${t.message}")
            null
        }
    }

    fun extract(
        archivePath: String,
        targetDir: String,
        password: String?,
        selectedPaths: List<String>? = null
    ): List<String>? {
        if (!isLoaded) return null
        return try {
            val selectedJson = if (selectedPaths != null) org.json.JSONArray(selectedPaths).toString() else null
            val resJson = JSONObject(extractArchive(archivePath, targetDir, password, selectedJson))
            if (!resJson.optBoolean("success", false)) {
                val code = resJson.optString("error_code")
                when (code) {
                    "PASSWORD_REQUIRED" -> throw ArchivePasswordRequiredException(archivePath)
                    "INVALID_PASSWORD" -> throw ArchiveInvalidPasswordException(archivePath)
                    "SECURITY_ERROR" -> throw SecurityException(resJson.optString("error_message"))
                    else -> return null // Fallback to Kotlin
                }
            }
            val pathsArray = resJson.optJSONArray("extracted_paths") ?: return emptyList()
            val list = ArrayList<String>(pathsArray.length())
            for (i in 0 until pathsArray.length()) {
                list.add(pathsArray.getString(i))
            }
            list
        } catch (e: ArchivePasswordRequiredException) {
            throw e
        } catch (e: ArchiveInvalidPasswordException) {
            throw e
        } catch (e: SecurityException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Native extract fallback: ${t.message}")
            null
        }
    }

    fun compress(sourcePaths: List<String>, targetZipPath: String): Long? {
        if (!isLoaded) return null
        return try {
            val sourcesJson = org.json.JSONArray(sourcePaths).toString()
            val resJson = JSONObject(compressZip(sourcesJson, targetZipPath))
            if (!resJson.optBoolean("success", false)) {
                return null // Fallback to Kotlin
            }
            resJson.optLong("total_bytes", 0L)
        } catch (t: Throwable) {
            Log.w(TAG, "Native compress fallback: ${t.message}")
            null
        }
    }

    @JvmStatic
    external fun listDirectory(
        dirPath: String,
        calcFolderSizes: Boolean,
        calcEffectiveDates: Boolean
    ): String

    @JvmStatic
    external fun searchFiles(
        rootPath: String,
        query: String,
        maxResults: Int,
        maxDepth: Int,
        allowedExtsJson: String?
    ): String

    fun scanDirectory(
        dirPath: String,
        calcFolderSizes: Boolean,
        calcEffectiveDates: Boolean
    ): List<NativeDirEntry>? {
        if (!isLoaded) return null
        return try {
            val resJson = JSONObject(listDirectory(dirPath, calcFolderSizes, calcEffectiveDates))
            if (!resJson.optBoolean("success", false)) return null
            val entriesArray = resJson.optJSONArray("entries") ?: return emptyList()
            val list = ArrayList<NativeDirEntry>(entriesArray.length())
            for (i in 0 until entriesArray.length()) {
                val item = entriesArray.getJSONObject(i)
                list.add(
                    NativeDirEntry(
                        name = item.optString("name"),
                        path = item.optString("path"),
                        isDirectory = item.optBoolean("is_directory"),
                        size = item.optLong("size"),
                        lastModified = item.optLong("last_modified"),
                        itemCount = item.optInt("item_count"),
                        subfolderCount = item.optInt("subfolder_count"),
                        isHidden = item.optBoolean("is_hidden")
                    )
                )
            }
            list
        } catch (t: Throwable) {
            Log.w(TAG, "Native scanDirectory fallback: ${t.message}")
            null
        }
    }

    fun searchStorage(
        rootPath: String,
        query: String,
        maxResults: Int = 500,
        maxDepth: Int = 12,
        allowedExts: Set<String>? = null
    ): List<NativeSearchItem>? {
        if (!isLoaded) return null
        return try {
            val extsJson = if (allowedExts != null) org.json.JSONArray(allowedExts).toString() else null
            val resJson = JSONObject(searchFiles(rootPath, query, maxResults, maxDepth, extsJson))
            if (!resJson.optBoolean("success", false)) return null
            val entriesArray = resJson.optJSONArray("entries") ?: return emptyList()
            val list = ArrayList<NativeSearchItem>(entriesArray.length())
            for (i in 0 until entriesArray.length()) {
                val item = entriesArray.getJSONObject(i)
                list.add(
                    NativeSearchItem(
                        name = item.optString("name"),
                        path = item.optString("path"),
                        isDirectory = item.optBoolean("is_directory"),
                        size = item.optLong("size"),
                        lastModified = item.optLong("last_modified"),
                        extension = item.optString("extension")
                    )
                )
            }
            list
        } catch (t: Throwable) {
            Log.w(TAG, "Native searchStorage fallback: ${t.message}")
            null
        }
    }

    @JvmStatic
    external fun copyFileFast(src: String, dst: String, overwrite: Boolean): Long

    @JvmStatic
    external fun moveFileFast(src: String, dst: String, overwrite: Boolean): Boolean

    @JvmStatic
    private external fun computeChecksums(path: String, flags: Int): String

    @JvmStatic
    external fun extractApkIcon(apkPath: String): ByteArray?

    @JvmStatic
    private external fun getApkInfo(apkPath: String): String

    @JvmStatic
    private external fun scanDocuments(rootsJson: String, extsJson: String, maxDepth: Int): String

    fun calculateChecksums(path: String, flags: Int = CHECKSUM_FLAG_ALL): ChecksumResult? {
        if (!isLoaded) return null
        return try {
            val json = JSONObject(computeChecksums(path, flags))
            if (json.has("error")) {
                Log.w(TAG, "Native calculateChecksums error: ${json.optString("error")}")
                return null
            }
            ChecksumResult(
                md5 = json.optString("md5").takeIf { it.isNotEmpty() },
                sha1 = json.optString("sha1").takeIf { it.isNotEmpty() },
                sha256 = json.optString("sha256").takeIf { it.isNotEmpty() },
                crc32 = json.optString("crc32").takeIf { it.isNotEmpty() },
                blake3 = json.optString("blake3").takeIf { it.isNotEmpty() }
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Native calculateChecksums failed: ${t.message}")
            null
        }
    }

    fun inspectApk(apkPath: String): ApkInfo? {
        if (!isLoaded) return null
        return try {
            val json = JSONObject(getApkInfo(apkPath))
            if (json.has("error")) return null
            val pkg = json.optString("package_name").takeIf { it.isNotEmpty() }
            val ver = json.optString("version_name").takeIf { it.isNotEmpty() }
            val code = if (json.has("version_code")) json.optLong("version_code") else null
            if (pkg == null && ver == null && code == null) null
            else ApkInfo(pkg, ver, code)
        } catch (t: Throwable) {
            Log.w(TAG, "Native inspectApk failed: ${t.message}")
            null
        }
    }

    fun scanDocumentsFast(roots: List<String>, extensions: Set<String>, maxDepth: Int = 5): List<NativeDocumentEntry>? {
        if (!isLoaded) return null
        return try {
            val rootsJson = org.json.JSONArray(roots).toString()
            val extsJson = org.json.JSONArray(extensions).toString()
            val raw = scanDocuments(rootsJson, extsJson, maxDepth)
            val arr = org.json.JSONArray(raw)
            val list = ArrayList<NativeDocumentEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    NativeDocumentEntry(
                        path = obj.optString("path"),
                        name = obj.optString("name"),
                        size = obj.optLong("size"),
                        lastModified = obj.optLong("last_modified")
                    )
                )
            }
            list
        } catch (t: Throwable) {
            Log.w(TAG, "Native scanDocumentsFast failed: ${t.message}")
            null
        }
    }
@JvmStatic
    external fun extractEmbeddedThumbnail(imagePath: String): ByteArray?

    @JvmStatic
    external fun extractVideoPoster(videoPath: String): ByteArray?

    @JvmStatic
    external fun extractAudioCover(audioPath: String): ByteArray?

    @JvmStatic
    private external fun getAudioMetadata(audioPath: String): String

    @JvmStatic
    private external fun getImageMetadata(imagePath: String): String

    @JvmStatic
    private external fun getVideoMetadata(videoPath: String): String

    fun getEmbeddedThumbnail(imagePath: String): ByteArray? {
        if (!isLoaded) return null
        return try {
            extractEmbeddedThumbnail(imagePath)
        } catch (t: Throwable) {
            Log.w(TAG, "extractEmbeddedThumbnail failed: ${t.message}")
            null
        }
    }

    fun getVideoPoster(videoPath: String): ByteArray? {
        if (!isLoaded) return null
        return try {
            extractVideoPoster(videoPath)
        } catch (t: Throwable) {
            Log.w(TAG, "extractVideoPoster failed: ${t.message}")
            null
        }
    }

    fun getAudioCover(audioPath: String): ByteArray? {
        if (!isLoaded) return null
        return try {
            extractAudioCover(audioPath)
        } catch (t: Throwable) {
            Log.w(TAG, "extractAudioCover failed: ${t.message}")
            null
        }
    }

    fun inspectAudio(audioPath: String): NativeAudioMetadata? {
        if (!isLoaded) return null
        return try {
            val raw = getAudioMetadata(audioPath)
            val json = JSONObject(raw)
            NativeAudioMetadata(
                title = json.optString("title").takeIf { it.isNotEmpty() },
                artist = json.optString("artist").takeIf { it.isNotEmpty() },
                album = json.optString("album").takeIf { it.isNotEmpty() },
                year = json.optString("year").takeIf { it.isNotEmpty() },
                durationMs = if (json.has("duration_ms") && !json.isNull("duration_ms")) json.optLong("duration_ms") else null,
                bitrateKbps = if (json.has("bitrate_kbps") && !json.isNull("bitrate_kbps")) json.optInt("bitrate_kbps") else null,
                sampleRateHz = if (json.has("sample_rate_hz") && !json.isNull("sample_rate_hz")) json.optInt("sample_rate_hz") else null
            )
        } catch (t: Throwable) {
            Log.w(TAG, "inspectAudio failed: ${t.message}")
            null
        }
    }

    fun inspectImage(imagePath: String): NativeImageMetadata? {
        if (!isLoaded) return null
        return try {
            val raw = getImageMetadata(imagePath)
            val json = JSONObject(raw)
            NativeImageMetadata(
                width = json.optInt("width", 0),
                height = json.optInt("height", 0),
                format = json.optString("format", ""),
                orientation = json.optInt("orientation", 1),
                hasEmbeddedThumbnail = json.optBoolean("has_embedded_thumbnail", false),
                dateTime = json.optString("date_time").takeIf { it.isNotEmpty() }
            )
        } catch (t: Throwable) {
            Log.w(TAG, "inspectImage failed: ${t.message}")
            null
        }
    }

    fun inspectVideo(videoPath: String): NativeVideoMetadata? {
        if (!isLoaded) return null
        return try {
            val raw = getVideoMetadata(videoPath)
            val json = JSONObject(raw)
            NativeVideoMetadata(
                width = json.optInt("width", 0),
                height = json.optInt("height", 0),
                durationMs = json.optLong("duration_ms", 0L),
                qualityBadge = json.optString("quality_badge", ""),
                hasPoster = json.optBoolean("has_poster", false)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "inspectVideo failed: ${t.message}")
            null
        }
    }
@JvmStatic
    private external fun scanFolderBreakdown(dirPath: String): String

    fun scanFolderBreakdownFast(dirPath: String): List<NativeFolderBreakdownItem>? {
        if (!isLoaded) return null
        return try {
            val raw = scanFolderBreakdown(dirPath)
            val arr = org.json.JSONArray(raw)
            val list = ArrayList<NativeFolderBreakdownItem>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    NativeFolderBreakdownItem(
                        name = obj.optString("name"),
                        path = obj.optString("path"),
                        isDirectory = obj.optBoolean("is_directory"),
                        size = obj.optLong("size"),
                        itemCount = obj.optInt("item_count")
                    )
                )
            }
            list
        } catch (t: Throwable) {
            Log.w(TAG, "Native scanFolderBreakdownFast failed: ${t.message}")
            null
        }
    }

    @JvmStatic
    private external fun calculateBlockMd5s(path: String, blockSize: Int): String

    @JvmStatic
    private external fun calculateDropboxContentHash(path: String): String

    @JvmStatic
    private external fun computeMegaChunkedMac(path: String, aesKey: ByteArray, nonce: ByteArray): ByteArray?

    @JvmStatic
    private external fun batchDecryptMegaNodes(nodesJson: String, masterKey: ByteArray): String

    @JvmStatic
    private external fun decryptMegaCtrSlice(data: ByteArray, aesKey: ByteArray, nonce: ByteArray, startOffset: Long): Boolean

    @JvmStatic
    private external fun saveFolderCacheNative(filePath: String, timestamp: Long, itemsJson: String): Boolean

    @JvmStatic
    private external fun loadFolderCacheNative(filePath: String): String

    @JvmStatic
    private external fun storeMegaCloudTree(accountId: String, nodesJson: String, masterKey: ByteArray): String

    @JvmStatic
    private external fun getCloudTreeChildren(accountId: String, parentId: String, sortOption: Int): String

    @JvmStatic
    private external fun getCloudTreeChildCounts(accountId: String, parentId: String): Long

    @JvmStatic
    private external fun searchCloudTreeNative(accountId: String, query: String, maxResults: Int): String

    @JvmStatic
    private external fun invalidateCloudTree(accountId: String): Boolean

    @JvmStatic
    private external fun saveCloudTreeToFile(accountId: String, filePath: String): Boolean

    @JvmStatic
    private external fun loadCloudTreeFromFile(accountId: String, filePath: String): Int

    @JvmStatic
    private external fun decryptMegaThumbnail(encrypted: ByteArray, nodeKey: ByteArray): ByteArray?

    @JvmStatic
    private external fun downscaleThumbnailBytes(rawBytes: ByteArray, maxW: Int, maxH: Int, quality: Int): ByteArray?

    fun calculateBlockMd5sFast(filePath: String, blockSize: Int = 4 * 1024 * 1024): List<String>? {
        if (!isLoaded) return null
        return try {
            val raw = calculateBlockMd5s(filePath, blockSize)
            val arr = org.json.JSONArray(raw)
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
            list
        } catch (t: Throwable) {
            Log.w(TAG, "calculateBlockMd5sFast failed: ${t.message}")
            null
        }
    }

    fun calculateDropboxContentHashFast(filePath: String): String? {
        if (!isLoaded) return null
        return try {
            val hash = calculateDropboxContentHash(filePath)
            if (hash.isBlank()) null else hash
        } catch (t: Throwable) {
            Log.w(TAG, "calculateDropboxContentHashFast failed: ${t.message}")
            null
        }
    }

    fun computeMegaChunkedMacFast(filePath: String, aesKey: ByteArray, nonce: ByteArray): ByteArray? {
        if (!isLoaded) return null
        return try {
            computeMegaChunkedMac(filePath, aesKey, nonce)
        } catch (t: Throwable) {
            Log.w(TAG, "computeMegaChunkedMacFast failed: ${t.message}")
            null
        }
    }

    fun batchDecryptMegaNodesFast(nodesJson: String, masterKey: ByteArray): List<NativeMegaNode>? {
        if (!isLoaded) return null
        return try {
            val raw = batchDecryptMegaNodes(nodesJson, masterKey)
            val arr = org.json.JSONArray(raw)
            val list = ArrayList<NativeMegaNode>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    NativeMegaNode(
                        handle = obj.optString("handle"),
                        parentHandle = obj.optString("parent_handle"),
                        type = obj.optInt("node_type"),
                        size = obj.optLong("size"),
                        timestamp = obj.optLong("timestamp"),
                        name = obj.optString("name"),
                        keyStr = obj.optString("key_str"),
                        fileAttrStr = obj.optString("file_attr_str")
                    )
                )
            }
            list
        } catch (t: Throwable) {
            Log.w(TAG, "batchDecryptMegaNodesFast failed: ${t.message}")
            null
        }
    }

    fun decryptMegaCtrSliceFast(data: ByteArray, aesKey: ByteArray, nonce: ByteArray, startOffset: Long): Boolean {
        if (!isLoaded) return false
        return try {
            decryptMegaCtrSlice(data, aesKey, nonce, startOffset)
        } catch (t: Throwable) {
            Log.w(TAG, "decryptMegaCtrSliceFast failed: ${t.message}")
            false
        }
    }

    data class NativeCloudTreeNode(
        val id: String,
        val parentId: String,
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val lastModified: Long,
        val extra: String
    )

    fun saveFolderCacheFast(filePath: String, timestamp: Long, itemsJson: String): Boolean {
        if (!isLoaded) return false
        return try {
            saveFolderCacheNative(filePath, timestamp, itemsJson)
        } catch (t: Throwable) {
            Log.w(TAG, "saveFolderCacheFast failed: ${t.message}")
            false
        }
    }

    fun loadFolderCacheFast(filePath: String): String? {
        if (!isLoaded) return null
        return try {
            val res = loadFolderCacheNative(filePath)
            if (res.isNotBlank()) res else null
        } catch (t: Throwable) {
            Log.w(TAG, "loadFolderCacheFast failed: ${t.message}")
            null
        }
    }

    fun storeMegaCloudTreeFast(accountId: String, nodesJson: String, masterKey: ByteArray): Pair<Int, String>? {
        if (!isLoaded) return null
        return try {
            val raw = storeMegaCloudTree(accountId, nodesJson, masterKey)
            if (raw.isBlank()) return null
            val obj = org.json.JSONObject(raw)
            Pair(obj.optInt("count", 0), obj.optString("root_handle", ""))
        } catch (t: Throwable) {
            Log.w(TAG, "storeMegaCloudTreeFast failed: ${t.message}")
            null
        }
    }

    fun getCloudTreeChildrenFast(accountId: String, parentId: String, sortOption: Int): List<NativeCloudTreeNode>? {
        if (!isLoaded) return null
        return try {
            val raw = getCloudTreeChildren(accountId, parentId, sortOption)
            val arr = org.json.JSONArray(raw)
            val list = ArrayList<NativeCloudTreeNode>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    NativeCloudTreeNode(
                        id = obj.optString("id"),
                        parentId = obj.optString("parent_id"),
                        name = obj.optString("name"),
                        isDir = obj.optBoolean("is_dir"),
                        size = obj.optLong("size"),
                        lastModified = obj.optLong("last_modified"),
                        extra = obj.optString("extra")
                    )
                )
            }
            list
        } catch (t: Throwable) {
            Log.w(TAG, "getCloudTreeChildrenFast failed: ${t.message}")
            null
        }
    }

    fun getCloudTreeChildCountsFast(accountId: String, parentId: String): Triple<Int, Int, Int>? {
        if (!isLoaded) return null
        return try {
            val packed = getCloudTreeChildCounts(accountId, parentId)
            val subfolders = (packed shr 32).toInt()
            val files = (packed and 0xFFFFFFFFL).toInt()
            Triple(subfolders + files, subfolders, files)
        } catch (t: Throwable) {
            Log.w(TAG, "getCloudTreeChildCountsFast failed: ${t.message}")
            null
        }
    }

    fun searchCloudTreeFast(accountId: String, query: String, maxResults: Int = 100): List<NativeCloudTreeNode>? {
        if (!isLoaded) return null
        return try {
            val raw = searchCloudTreeNative(accountId, query, maxResults)
            val arr = org.json.JSONArray(raw)
            val list = ArrayList<NativeCloudTreeNode>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    NativeCloudTreeNode(
                        id = obj.optString("id"),
                        parentId = obj.optString("parent_id"),
                        name = obj.optString("name"),
                        isDir = obj.optBoolean("is_dir"),
                        size = obj.optLong("size"),
                        lastModified = obj.optLong("last_modified"),
                        extra = obj.optString("extra")
                    )
                )
            }
            list
        } catch (t: Throwable) {
            Log.w(TAG, "searchCloudTreeFast failed: ${t.message}")
            null
        }
    }

    fun invalidateCloudTreeFast(accountId: String): Boolean {
        if (!isLoaded) return false
        return try {
            invalidateCloudTree(accountId)
        } catch (t: Throwable) {
            Log.w(TAG, "invalidateCloudTreeFast failed: ${t.message}")
            false
        }
    }

    fun saveCloudTreeToFileFast(accountId: String, filePath: String): Boolean {
        if (!isLoaded) return false
        return try {
            saveCloudTreeToFile(accountId, filePath)
        } catch (t: Throwable) {
            Log.w(TAG, "saveCloudTreeToFileFast failed: ${t.message}")
            false
        }
    }

    fun loadCloudTreeFromFileFast(accountId: String, filePath: String): Int {
        if (!isLoaded) return -1
        return try {
            loadCloudTreeFromFile(accountId, filePath)
        } catch (t: Throwable) {
            Log.w(TAG, "loadCloudTreeFromFileFast failed: ${t.message}")
            -1
        }
    }

    fun decryptMegaThumbnailFast(encrypted: ByteArray, nodeKey: ByteArray): ByteArray? {
        if (!isLoaded) return null
        return try {
            decryptMegaThumbnail(encrypted, nodeKey)
        } catch (t: Throwable) {
            Log.w(TAG, "decryptMegaThumbnailFast failed: ${t.message}")
            null
        }
    }

    fun downscaleThumbnailBytesFast(rawBytes: ByteArray, maxW: Int = 240, maxH: Int = 240, quality: Int = 85): ByteArray? {
        if (!isLoaded) return null
        return try {
            downscaleThumbnailBytes(rawBytes, maxW, maxH, quality)
        } catch (t: Throwable) {
            Log.w(TAG, "downscaleThumbnailBytesFast failed: ${t.message}")
            null
        }
    }
}
