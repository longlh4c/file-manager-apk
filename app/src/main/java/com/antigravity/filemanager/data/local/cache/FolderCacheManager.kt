package com.antigravity.filemanager.data.local.cache

import android.content.Context
import com.antigravity.filemanager.domain.model.AppSourceBadge
import com.antigravity.filemanager.domain.model.CategorySummary
import com.antigravity.filemanager.domain.model.CategoryType
import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.domain.model.FileSortOption
import com.antigravity.filemanager.domain.model.FolderBadgeType
import com.antigravity.filemanager.domain.model.MediaFolder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-performance folder cache providing instant (0ms) folder loading
 * on both Local Storage and Cloud Storage (Google Drive, Dropbox, Mega).
 *
 * Employs a Stale-While-Revalidate pattern: cached folder contents are returned
 * immediately on the UI thread / first frame, while fresh folder updates are fetched
 * asynchronously in the background.
 */
/** Result of a cache lookup: the cached files, and whether they're still within the caller's freshness window. */
data class CachedFolderResult(val files: List<FileItem>, val isFresh: Boolean)

/** Same idea as [CachedFolderResult] but for the MediaFolder (bucket) listings shown on the Categories screen. */
data class CachedMediaFoldersResult(val folders: List<MediaFolder>, val isFresh: Boolean)

