package com.antigravity.filemanager.data.remote.cloud.api

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

// Dropbox API v2 client using official dropbox-core-sdk with OAuth2/PKCE tokens.
@Singleton
class DropboxApiClient @Inject constructor() {

    // Dropbox's list_folder has no "children of X with descendant counts" mode — like MEGA,
    // the only way to avoid an N+1 fan-out (one API call per subfolder just to get its item
    // count) is to fetch the WHOLE account tree once via recursive=true and slice it in memory.
    // Cached per account indefinitely — it never expires on its own, only [invalidateTree] (a
    // manual refresh at the account root, or after a mutation) forces the next call to re-fetch.
    private data class DropboxEntry(
        val path: String,
        val parentPath: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
        val id: String
    )
    private data class TreeCache(val entries: List<DropboxEntry>, val timestamp: Long)
    private val treeCache = ConcurrentHashMap<String, TreeCache>()
    private val treeMutexes = ConcurrentHashMap<String, Mutex>()

    private fun treeMutexFor(accountId: String): Mutex = treeMutexes.getOrPut(accountId) { Mutex() }

    /** Call after any mutation (create/delete/rename) so the next listing re-fetches. */
    fun invalidateTree(accountId: String) {
        treeCache.remove(accountId)
    }

    /** Patches a freshly uploaded file into the cached tree in place, so the folder listing that
     * follows an upload reflects it immediately without forcing a full recursive re-fetch of the
     * WHOLE account (that fetch is what made "copy to Dropbox" feel like it hung/timed out —
     * upload one file, then wait on a full-tree listFolder(recursive=true) just to redraw one
     * folder). No-ops if nothing is cached yet; the next listFolderCached() will fetch fresh anyway. */
    private fun patchTreeAfterUpload(accountId: String, metadata: FileMetadata) {
        val cached = treeCache[accountId] ?: return
        val path = metadata.pathDisplay ?: return
        val parent = path.substringBeforeLast('/', "")
        val entry = DropboxEntry(path, parent, metadata.name, false, metadata.size, metadata.serverModified.time, metadata.id)
        // WriteMode.OVERWRITE means a re-upload of an existing name reuses the same path, so drop
        // any stale entry for that path before adding the fresh one.
        val updated = cached.entries.filterNot { it.path == path } + entry
        // Renew the freshness timestamp too, not just the contents — otherwise the cache keeps
        // counting down from whenever it was first fetched regardless of being patched, and a
        // listing shortly after several patches can still land past the original TTL and pay for
        // a full recursive re-fetch anyway, which is exactly what an upload here should avoid.
        treeCache[accountId] = TreeCache(updated, System.currentTimeMillis())
    }

    /** Same idea as [patchTreeAfterUpload] but for a freshly created folder — a recursive
     * cloud-to-cloud folder copy calls createFolder once per subfolder, and each one used to
     * call [invalidateTree] and throw away the whole cached account tree, forcing every listing
     * right after (including this same copy's own per-folder conflict checks) to pay for a full
     * recursive re-fetch again. Patching in place keeps the "build the tree once, reuse it"
     * cache actually holding across a multi-folder copy instead of restarting on every folder. */
    fun patchTreeAfterFolderCreate(accountId: String, path: String, id: String) {
        val cached = treeCache[accountId] ?: return
        val parent = path.trimEnd('/').substringBeforeLast('/', "")
        val name = path.trimEnd('/').substringAfterLast('/')
        // lastModified=0L to match every real folder entry (fetchTreeFromNetwork always sets 0L
        // for FolderMetadata — Dropbox's API just doesn't expose a modified-time for folders).
        // This used to be "now", which put a freshly created folder wildly out of position
        // whenever the list was sorted by date, since every other folder sits at epoch 0.
        val entry = DropboxEntry(path, parent, name, true, 0L, 0L, id)
        val updated = cached.entries.filterNot { it.path == path } + entry
        treeCache[accountId] = TreeCache(updated, System.currentTimeMillis())
    }

