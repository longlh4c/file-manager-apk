package com.antigravity.filemanager.data.local.cache

import android.content.Context
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
    // (MediaFolder, not FileItem) from everything else this class caches, and — unlike a plain
    // directory listing — expensive mainly because of the MediaStore query + per-file stat()
    // fan-out, not disk I/O we'd want to persist across process restarts. In-memory only is enough
    // to make repeat visits within a session instant; a cold app start always re-scans for real.
    private data class MediaFoldersCacheEntry(val folders: List<MediaFolder>, val timestamp: Long)
    private val mediaFoldersCache = ConcurrentHashMap<String, MediaFoldersCacheEntry>()

    private fun mediaFoldersKey(categoryType: CategoryType, sort: FileSortOption) = "${categoryType.name}_${sort.name}"

    fun getMediaFolders(categoryType: CategoryType, sort: FileSortOption, freshTtlMs: Long = 0L): CachedMediaFoldersResult? {
        val entry = mediaFoldersCache[mediaFoldersKey(categoryType, sort)] ?: return null
        val fresh = freshTtlMs > 0 && (System.currentTimeMillis() - entry.timestamp) < freshTtlMs
        return CachedMediaFoldersResult(entry.folders, fresh)
    }

    fun putMediaFolders(categoryType: CategoryType, sort: FileSortOption, folders: List<MediaFolder>) {
        mediaFoldersCache[mediaFoldersKey(categoryType, sort)] = MediaFoldersCacheEntry(folders, System.currentTimeMillis())
    }

    /** Call after any operation that could add/remove/rename files under any media category
     * (copy/move/delete/download-to-local, etc). Clears every category rather than trying to
     * guess which bucket(s) were affected — cheap since it's just re-scanning MediaStore next visit. */
    fun invalidateMediaFolders() {
        mediaFoldersCache.clear()
        val keysToRemove = cacheRemoveByPrefix("docs_")
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
        ioScope.launch {
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
        }
    }
}