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
}