@Singleton
class FolderCacheManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private data class CacheEntry(val files: List<FileItem>, val timestamp: Long)

    // Bounded LRU: unbounded navigation over a long session must not grow this forever.
    // accessOrder=true + removeEldestEntry evicts the least-recently-used folder first.
    private val maxMemoryCacheEntries = 60
    private val memoryCache = object : LinkedHashMap<String, CacheEntry>(maxMemoryCacheEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>): Boolean =
            size > maxMemoryCacheEntries
    }
    private val cacheDir = File(context.cacheDir, "folder_cache").apply { mkdirs() }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // MediaStore-backed category folders (Images/Audio/Videos/Downloads) are a different shape
    // (MediaFolder, not FileItem) from everything else this class caches. Like Documents below,
    // this is persisted to disk (not just in-memory) so a cold app start can paint the previous
    // result instantly instead of always re-running the MediaStore query + per-file stat() fan-out
    // before showing anything — a fresh scan still happens in the background to catch up.
    private data class MediaFoldersCacheEntry(val folders: List<MediaFolder>, val timestamp: Long)
    private val mediaFoldersCache = ConcurrentHashMap<String, MediaFoldersCacheEntry>()

    private fun mediaFoldersKey(categoryType: CategoryType, sort: FileSortOption) = "mediafolders_${categoryType.name}_${sort.name}"

    suspend fun getMediaFolders(categoryType: CategoryType, sort: FileSortOption, freshTtlMs: Long = 0L): CachedMediaFoldersResult? {
        val key = mediaFoldersKey(categoryType, sort)
        fun freshnessOf(timestamp: Long) = freshTtlMs > 0 && (System.currentTimeMillis() - timestamp) < freshTtlMs

        mediaFoldersCache[key]?.let { return CachedMediaFoldersResult(it.folders, freshnessOf(it.timestamp)) }

        return withContext(Dispatchers.IO) {
            val hashed = hashKey(key)
            val file = File(cacheDir, "$hashed.json")
            if (!file.exists() || file.length() == 0L) return@withContext null

            try {
                val jsonStr = file.readText()
                val root = JSONObject(jsonStr)
                val timestamp = root.optLong("timestamp", 0L)
                val arr = root.getJSONArray("items")
                val list = mutableListOf<MediaFolder>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val badgeName = obj.optString("appSourceBadge", AppSourceBadge.NONE.name)
                    val badge = try { AppSourceBadge.valueOf(badgeName) } catch (e: Exception) { AppSourceBadge.NONE }
                    list.add(
                        MediaFolder(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            path = obj.getString("path"),
                            itemCount = obj.optInt("itemCount", 0),
                            latestThumbnailUri = obj.optString("latestThumbnailUri").ifBlank { null },
                            appSourceBadge = badge,
                            totalSizeBytes = obj.optLong("totalSizeBytes", 0L),
                            lastModified = obj.optLong("lastModified", 0L)
                        )
                    )
                }
                mediaFoldersCache[key] = MediaFoldersCacheEntry(list, timestamp)
                CachedMediaFoldersResult(list, freshnessOf(timestamp))
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun putMediaFolders(categoryType: CategoryType, sort: FileSortOption, folders: List<MediaFolder>) {
        val key = mediaFoldersKey(categoryType, sort)
        val timestamp = System.currentTimeMillis()
        mediaFoldersCache[key] = MediaFoldersCacheEntry(folders, timestamp)
        withContext(Dispatchers.IO) {
            val hashed = hashKey(key)
            try {
                val arr = JSONArray()
                folders.forEach { f ->
                    arr.put(JSONObject().apply {
                        put("id", f.id)
                        put("name", f.name)
                        put("path", f.path)
                        put("itemCount", f.itemCount)
                        put("latestThumbnailUri", f.latestThumbnailUri ?: "")
                        put("appSourceBadge", f.appSourceBadge.name)
                        put("totalSizeBytes", f.totalSizeBytes)
                        put("lastModified", f.lastModified)
                    })
                }
                val root = JSONObject().apply {
                    put("timestamp", timestamp)
                    put("items", arr)
                }
                File(cacheDir, "$hashed.json").writeText(root.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Dashboard category cards (Main storage / Downloads / Images / Audio / Videos / Documents /
    // Cloud / Recycle Bin counts+sizes) — same instant-then-refresh idea as media folders above.
    // Single global entry: there's only ever one dashboard.
    @Volatile private var dashboardSummaryCache: Pair<List<CategorySummary>, Long>? = null
    private val dashboardCacheFile = File(cacheDir, "dashboard_summary.json")

    suspend fun getDashboardSummaries(): List<CategorySummary>? {
        dashboardSummaryCache?.let { return it.first }
        return withContext(Dispatchers.IO) {
            if (!dashboardCacheFile.exists() || dashboardCacheFile.length() == 0L) return@withContext null
            try {
                val root = JSONObject(dashboardCacheFile.readText())
                val arr = root.getJSONArray("items")
                val list = mutableListOf<CategorySummary>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        CategorySummary(
                            type = CategoryType.valueOf(obj.getString("type")),
                            title = obj.getString("title"),
                            totalSizeBytes = obj.optLong("totalSizeBytes", 0L),
                            itemCount = obj.optInt("itemCount", 0),
                            subtitle = obj.optString("subtitle", "")
                        )
                    )
                }
                val timestamp = root.optLong("timestamp", 0L)
                dashboardSummaryCache = list to timestamp
                list
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun putDashboardSummaries(summaries: List<CategorySummary>) {
        val timestamp = System.currentTimeMillis()
        dashboardSummaryCache = summaries to timestamp
        withContext(Dispatchers.IO) {
            try {
                val arr = JSONArray()
                summaries.forEach { s ->
                    arr.put(JSONObject().apply {
                        put("type", s.type.name)
                        put("title", s.title)
                        put("totalSizeBytes", s.totalSizeBytes)
                        put("itemCount", s.itemCount)
                        put("subtitle", s.subtitle)
                    })
                }
                val root = JSONObject().apply {
                    put("timestamp", timestamp)
                    put("items", arr)
                }
                dashboardCacheFile.writeText(root.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** Call after any operation that could add/remove/rename files under any media category
     * (copy/move/delete/download-to-local, etc). Clears every category rather than trying to
     * guess which bucket(s) were affected — cheap since it's just re-scanning MediaStore next visit. */
    fun invalidateMediaFolders() {
        dashboardSummaryCache = null
        ioScope.launch { dashboardCacheFile.delete() }
        val mediaKeys = mediaFoldersCache.keys.toList()
        mediaFoldersCache.clear()
        val keysToRemove = cacheRemoveByPrefix("docs_") + cacheRemoveByPrefix("catsub_") + mediaKeys
        ioScope.launch {
            keysToRemove.forEach { key ->
                val hashed = hashKey(key)
                File(cacheDir, "$hashed.json").delete()
            }
        }
    }

    // LinkedHashMap isn't thread-safe, and this manager is a singleton shared across
    // ViewModels/coroutines, so every access goes through these synchronized helpers.
    private fun cacheGet(key: String): CacheEntry? = synchronized(memoryCache) { memoryCache[key] }

    private fun cachePut(key: String, entry: CacheEntry) {
        synchronized(memoryCache) { memoryCache[key] = entry }
    }

    private fun cacheRemoveByPrefix(prefix: String): List<String> = synchronized(memoryCache) {
        val matching = memoryCache.keys.filter { it.startsWith(prefix) }
        matching.forEach { memoryCache.remove(it) }
        matching
    }

    private fun cacheClear() = synchronized(memoryCache) { memoryCache.clear() }

    private fun hashKey(key: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(key.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            key.replace(Regex("[^a-zA-Z0-9_]"), "_").takeLast(64)
        }
    }

    private fun getLocalKey(path: String, sort: FileSortOption, showHidden: Boolean): String {
        return "local_${path}_${sort.name}_$showHidden"
    }

    private fun getCloudKey(accountId: String, path: String): String {
        return "cloud_${accountId}_$path"
    }

    /**
     * @param freshTtlMs if the cached entry is younger than this, [CachedFolderResult.isFresh] is true,
     * signalling the caller it can skip re-scanning/re-fetching and just trust the cache as-is.
     * Pass 0 to always report stale (i.e. only use the cache for instant first-paint).
     */
    suspend fun getLocalFolder(path: String, sort: FileSortOption, showHidden: Boolean, freshTtlMs: Long = 0L): CachedFolderResult? {
        val key = getLocalKey(path, sort, showHidden)
        return getFolder(key, freshTtlMs)
    }

    suspend fun putLocalFolder(path: String, sort: FileSortOption, showHidden: Boolean, files: List<FileItem>) {
        val key = getLocalKey(path, sort, showHidden)
        putFolder(key, files)
    }

    suspend fun getDocuments(sort: FileSortOption, freshTtlMs: Long = 0L): CachedFolderResult? {
        return getFolder("docs_${sort.name}", freshTtlMs)
    }

    suspend fun putDocuments(sort: FileSortOption, files: List<FileItem>) {
        putFolder("docs_${sort.name}", files)
    }

    // Subfolders drilled into from a media Category screen (e.g. Images > DCIM > Camera) — the
    // filtered-to-this-category file list, keyed separately from the plain local browser cache
    // above since the same path shows different contents depending on which category (Images vs
    // Videos, etc.) it was opened from. Same stale-while-revalidate contract as getLocalFolder.
    private fun getCategorySubfolderKey(categoryType: CategoryType, path: String, sort: FileSortOption, showHidden: Boolean) =
        "catsub_${categoryType.name}_${path}_${sort.name}_$showHidden"

    suspend fun getCategorySubfolder(categoryType: CategoryType, path: String, sort: FileSortOption, showHidden: Boolean, freshTtlMs: Long = 0L): CachedFolderResult? {
        return getFolder(getCategorySubfolderKey(categoryType, path, sort, showHidden), freshTtlMs)
    }

    suspend fun putCategorySubfolder(categoryType: CategoryType, path: String, sort: FileSortOption, showHidden: Boolean, files: List<FileItem>) {
        putFolder(getCategorySubfolderKey(categoryType, path, sort, showHidden), files)
    }

    suspend fun getCloudFolder(accountId: String, path: String, freshTtlMs: Long = 0L): CachedFolderResult? {
        val key = getCloudKey(accountId, path)
        return getFolder(key, freshTtlMs)
    }

    suspend fun putCloudFolder(accountId: String, path: String, files: List<FileItem>) {
        val key = getCloudKey(accountId, path)
        putFolder(key, files)
    }

    private suspend fun getFolder(key: String, freshTtlMs: Long): CachedFolderResult? {
        fun freshnessOf(timestamp: Long) = freshTtlMs > 0 && (System.currentTimeMillis() - timestamp) < freshTtlMs

        // 1. Check in-memory cache for instantaneous retrieval (0ms, no thread hop)
        cacheGet(key)?.let { return CachedFolderResult(it.files, freshnessOf(it.timestamp)) }

        // 2. Check persistent disk cache across app restarts (off the calling thread)
        return withContext(Dispatchers.IO) {
            val hashed = hashKey(key)
            val file = File(cacheDir, "$hashed.json")
            if (!file.exists() || file.length() == 0L) return@withContext null

            try {
                val jsonStr = file.readText()
                val root = JSONObject(jsonStr)
                val timestamp = root.optLong("timestamp", 0L)
                val arr = root.getJSONArray("items")
                val list = mutableListOf<FileItem>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val badgeName = obj.optString("badge", FolderBadgeType.STANDARD.name)
                    val badge = try { FolderBadgeType.valueOf(badgeName) } catch (e: Exception) { FolderBadgeType.STANDARD }
                    list.add(
                        FileItem(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            path = obj.getString("path"),
                            size = obj.optLong("size", 0L),
                            lastModified = obj.optLong("lastModified", 0L),
                            isDirectory = obj.optBoolean("isDirectory", false),
                            itemCount = obj.optInt("itemCount", 0),
                            // Missing these two used to silently reset to 0 on every disk-cache
                            // round trip, breaking the "N folders, M items" split label (it only
                            // trusts subfolderCount+fileChildCount when they sum back to
                            // itemCount — see folderItemCountLabel) even though the fan-out that
                            // computed them moments earlier got it right.
                            subfolderCount = obj.optInt("subfolderCount", 0),
                            fileChildCount = obj.optInt("fileChildCount", 0),
                            extension = obj.optString("extension", ""),
                            thumbnailUri = obj.optString("thumbnailUri").ifBlank { null },
                            folderBadgeType = badge
                        )
                    )
                }
                cachePut(key, CacheEntry(list, timestamp))
                CachedFolderResult(list, freshnessOf(timestamp))
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun putFolder(key: String, files: List<FileItem>) {
        val timestamp = System.currentTimeMillis()
        cachePut(key, CacheEntry(files, timestamp))
        withContext(Dispatchers.IO) {
            val hashed = hashKey(key)
            try {
                val arr = JSONArray()
                files.forEach { item ->
                    arr.put(JSONObject().apply {
                        put("id", item.id)
                        put("name", item.name)
                        put("path", item.path)
                        put("size", item.size)
                        put("lastModified", item.lastModified)
                        put("isDirectory", item.isDirectory)
                        put("itemCount", item.itemCount)
                        put("subfolderCount", item.subfolderCount)
                        put("fileChildCount", item.fileChildCount)
                        put("extension", item.extension)
                        put("thumbnailUri", item.thumbnailUri ?: "")
                        put("badge", item.folderBadgeType.name)
                    })
                }
                val root = JSONObject().apply {
                    put("timestamp", timestamp)
                    put("items", arr)
                }
                val file = File(cacheDir, "$hashed.json")
                file.writeText(root.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun invalidateLocal(path: String) {
        val prefix = "local_${path}_"
        val keysToRemove = cacheRemoveByPrefix(prefix)
        ioScope.launch {
            keysToRemove.forEach { key ->
                val hashed = hashKey(key)
                File(cacheDir, "$hashed.json").delete()
            }
        }
    }

    fun invalidateCloud(accountId: String, path: String? = null) {
        val prefix = if (path != null) "cloud_${accountId}_$path" else "cloud_${accountId}_"
        val keysToRemove = cacheRemoveByPrefix(prefix)
        ioScope.launch {
            keysToRemove.forEach { key ->
                val hashed = hashKey(key)
                File(cacheDir, "$hashed.json").delete()
            }
        }
    }

    fun clearAll() {
        cacheClear()
        mediaFoldersCache.clear()
        dashboardSummaryCache = null
        ioScope.launch {
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
        }
    }
}