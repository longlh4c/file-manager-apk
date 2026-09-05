package com.antigravity.filemanager.data.local.cache

import android.content.Context
import com.antigravity.filemanager.data.local.observer.MediaChangeSignal
import com.antigravity.filemanager.domain.model.AppSourceBadge
import com.antigravity.filemanager.domain.model.CategorySummary
import com.antigravity.filemanager.domain.model.CategoryType
import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.domain.model.FileSortOption
import com.antigravity.filemanager.domain.model.FolderBadgeType
import com.antigravity.filemanager.domain.model.MediaFolder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
    @ApplicationContext private val context: Context,
    mediaChangeSignal: MediaChangeSignal
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

    // A time-based TTL meant "reopen after N seconds and pay for a full rescan again" no matter
    // how many times that happened in a row — the common case (switching tabs, backgrounding the
    // app for a minute) never actually needed re-verifying, because the two things that can make
    // a cached listing wrong are both already handled by explicit signals, not by a timer:
    // an in-app copy/move/delete/rename invalidates the relevant keys immediately (see
    // invalidateMediaFolders below), and an external change (new photo, a finished download)
    // clears the reconciled flag below so the next open re-verifies. The only gap either of those
    // can miss is something that happened while the app process wasn't alive to observe it — so
    // instead of "fresh for N seconds", a key only needs reconciling once per process lifetime:
    // the first read after cold start (or after the flag below was last cleared) double-checks
    // against MediaStore in the background (still painting the cached list instantly first), and
    // every read after that trusts the cache outright, until something actually changes it again.
    private val reconciledOnceKeys = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    // MediaChangeSignal's ContentObserver is only ever useful here if SOMETHING is always
    // listening — a screen-scoped ViewModel collecting it (the original design) stops listening
    // the moment that screen isn't open, so a photo saved while the user wasn't sitting on the
    // Images screen was never seen: the reconciled flag above stayed set from before the change,
    // so the next time Images was opened it just trusted the (now stale) cache with nothing left
    // to ever re-check it. Subscribing here instead, in a singleton alive for the whole process,
    // means an external change clears the affected reconcile flags regardless of which screen (if
    // any) happens to be open when it arrives — the next read of any of them re-verifies against
    // MediaStore once, the same "instant stale paint, then a silent background catch-up" as a
    // fresh cold start.
    init {
        // Shorter debounce than CategoriesViewModel's own 150ms collector on the same signal —
        // when both fire for the same change, this clearing the flag first (then that screen's
        // own handler writing fresh data back, which re-sets it) means a screen that's actually
        // open when the change happens doesn't pay for a redundant extra reconcile on next open.
        mediaChangeSignal.changes
            .debounce(100)
            .onEach { markMediaCachesUnreconciled() }
            .launchIn(ioScope)
    }

    private fun markMediaCachesUnreconciled() {
        reconciledOnceKeys.removeAll { it.startsWith("mediafolders_") || it.startsWith("catsub_") }
    }

    /** False the first time this exact key is asked about since process start (caller should
     * kick off one background reconcile); true every time after (caller can trust the cache
     * as-is — no revalidation needed, since ContentObserver/explicit invalidation keep it live). */
    private fun markReconciledOnce(key: String): Boolean = !reconciledOnceKeys.add(key)

    private fun mediaFoldersKey(categoryType: CategoryType, sort: FileSortOption) = "mediafolders_${categoryType.name}_${sort.name}"

    suspend fun getMediaFolders(categoryType: CategoryType, sort: FileSortOption): CachedMediaFoldersResult? {
        val key = mediaFoldersKey(categoryType, sort)

        mediaFoldersCache[key]?.let { return CachedMediaFoldersResult(it.folders, markReconciledOnce(key)) }

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
                CachedMediaFoldersResult(list, markReconciledOnce(key))
            } catch (e: Exception) {
                null
            }
        }
    }

    // Keyed the same as mediaFoldersCache — tracks a reconcile scan currently in flight for that
    // exact (categoryType, sort) so a second caller asking for it moments later awaits the same
    // result instead of starting a fully redundant second MediaStore scan.
    private val inFlightMediaFolders = ConcurrentHashMap<String, Deferred<List<MediaFolder>>>()

    /**
     * The dashboard's proactive warm-up (DashboardViewModel.warmMediaFolderCaches) and a category
     * screen the user taps into right after cold start can easily both decide "this needs
     * reconciling" for the exact same category at the exact same moment — without this, both ran
     * the full MediaStore scan (stat() fan-out across the whole library) independently, competing
     * for the same CPU/IO and making the one the user is actually staring at (e.g. Images, the
     * largest category and so the slowest scan) *slower* than if the warm-up didn't exist at all.
     * [fetch] runs on this manager's own IO scope, not the caller's — so a caller navigating away
     * and having its own coroutine cancelled doesn't cancel the scan out from under a second
     * caller still awaiting the same result.
     */
    suspend fun reconcileMediaFolders(categoryType: CategoryType, sort: FileSortOption, fetch: suspend () -> List<MediaFolder>): List<MediaFolder> {
        val key = mediaFoldersKey(categoryType, sort)
        inFlightMediaFolders[key]?.let { return it.await() }

        // LAZY: creating this doesn't start fetch() running yet. Only whichever Deferred actually
        // gets awaited below (the winner of the race, or the sole caller when there's no race at
        // all) ever executes — the loser's is simply dropped, having done no work at all, rather
        // than being cancelled mid-flight after already duplicating part of the scan.
        val deferred = ioScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) { fetch() }
        val winner = inFlightMediaFolders.putIfAbsent(key, deferred) ?: deferred
        if (winner !== deferred) deferred.cancel()
        return try {
            val result = winner.await()
            if (winner === deferred) putMediaFolders(categoryType, sort, result)
            result
        } finally {
            inFlightMediaFolders.remove(key, winner)
        }
    }

    suspend fun putMediaFolders(categoryType: CategoryType, sort: FileSortOption, folders: List<MediaFolder>) {
        val key = mediaFoldersKey(categoryType, sort)
        val timestamp = System.currentTimeMillis()
        // Writing a fresh result IS the reconcile — mark it done so a get() that raced in behind
        // this put (or arrives moments later) doesn't think it still owes the process its one
        // background double-check.
        reconciledOnceKeys.add(key)
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

    private val deleteExtCategory = buildMap {
        val images = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "svg", "raw", "dng")
        val videos = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "ts", "m4v", "mpg", "mpeg", "vob", "ogv", "f4v")
        val audio = setOf("mp3", "m4a", "wav", "flac", "aac", "ogg", "wma", "opus", "amr", "mid", "midi")
        val docs = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "epub")
        images.forEach { put(it, CategoryType.IMAGES) }
        videos.forEach { put(it, CategoryType.VIDEOS) }
        audio.forEach { put(it, CategoryType.AUDIO) }
        docs.forEach { put(it, CategoryType.DOCUMENTS) }
    }

    /**
     * A plain delete only ever shrinks the exact folder(s) the deleted file(s) were sitting in —
     * unlike invalidateMediaFolders() (still used for copy/move/rename, where a file can land in
     * a folder that isn't cached yet), there's no need to drop every category's cache and force
     * Images AND Videos AND Audio AND Documents to all redo a full MediaStore rescan just because
     * one photo was deleted. Instead, patch the affected folder's itemCount (dropping it entirely
     * once it hits zero) and clear a thumbnail that pointed at the deleted file, for only the
     * category the deletion actually touched — everything else stays exactly as cached. A
     * category that hasn't been opened yet this process isn't touched here at all; it still gets
     * its own one-time reconcile against MediaStore the first time it's opened (reconciledOnceKeys).
     */
    suspend fun removeFromMediaFolders(deletedPaths: List<String>) {
        data class Affected(val categoryType: CategoryType, val parentDir: String)
        val byFolder = HashMap<Affected, MutableList<String>>()
        for (path in deletedPaths) {
            val ext = File(path).extension.lowercase()
            val categoryType = deleteExtCategory[ext] ?: continue
            val parentDir = File(path).parentFile?.absolutePath ?: continue
            byFolder.getOrPut(Affected(categoryType, parentDir)) { mutableListOf() }.add(path)
        }
        if (byFolder.isEmpty()) return

        // Dashboard totals (item counts / sizes per category) are cheap to just drop and let
        // recompute in the background — no per-bucket bookkeeping needed there.
        dashboardSummaryCache = null
        ioScope.launch { dashboardCacheFile.delete() }

        // Root bucket grid (Images/Videos/Audio/Documents) — every sort variant currently held
        // for an affected category, since the folder contents/counts don't depend on sort order.
        for (categoryType in byFolder.keys.map { it.categoryType }.toSet()) {
            val prefix = "mediafolders_${categoryType.name}_"
            for (key in mediaFoldersCache.keys.filter { it.startsWith(prefix) }) {
                val sort = try { FileSortOption.valueOf(key.removePrefix(prefix)) } catch (e: Exception) { continue }
                val entry = mediaFoldersCache[key] ?: continue
                var changed = false
                val patched = entry.folders.mapNotNull { folder ->
                    val removedHere = byFolder[Affected(categoryType, folder.path)] ?: return@mapNotNull folder
                    changed = true
                    val newCount = folder.itemCount - removedHere.size
                    if (newCount <= 0) {
                        null
                    } else {
                        // totalSizeBytes is left as-is rather than tracked down and subtracted —
                        // a few bytes stale until the category's next real reconcile (a fresh
                        // process, or an external MediaStore change) is a fine trade for not
                        // having to fetch each deleted file's size after it's already gone.
                        folder.copy(
                            itemCount = newCount,
                            latestThumbnailUri = folder.latestThumbnailUri?.takeUnless { it in removedHere }
                        )
                    }
                }
                if (changed) putMediaFolders(categoryType, sort, patched)
            }
        }

        // Category-subfolder file lists (Images > DCIM > Camera drill-down) — drop the deleted
        // file(s) from every cached (sort, showHidden) variant of the folder they were in.
        for ((affected, paths) in byFolder) {
            val prefix = "catsub_${affected.categoryType.name}_${affected.parentDir}_"
            val matchingKeys = synchronized(memoryCache) { memoryCache.keys.filter { it.startsWith(prefix) } }
            for (key in matchingKeys) {
                // Remainder after the prefix is "<SORT>_<showHidden>" — split on the LAST
                // underscore since FileSortOption names (BY_NAME_ASC, ...) contain underscores too.
                val rest = key.removePrefix(prefix)
                val splitAt = rest.lastIndexOf('_')
                if (splitAt < 0) continue
                val sort = try { FileSortOption.valueOf(rest.substring(0, splitAt)) } catch (e: Exception) { continue }
                val showHidden = rest.substring(splitAt + 1).toBooleanStrictOrNull() ?: continue
                val entry = cacheGet(key) ?: continue
                val patchedFiles = entry.files.filterNot { it.path in paths }
                if (patchedFiles.size != entry.files.size) {
                    putCategorySubfolder(affected.categoryType, affected.parentDir, sort, showHidden, patchedFiles)
                }
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

    // Same "reconcile once per process, then trust the cache" contract as getMediaFolders above
    // (see the comment on reconciledOnceKeys) rather than a time-based TTL — a category subfolder
    // is invalidated the same way a media folder bucket is: explicitly on any in-app mutation, and
    // implicitly live via MediaChangeSignal for anything that happens outside the app.
    suspend fun getCategorySubfolder(categoryType: CategoryType, path: String, sort: FileSortOption, showHidden: Boolean): CachedFolderResult? {
        return getFolder(getCategorySubfolderKey(categoryType, path, sort, showHidden), useReconcileOnce = true)
    }

    suspend fun putCategorySubfolder(categoryType: CategoryType, path: String, sort: FileSortOption, showHidden: Boolean, files: List<FileItem>) {
        putFolder(getCategorySubfolderKey(categoryType, path, sort, showHidden), files, markReconciled = true)
    }

    // Same "reconcile once per process, then trust the cache" contract as getMediaFolders/
    // getCategorySubfolder (see reconciledOnceKeys) rather than the 30s TTL this used to carry —
    // a cloud folder is invalidated the same explicit way a local one is (any in-app
    // copy/move/delete/rename calls invalidateCloud), there's no external-change signal for cloud
    // storage the way MediaChangeSignal covers local media, but that's exactly why trusting a
    // real fetch for the rest of the session (instead of re-fetching every 30s "just in case") is
    // safe: nothing else in this app's own session can change a cloud folder without going
    // through this app and invalidating it. This is also the cache the "pick a destination"
    // folder pickers (CategoriesViewModel/FileBrowserViewModel) share with the Cloud tab
    // (CloudExplorerViewModel) — a folder fetched once from either screen now stays fresh for
    // both for the rest of the session, not just the 30s it used to.
    suspend fun getCloudFolder(accountId: String, path: String): CachedFolderResult? {
        val key = getCloudKey(accountId, path)
        return getFolder(key, useReconcileOnce = true)
    }

    suspend fun putCloudFolder(accountId: String, path: String, files: List<FileItem>) {
        val key = getCloudKey(accountId, path)
        putFolder(key, files, markReconciled = true)
    }

    private suspend fun getFolder(key: String, freshTtlMs: Long = 0L, useReconcileOnce: Boolean = false): CachedFolderResult? {
        fun freshnessOf(timestamp: Long) = if (useReconcileOnce) {
            markReconciledOnce(key)
        } else {
            freshTtlMs > 0 && (System.currentTimeMillis() - timestamp) < freshTtlMs
        }

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

    private suspend fun putFolder(key: String, files: List<FileItem>, markReconciled: Boolean = false) {
        val timestamp = System.currentTimeMillis()
        // Same reasoning as putMediaFolders: a fresh write already IS the reconcile.
        if (markReconciled) reconciledOnceKeys.add(key)
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

    // getCategorySubfolder uses the reconcile-once model (see reconciledOnceKeys), not a TTL —
    // unlike invalidateLocal's plain local browser folders, just dropping the cached entry here
    // isn't enough: reconciledOnceKeys still remembers this key as already reconciled, so the very
    // next read would trust a freshly-refetched (but by definition once again "reconciled") result
    // forever regardless, UNLESS the flag itself is cleared too. Without this, creating a new
    // folder from inside a category (Images > Pictures > New Folder) never showed the folder
    // afterward — openSubfolder()'s cache read still thought this exact folder didn't need
    // re-checking, since nothing had ever told it otherwise.
    fun invalidateCategorySubfolder(categoryType: CategoryType, path: String) {
        val prefix = "catsub_${categoryType.name}_${path}_"
        val keysToRemove = cacheRemoveByPrefix(prefix)
        reconciledOnceKeys.removeAll { it.startsWith(prefix) }
        ioScope.launch {
            keysToRemove.forEach { key ->
                val hashed = hashKey(key)
                File(cacheDir, "$hashed.json").delete()
            }
        }
    }

    /**
     * A single stream any screen holding a cloud folder open can subscribe to, to react when
     * that exact folder changes from somewhere else in the app instead of only catching up on
     * its next open/navigation. Two variants:
     * - [FilesAdded]: the caller already knows EXACTLY what landed and under what name (a plain
     *   flat-file Copy/Move to Dropbox with no rename/merge decision — no overwrite conflict, no
     *   folder in the selection). A listening screen can splice these straight into what it's
     *   showing, no network call needed — the same "already know the answer" shortcut a delete
     *   already uses for its own optimistic removal.
     * - [FilesRemoved]: same idea, the other direction — the caller knows exactly which item(s)
     *   left this folder (e.g. the source side of a "Move to Local"). A listening screen can drop
     *   them from what it's showing directly, no re-fetch needed.
     * - [Invalidated]: something changed but the caller doesn't know exactly what (an overwrite
     *   that deleted+replaced an item, a folder copy, a rename) — a listening screen has to
     *   actually re-fetch to find out.
     *
     * All three carry `accountId`/`path` so a subscriber can ignore events for any folder other
     * than the one it's currently showing. See [invalidateCloud], [notifyCloudFilesAdded] and
     * [notifyCloudFilesRemoved] for the write side.
     */
    sealed class CloudFolderEvent {
        abstract val accountId: String
        abstract val path: String
        data class FilesAdded(override val accountId: String, override val path: String, val files: List<FileItem>) : CloudFolderEvent()
        data class FilesRemoved(override val accountId: String, override val path: String, val removedPaths: Set<String>) : CloudFolderEvent()
        data class Invalidated(override val accountId: String, override val path: String) : CloudFolderEvent()
    }

    // replay=0/extraBufferCapacity so a screen that isn't collecting when this fires (e.g. not
    // currently open) simply catches up via the normal cache-miss path next time it's opened,
    // rather than replaying a now-irrelevant old change.
    private val _cloudFolderEvents = kotlinx.coroutines.flow.MutableSharedFlow<CloudFolderEvent>(replay = 0, extraBufferCapacity = 8)
    val cloudFolderEvents: kotlinx.coroutines.flow.SharedFlow<CloudFolderEvent> = _cloudFolderEvents

    // notify=false is for a screen invalidating a folder IT ITSELF is about to re-fetch (e.g.
    // CloudExplorerViewModel.refresh()) — emitting an event there caused that same screen's own
    // collector (added for the "another screen just changed this folder" case) to see its own
    // invalidation and call refresh() again, which invalidates again, which emits again... an
    // infinite refresh loop with no way out short of force-quitting the app. Only an invalidate
    // coming from somewhere OTHER than the folder's own screen should notify.
    fun invalidateCloud(accountId: String, path: String? = null, notify: Boolean = true) {
        val prefix = if (path != null) "cloud_${accountId}_$path" else "cloud_${accountId}_"
        val keysToRemove = cacheRemoveByPrefix(prefix)
        ioScope.launch {
            keysToRemove.forEach { key ->
                val hashed = hashKey(key)
                File(cacheDir, "$hashed.json").delete()
            }
        }
        if (path != null && notify) {
            _cloudFolderEvents.tryEmit(CloudFolderEvent.Invalidated(accountId, path))
        }
    }

    // Merges files straight into this path's cache (so the next reader anywhere gets them without
    // a refetch) and emits FilesAdded so a screen already open on this exact folder can splice
    // them into what it's showing directly. See CloudFolderEvent's doc above.
    suspend fun notifyCloudFilesAdded(accountId: String, path: String, addedFiles: List<FileItem>) {
        if (addedFiles.isEmpty()) return
        val cached = getCloudFolder(accountId, path)
        val currentFiles = cached?.files ?: emptyList()
        val existingNames = currentFiles.map { it.name }.toSet()
        val newOnes = addedFiles.filter { it.name !in existingNames }
        if (newOnes.isNotEmpty()) {
            putCloudFolder(accountId, path, currentFiles + newOnes)
        }
        _cloudFolderEvents.tryEmit(CloudFolderEvent.FilesAdded(accountId, path, addedFiles))
    }

    // The removal-side counterpart of notifyCloudFilesAdded above — drops known-gone items from
    // this path's cache (so the next reader anywhere doesn't see them either) and emits
    // FilesRemoved so a screen already open on this exact folder can drop them from what it's
    // showing directly, instead of paying for a refetch via the generic invalidateCloud.
    suspend fun notifyCloudFilesRemoved(accountId: String, path: String, removedRemotePaths: Set<String>) {
        if (removedRemotePaths.isEmpty()) return
        val cached = getCloudFolder(accountId, path)
        if (cached != null) {
            val remaining = cached.files.filterNot { it.path in removedRemotePaths }
            if (remaining.size != cached.files.size) {
                putCloudFolder(accountId, path, remaining)
            }
        }
        _cloudFolderEvents.tryEmit(CloudFolderEvent.FilesRemoved(accountId, path, removedRemotePaths))
    }

    // Shared by every "Copy/Move flat local files to a cloud folder, no conflicts" call site
    // (CategoriesViewModel, FileBrowserViewModel) — was duplicated inline in both. Builds the
    // FileItem each uploaded local file becomes at its destination, for notifyCloudFilesAdded.
    // Only valid when the caller has already confirmed no overwrite conflicts and no directories
    // were in the selection (uploadFiles never renamed/merged anything in that case, so the local
    // name IS the final remote name).
    fun buildUploadedFileItems(localPaths: List<String>, skipNames: Set<String>, destPath: String): List<FileItem> {
        val now = System.currentTimeMillis()
        return localPaths
            .map { File(it) }
            .filter { it.isFile && it.name !in skipNames }
            .map { file ->
                val remotePath = if (destPath == "/" || destPath.isBlank()) "/${file.name}" else "${destPath.trimEnd('/')}/${file.name}"
                val ext = file.extension.lowercase(java.util.Locale.getDefault())
                FileItem(
                    id = remotePath,
                    name = file.name,
                    path = remotePath,
                    size = file.length(),
                    lastModified = now,
                    isDirectory = false,
                    mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*",
                    extension = ext
                )
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