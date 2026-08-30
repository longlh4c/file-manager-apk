package com.antigravity.filemanager.data.remote.cloud.api

import com.antigravity.filemanager.domain.model.CloudAccount
import com.antigravity.filemanager.domain.model.FileItem
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.FolderMetadata
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

    private suspend fun getOrFetchTree(account: CloudAccount): Result<List<DropboxEntry>> {
        treeCache[account.id]?.let { cached ->
            return Result.success(cached.entries)
        }
        return treeMutexFor(account.id).withLock {
            treeCache[account.id]?.let { recheck ->
                return@withLock Result.success(recheck.entries)
            }
            val fetched = fetchTreeFromNetwork(account)
            fetched.onSuccess { entries -> treeCache[account.id] = TreeCache(entries, System.currentTimeMillis()) }
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
                FileItem(
                    id = entry.id,
                    name = entry.name,
                    path = entry.path,
                    size = entry.size,
                    lastModified = entry.lastModified,
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
        try {
            val parentJob = coroutineContext[Job]
            val client = buildClient(account)
            val targetPath = if (remoteDirPath == "/" || remoteDirPath.isBlank()) "/${localFile.name}" else "${remoteDirPath.trimEnd('/')}/${localFile.name}"
            android.util.Log.d("DropboxApiClient", "uploadFile: remoteDirPath='$remoteDirPath' -> targetPath='$targetPath', size=${localFile.length()}")
            val totalBytes = localFile.length()
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
            Result.success(metadata.pathDisplay ?: targetPath)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("DropboxApiClient", "uploadFile: FAILED for remoteDirPath='$remoteDirPath', file='${localFile.name}'", e)
            Result.failure(e)
        }
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
