package com.antigravity.filemanager.data.remote.cloud.api

import android.content.Context
import com.antigravity.filemanager.domain.model.CloudAccount
import com.antigravity.filemanager.domain.model.FileItem
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.DeletedMetadata
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.FolderMetadata
import com.dropbox.core.v2.files.ListRevisionsMode
import com.dropbox.core.v2.files.Metadata
import com.dropbox.core.v2.files.WriteMode
import com.dropbox.core.v2.files.CommitInfo
import com.dropbox.core.v2.files.UploadSessionCursor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

// Dropbox API v2 client using official dropbox-core-sdk with OAuth2/PKCE tokens.
@Singleton
class DropboxApiClient @Inject constructor(
    @ApplicationContext private val context: Context
) {

    // Dropbox's list_folder has no "children of X with descendant counts" mode — like MEGA,
    // the only way to avoid an N+1 fan-out (one API call per subfolder just to get its item
    // count) is to fetch the WHOLE account tree once via recursive=true and slice it in memory.
    // Cached per account in RAM, backed by a disk copy (see [persistTreeToDisk]/[loadTreeFromDisk])
    // so it survives a process restart instead of forcing a full recursive re-fetch every time the
    // app is reopened — unlike MEGA's SDK, Dropbox's SDK has no local persistence of its own.
    // Expires after [treeTtlMillis] or on [invalidateTree] (a manual refresh at the account root,
    // or after a mutation), whichever comes first.
    private data class DropboxEntry(
        val path: String,
        val parentPath: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
        val id: String
    )
    /** [cursor] is list_folder's cursor for the whole-account listing, used to fetch only what
     * changed since (see [syncDelta]). */
    private data class TreeCache(val entries: List<DropboxEntry>, val timestamp: Long, val cursor: String? = null)
    private val treeCache = ConcurrentHashMap<String, TreeCache>()
    private val treeMutexes = ConcurrentHashMap<String, Mutex>()

    // 24h: long enough that reopening the app (or a process the OS killed in the background)
    // doesn't pay for a full recursive re-fetch just to redraw the same folder. Changes made
    // elsewhere in the meantime are picked up by [syncDelta], not by this expiry.
    private val treeTtlMillis = 24L * 60 * 60 * 1000

    private fun treeMutexFor(accountId: String): Mutex = treeMutexes.getOrPut(accountId) { Mutex() }

    private companion object {
        const val SINGLE_UPLOAD_LIMIT = 150L * 1024 * 1024
        const val UPLOAD_CHUNK_SIZE = 8L * 1024 * 1024
    }

    private fun treeCacheDir(): File = File(context.filesDir, "dropbox_tree_cache").apply { mkdirs() }
    private fun treeCacheFile(accountId: String): File = File(treeCacheDir(), "$accountId.json")

    private fun persistTreeToDisk(accountId: String, cache: TreeCache) {
        try {
            val entriesJson = JSONArray()
            cache.entries.forEach { e ->
                entriesJson.put(
                    JSONObject().apply {
                        put("path", e.path)
                        put("parentPath", e.parentPath)
                        put("name", e.name)
                        put("isDirectory", e.isDirectory)
                        put("size", e.size)
                        put("lastModified", e.lastModified)
                        put("id", e.id)
                    }
                )
            }
            val root = JSONObject().apply {
                put("timestamp", cache.timestamp)
                cache.cursor?.let { put("cursor", it) }
                put("entries", entriesJson)
            }
            treeCacheFile(accountId).writeText(root.toString())
        } catch (e: Exception) {
            android.util.Log.e("DropboxApiClient", "persistTreeToDisk: FAILED for $accountId", e)
        }
    }

    private fun loadTreeFromDisk(accountId: String): TreeCache? {
        return try {
            val file = treeCacheFile(accountId)
            if (!file.exists()) return null
            val root = JSONObject(file.readText())
            val timestamp = root.getLong("timestamp")
            if (System.currentTimeMillis() - timestamp > treeTtlMillis) {
                // Expired — delete so a corrupt/ancient file doesn't linger around forever.
                file.delete()
                return null
            }
            val entriesJson = root.getJSONArray("entries")
            val entries = (0 until entriesJson.length()).map { i ->
                val o = entriesJson.getJSONObject(i)
                DropboxEntry(
                    path = o.getString("path"),
                    parentPath = o.getString("parentPath"),
                    name = o.getString("name"),
                    isDirectory = o.getBoolean("isDirectory"),
                    size = o.getLong("size"),
                    lastModified = o.getLong("lastModified"),
                    id = o.getString("id")
                )
            }
            TreeCache(entries, timestamp, root.optString("cursor").ifBlank { null })
        } catch (e: Exception) {
            android.util.Log.e("DropboxApiClient", "loadTreeFromDisk: FAILED for $accountId, discarding", e)
            treeCacheFile(accountId).delete()
            null
        }
    }

    /** Call after any mutation (create/delete/rename) so the next listing re-fetches. */
    fun invalidateTree(accountId: String) {
        treeCache.remove(accountId)
        treeCacheFile(accountId).delete()
    }

    /** The cached tree to patch: the in-memory copy, or the disk copy when the process was
     * restarted. Patching only the in-memory copy left a stale disk tree behind that the next
     * listing then loaded — a folder created right after reopening the app didn't show up. */
    private fun cachedTree(accountId: String): TreeCache? =
        treeCache[accountId] ?: loadTreeFromDisk(accountId)?.also { treeCache[accountId] = it }

    /** Patches a freshly uploaded file into the cached tree in place, so the folder listing that
     * follows an upload reflects it immediately without forcing a full recursive re-fetch of the
     * WHOLE account (that fetch is what made "copy to Dropbox" feel like it hung/timed out —
     * upload one file, then wait on a full-tree listFolder(recursive=true) just to redraw one
     * folder). No-ops if nothing is cached yet; the next listFolderCached() will fetch fresh anyway. */
    private fun patchTreeAfterUpload(accountId: String, metadata: FileMetadata) {
        val cached = cachedTree(accountId) ?: return
        val path = metadata.pathDisplay ?: return
        val parent = path.substringBeforeLast('/', "")
        val entry = DropboxEntry(path, parent, metadata.name, false, metadata.size, metadata.serverModified.time, metadata.id)
        // WriteMode.OVERWRITE means a re-upload of an existing name reuses the same path, so drop
        // any stale entry for that path before adding the fresh one.
        val updated = cached.entries.filterNot { it.path.equals(path, ignoreCase = true) } + entry
        // Renew the freshness timestamp too, not just the contents — otherwise the cache keeps
        // counting down from whenever it was first fetched regardless of being patched, and a
        // listing shortly after several patches can still land past the original TTL and pay for
        // a full recursive re-fetch anyway, which is exactly what an upload here should avoid.
        val newCache = cached.copy(entries = updated, timestamp = System.currentTimeMillis())
        treeCache[accountId] = newCache
        persistTreeToDisk(accountId, newCache)
    }

    /** Same idea as [patchTreeAfterUpload] but for a freshly created folder — a recursive
     * cloud-to-cloud folder copy calls createFolder once per subfolder, and each one used to
     * call [invalidateTree] and throw away the whole cached account tree, forcing every listing
     * right after (including this same copy's own per-folder conflict checks) to pay for a full
     * recursive re-fetch again. Patching in place keeps the "build the tree once, reuse it"
     * cache actually holding across a multi-folder copy instead of restarting on every folder. */
    fun patchTreeAfterFolderCreate(accountId: String, path: String, id: String) {
        val cached = cachedTree(accountId) ?: return
        val parent = path.trimEnd('/').substringBeforeLast('/', "")
        val name = path.trimEnd('/').substringAfterLast('/')
        // lastModified=0L to match every real folder entry (fetchTreeFromNetwork always sets 0L
        // for FolderMetadata — Dropbox's API just doesn't expose a modified-time for folders).
        // This used to be "now", which put a freshly created folder wildly out of position
        // whenever the list was sorted by date, since every other folder sits at epoch 0.
        val entry = DropboxEntry(path, parent, name, true, 0L, 0L, id)
        val updated = cached.entries.filterNot { it.path.equals(path, ignoreCase = true) } + entry
        val newCache = cached.copy(entries = updated, timestamp = System.currentTimeMillis())
        treeCache[accountId] = newCache
        persistTreeToDisk(accountId, newCache)
    }

    /** Re-paths a renamed or moved item (and everything under it, for a folder) in the cached
     * tree. Rename/move used to [invalidateTree], so the listing right after re-downloaded the
     * whole account and kept showing the old name for many seconds on a large Dropbox. */
    fun patchTreeAfterMove(accountId: String, fromPath: String, toPath: String) {
        val cached = cachedTree(accountId) ?: return
        val from = fromPath.trimEnd('/')
        val to = toPath.trimEnd('/')
        val updated = cached.entries.map { e ->
            when {
                e.path.equals(from, ignoreCase = true) ->
                    e.copy(path = to, parentPath = to.substringBeforeLast('/', ""), name = to.substringAfterLast('/'))
                e.path.startsWith("$from/", ignoreCase = true) -> {
                    val newPath = to + e.path.substring(from.length)
                    e.copy(path = newPath, parentPath = newPath.substringBeforeLast('/', ""))
                }
                else -> e
            }
        }
        val newCache = cached.copy(entries = updated, timestamp = System.currentTimeMillis())
        treeCache[accountId] = newCache
        persistTreeToDisk(accountId, newCache)
    }

    /** Removes one deleted item from the cached tree in place instead of the full [invalidateTree]
     * wipe deleteItem used to always do. Overwriting an existing file deletes it first (see
     * FileUseCases.uploadFiles' conflict handling) — on a folder move/copy re-run against a
     * destination that already has matching files from an earlier attempt, that meant nearly
     * every file paid for a full account-tree re-fetch just to delete the one file it was about
     * to replace anyway. Also drops any entries nested under this path, for a deleted folder. */
    fun patchTreeAfterDelete(accountId: String, path: String) {
        val cached = cachedTree(accountId) ?: run {
            android.util.Log.d("DropboxApiClient", "patchTreeAfterDelete: no cache yet for $accountId, nothing to patch (path='$path')")
            return
        }
        val normalized = path.trimEnd('/')
        val updated = cached.entries.filterNot { it.path.equals(normalized, ignoreCase = true) || it.path.startsWith("$normalized/", ignoreCase = true) }
        android.util.Log.d("DropboxApiClient", "patchTreeAfterDelete: path='$normalized' removed ${cached.entries.size - updated.size} entries (${cached.entries.size} -> ${updated.size})")
        val newCache = cached.copy(entries = updated, timestamp = System.currentTimeMillis())
        treeCache[accountId] = newCache
        persistTreeToDisk(accountId, newCache)
    }

    /** Manual pull-to-refresh's actual work for Dropbox: a plain, non-recursive list_folder(path)
     * for exactly this one folder, patched into the cached tree by replacing every entry whose
     * parent is exactly this path with what the server just returned — everywhere else in the
     * cached tree (every other folder) is left untouched. This is what lets a manual refresh stay
     * a single lightweight call instead of the account-wide list_folder(recursive=true) rebuild a
     * full [invalidateTree] would force on the next listing.
     *
     * The tradeoffs of scoping it this way: subfolders revealed by this refresh come back with
     * itemCount=0 like any plain (non-tree) listing does — the caller's own bounded per-folder
     * fan-out (see CloudExplorerViewModel.fetchFolderItemCounts) is what backfills those, same as
     * it already does for Google Drive. And a change made to some OTHER folder around the same
     * time (another device, or this account open elsewhere) won't show up here until that other
     * folder is itself listed/refreshed — a full tree rebuild would have caught it everywhere at
     * once, this deliberately does not.
     *
     * No-ops (leaving the next listing to do a full fetch as usual) if nothing is cached yet —
     * patching a folder into an empty/nonexistent tree wouldn't leave anything usable behind. */
    suspend fun refreshFolderShallow(account: CloudAccount, path: String): Result<Unit> = withContext(Dispatchers.IO) {
        val cached = cachedTree(account.id) ?: return@withContext Result.success(Unit)
        val normalizedPath = if (path == "/" || path.isBlank()) "" else path.trimEnd('/')
        val freshItems = listFolder(account, path).getOrElse { return@withContext Result.failure(it) }
        val freshEntries = freshItems.map { item ->
            DropboxEntry(item.path, normalizedPath, item.name, item.isDirectory, item.size, item.lastModified, item.id)
        }
        val kept = cached.entries.filterNot { it.parentPath.equals(normalizedPath, ignoreCase = true) }
        val newCache = cached.copy(entries = kept + freshEntries, timestamp = System.currentTimeMillis())
        treeCache[account.id] = newCache
        persistTreeToDisk(account.id, newCache)
        Result.success(Unit)
    }

    private fun isFreshAndNonEmpty(cache: TreeCache): Boolean =
        cache.entries.isNotEmpty() && System.currentTimeMillis() - cache.timestamp <= treeTtlMillis

    // When each account's tree was last brought up to date with the server (see [syncDelta]).
    private val lastDeltaSync = ConcurrentHashMap<String, Long>()
    private val deltaSyncIntervalMillis = 30_000L

    private fun deltaDue(accountId: String): Boolean =
        System.currentTimeMillis() - (lastDeltaSync[accountId] ?: 0L) > deltaSyncIntervalMillis

    private suspend fun getOrFetchTree(account: CloudAccount): Result<List<DropboxEntry>> {
        treeCache[account.id]?.let { cached ->
            // A tree that was ever successfully cached as EMPTY (e.g. one bad fetch mid-account-
            // migration, or a token that was valid but scoped to zero content at that instant) used
            // to get served back silently forever after — no log, no retry, indistinguishable from
            // a genuinely empty Dropbox account. Treat an empty cached tree as not-actually-cached
            // so it gets one real re-fetch instead of being trusted permanently. A tree past
            // treeTtlMillis is treated the same way — old enough it should just re-fetch.
            if (isFreshAndNonEmpty(cached) && !deltaDue(account.id)) {
                return Result.success(cached.entries)
            }
        }
        return treeMutexFor(account.id).withLock {
            // In-memory cache is cold (first call this process, or it just expired) — try the
            // on-disk copy before paying for a full recursive network re-fetch. This is what
            // makes search/browsing instant again right after reopening the app instead of
            // rebuilding the whole tree from scratch every time, same as before the app was killed.
            var cache = treeCache[account.id]?.takeIf { isFreshAndNonEmpty(it) }
                ?: loadTreeFromDisk(account.id)?.also {
                    android.util.Log.d("DropboxApiClient", "getOrFetchTree: loaded ${it.entries.size} entries from disk cache for ${account.id}")
                    treeCache[account.id] = it
                }
            // The cached tree only knew about this app's own changes, so anything done from another
            // device or dropbox.com stayed invisible for up to a day. Catch up with just what
            // changed since the tree was fetched, at most every [deltaSyncIntervalMillis].
            if (cache != null && deltaDue(account.id)) {
                val synced = syncDelta(account, cache)
                synced.onSuccess { cache = it }
                    .onFailure { e ->
                        if (e is com.dropbox.core.v2.files.ListFolderContinueErrorException && e.errorValue.isReset) {
                            android.util.Log.d("DropboxApiClient", "getOrFetchTree: cursor reset for ${account.id}, re-fetching the whole tree")
                            cache = null
                        } else {
                            android.util.Log.w("DropboxApiClient", "getOrFetchTree: delta sync failed for ${account.id}, keeping the cached tree", e)
                        }
                    }
                lastDeltaSync[account.id] = System.currentTimeMillis()
            }
            cache?.let { return@withLock Result.success(it.entries) }
            android.util.Log.d("DropboxApiClient", "getOrFetchTree: cache MISS — fetching fresh tree from network for ${account.id}")
            val fetched = fetchTreeFromNetwork(account)
            fetched.map { newCache ->
                android.util.Log.d("DropboxApiClient", "getOrFetchTree: fetch completed with ${newCache.entries.size} entries for ${account.id}")
                treeCache[account.id] = newCache
                persistTreeToDisk(account.id, newCache)
                lastDeltaSync[account.id] = System.currentTimeMillis()
                newCache.entries
            }
        }
    }

    private fun entryOf(meta: Metadata): DropboxEntry? {
        val p = meta.pathDisplay ?: return null
        val parent = p.substringBeforeLast('/', "")
        return when (meta) {
            is FolderMetadata -> DropboxEntry(p, parent, meta.name, true, 0L, 0L, meta.id)
            is FileMetadata -> DropboxEntry(p, parent, meta.name, false, meta.size, meta.serverModified.time, meta.id)
            else -> null
        }
    }

    private fun fetchTreeFromNetwork(account: CloudAccount): Result<TreeCache> {
        return try {
            val client = buildClient(account)
            val entries = mutableListOf<DropboxEntry>()
            var result = client.files().listFolderBuilder("").withRecursive(true).start()
            result.entries.mapNotNullTo(entries, ::entryOf)
            while (result.hasMore) {
                result = client.files().listFolderContinue(result.cursor)
                result.entries.mapNotNullTo(entries, ::entryOf)
            }
            android.util.Log.d("DropboxApiClient", "fetchTreeFromNetwork: ${entries.size} total entries")
            Result.success(TreeCache(entries, System.currentTimeMillis(), result.cursor))
        } catch (e: Exception) {
            android.util.Log.e("DropboxApiClient", "fetchTreeFromNetwork: FAILED", e)
            Result.failure(e)
        }
    }

    /** Applies every change since [cache]'s cursor (list_folder/continue returns only those) and
     * stores the result. A tree without a cursor (cached by an older version) is fetched anew. */
    private fun syncDelta(account: CloudAccount, cache: TreeCache): Result<TreeCache> {
        val cursor = cache.cursor ?: return fetchTreeFromNetwork(account).onSuccess {
            treeCache[account.id] = it
            persistTreeToDisk(account.id, it)
        }
        return try {
            val client = buildClient(account)
            val byPath = LinkedHashMap<String, DropboxEntry>()
            cache.entries.forEach { byPath[it.path.lowercase()] = it }
            var changed = 0
            var result = client.files().listFolderContinue(cursor)
            while (true) {
                for (meta in result.entries) {
                    changed++
                    if (meta is DeletedMetadata) {
                        val gone = (meta.pathLower ?: meta.pathDisplay?.lowercase() ?: continue)
                        byPath.keys.removeAll { it == gone || it.startsWith("$gone/") }
                    } else {
                        entryOf(meta)?.let { byPath[it.path.lowercase()] = it }
                    }
                }
                if (!result.hasMore) break
                result = client.files().listFolderContinue(result.cursor)
            }
            if (changed == 0 && result.cursor == cursor) return Result.success(cache)
            android.util.Log.d("DropboxApiClient", "syncDelta: applied $changed change(s) for ${account.id}")
            val updated = TreeCache(byPath.values.toList(), cache.timestamp, result.cursor)
            treeCache[account.id] = updated
            persistTreeToDisk(account.id, updated)
            Result.success(updated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Lists the direct children of [path]. When the whole-account tree is already cached and
     * fresh, this is free (in-memory slice) and subfolders get an accurate itemCount for free
     * too. When it's NOT cached/fresh, [allowFullTreeFetch] decides what happens: true fetches
     * the whole account via list_folder(recursive=true) same as before; false — the default —
     * instead does a plain single-folder list_folder(path) call, so ordinary navigation and
     * automatic refreshes (after paste/delete/rename) never pay for a whole-account listing just
     * to redraw one folder. Subfolder item counts from that path come back as 0 and are backfilled
     * by the bounded per-folder fetchFolderItemCounts fan-out, same as Google Drive already does.
     * Callers should only pass true for an explicit user-triggered refresh at the account root. */
    suspend fun listFolderCached(account: CloudAccount, path: String = "", allowFullTreeFetch: Boolean = false): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            val isFresh = treeCache[account.id] != null
            if (!isFresh && !allowFullTreeFetch) {
                return@withContext listFolder(account, path)
            }
            val entries = getOrFetchTree(account).getOrElse { return@withContext Result.failure(it) }
            // Dropbox paths are case-insensitive, and path_display casing is not guaranteed to be
            // consistent between a folder and its children — match on lowercase keys, or a folder
            // whose children report a different casing lists as empty.
            val normalizedPath = (if (path == "/" || path.isBlank()) "" else path.trimEnd('/')).lowercase()
            val childrenByParent = entries.groupBy { it.parentPath.lowercase() }
            val direct = childrenByParent[normalizedPath] ?: emptyList()
            // Dropbox's API never gives a folder its own modified-time, so use the newest file
            // modified anywhere underneath it (like Dropbox's own desktop app). Computed in one
            // pass over the tree by propagating each file's date to its ancestors — a per-folder
            // scan of the whole account tree was O(folders × entries) per listing.
            val newestUnder = HashMap<String, Long>()
            for (e in entries) {
                if (e.isDirectory) continue
                var parent = e.parentPath.lowercase()
                while (parent.isNotEmpty()) {
                    val current = newestUnder[parent]
                    if (current != null && current >= e.lastModified) break
                    newestUnder[parent] = e.lastModified
                    parent = parent.substringBeforeLast('/', "")
                }
            }
            val items = direct.map { entry ->
                val children = if (entry.isDirectory) childrenByParent[entry.path.lowercase()] else null
                val subfolders = children?.count { it.isDirectory } ?: 0
                val childFiles = children?.count { !it.isDirectory } ?: 0
                val effectiveLastModified = if (entry.isDirectory) newestUnder[entry.path.lowercase()] ?: 0L else entry.lastModified
                FileItem(
                    id = entry.id,
                    name = entry.name,
                    path = entry.path,
                    size = entry.size,
                    lastModified = effectiveLastModified,
                    isDirectory = entry.isDirectory,
                    itemCount = subfolders + childFiles,
                    subfolderCount = subfolders,
                    fileChildCount = childFiles,
                    extension = if (!entry.isDirectory) entry.name.substringAfterLast(".", "") else ""
                )
            }
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // The SDK's default HTTP requestor has no connect/read timeout at all — on a stalled or very
    // poor connection, any call (delete, list, upload...) can block the calling coroutine
    // indefinitely. That call always runs inside withContext(Dispatchers.IO), so it never blocks
    // the main thread directly, but the screen making the call is left showing an un-dismissable
    // "in progress" modal (by design, so a real transfer can't be walked away from mid-copy) with
    // no way out until the call finally gives up — which, with no timeout, means never. From the
    // user's side that reads as the whole app being frozen, forcing a force-quit. A bounded
    // OkHttp client here means every Dropbox call fails with a clear timeout error instead.
    private val dropboxHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private fun buildClient(account: CloudAccount): DbxClientV2 {
        val requestConfig = DbxRequestConfig.newBuilder("FileManagerPlus/1.0")
            .withHttpRequestor(com.dropbox.core.http.OkHttp3Requestor(dropboxHttpClient))
            .build()
        val accessToken = account.accessToken.orEmpty()
        val refreshToken = account.refreshToken
        val expiresAt = account.sessionHandle?.toLongOrNull()
        if (accessToken.isBlank()) {
            android.util.Log.e("DropboxApiClient", "buildClient: accessToken is BLANK for account ${account.id} — every call will fail auth")
        }
        val credential = DbxCredential(accessToken, expiresAt, refreshToken, DropboxAuthManager.APP_KEY)
        return DbxClientV2(requestConfig, credential)
    }

    suspend fun listFolder(account: CloudAccount, path: String = ""): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("DropboxApiClient", "listFolder: path='$path' accountId=${account.id}")
            val client = buildClient(account)
            val normalizedPath = if (path == "/" || path.isBlank()) "" else path
            var result = client.files().listFolder(normalizedPath)
            android.util.Log.d("DropboxApiClient", "listFolder: got ${result.entries.size} entries, hasMore=${result.hasMore}")
            val entries = result.entries.toMutableList()
            while (result.hasMore) {
                result = client.files().listFolderContinue(result.cursor)
                entries.addAll(result.entries)
            }

            val items = entries.map { metadata ->
                when (metadata) {
                    is FolderMetadata -> FileItem(
                        id = metadata.id,
                        name = metadata.name,
                        path = metadata.pathDisplay ?: "/${metadata.name}",
                        isDirectory = true,
                        itemCount = 0
                    )
                    is FileMetadata -> FileItem(
                        id = metadata.id,
                        name = metadata.name,
                        path = metadata.pathDisplay ?: "/${metadata.name}",
                        size = metadata.size,
                        lastModified = metadata.serverModified.time,
                        isDirectory = false,
                        extension = metadata.name.substringAfterLast(".", "")
                    )
                    else -> FileItem(id = metadata.name, name = metadata.name, path = "/${metadata.name}")
                }
            }
            Result.success(items)
        } catch (e: Exception) {
            android.util.Log.e("DropboxApiClient", "listFolder: FAILED for path='$path'", e)
            Result.failure(e)
        }
    }

    /** A pre-signed, unauthenticated HTTPS URL good for a few hours — supports HTTP Range
     * requests, so MediaMetadataRetriever can seek and decode a single frame without pulling
     * down the whole file (unlike [downloadFile], which streams the entire body to disk). */
    suspend fun getTemporaryLink(account: CloudAccount, path: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val client = buildClient(account)
            val targetPath = if (path.startsWith("/")) path else "/$path"
            val result = client.files().getTemporaryLink(targetPath)
            Result.success(result.link)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSpaceUsage(account: CloudAccount): Result<Pair<Long, Long>> = withContext(Dispatchers.IO) {
        try {
            val client = buildClient(account)
            val usage = client.users().spaceUsage
            val used = usage.used
            val allocation = usage.allocation
            val allocated = allocation.individualValue?.allocated
                ?: allocation.teamValue?.allocated // Business accounts share a team pool
                ?: (2L * 1024 * 1024 * 1024)
            Result.success(Pair(allocated, used))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadFile(
        account: CloudAccount,
        remotePath: String,
        localTargetDir: String,
        fileName: String,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        val targetFile = File(localTargetDir, fileName)
        try {
            val client = buildClient(account)
            val path = if (remotePath.startsWith("/")) remotePath else "/$remotePath"
            targetFile.parentFile?.mkdirs()
            if (targetFile.exists()) {
                targetFile.delete()
            }
            val downloader = client.files().download(path)
            val totalBytes = downloader.result.size
            FileOutputStream(targetFile).use { output ->
                downloader.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        output.write(buffer, 0, read)
                        bytesRead += read
                        onProgress?.invoke(bytesRead, totalBytes)
                    }
                }
            }
            onProgress?.invoke(targetFile.length(), targetFile.length())
            Result.success(targetFile)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                targetFile.delete()
                throw e
            }
            // Unlike listFolder/uploadFile just above, this had no logging at all — a failed
            // download (bad path, expired token, network drop mid-transfer, ...) surfaced as
            // Result.failure with zero trace in logcat, and the paste-to-local flow that calls
            // this (DashboardViewModel.doPasteCloud) didn't check isFailure either, so the whole
            // thing looked like it silently did nothing.
            android.util.Log.e("DropboxApiClient", "downloadFile: FAILED for remotePath='$remotePath'", e)
            targetFile.delete()
            Result.failure(e)
        }
    }

    suspend fun uploadFile(
        account: CloudAccount,
        localFile: File,
        remoteDirPath: String,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val parentJob = coroutineContext[Job]
        val client = buildClient(account)
        val targetPath = if (remoteDirPath == "/" || remoteDirPath.isBlank()) "/${localFile.name}" else "${remoteDirPath.trimEnd('/')}/${localFile.name}"
        val totalBytes = localFile.length()

        // Dropbox rate-limits bursts of parallel requests (429 RateLimitException) — expected
        // when several files upload concurrently (see the paste-flow's Semaphore(8)). Its response
        // tells us exactly how long to back off, so retry instead of counting a throttled request
        // as a real failure; a genuine error (auth, network, quota) still fails immediately since
        // it's a different exception type.
        var attempt = 0
        while (true) {
            attempt++
            android.util.Log.d("DropboxApiClient", "uploadFile: remoteDirPath='$remoteDirPath' -> targetPath='$targetPath', size=$totalBytes, attempt=$attempt")
            val progressStream = object : java.io.FilterInputStream(FileInputStream(localFile)) {
                var bytesSent = 0L
                override fun read(): Int {
                    if (parentJob?.isActive == false) throw java.io.IOException("Upload cancelled")
                    val b = super.read()
                    if (b != -1) {
                        bytesSent++
                        onProgress?.invoke(bytesSent, totalBytes)
                    }
                    return b
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (parentJob?.isActive == false) throw java.io.IOException("Upload cancelled")
                    val read = super.read(b, off, len)
                    if (read != -1) {
                        bytesSent += read
                        onProgress?.invoke(bytesSent, totalBytes)
                    }
                    return read
                }
            }
            try {
                val metadata = progressStream.use { input ->
                    if (totalBytes <= SINGLE_UPLOAD_LIMIT) {
                        client.files().uploadBuilder(targetPath)
                            .withMode(WriteMode.OVERWRITE)
                            .withAutorename(false)
                            .withMute(false)
                            .uploadAndFinish(input)
                    } else {
                        // /upload rejects anything over 150 MB outright; bigger files have to go
                        // through an upload session, one chunk per request.
                        val sessionId = client.files().uploadSessionStart().uploadAndFinish(input, UPLOAD_CHUNK_SIZE).sessionId
                        var offset = UPLOAD_CHUNK_SIZE
                        while (totalBytes - offset > UPLOAD_CHUNK_SIZE) {
                            client.files().uploadSessionAppendV2(UploadSessionCursor(sessionId, offset)).uploadAndFinish(input, UPLOAD_CHUNK_SIZE)
                            offset += UPLOAD_CHUNK_SIZE
                        }
                        val commit = CommitInfo.newBuilder(targetPath)
                            .withMode(WriteMode.OVERWRITE)
                            .withAutorename(false)
                            .withMute(false)
                            .build()
                        client.files().uploadSessionFinish(UploadSessionCursor(sessionId, offset), commit)
                            .uploadAndFinish(input, totalBytes - offset)
                    }
                }
                onProgress?.invoke(totalBytes, totalBytes)
                android.util.Log.d("DropboxApiClient", "uploadFile: success, pathDisplay=${metadata.pathDisplay}")
                patchTreeAfterUpload(account.id, metadata)
                return@withContext Result.success(metadata.pathDisplay ?: targetPath)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is com.dropbox.core.RateLimitException && attempt < 8) {
                    // 4 attempts wasn't always enough under sustained load (8 files uploading in
                    // parallel can keep tripping the rate limit for several rounds in a row on a
                    // big batch) — a few files were still failing outright once retries ran out.
                    // Still bounded, still honors Dropbox's own advertised backoff per attempt.
                    val backoffMs = (e.backoffMillis).coerceIn(1000L, 30_000L)
                    android.util.Log.d("DropboxApiClient", "uploadFile: rate-limited for '${localFile.name}', retrying in ${backoffMs}ms (attempt $attempt)")
                    kotlinx.coroutines.delay(backoffMs)
                    continue
                }
                android.util.Log.e("DropboxApiClient", "uploadFile: FAILED for remoteDirPath='$remoteDirPath', file='${localFile.name}'", e)
                return@withContext Result.failure(e)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        Result.failure(Exception("unreachable"))
    }

    /** Runs [block], retrying on Dropbox's 429 with the backoff it asks for. Uploads and permanent
     * deletes already did this; create/delete/move/rename didn't, and those run 8 at a time in a
     * folder copy or move, so a burst failed items that would have gone through a second later. */
    private suspend fun <T> retryOnRateLimit(label: String, block: () -> T): T {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return block()
            } catch (e: com.dropbox.core.RateLimitException) {
                if (attempt >= 8) throw e
                val backoffMs = e.backoffMillis.coerceIn(1000L, 30_000L)
                android.util.Log.d("DropboxApiClient", "$label: rate-limited, retrying in ${backoffMs}ms (attempt $attempt)")
                kotlinx.coroutines.delay(backoffMs)
            }
        }
    }

    suspend fun createFolder(account: CloudAccount, path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val client = buildClient(account)
            val targetPath = if (path.startsWith("/")) path else "/$path"
            val result = retryOnRateLimit("createFolder") { client.files().createFolderV2(targetPath) }
            val metadata = result.metadata
            Result.success(
                FileItem(
                    id = metadata.id,
                    name = metadata.name,
                    path = metadata.pathDisplay ?: targetPath,
                    isDirectory = true
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteFile(account: CloudAccount, path: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val client = buildClient(account)
            val targetPath = if (path.startsWith("/")) path else "/$path"
            retryOnRateLimit("delete") { client.files().deleteV2(targetPath) }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun delete(account: CloudAccount, path: String): Result<Unit> = deleteFile(account, path)

    /** Every deleted item Dropbox is still holding a recoverable copy of, account-wide — unlike
     * MEGA/Drive, Dropbox has no distinct "Trash" location; a deleted item just gets flagged
     * ".tag":"deleted" wherever it used to live, so finding all of them means walking the whole
     * account with recursive+includeDeleted, the same shape as the existing whole-tree fetch used
     * for the normal listing cache. Dropbox itself expires these automatically after ~30 days
     * (or per the account's own retention setting) — this only surfaces what Dropbox is still
     * holding right now, nothing this app controls. */
    suspend fun listTrash(account: CloudAccount, fullScan: Boolean = false): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            val client = buildClient(account)
            val deleted = mutableListOf<DeletedMetadata>()
            var result = client.files().listFolderBuilder("").withRecursive(true).withIncludeDeleted(true).start()
            fun collect(metas: List<Metadata>) {
                for (meta in metas) {
                    if (meta is DeletedMetadata) deleted.add(meta)
                }
            }
            collect(result.entries)
            android.util.Log.d("DropboxApiClient", "listTrash: page 0 -> ${result.entries.size} entries, hasMore=${result.hasMore}, fullScan=$fullScan")
            // Some Dropbox accounts genuinely have tens of thousands of deleted entries piled up
            // over years (one seen on-device hit 37,000+ and was still paging with hasMore=true) —
            // walking all of it isn't a bug, it's real data, but it made Trash take minutes to open
            // and burned a full page of metadata in memory per request the whole time. Capping at
            // the most recent ~1k entries keeps a plain "open Trash" view to a couple requests.
            // BUT Dropbox's list_folder doesn't order entries by deletion time at all, so the item
            // someone just deleted can easily land past that cap — capping a SEARCH (fullScan=true,
            // see CloudExplorerViewModel's recursive search) would make it silently miss the exact
            // thing the user is looking for, which defeats the point of searching Trash at all.
            var page = 0
            val maxDeletedEntries = 1_000
            while (result.hasMore && (fullScan || deleted.size < maxDeletedEntries)) {
                currentCoroutineContext().ensureActive()
                page++
                val prevCursor = result.cursor
                result = client.files().listFolderContinue(result.cursor)
                collect(result.entries)
                val uniqueSoFar = deleted.distinctBy { it.pathDisplay ?: it.name }.size
                android.util.Log.d("DropboxApiClient", "listTrash: page $page -> ${result.entries.size} entries (total deleted=${deleted.size}, unique=$uniqueSoFar), hasMore=${result.hasMore}, cursorChanged=${prevCursor != result.cursor}")
            }
            if (result.hasMore) {
                android.util.Log.d("DropboxApiClient", "listTrash: stopped at ${deleted.size} entries (cap $maxDeletedEntries) — account has more, not fetching the rest")
            }
            Result.success(
                deleted.map { meta ->
                    val path = meta.pathDisplay ?: "/${meta.name}"
                    FileItem(
                        id = path,
                        name = meta.name,
                        path = path,
                        // DeletedMetadata carries no type/size info at all — Dropbox doesn't say
                        // whether a deleted entry was a file or folder, or how big it was.
                        isDirectory = false,
                        extension = meta.name.substringAfterLast(".", "")
                    )
                }
            )
        } catch (e: Exception) {
            android.util.Log.e("DropboxApiClient", "listTrash failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** Restores the most recently deleted revision at [path] back to that same path. Dropbox's
     * restore API needs a specific revision id, not just a path — DeletedMetadata (what listTrash
     * returns) doesn't carry one, so this looks it up via list_revisions first. */
    suspend fun restoreFile(account: CloudAccount, path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val client = buildClient(account)
            val targetPath = if (path.startsWith("/")) path else "/$path"
            val revisions = client.files().listRevisionsBuilder(targetPath).withMode(ListRevisionsMode.PATH).start()
            val latestRev = revisions.entries.firstOrNull()?.rev
                ?: return@withContext Result.failure(Exception("No recoverable revision found for '$targetPath'"))
            val metadata = client.files().restore(targetPath, latestRev)
            Result.success(
                FileItem(
                    id = metadata.id,
                    name = metadata.name,
                    path = metadata.pathDisplay ?: targetPath,
                    isDirectory = false,
                    size = metadata.size,
                    extension = metadata.name.substringAfterLast(".", "")
                )
            )
        } catch (e: Exception) {
            android.util.Log.e("DropboxApiClient", "restoreFile failed for '$path': ${e.message}", e)
            Result.failure(e)
        }
    }

    /** Purges a deleted item immediately instead of waiting out Dropbox's own retention window.
     * Note: Dropbox's permanently_delete endpoint requires the account to have extended-deletion
     * capability (Business/Team accounts with the right admin setting) — on a plain personal
     * account this call itself fails; there is no app-side workaround for that. */
    suspend fun permanentlyDelete(account: CloudAccount, path: String): Result<Unit> = withContext(Dispatchers.IO) {
        val client = buildClient(account)
        val targetPath = if (path.startsWith("/")) path else "/$path"
        // Same rate-limit retry as uploadFile — permanently_delete gets hit especially hard by
        // Empty Trash firing 8 of these in parallel (see deleteSelected's Semaphore(8)), and
        // without a retry here almost every one of them came back RateLimitException instead of
        // actually deleting anything: the item stayed in Trash with no clear reason why.
        var attempt = 0
        while (true) {
            attempt++
            try {
                client.files().permanentlyDelete(targetPath)
                return@withContext Result.success(Unit)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is com.dropbox.core.RateLimitException && attempt < 8) {
                    val backoffMs = (e.backoffMillis).coerceIn(1000L, 30_000L)
                    android.util.Log.d("DropboxApiClient", "permanentlyDelete: rate-limited for '$path', retrying in ${backoffMs}ms (attempt $attempt)")
                    kotlinx.coroutines.delay(backoffMs)
                    continue
                }
                android.util.Log.e("DropboxApiClient", "permanentlyDelete failed for '$path': ${e.message}", e)
                return@withContext Result.failure(e)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        Result.failure(Exception("unreachable"))
    }

    /** Relocates an item to a different folder in the SAME account entirely server-side — no
     * data ever passes through this device, unlike the generic cloud-to-cloud paste flow's
     * download-then-upload round trip (which exists only because there's no such API when the
     * source and destination are different providers/accounts). Dropbox's path-based moveV2 does
     * this identically to a rename; only the target path's directory differs. */
    suspend fun moveItem(account: CloudAccount, fromPath: String, toDir: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val client = buildClient(account)
            val normalizedFrom = if (fromPath.startsWith("/")) fromPath else "/$fromPath"
            val name = normalizedFrom.substringAfterLast("/")
            val normalizedToDir = if (toDir == "/" || toDir.isBlank()) "" else if (toDir.startsWith("/")) toDir.trimEnd('/') else "/${toDir.trimEnd('/')}"
            val toPath = "$normalizedToDir/$name"
            val metadata = retryOnRateLimit("move") { client.files().moveV2(normalizedFrom, toPath) }.metadata
            val id = when (metadata) {
                is FolderMetadata -> metadata.id
                is FileMetadata -> metadata.id
                else -> metadata.name
            }
            Result.success(
                FileItem(
                    id = id,
                    name = metadata.name,
                    path = metadata.pathDisplay ?: toPath,
                    isDirectory = metadata is FolderMetadata,
                    size = (metadata as? FileMetadata)?.size ?: 0L,
                    extension = if (metadata is FileMetadata) metadata.name.substringAfterLast(".", "") else ""
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun renameFile(account: CloudAccount, path: String, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val client = buildClient(account)
            val fromPath = if (path.startsWith("/")) path else "/$path"
            val parentPath = fromPath.substringBeforeLast("/", "")
            val toPath = if (parentPath.isEmpty()) "/$newName" else "$parentPath/$newName"
            val metadata = retryOnRateLimit("rename") { client.files().moveV2(fromPath, toPath) }.metadata
            val id = when (metadata) {
                is FolderMetadata -> metadata.id
                is FileMetadata -> metadata.id
                else -> metadata.name
            }
            Result.success(
                FileItem(
                    id = id,
                    name = metadata.name,
                    path = metadata.pathDisplay ?: toPath,
                    isDirectory = metadata is FolderMetadata,
                    size = (metadata as? FileMetadata)?.size ?: 0L,
                    extension = if (metadata is FileMetadata) metadata.name.substringAfterLast(".", "") else ""
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
