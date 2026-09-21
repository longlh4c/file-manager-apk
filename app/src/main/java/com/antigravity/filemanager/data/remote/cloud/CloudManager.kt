package com.antigravity.filemanager.data.remote.cloud

import android.content.Context
import com.antigravity.filemanager.data.remote.cloud.api.DropboxApiClient
import com.antigravity.filemanager.data.remote.cloud.api.GoogleDriveApiClient
import com.antigravity.filemanager.data.remote.cloud.api.MegaApiClient
import com.antigravity.filemanager.data.remote.cloud.api.MegaDecryptingDataSource
import com.antigravity.filemanager.data.remote.cloud.api.TeraBoxApiClient
import com.antigravity.filemanager.domain.model.CloudAccount
import com.antigravity.filemanager.domain.model.CloudProvider
import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.domain.model.FolderBadgeType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val googleDriveApi: GoogleDriveApiClient,
    private val dropboxApi: DropboxApiClient,
    private val megaApi: MegaApiClient,
    private val teraBoxApi: TeraBoxApiClient
) {

    init {
        // Also run once at startup (not just after each new download — see trimCloudDownloadsCache
        // below) so whatever already piled up in cloud_downloads/ before this cap existed gets
        // trimmed down immediately, instead of only shrinking gradually the next time each account
        // happens to download something new.
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            trimCloudDownloadsCache()
            // filesDir/cloud_accounts/ used to hold a permanent private duplicate of every file
            // ever uploaded (plus empty mirrors of created folders) — pure storage waste, since the
            // originals are still on the device and the real copies are in the cloud. Nothing
            // reads it anymore; reclaim whatever older versions left behind.
            try { File(context.filesDir, "cloud_accounts").deleteRecursively() } catch (e: Exception) {}
        }
    }

    // Display path -> provider id/handle, remembered from listings. Kept per account: two
    // accounts routinely share display paths ("/My Drive/Photos", "/Documents"), and one shared
    // map handed account B the ids of account A's folders — "folder shows empty" at best.
    private val folderIdCaches = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    private val masterKeyCache = ConcurrentHashMap<String, String>()

    private fun ids(accountId: String): ConcurrentHashMap<String, String> =
        folderIdCaches.getOrPut(accountId) { ConcurrentHashMap() }

    private fun rememberId(accountId: String, displayPath: String, name: String, id: String) {
        val cache = ids(accountId)
        cache[displayPath] = id
        cache[displayPath.trimStart('/')] = id
        cache[name] = id
        cache[id] = id
    }

    private fun forgetId(accountId: String, displayPath: String, id: String? = null) {
        val cache = ids(accountId)
        cache.remove(displayPath)
        cache.remove(displayPath.trimStart('/'))
        if (id != null) cache.remove(id)
    }

    private fun isRootPath(path: String) = path == "/" || path.isBlank()

    private fun childDisplayPath(parentPath: String, name: String): String =
        if (isRootPath(parentPath)) "/$name" else "${parentPath.trimEnd('/')}/$name"

    // cloud_downloads/<accountId> (see downloadFile below) is used as a permanent "already viewed,
    // don't re-fetch" cache for the media viewer/preview — nothing ever capped its size or evicted
    // old entries, so every full-resolution photo/video ever opened from any connected cloud
    // account piled up here forever. Trimmed after every successful download into it:
    // oldest-by-last-modified files removed first until back under the cap.
    private val cloudDownloadsCacheMaxBytes = 500L * 1024 * 1024 // 500 MB

    private suspend fun trimCloudDownloadsCache() = withContext(Dispatchers.IO) {
        try {
            val root = File(context.cacheDir, "cloud_downloads")
            if (!root.exists()) return@withContext
            val files = root.walkTopDown().filter { it.isFile }.toList()
            var total = files.sumOf { it.length() }
            if (total <= cloudDownloadsCacheMaxBytes) return@withContext
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= cloudDownloadsCacheMaxBytes) break
                val size = f.length()
                if (f.delete()) total -= size
            }
        } catch (e: Exception) {}
    }

    //region Account session payload
    fun saveSessionPayload(accountId: String, payload: String?) {
        if (payload.isNullOrBlank()) return
        try {
            masterKeyCache.remove(accountId)
            val sessionDir = File(context.filesDir, "cloud_sessions")
            if (!sessionDir.exists()) sessionDir.mkdirs()
            File(sessionDir, "$accountId.json").writeText(payload)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getSessionPayload(accountId: String, fallback: String? = null): String {
        try {
            val file = File(context.filesDir, "cloud_sessions/$accountId.json")
            if (file.exists()) {
                val text = file.readText()
                if (text.isNotBlank()) return text
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return fallback ?: ""
    }

    fun deleteSessionPayload(accountId: String) {
        // Account-keyed in-memory caches must be dropped here too, otherwise they linger for the
        // life of the process (this manager is a singleton) even after the account is removed.
        folderIdCaches.remove(accountId)
        masterKeyCache.remove(accountId)
        try {
            val file = File(context.filesDir, "cloud_sessions/$accountId.json")
            if (file.exists()) file.delete()
            com.antigravity.filemanager.utils.CloudDownloadCache.accountDir(context, accountId).deleteRecursively()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearProviderAuthData(@Suppress("UNUSED_PARAMETER") provider: CloudProvider? = null) {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val cookieManager = android.webkit.CookieManager.getInstance()
                    cookieManager.removeAllCookies(null)
                    cookieManager.removeSessionCookies(null)
                    cookieManager.flush()
                    android.webkit.WebStorage.getInstance().deleteAllData()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Every MEGA call needs the master key in `refreshToken` to decrypt node keys/names. It may
     * instead only live in the imported session payload, so fall back to that — for every MEGA
     * operation, not just some of them. */
    private fun megaAccount(account: CloudAccount): CloudAccount {
        if (!account.refreshToken.isNullOrBlank()) return account
        val masterKey = masterKeyCache[account.id] ?: try {
            val sessionJson = getSessionPayload(account.id, account.sessionHandle)
            if (sessionJson.isNotBlank()) {
                org.json.JSONObject(sessionJson).optString("masterKey", "").also {
                    if (it.isNotBlank()) masterKeyCache[account.id] = it
                }
            } else ""
        } catch (e: Exception) { "" }
        return if (masterKey.isNotBlank()) account.copy(refreshToken = masterKey) else account
    }

    /** Resolves a MEGA display path to a node handle: remembered id, else a walk of the node tree. */
    private suspend fun megaHandle(account: CloudAccount, displayPath: String): String {
        val cache = ids(account.id)
        val cached = cache[displayPath] ?: cache[displayPath.trimStart('/')] ?: displayPath
        return if (cached.startsWith("/")) megaApi.resolveHandleForDisplayPath(account, cached) ?: cached else cached
    }

    /** Resolves a Google Drive display path to a file/folder id. */
    private suspend fun driveId(account: CloudAccount, displayPath: String, allowNameFallback: Boolean = false): String {
        val cache = ids(account.id)
        cache[displayPath]?.let { return it }
        cache[displayPath.trimStart('/')]?.let { return it }
        if (allowNameFallback) {
            // resolveIdForDisplayPath only walks folders, so a file path cold-cache miss relies on
            // its name having been seen in a listing of this account.
            val fileName = displayPath.substringAfterLast("/")
            (cache[fileName] ?: cache["/$fileName"])?.let { return it }
        }
        return googleDriveApi.resolveIdForDisplayPath(account, displayPath) ?: displayPath
    }
    //endregion

    //region Listing
    // Lists cloud files via the real per-provider API — the single source of truth per call.
    suspend fun listCloudFiles(account: CloudAccount, remotePath: String, forceFullRefresh: Boolean = false): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            when (account.provider) {
                CloudProvider.GOOGLE_DRIVE -> {
                    if (isRootPath(remotePath)) {
                        // Virtual top-level menu (My Drive / Starred / Shared with me / Shared
                        // Drives) — matches how the stock Drive app and most third-party file
                        // managers group a Drive account, instead of dumping My Drive's raw
                        // contents at the account root. "My Drive" aliases straight to the real
                        // "root" folder id so upload/create-folder there need no special-casing.
                        val virtualItems = listOf(
                            FileItem(id = "root", name = "My Drive", path = "/My Drive", isDirectory = true, mimeType = "application/vnd.google-apps.folder"),
                            FileItem(id = "__starred__", name = "Starred", path = "/Starred", isDirectory = true, mimeType = "application/vnd.google-apps.folder"),
                            FileItem(id = "__shared_with_me__", name = "Shared with me", path = "/Shared with me", isDirectory = true, mimeType = "application/vnd.google-apps.folder"),
                            FileItem(id = "__shared_drives__", name = "Shared Drives", path = "/Shared Drives", isDirectory = true, mimeType = "application/vnd.google-apps.folder"),
                            FileItem(id = "__trash__", name = "Trash", path = "/Trash", isDirectory = true, mimeType = "application/vnd.google-apps.folder", folderBadgeType = FolderBadgeType.TRASH)
                        )
                        virtualItems.forEach { rememberId(account.id, it.path, it.name, it.id) }
                        Result.success(virtualItems)
                    } else {
                        val resolvedId = ids(account.id)[remotePath]
                            ?: googleDriveApi.resolveIdForDisplayPath(account, remotePath)
                            ?: remotePath.trimStart('/')
                        val result = when (resolvedId) {
                            "__starred__" -> googleDriveApi.listStarred(account)
                            "__shared_with_me__" -> googleDriveApi.listSharedWithMe(account)
                            "__shared_drives__" -> googleDriveApi.listSharedDrives(account)
                            "__trash__" -> googleDriveApi.listTrash(account)
                            else -> googleDriveApi.listFiles(account, resolvedId)
                        }
                        result.map { list ->
                            list.map { item ->
                                val itemDisplayPath = childDisplayPath(remotePath, item.name)
                                rememberId(account.id, itemDisplayPath, item.name, item.id)
                                item.copy(
                                    path = itemDisplayPath,
                                    folderBadgeType = if (item.isDirectory) detectFolderBadge(item.name) else FolderBadgeType.STANDARD
                                )
                            }
                        }
                    }
                }
                CloudProvider.DROPBOX -> {
                    // No virtual "Trash" entry: Dropbox has no real trash API (list_folder's
                    // include_deleted returns every item ever deleted since account creation, not
                    // the ~30-day-recoverable set), so it could only show a wildly inaccurate list.
                    val path = if (isRootPath(remotePath)) "" else remotePath
                    if (forceFullRefresh) {
                        // Re-lists just this one folder and patches its direct children into the
                        // cached tree, instead of rebuilding the whole account's tree.
                        dropboxApi.refreshFolderShallow(account, path)
                    }
                    // Always build/reuse the cached whole-account tree: one full recursive fetch
                    // (only when nothing is cached yet) serves every later navigation from memory.
                    dropboxApi.listFolderCached(account, path, allowFullTreeFetch = true).map { list ->
                        list.map { item ->
                            val itemDisplayPath = childDisplayPath(remotePath, item.name)
                            rememberId(account.id, itemDisplayPath, item.name, item.id)
                            item.copy(path = itemDisplayPath, folderBadgeType = if (item.isDirectory) detectFolderBadge(item.name) else FolderBadgeType.STANDARD)
                        }
                    }
                }
                CloudProvider.MEGA -> {
                    if (forceFullRefresh) {
                        megaApi.invalidateNodeTreeCache(account.id)
                    }
                    val megaAccount = megaAccount(account)
                    val handle = if (isRootPath(remotePath) || remotePath == "root") {
                        null
                    } else {
                        // The id cache is in-memory only — a cold process (or navigating straight
                        // to a remembered deep folder) can miss it, so resolve by walking the node
                        // tree rather than passing the display path as if it were a handle.
                        megaHandle(megaAccount, remotePath)
                    }
                    megaApi.listFiles(megaAccount, handle).map { list ->
                        list.map { item ->
                            val itemDisplayPath = childDisplayPath(remotePath, item.name)
                            ids(account.id)[itemDisplayPath] = item.id
                            item.copy(path = itemDisplayPath)
                        }
                    }
                }
                CloudProvider.TERABOX -> teraBoxApi.listFiles(account, remotePath)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

    fun detectFolderBadge(folderName: String): FolderBadgeType {
        val lower = folderName.lowercase(Locale.getDefault())
        return when {
            lower == "dcim" || lower.contains("camera") || lower.contains("photos") || lower.contains("ảnh") || lower.contains("pictures") || lower.contains("gals") -> FolderBadgeType.CAMERA
            lower == "documents" || lower.contains("document") || lower.contains("docs") || lower.contains("tài liệu") -> FolderBadgeType.DOCUMENTS
            lower == "download" || lower.contains("downloads") || lower.contains("tải về") -> FolderBadgeType.DOWNLOAD
            lower == "movies" || lower.contains("movie") || lower.contains("videos") || lower.contains("video") || lower.contains("vids") -> FolderBadgeType.MOVIES
            lower == "music" || lower.contains("audio") || lower.contains("songs") || lower.contains("nhạc") -> FolderBadgeType.MUSIC
            else -> FolderBadgeType.STANDARD
        }
    }
    //endregion

    //region Quota, thumbnails & streaming
    suspend fun getAccountQuota(account: CloudAccount): Result<Pair<Long, Long>> = withContext(Dispatchers.IO) {
        try {
            val apiQuota = when (account.provider) {
                CloudProvider.GOOGLE_DRIVE -> googleDriveApi.getStorageQuota(account)
                CloudProvider.DROPBOX -> dropboxApi.getSpaceUsage(account)
                CloudProvider.MEGA -> megaApi.getStorageQuota(megaAccount(account))
                CloudProvider.TERABOX -> teraBoxApi.getQuota(account.accessToken ?: account.sessionHandle ?: "").map { it.totalBytes to it.usedBytes }
            }
            val quota = apiQuota.getOrNull()
            if (quota != null && quota.first > 0L) return@withContext Result.success(quota)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }

        // Fallback: whatever was recorded on the account, else the provider's free-tier size.
        val total = account.totalSpaceBytes ?: when (account.provider) {
            CloudProvider.GOOGLE_DRIVE -> 15L * 1024 * 1024 * 1024
            CloudProvider.DROPBOX -> 2L * 1024 * 1024 * 1024
            CloudProvider.MEGA -> 50L * 1024 * 1024 * 1024
            CloudProvider.TERABOX -> 1024L * 1024 * 1024 * 1024
        }
        Result.success(Pair(total, account.usedSpaceBytes ?: 0L))
    }

    /**
     * Fetches the provider's own pre-generated thumbnail (a few KB) instead of the full file.
     * Fails (not throws) when a provider has no thumbnail endpoint or the item has none, so
     * callers can fall back to downloading the full file.
     */
    suspend fun downloadThumbnail(account: CloudAccount, nodeId: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        when (account.provider) {
            CloudProvider.MEGA -> megaApi.downloadThumbnail(megaAccount(account), nodeId)
            CloudProvider.GOOGLE_DRIVE -> googleDriveApi.downloadThumbnail(account, nodeId)
            CloudProvider.TERABOX -> teraBoxApi.downloadThumbnail(account, nodeId)
            CloudProvider.DROPBOX -> Result.failure(Exception("Thumbnail endpoint not supported for ${account.provider}"))
        }
    }

    /**
     * Fallback for [openThumbnailDataSource] — downloads just the first [maxBytes] of a file,
     * decrypted. MEGA-only: its AES-CTR encryption is byte-range addressable, unlike other
     * providers here.
     */
    suspend fun downloadFilePartial(account: CloudAccount, nodeId: String, localTargetFile: java.io.File, maxBytes: Long): Result<java.io.File> =
        withContext(Dispatchers.IO) {
            when (account.provider) {
                CloudProvider.MEGA -> megaApi.downloadFilePartial(megaAccount(account), nodeId, localTargetFile, maxBytes)
                CloudProvider.DROPBOX, CloudProvider.GOOGLE_DRIVE, CloudProvider.TERABOX ->
                    Result.failure(Exception("Partial download not supported for ${account.provider}"))
            }
        }

    /**
     * MEGA-only: an on-demand decrypting [android.media.MediaDataSource] that fetches just the
     * byte ranges a reader (MediaMetadataRetriever for thumbnail extraction, or ExoPlayer via
     * MediaDataSourceDataSource when [forPlayback]) actually requests, instead of eagerly
     * downloading a fixed-size prefix. See
     * [com.antigravity.filemanager.data.remote.cloud.api.MegaApiClient.openThumbnailDataSource].
     */
    suspend fun openThumbnailDataSource(account: CloudAccount, nodeId: String, forPlayback: Boolean = false): Result<android.media.MediaDataSource> =
        withContext(Dispatchers.IO) {
            when (account.provider) {
                CloudProvider.MEGA -> megaApi.openThumbnailDataSource(
                    megaAccount(account),
                    nodeId,
                    if (forPlayback) MegaDecryptingDataSource.PLAYBACK_FETCH_WINDOW else MegaDecryptingDataSource.DEFAULT_FETCH_WINDOW
                )
                CloudProvider.DROPBOX, CloudProvider.GOOGLE_DRIVE, CloudProvider.TERABOX ->
                    Result.failure(Exception("On-demand thumbnail data source not supported for ${account.provider}"))
            }
        }

    /**
     * A pre-signed Range-request-capable URL for streaming/seeking directly into the file
     * (e.g. so MediaMetadataRetriever can decode one video frame without downloading the whole
     * file). Only Dropbox exposes this today — MEGA's content is client-side encrypted (a raw
     * range fetch would return ciphertext) and Google Drive's media endpoint needs an auth
     * header rather than a bare URL, so both fail here rather than pretending to support it.
     */
    suspend fun getStreamableLink(account: CloudAccount, remotePath: String): Result<String> = withContext(Dispatchers.IO) {
        when (account.provider) {
            CloudProvider.DROPBOX -> dropboxApi.getTemporaryLink(account, remotePath)
            CloudProvider.MEGA, CloudProvider.GOOGLE_DRIVE, CloudProvider.TERABOX ->
                Result.failure(Exception("Streamable link not supported for ${account.provider}"))
        }
    }

    /**
     * Like [getStreamableLink], but for the media viewers (image/video playback) rather than
     * the video-thumbnail frame grab — also covers Google Drive (media endpoint needs a bearer
     * token attached to the request) and TeraBox (needs its session cookie). Still unsupported
     * for MEGA (client-side encrypted; a raw range fetch would return ciphertext).
     */
    suspend fun getStreamSource(account: CloudAccount, remotePath: String): Result<com.antigravity.filemanager.domain.model.CloudStreamSource> =
        withContext(Dispatchers.IO) {
            when (account.provider) {
                CloudProvider.DROPBOX -> dropboxApi.getTemporaryLink(account, remotePath)
                    .map { com.antigravity.filemanager.domain.model.CloudStreamSource(it) }
                // A Drive FileItem's `path` is a synthetic display path (e.g. "/My Drive/x.jpg"),
                // not the real Drive file ID the media endpoint needs.
                CloudProvider.GOOGLE_DRIVE -> googleDriveApi.getAuthenticatedMediaUrl(account, driveId(account, remotePath, allowNameFallback = true))
                // Like Drive, the URL only works with the session cookie sent as a header.
                CloudProvider.TERABOX -> teraBoxApi.getStreamSource(account, remotePath)
                CloudProvider.MEGA -> Result.failure(Exception("Streaming not supported for ${account.provider}"))
            }
        }
    //endregion

    //region Transfer (download / upload)
    suspend fun downloadFile(
        account: CloudAccount,
        remotePath: String,
        localTargetDir: String,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            // Always fetched from the provider. The old name-based shortcuts (any cached or
            // mirrored file with the same NAME, anywhere in the account) handed back a different
            // file whenever two folders held same-named files — e.g. every camera's IMG_0001.jpg
            // — and pasted that wrong file into the user's folder. Callers that keep a viewer
            // cache check it themselves, with a size match, before calling this.
            val fileName = remotePath.substringAfterLast("/").ifEmpty { "cloud_file" }
            val destFile = File(localTargetDir, fileName)
            destFile.parentFile?.mkdirs()

            val result = when (account.provider) {
                CloudProvider.GOOGLE_DRIVE -> {
                    val fileId = driveId(account, remotePath, allowNameFallback = true)
                    googleDriveApi.downloadFile(account, fileId, localTargetDir, fileName, onProgress)
                }
                CloudProvider.DROPBOX -> {
                    val path = if (remotePath.startsWith("/")) remotePath else "/$remotePath"
                    dropboxApi.downloadFile(account, path, localTargetDir, fileName, onProgress)
                }
                CloudProvider.MEGA -> {
                    val megaAccount = megaAccount(account)
                    val cache = ids(account.id)
                    val nodeHandle = cache[remotePath]
                        ?: cache[remotePath.trimStart('/')]
                        ?: megaApi.resolveHandleForDisplayPath(megaAccount, remotePath)
                        ?: fileName
                    megaApi.downloadFile(megaAccount, nodeHandle, localTargetDir, fileName, "", onProgress)
                }
                CloudProvider.TERABOX -> {
                    val path = if (remotePath.startsWith("/")) remotePath else "/$remotePath"
                    teraBoxApi.downloadFile(account, path, destFile, onProgress)
                }
            }
            if (result.isSuccess) {
                trimCloudDownloadsCache()
            } else {
                android.util.Log.e("CloudManager", "downloadFile failed — provider=${account.provider} remotePath='$remotePath' error=${result.exceptionOrNull()}")
            }
            result
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("CloudManager", "downloadFile failed for account=${account.id}, remotePath=$remotePath: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun uploadFile(
        account: CloudAccount,
        localFilePath: String,
        remoteTargetDir: String,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val srcFile = File(localFilePath)
            if (!srcFile.exists()) return@withContext Result.failure(Exception("Local file not found: $localFilePath"))
            val totalBytes = srcFile.length()

            // Every provider's real remote failure must be reported — never a silent success.
            val remoteResult: Result<String> = when (account.provider) {
                CloudProvider.GOOGLE_DRIVE -> {
                    val parentId = if (isRootPath(remoteTargetDir)) "root" else driveId(account, remoteTargetDir)
                    googleDriveApi.uploadFile(account, srcFile, parentId, onProgress)
                }
                // uploadFile() already patches the cached tree in place with the new entry.
                CloudProvider.DROPBOX -> dropboxApi.uploadFile(account, srcFile, remoteTargetDir, onProgress)
                CloudProvider.MEGA -> {
                    val megaAccount = megaAccount(account)
                    // Resolve the parent by walking the node tree on a cold cache; the raw display
                    // path is not a valid handle and made the upload hang until it timed out.
                    val parentHandle = if (isRootPath(remoteTargetDir)) null else megaHandle(megaAccount, remoteTargetDir)
                    megaApi.uploadFile(megaAccount, srcFile, parentHandle, onProgress).map { it.id }
                }
                CloudProvider.TERABOX -> teraBoxApi.uploadFile(account, srcFile, remoteTargetDir, onProgress).map { it.id }
            }

            val remoteFileId = remoteResult.getOrElse { error ->
                android.util.Log.e("CloudManager", "Remote upload failed for ${account.provider}", error)
                return@withContext Result.failure(error)
            }
            if (remoteFileId.isNotBlank()) {
                rememberId(account.id, childDisplayPath(remoteTargetDir, srcFile.name), srcFile.name, remoteFileId)
            }
            onProgress?.invoke(totalBytes, totalBytes)
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }
    //endregion

    //region Folder & item mutations (create / delete / restore / rename / move)
    suspend fun createFolder(account: CloudAccount, folderName: String, parentPath: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            com.antigravity.filemanager.data.local.storage.invalidFileNameReason(folderName)?.let { return@withContext Result.failure(java.io.IOException(it)) }
            val itemPath = childDisplayPath(parentPath, folderName)
            // A failure here must be reported, not swallowed — callers (e.g. a recursive
            // cloud-to-cloud folder copy) rely on the folder actually existing server-side before
            // uploading into it.
            val created: Result<FileItem> = when (account.provider) {
                CloudProvider.GOOGLE_DRIVE -> {
                    // parentPath is a display path, not a Drive id — the raw string 404s.
                    val parentId = if (isRootPath(parentPath)) "root" else driveId(account, parentPath)
                    googleDriveApi.createFolder(account, folderName, parentId)
                }
                CloudProvider.DROPBOX -> dropboxApi.createFolder(account, itemPath).onSuccess { item ->
                    // Patch the new folder into the cached tree instead of invalidating it — a
                    // recursive copy calls this once per subfolder.
                    dropboxApi.patchTreeAfterFolderCreate(account.id, itemPath, item.id)
                }
                CloudProvider.MEGA -> {
                    val megaAccount = megaAccount(account)
                    val parentHandle = if (isRootPath(parentPath)) null else megaHandle(megaAccount, parentPath)
                    megaApi.createFolder(megaAccount, folderName, parentHandle)
                }
                CloudProvider.TERABOX -> teraBoxApi.createFolder(account, folderName, parentPath)
            }
            created.map { item ->
                rememberId(account.id, itemPath, folderName, item.id)
                FileItem(
                    id = item.id,
                    name = folderName,
                    path = itemPath,
                    isDirectory = true,
                    itemCount = 0
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun deleteItem(account: CloudAccount, remotePathOrId: String, moveToTrash: Boolean = true): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cache = ids(account.id)
            val targetId = cache[remotePathOrId] ?: cache[remotePathOrId.trimStart('/')] ?: remotePathOrId

            // Remote API deletion — moveToTrash routes to each provider's real trash/rubbish
            // bin where one exists (Google Drive, MEGA); Dropbox itself keeps deleted files
            // recoverable from its own web UI for ~30 days regardless of which call is used.
            // A failure here MUST be reported: a cloud-to-cloud "Move" checks it before
            // treating the source as gone.
            val remoteResult = when (account.provider) {
                CloudProvider.GOOGLE_DRIVE -> {
                    val resolvedTargetId = if (targetId.startsWith("/")) {
                        googleDriveApi.resolveIdForDisplayPath(account, targetId) ?: targetId
                    } else {
                        targetId
                    }
                    if (moveToTrash) googleDriveApi.trashFile(account, resolvedTargetId) else googleDriveApi.deleteFile(account, resolvedTargetId)
                }
                CloudProvider.DROPBOX -> {
                    val dropboxPath = if (remotePathOrId.startsWith("/")) remotePathOrId else "/$remotePathOrId"
                    if (moveToTrash) {
                        dropboxApi.delete(account, dropboxPath).also { dropboxApi.patchTreeAfterDelete(account.id, dropboxPath) }
                    } else {
                        // moveToTrash=false means this delete came from inside the Trash view
                        // itself — purge it from Dropbox's own retained copy instead.
                        dropboxApi.permanentlyDelete(account, dropboxPath)
                    }
                }
                CloudProvider.MEGA -> {
                    // MEGA identifies nodes by handle; a display path handed over as a handle
                    // silently no-op'd while reporting success.
                    val megaAccount = megaAccount(account)
                    val handle = megaHandle(megaAccount, remotePathOrId)
                    (if (moveToTrash) megaApi.moveToRubbishBin(megaAccount, handle) else megaApi.deleteNode(megaAccount, handle))
                        .also { megaApi.invalidateNodeTreeCache(account.id) }
                }
                CloudProvider.TERABOX -> {
                    val path = if (remotePathOrId.startsWith("/")) remotePathOrId else "/$remotePathOrId"
                    teraBoxApi.deleteFile(account, path)
                }
            }
            android.util.Log.d("CloudManager", "deleteItem: provider=${account.provider} remotePathOrId='$remotePathOrId' targetId='$targetId' isSuccess=${remoteResult.isSuccess} error=${remoteResult.exceptionOrNull()}")

            if (remoteResult.isFailure) {
                return@withContext Result.failure(remoteResult.exceptionOrNull() ?: Exception("Remote delete failed"))
            }
            forgetId(account.id, remotePathOrId, targetId)
            com.antigravity.filemanager.utils.CloudDownloadCache.dirFor(context, account.id, remotePathOrId).deleteRecursively()
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

    /** Permanently deletes many MEGA items in as few HTTP round trips as possible — see
     * MegaApiClient.deleteNodesBatch. Emptying a MEGA Rubbish Bin with hundreds of items
     * sequentially could take over an hour; this cuts it to roughly (item count / 25) requests.
     * Returns results keyed by the ORIGINAL remotePathOrId strings passed in, not MEGA handles,
     * so callers can match failures back to what the user actually selected. */
    suspend fun deletePermanentlyBatchMega(account: CloudAccount, remotePathsOrIds: List<String>): Map<String, Result<Unit>> = withContext(Dispatchers.IO) {
        val megaAccount = megaAccount(account)
        val pathToHandle = remotePathsOrIds.associateWith { megaHandle(megaAccount, it) }
        val handleResults = megaApi.deleteNodesBatch(megaAccount, pathToHandle.values.toList())
        remotePathsOrIds.associateWith { remotePathOrId ->
            val handle = pathToHandle.getValue(remotePathOrId)
            val result = handleResults[handle] ?: Result.failure(Exception("No result for '$remotePathOrId'"))
            if (result.isSuccess) forgetId(account.id, remotePathOrId, handle)
            result
        }
    }

    /** Restores an item out of the provider's real trash/rubbish bin. */
    suspend fun restoreItem(account: CloudAccount, remotePathOrId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val cache = ids(account.id)
        val targetId = cache[remotePathOrId] ?: cache[remotePathOrId.trimStart('/')] ?: remotePathOrId
        when (account.provider) {
            CloudProvider.GOOGLE_DRIVE -> {
                val resolvedTargetId = if (targetId.startsWith("/")) {
                    googleDriveApi.resolveIdForDisplayPath(account, targetId) ?: targetId
                } else {
                    targetId
                }
                googleDriveApi.restoreFromTrash(account, resolvedTargetId)
            }
            CloudProvider.MEGA -> megaApi.restoreFromRubbishBin(megaAccount(account), targetId).also { megaApi.invalidateNodeTreeCache(account.id) }
            CloudProvider.DROPBOX -> {
                val dropboxPath = if (remotePathOrId.startsWith("/")) remotePathOrId else "/$remotePathOrId"
                dropboxApi.restoreFile(account, dropboxPath).map { }.also { dropboxApi.invalidateTree(account.id) }
            }
            CloudProvider.TERABOX -> Result.failure(UnsupportedOperationException("TeraBox has no restore API"))
        }
    }

    suspend fun renameItem(account: CloudAccount, remotePath: String, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        com.antigravity.filemanager.data.local.storage.invalidFileNameReason(newName)?.let { return@withContext Result.failure(java.io.IOException(it)) }
        when (account.provider) {
            CloudProvider.DROPBOX -> dropboxApi.renameFile(account, remotePath, newName).also { dropboxApi.invalidateTree(account.id) }
            CloudProvider.GOOGLE_DRIVE -> {
                val targetId = driveId(account, remotePath)
                googleDriveApi.renameFile(account, targetId, newName).map { FileItem(id = targetId, name = newName, path = remotePath) }
            }
            CloudProvider.MEGA -> {
                val megaAccount = megaAccount(account)
                val targetId = megaHandle(megaAccount, remotePath)
                megaApi.renameNode(megaAccount, targetId, newName)
                    .also { megaApi.invalidateNodeTreeCache(account.id) }
                    .map { FileItem(id = targetId, name = newName, path = remotePath) }
            }
            CloudProvider.TERABOX -> {
                val cleanPath = if (remotePath.startsWith("/")) remotePath else "/$remotePath"
                val newPath = cleanPath.substringBeforeLast("/") + "/" + newName
                teraBoxApi.renameFile(account, cleanPath, newName).map {
                    FileItem(id = newPath, name = newName, path = newPath)
                }
            }
        }.onSuccess { forgetId(account.id, remotePath) }
    }

    /** Relocates an item to a different folder within the SAME cloud account, entirely
     * server-side — no download+reupload round trip. Copy is not handled here — MEGA has no
     * simple "duplicate this subtree" command, so a same-account Copy still goes through the
     * generic round trip. */
    suspend fun moveItemWithinAccount(account: CloudAccount, sourcePath: String, targetDir: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cache = ids(account.id)
            when (account.provider) {
                CloudProvider.DROPBOX -> {
                    dropboxApi.moveItem(account, sourcePath, targetDir).map { }
                        .also { if (it.isSuccess) dropboxApi.invalidateTree(account.id) }
                }
                CloudProvider.MEGA -> {
                    val megaAccount = megaAccount(account)
                    val nodeHandle = cache[sourcePath]
                        ?: megaApi.resolveHandleForDisplayPath(megaAccount, sourcePath)
                        ?: return@withContext Result.failure(Exception("Could not resolve MEGA handle for '$sourcePath'"))
                    val newParentHandle = if (isRootPath(targetDir)) {
                        megaApi.resolveRootHandle(megaAccount)
                    } else {
                        cache[targetDir] ?: megaApi.resolveHandleForDisplayPath(megaAccount, targetDir)
                    } ?: return@withContext Result.failure(Exception("Could not resolve MEGA target folder"))
                    megaApi.moveNode(megaAccount, nodeHandle, newParentHandle)
                }
                CloudProvider.GOOGLE_DRIVE -> {
                    val fileId = cache[sourcePath]
                        ?: googleDriveApi.resolveIdForDisplayPath(account, sourcePath)
                        ?: return@withContext Result.failure(Exception("Could not resolve Drive file id for '$sourcePath'"))
                    val oldParentPath = sourcePath.substringBeforeLast('/', "")
                    val oldParentId = if (oldParentPath.isEmpty() || oldParentPath == "/My Drive") {
                        "root"
                    } else {
                        cache[oldParentPath] ?: googleDriveApi.resolveIdForDisplayPath(account, oldParentPath) ?: "root"
                    }
                    val newParentId = if (isRootPath(targetDir) || targetDir == "/My Drive") {
                        "root"
                    } else {
                        cache[targetDir] ?: googleDriveApi.resolveIdForDisplayPath(account, targetDir) ?: targetDir
                    }
                    googleDriveApi.moveFile(account, fileId, oldParentId, newParentId)
                }
                CloudProvider.TERABOX -> teraBoxApi.moveFile(account, sourcePath, targetDir)
            }.onSuccess { forgetId(account.id, sourcePath) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }
    //endregion
}