    /** Removes one deleted item from the cached tree in place instead of the full [invalidateTree]
     * wipe deleteItem used to always do. Overwriting an existing file deletes it first (see
     * FileUseCases.uploadFiles' conflict handling) — on a folder move/copy re-run against a
     * destination that already has matching files from an earlier attempt, that meant nearly
     * every file paid for a full account-tree re-fetch just to delete the one file it was about
     * to replace anyway. Also drops any entries nested under this path, for a deleted folder. */
    fun patchTreeAfterDelete(accountId: String, path: String) {
        val cached = treeCache[accountId] ?: run {
            android.util.Log.d("DropboxApiClient", "patchTreeAfterDelete: no cache yet for $accountId, nothing to patch (path='$path')")
            return
        }
        val normalized = path.trimEnd('/')
        val updated = cached.entries.filterNot { it.path == normalized || it.path.startsWith("$normalized/") }
        android.util.Log.d("DropboxApiClient", "patchTreeAfterDelete: path='$normalized' removed ${cached.entries.size - updated.size} entries (${cached.entries.size} -> ${updated.size})")
        treeCache[accountId] = TreeCache(updated, System.currentTimeMillis())
    }

    private suspend fun getOrFetchTree(account: CloudAccount): Result<List<DropboxEntry>> {
        treeCache[account.id]?.let { cached ->
            // A tree that was ever successfully cached as EMPTY (e.g. one bad fetch mid-account-
            // migration, or a token that was valid but scoped to zero content at that instant) used
            // to get served back silently forever after — no log, no retry, indistinguishable from
            // a genuinely empty Dropbox account. Treat an empty cached tree as not-actually-cached
            // so it gets one real re-fetch instead of being trusted permanently.
            if (cached.entries.isNotEmpty()) {
                return Result.success(cached.entries)
            }
            android.util.Log.d("DropboxApiClient", "getOrFetchTree: cached tree for ${account.id} is EMPTY — treating as stale, re-fetching")
        }
        return treeMutexFor(account.id).withLock {
            treeCache[account.id]?.let { recheck ->
                if (recheck.entries.isNotEmpty()) {
                    return@withLock Result.success(recheck.entries)
                }
            }
            android.util.Log.d("DropboxApiClient", "getOrFetchTree: cache MISS — fetching fresh tree from network for ${account.id}")
            val fetched = fetchTreeFromNetwork(account)
            fetched.onSuccess { entries ->
                android.util.Log.d("DropboxApiClient", "getOrFetchTree: fetch completed with ${entries.size} entries for ${account.id}")
                treeCache[account.id] = TreeCache(entries, System.currentTimeMillis())
            }
            fetched
        }
    }

    private fun fetchTreeFromNetwork(account: CloudAccount): Result<List<DropboxEntry>> {
        return try {
            val client = buildClient(account)
            val entries = mutableListOf<DropboxEntry>()
            fun addAll(metas: List<Metadata>) {
                for (meta in metas) {
                    val p = meta.pathDisplay ?: continue
                    val parent = p.substringBeforeLast('/', "")
                    when (meta) {
                        is FolderMetadata -> entries.add(DropboxEntry(p, parent, meta.name, true, 0L, 0L, meta.id))
                        is FileMetadata -> entries.add(DropboxEntry(p, parent, meta.name, false, meta.size, meta.serverModified.time, meta.id))
                    }
                }
            }
            var result = client.files().listFolderBuilder("").withRecursive(true).start()
            addAll(result.entries)
            while (result.hasMore) {
                result = client.files().listFolderContinue(result.cursor)
                addAll(result.entries)
            }
            android.util.Log.d("DropboxApiClient", "fetchTreeFromNetwork: ${entries.size} total entries")
            Result.success(entries)
        } catch (e: Exception) {
            android.util.Log.e("DropboxApiClient", "fetchTreeFromNetwork: FAILED", e)
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
            val normalizedPath = if (path == "/" || path.isBlank()) "" else path.trimEnd('/')
            val childrenByParent = entries.groupBy { it.parentPath }
            val direct = childrenByParent[normalizedPath] ?: emptyList()
            val items = direct.map { entry ->
                val children = if (entry.isDirectory) childrenByParent[entry.path] else null
                val subfolders = children?.count { it.isDirectory } ?: 0
                val childFiles = children?.count { !it.isDirectory } ?: 0
                // Dropbox's API never gives a folder its own modified-time, so instead of always
                // showing 0 (which made "sort by date" put every folder in an arbitrary tie-order),
                // use the newest file modified anywhere underneath it — recursively, not just
                // direct children — matching how Dropbox's own desktop app displays a folder's
                // date. entries here is the WHOLE cached account tree, so this is free (no extra
                // network call), just a path-prefix scan.
                val effectiveLastModified = if (entry.isDirectory) {
                    val prefix = "${entry.path}/"
                    entries.filter { !it.isDirectory && it.path.startsWith(prefix) }
                        .maxOfOrNull { it.lastModified } ?: 0L
                } else {
                    entry.lastModified
                }
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

    private fun buildClient(account: CloudAccount): DbxClientV2 {
        val requestConfig = DbxRequestConfig.newBuilder("FileManagerPlus/1.0").build()
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
            val allocated = usage.allocation.individualValue?.allocated ?: (2L * 1024 * 1024 * 1024)
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
                    client.files().uploadBuilder(targetPath)
                        .withMode(WriteMode.OVERWRITE)
                        .withAutorename(false)
                        .withMute(false)
                        .uploadAndFinish(input)
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

    suspend fun createFolder(account: CloudAccount, path: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val client = buildClient(account)
            val targetPath = if (path.startsWith("/")) path else "/$path"
            val result = client.files().createFolderV2(targetPath)
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
            client.files().deleteV2(targetPath)
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
    suspend fun listTrash(account: CloudAccount): Result<List<FileItem>> = withContext(Dispatchers.IO) {
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
            android.util.Log.d("DropboxApiClient", "listTrash: page 0 -> ${result.entries.size} entries, hasMore=${result.hasMore}")
            // recursive+includeDeleted pagination for a `hasMore` that never actually clears was
            // seen hanging indefinitely on-device — no request ever fails, no exception is ever
            // thrown, it just keeps paging into what looks like the same tail of results forever
            // (allocating a full page of metadata each time, which showed up as a nonstop GC storm
            // with the screen stuck on a spinner). A hard page cap turns a real Dropbox API/SDK
            // pagination bug into "this account has an unusually large trash, showing what's
            // findable" instead of hanging the UI and burning memory with no way out.
            var page = 0
            val maxPages = 200
            while (result.hasMore) {
                currentCoroutineContext().ensureActive()
                page++
                if (page > maxPages) {
                    android.util.Log.e("DropboxApiClient", "listTrash: hit $maxPages page cap, stopping early with ${deleted.size} entries so far — hasMore never cleared")
                    break
                }
                result = client.files().listFolderContinue(result.cursor)
                collect(result.entries)
                android.util.Log.d("DropboxApiClient", "listTrash: page $page -> ${result.entries.size} entries (total deleted=${deleted.size}), hasMore=${result.hasMore}")
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
        try {
            val client = buildClient(account)
            val targetPath = if (path.startsWith("/")) path else "/$path"
            client.files().permanentlyDelete(targetPath)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("DropboxApiClient", "permanentlyDelete failed for '$path': ${e.message}", e)
            Result.failure(e)
        }
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
            val metadata = client.files().moveV2(normalizedFrom, toPath).metadata
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
            val metadata = client.files().moveV2(fromPath, toPath).metadata
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
