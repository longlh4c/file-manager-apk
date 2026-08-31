package com.antigravity.filemanager.data.remote.cloud

import android.content.Context
import com.antigravity.filemanager.data.remote.cloud.api.DropboxApiClient
import com.antigravity.filemanager.data.remote.cloud.api.GoogleDriveApiClient
import com.antigravity.filemanager.data.remote.cloud.api.MegaApiClient
import com.antigravity.filemanager.domain.model.CloudAccount
import com.antigravity.filemanager.domain.model.CloudProvider
import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.domain.model.FolderBadgeType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
    private val megaApi: MegaApiClient
) {

    private val folderIdCache = ConcurrentHashMap<String, String>()
    private val nodeKeyCache = ConcurrentHashMap<String, String>()
    private val sessionNodesCache = ConcurrentHashMap<String, Pair<String, List<MegaNode>>>()
    private val masterKeyCache = ConcurrentHashMap<String, String>()

    private data class RootResolution(val resolvedRootId: String, val candidateRoots: List<String>)
    // Keyed by accountId, validated by reference-equality against the `allNodes` list it was computed
    // from. Every mutation to sessionNodesCache builds a genuinely new list, so this self-invalidates
    // without needing to be cleared explicitly wherever the node list changes.
    private val rootResolutionCache = ConcurrentHashMap<String, Pair<List<MegaNode>, RootResolution>>()

    /** Root-parent resolution only depends on `allNodes`/`rootId`, not on the folder being viewed —
     * memoize it instead of recomputing three linear scans over the whole node list on every navigation. */
    private fun resolveRoot(accountId: String, allNodes: List<MegaNode>, rootId: String): RootResolution {
        rootResolutionCache[accountId]?.let { (cachedNodes, cachedResult) ->
            if (cachedNodes === allNodes) return cachedResult
        }
        val allNodeIds = allNodes.map { it.id }.toSet()
        val candidateRoots = allNodes.map { it.p }.filter { it.isNotBlank() && !allNodeIds.contains(it) }.distinct()
        val resolvedRootId = if (rootId.isNotBlank() && candidateRoots.contains(rootId)) {
            rootId
        } else {
            candidateRoots.find { parentId ->
                val children = allNodes.filter { it.p == parentId }
                children.any { it.name != "SyncDebris" && it.name != "Rubbish" && it.name != "Trash" && it.isDir }
            } ?: candidateRoots.maxByOrNull { parentId -> allNodes.count { it.p == parentId && it.isDir } } ?: ""
        }
        val result = RootResolution(resolvedRootId, candidateRoots)
        rootResolutionCache[accountId] = allNodes to result
        return result
    }

    data class MegaNode(
        val id: String,
        val p: String,
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val ts: Long,
        val t: Int,
        val k: String = ""
    )

    fun getAccountStorageDir(accountId: String, relativePath: String = ""): File {
        val baseDir = File(context.filesDir, "cloud_accounts/$accountId")
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }

        val cleaned = relativePath.trimStart('/')
        return if (cleaned.isEmpty()) baseDir else File(baseDir, cleaned)
    }

    fun saveSessionPayload(accountId: String, payload: String?) {
        if (payload.isNullOrBlank()) return
        try {
            sessionNodesCache.remove(accountId)
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
        sessionNodesCache.remove(accountId)
        masterKeyCache.remove(accountId)
        rootResolutionCache.remove(accountId)
        try {
            val file = File(context.filesDir, "cloud_sessions/$accountId.json")
            if (file.exists()) file.delete()
            val accountDir = File(context.filesDir, "cloud_accounts/$accountId")
            if (accountDir.exists()) accountDir.deleteRecursively()
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

    // Lists cloud files via the real per-provider API, falling back to the local account-mirror
    // directory only if that call fails outright (e.g. no network, expired session).
    //
    // parseSessionNodes()/sessionNodesCache used to be checked FIRST here, short-circuiting the
    // real API with whatever a WebView-DOM-scrape had last saved to session payload — dating
    // from before any provider had a real API client. That scrape flow (CloudLoginWebViewDialog)
    // is dead now (MEGA logs in natively, Drive/Dropbox use real OAuth clients below), so nothing
    // populates a session payload anymore and the check was permanently a no-op — but it was a
    // live landmine: it would have silently served stale/wrong listings again the moment anything
    // ever wrote to that cache. Removed so this function has exactly one source of truth per call.
    suspend fun listCloudFiles(account: CloudAccount, remotePath: String, forceFullRefresh: Boolean = false): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            // 1. Try remote API if valid token/session exists
            val remoteResult = when (account.provider) {
                CloudProvider.GOOGLE_DRIVE -> {
                    if (remotePath == "/" || remotePath.isBlank()) {
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
                        virtualItems.forEach { item ->
                            folderIdCache[item.path] = item.id
                            folderIdCache[item.name] = item.id
                        }
                        Result.success(virtualItems)
                    } else {
                        val resolvedId = folderIdCache[remotePath] ?: remotePath.trimStart('/')
                        val result = when (resolvedId) {
                            "__starred__" -> googleDriveApi.listStarred(account)
                            "__shared_with_me__" -> googleDriveApi.listSharedWithMe(account)
                            "__shared_drives__" -> googleDriveApi.listSharedDrives(account)
                            "__trash__" -> googleDriveApi.listTrash(account)
                            else -> googleDriveApi.listFiles(account, resolvedId)
                        }
                        if (result.isSuccess) {
                            val list = result.getOrNull() ?: emptyList()
                            list.forEach { item ->
                                val itemDisplayPath = "${remotePath.trimEnd('/')}/${item.name}"
                                folderIdCache[itemDisplayPath] = item.id
                                folderIdCache[item.id] = item.id
                                folderIdCache[item.name] = item.id
                            }
                            Result.success(list.map { item ->
                                val itemDisplayPath = "${remotePath.trimEnd('/')}/${item.name}"
                                item.copy(
                                    path = itemDisplayPath,
                                    folderBadgeType = if (item.isDirectory) detectFolderBadge(item.name) else FolderBadgeType.STANDARD
                                )
                            })
                        } else {
                            result
                        }
                    }
                }
                CloudProvider.DROPBOX -> {
                    val path = if (remotePath == "/" || remotePath.isBlank()) "" else remotePath
                    if (forceFullRefresh) {
                        dropboxApi.invalidateTree(account.id)
                    }
                    // Always build/reuse the cached whole-account tree, same as MEGA — now that
                    // the tree cache never expires on its own (only an explicit invalidateTree()
                    // from a manual root refresh or a mutation drops it), paying for one full
                    // recursive fetch is worth it: every navigation after that (including the
                    // "copy/move to Dropbox" destination picker, and revisiting a folder right
                    // after a transfer) is served from memory instead of a fresh network call
                    // that looked like the folder was "reloading" every time.
                    val result = dropboxApi.listFolderCached(account, path, allowFullTreeFetch = true)
                    if (result.isSuccess) {
                        val list = result.getOrNull() ?: emptyList()
                        list.forEach { item ->
                            val itemDisplayPath = if (remotePath == "/" || remotePath.isBlank()) "/${item.name}" else "${remotePath.trimEnd('/')}/${item.name}"
                            folderIdCache[itemDisplayPath] = item.id
                            folderIdCache[item.id] = item.id
                            folderIdCache[item.name] = item.id
                        }
                        Result.success(list.map { item ->
                            val itemDisplayPath = if (remotePath == "/" || remotePath.isBlank()) "/${item.name}" else "${remotePath.trimEnd('/')}/${item.name}"
                            item.copy(path = itemDisplayPath, folderBadgeType = if (item.isDirectory) detectFolderBadge(item.name) else FolderBadgeType.STANDARD)
                        })
                    } else {
                        result
                    }
                }
                CloudProvider.MEGA -> {
                    if (forceFullRefresh) {
                        megaApi.invalidateNodeTreeCache(account.id)
                    }
                    val masterKeyFromSession = masterKeyCache[account.id] ?: try {
                        val sessionJson = getSessionPayload(account.id, account.sessionHandle)
                        if (sessionJson.isNotBlank()) org.json.JSONObject(sessionJson).optString("masterKey", "") else ""
                    } catch (e: Exception) { "" }

                    val effectiveMasterKey = account.refreshToken?.ifBlank { null } ?: masterKeyFromSession.ifBlank { null }
                    val effectiveAccount = if (account.refreshToken.isNullOrBlank() && !effectiveMasterKey.isNullOrBlank()) {
                        account.copy(refreshToken = effectiveMasterKey)
                    } else {
                        account
                    }

                    val handle = if (remotePath == "/" || remotePath.isBlank() || remotePath == "root") {
                        null
                    } else {
                        // folderIdCache is normally populated by the listing that revealed this
                        // folder in the first place, but it's in-memory only — a cold process (or
                        // navigating straight to a remembered deep folder without replaying every
                        // parent level) can miss it. Resolving by walking the node tree instead of
                        // falling back to the raw display-path string is what actually fixes
                        // "folder shows empty" here; see resolveHandleForDisplayPath's comment.
                        folderIdCache[remotePath] ?: megaApi.resolveHandleForDisplayPath(effectiveAccount, remotePath) ?: remotePath
                    }
                    val result = megaApi.listFiles(effectiveAccount, handle)
                    if (result.isSuccess) {
                        val list = result.getOrNull() ?: emptyList()
                        list.forEach { item ->
                            val itemDisplayPath = if (remotePath == "/" || remotePath.isBlank()) "/${item.name}" else "${remotePath.trimEnd('/')}/${item.name}"
                            folderIdCache[itemDisplayPath] = item.id
                        }
                        Result.success(list.map { item ->
                            val itemDisplayPath = if (remotePath == "/" || remotePath.isBlank()) "/${item.name}" else "${remotePath.trimEnd('/')}/${item.name}"
                            item.copy(path = itemDisplayPath)
                        })
                    } else {
                        result
                    }
                }
            }

            if (remoteResult.isSuccess) {
                return@withContext remoteResult
            }

            // 2. Read from the account's local cloud drive directory — a best-effort offline
            // fallback for providers that actually mirror files there. For MEGA this directory
            // is normally empty (nothing mirrors into it), so on a transient remote failure (e.g.
            // MEGA briefly rate-limiting under concurrent requests) this used to silently return
            // an empty "success", wiping out whatever was already displayed. Only trust this
            // fallback when it actually found something — otherwise surface the original remote
            // failure so callers can keep showing their last-known-good (e.g. cached) list.
            val folder = getAccountStorageDir(account.id, remotePath)
            val children = folder.listFiles() ?: emptyArray()
            if (children.isEmpty()) {
                return@withContext remoteResult
            }

            val resultList = children.map { file ->
                val isDir = file.isDirectory
                val count = if (isDir) file.listFiles()?.size ?: 0 else 0
                val ext = if (!isDir) file.extension else ""
                val itemPath = if (remotePath == "/" || remotePath.isBlank()) "/${file.name}" else "${remotePath.trimEnd('/')}/${file.name}"

                FileItem(
                    id = file.absolutePath,
                    name = file.name,
                    path = itemPath,
                    size = if (isDir) 0L else file.length(),
                    lastModified = file.lastModified(),
                    isDirectory = isDir,
                    itemCount = count,
                    extension = ext
                )
            }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.getDefault()) }))

            Result.success(resultList)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun parseSessionNodes(account: CloudAccount, remotePath: String): List<FileItem>? {
        val cachedEntry = sessionNodesCache[account.id]
        val (allNodes, rootId) = if (cachedEntry != null) {
            Pair(cachedEntry.second, cachedEntry.first)
        } else {
            val sessionStr = getSessionPayload(account.id, account.sessionHandle)
            if (!sessionStr.contains("\"folders\":") && !sessionStr.contains("\"files\":")) {
                return null
            }
            try {
                val json = org.json.JSONObject(sessionStr)
                val mKey = json.optString("masterKey", "")
                if (mKey.isNotBlank()) {
                    masterKeyCache[account.id] = mKey
                }
                val arr = json.optJSONArray("folders") ?: json.optJSONArray("files") ?: org.json.JSONArray()
                val rId = json.optString("rootId")
                
                val parsedNodes = mutableListOf<MegaNode>()
                val gJunk = setOf(
                    "Close", "Cancel", "Search", "Shared with me", "Chia sẻ với tôi", 
                    "Shared Google Drive", "Shared drives", "Bộ nhớ dùng chung",
                    "Computers", "Máy tính", "Starred", "Có gắn dấu sao", 
                    "Trash", "Thùng rác", "Storage", "Bộ nhớ lưu trữ", 
                    "Get Drive for desktop", "Tải Drive cho máy tính", "New", "Mới", 
                    "Priority", "Ưu tiên", "Recent", "Gần đây", "Home", "Trang chủ", 
                    "My Drive", "Drive của tôi", "Activity", "Hoạt động", "Spam", "Thư rác", 
                    "Google Drive", "Sign in", "Đăng nhập", "Loading", "Đang tải", 
                    "Loading...", "Đang tải...", "Close overlay", "Details", "Chi tiết", 
                    "Đóng lớp phủ", "Open", "Mở", "Offline", "Ngoại tuyến",
                    "More options", "Open side menu", "Tùy chọn khác",
                    "Mở menu bên", "Search in Drive", "Tìm kiếm trong Drive"
                )

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val id = obj.optString("id")
                    val p = obj.optString("p", obj.optString("parent"))
                    val rawName = obj.optString("name")
                    val cleanName = rawName
                        .replace(Regex("^(Folder|Thư mục)\\s*[:\\-]?\\s*", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("\\s*[:\\-]?\\s*(Folder|Thư mục)$", RegexOption.IGNORE_CASE), "")
                        .trim()
                    val isDir = obj.optBoolean("isDir", true)
                    val size = obj.optLong("size", 0L)
                    val ts = obj.optLong("ts", 0L)
                    val t = obj.optInt("t", if (isDir) 1 else 0)
                    val k = obj.optString("k", "")
                    if (k.isNotBlank()) {
                        nodeKeyCache[id] = k
                        nodeKeyCache[cleanName] = k
                        nodeKeyCache["/$cleanName"] = k
                    }
                    if (id.isNotBlank() && cleanName.isNotBlank() && !gJunk.contains(cleanName) && !cleanName.startsWith("Switch from", ignoreCase = true) && !cleanName.startsWith("Chuyển sang", ignoreCase = true)) {
                        folderIdCache[id] = id
                        folderIdCache[cleanName] = id
                        folderIdCache[rawName] = id
                        folderIdCache["/$cleanName"] = id
                        parsedNodes.add(MegaNode(id, p, cleanName, isDir, size, ts, t, k))
                    }
                }
                sessionNodesCache[account.id] = Pair(rId, parsedNodes)
                Pair(parsedNodes, rId)
            } catch (e: Exception) {
                return null
            }
        }
        
        if (allNodes.isEmpty()) return null

            // Root-parent resolution is independent of remotePath and unchanged until allNodes
            // itself changes, so it's memoized instead of rescanned on every navigation.
            val rootResolution = resolveRoot(account.id, allNodes, rootId)
            val candidateRoots = rootResolution.candidateRoots
            val resolvedRootId = rootResolution.resolvedRootId

            // Determine current parent ID based on remotePath
            val currentParentId = if (remotePath == "/" || remotePath.isBlank() || remotePath == "root") {
                resolvedRootId
            } else {
                folderIdCache[remotePath] ?: run {
                    val segments = remotePath.split("/").filter { it.isNotBlank() }
                    var currParent = resolvedRootId
                    for (seg in segments) {
                        val matchingNode = allNodes.find { (it.p == currParent || currParent.isBlank()) && it.name.equals(seg, ignoreCase = true) && it.isDir }
                            ?: allNodes.find { it.name.equals(seg, ignoreCase = true) && it.isDir }
                        if (matchingNode != null) {
                            currParent = matchingNode.id
                        }
                    }
                    currParent
                }
            }

            val visibleNodes = if (currentParentId.isNotBlank()) {
                allNodes.filter { it.p == currentParentId }
            } else {
                val candidate = candidateRoots.find { parentId ->
                    val children = allNodes.filter { it.p == parentId }
                    children.any { it.name != "SyncDebris" && it.name != "Rubbish" && it.name != "Trash" && it.isDir }
                }
                if (candidate != null) allNodes.filter { it.p == candidate } else allNodes.filter { it.p == "root" || it.p.isBlank() }
            }

            return visibleNodes.map { node ->
                val itemDisplayPath = if (remotePath == "/" || remotePath.isBlank()) "/${node.name}" else "${remotePath.trimEnd('/')}/${node.name}"
                folderIdCache[itemDisplayPath] = node.id
                folderIdCache[node.id] = node.id
                folderIdCache[node.name] = node.id
                if (node.k.isNotBlank()) {
                    nodeKeyCache[itemDisplayPath] = node.k
                    nodeKeyCache[node.id] = node.k
                    nodeKeyCache[node.name] = node.k
                }
                val subItemCount = if (node.isDir) {
                    allNodes.count { it.p == node.id }
                } else 0

                val localFile = File(getAccountStorageDir(account.id), itemDisplayPath.trimStart('/'))
                val cacheFile = File(File(context.cacheDir, "cloud_downloads/${account.id}"), node.name)
                val thumbUri = if (localFile.exists()) localFile.absolutePath else if (cacheFile.exists() && cacheFile.length() > 0) cacheFile.absolutePath else null

                FileItem(
                    id = node.id,
                    name = node.name,
                    path = itemDisplayPath,
                    size = node.size,
                    lastModified = if (node.ts > 0) (if (node.ts > 1000000000000L) node.ts else node.ts * 1000L) else System.currentTimeMillis(),
                    isDirectory = node.isDir,
                    itemCount = subItemCount,
                    extension = if (!node.isDir) node.name.substringAfterLast('.', "") else "",
                    thumbnailUri = thumbUri,
                    folderBadgeType = if (node.isDir) detectFolderBadge(node.name) else FolderBadgeType.STANDARD
                )
            }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.getDefault()) }))
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

    suspend fun getAccountQuota(account: CloudAccount): Result<Pair<Long, Long>> = withContext(Dispatchers.IO) {
        // 1. Try querying cloud API client
        try {
            val apiQuota = when (account.provider) {
                CloudProvider.GOOGLE_DRIVE -> googleDriveApi.getStorageQuota(account)
                CloudProvider.DROPBOX -> dropboxApi.getSpaceUsage(account)
                CloudProvider.MEGA -> megaApi.getStorageQuota(account)
            }
            if (apiQuota.isSuccess) {
                val (apiTotal, apiUsed) = apiQuota.getOrThrow()
                if (apiTotal > 0L) {
                    var finalUsed = apiUsed
                    if (finalUsed == 0L) {
                        // Check session payload for exact used size
                        val sessionJson = getSessionPayload(account.id, account.sessionHandle)
                        if (sessionJson.isNotBlank()) {
                            val json = org.json.JSONObject(sessionJson)
                            val arr = json.optJSONArray("folders")
                            if (arr != null && arr.length() > 0) {
                                var sumBytes = 0L
                                for (i in 0 until arr.length()) {
                                    val obj = arr.getJSONObject(i)
                                    if (!obj.optBoolean("isDir", false)) {
                                        sumBytes += obj.optLong("size", 0L)
                                    }
                                }
                                if (sumBytes > 0L) finalUsed = sumBytes
                            }
                        }
                    }
                    return@withContext Result.success(Pair(apiTotal, finalUsed))
                }
            }
        } catch (e: Exception) {
            // Fall through
        }

        // 2. Fallback to session payload and local files
        val total = account.totalSpaceBytes ?: when (account.provider) {
            CloudProvider.GOOGLE_DRIVE -> 15L * 1024 * 1024 * 1024
            CloudProvider.DROPBOX -> 2L * 1024 * 1024 * 1024
            CloudProvider.MEGA -> 50L * 1024 * 1024 * 1024
        }
        var used = account.usedSpaceBytes ?: 0L

        try {
            val sessionJson = getSessionPayload(account.id, account.sessionHandle)
            if (sessionJson.isNotBlank()) {
                val json = org.json.JSONObject(sessionJson)
                val arr = json.optJSONArray("folders")
                if (arr != null && arr.length() > 0) {
                    var sumBytes = 0L
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        if (!obj.optBoolean("isDir", false)) {
                            sumBytes += obj.optLong("size", 0L)
                        }
                    }
                    if (sumBytes > 0L) {
                        used = sumBytes
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        if (used == 0L) {
            val accountDir = getAccountStorageDir(account.id)
            val localUsed = accountDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            if (localUsed > 0L) {
                used = localUsed
            }
        }
        Result.success(Pair(total, used))
    }

    /**
     * Fetches the provider's own pre-generated thumbnail (a few KB) instead of the full file.
     * Fails (not throws) when a provider has no thumbnail endpoint or the item has none, so
     * callers can fall back to downloading the full file.
     */
    suspend fun downloadThumbnail(account: CloudAccount, nodeId: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        when (account.provider) {
            CloudProvider.MEGA -> megaApi.downloadThumbnail(account, nodeId)
            CloudProvider.GOOGLE_DRIVE -> googleDriveApi.downloadThumbnail(account, nodeId)
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
                CloudProvider.MEGA -> megaApi.downloadFilePartial(account, nodeId, localTargetFile, maxBytes)
                CloudProvider.DROPBOX, CloudProvider.GOOGLE_DRIVE ->
                    Result.failure(Exception("Partial download not supported for ${account.provider}"))
            }
        }

    /**
     * MEGA-only: an on-demand decrypting [android.media.MediaDataSource] that fetches just the
     * byte ranges a reader (MediaMetadataRetriever, for thumbnail extraction) actually requests,
     * instead of eagerly downloading a fixed-size prefix. See
     * [com.antigravity.filemanager.data.remote.cloud.api.MegaApiClient.openThumbnailDataSource].
     */
    suspend fun openThumbnailDataSource(account: CloudAccount, nodeId: String): Result<android.media.MediaDataSource> =
        withContext(Dispatchers.IO) {
            when (account.provider) {
                CloudProvider.MEGA -> megaApi.openThumbnailDataSource(account, nodeId)
                CloudProvider.DROPBOX, CloudProvider.GOOGLE_DRIVE ->
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
            CloudProvider.MEGA, CloudProvider.GOOGLE_DRIVE ->
                Result.failure(Exception("Streamable link not supported for ${account.provider}"))
        }
    }

    /**
     * Like [getStreamableLink], but for the media viewers (image/video playback) rather than
     * the video-thumbnail frame grab — also covers Google Drive, whose media endpoint needs a
     * bearer token attached to the request rather than a bare pre-signed URL. Still unsupported
     * for MEGA (client-side encrypted; a raw range fetch would return ciphertext).
     */
    suspend fun getStreamSource(account: CloudAccount, remotePath: String): Result<com.antigravity.filemanager.domain.model.CloudStreamSource> =
        withContext(Dispatchers.IO) {
            when (account.provider) {
                CloudProvider.DROPBOX -> dropboxApi.getTemporaryLink(account, remotePath)
                    .map { com.antigravity.filemanager.domain.model.CloudStreamSource(it) }
                CloudProvider.GOOGLE_DRIVE -> {
                    // A Drive FileItem's `path` is a synthetic display path (e.g. "/My Drive/x.jpg"),
                    // not the real Drive file ID the media endpoint needs — same resolution
                    // [downloadFile] below uses to turn one back into an ID via folderIdCache.
                    val fileName = remotePath.substringAfterLast("/").ifEmpty { "cloud_file" }
                    val fileId = folderIdCache[remotePath]
                        ?: folderIdCache[remotePath.trimStart('/')]
                        ?: folderIdCache[fileName]
                        ?: folderIdCache["/$fileName"]
                        ?: remotePath
                    googleDriveApi.getAuthenticatedMediaUrl(account, fileId)
                }
                CloudProvider.MEGA -> Result.failure(Exception("Streaming not supported for ${account.provider}"))
            }
        }

    suspend fun downloadFile(
        account: CloudAccount,
        remotePath: String,
        localTargetDir: String,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val fileName = remotePath.substringAfterLast("/").ifEmpty { "cloud_file" }
            val accountDir = getAccountStorageDir(account.id)
            val localSource = File(accountDir, remotePath.trimStart('/'))
            val cacheDir = File(context.cacheDir, "cloud_downloads/${account.id}")
            val cacheFile = File(cacheDir, fileName)

            val destFile = File(localTargetDir, fileName)
            destFile.parentFile?.mkdirs()

            var localFile: File? = if (localSource.exists() && localSource.isFile) localSource else null
            if (localFile == null) {
                val altFile = File(accountDir, fileName)
                if (altFile.exists() && altFile.isFile) localFile = altFile
            }
            if (localFile == null && cacheFile.exists() && cacheFile.isFile && cacheFile.length() > 0) {
                localFile = cacheFile
            }
            if (localFile == null) {
                localFile = accountDir.walkTopDown().find { it.isFile && (it.name == fileName || it.name == remotePath || it.nameWithoutExtension == fileName) }
            }

            if (localFile != null && localFile.exists() && localFile.isFile) {
                if (destFile.absolutePath != localFile.absolutePath) {
                    if (destFile.exists()) {
                        destFile.delete()
                    }
                    val total = localFile.length()
                    localFile.inputStream().use { input ->
                        destFile.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var read: Int
                            var count = 0L
                            while (input.read(buf).also { read = it } != -1) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                output.write(buf, 0, read)
                                count += read
                                onProgress?.invoke(count, total)
                            }
                        }
                    }
                } else {
                    onProgress?.invoke(destFile.length(), destFile.length())
                }
                return@withContext Result.success(destFile)
            }

            val matchingNode = sessionNodesCache[account.id]?.second?.find {
                it.name == fileName || it.id == remotePath || it.id == folderIdCache[remotePath] || it.name.equals(fileName, ignoreCase = true)
            }

            val remoteResult = when (account.provider) {
                CloudProvider.GOOGLE_DRIVE -> {
                    val fileId = matchingNode?.id
                        ?: folderIdCache[remotePath]
                        ?: folderIdCache[remotePath.trimStart('/')]
                        ?: folderIdCache[fileName]
                        ?: folderIdCache["/$fileName"]
                        ?: remotePath
                    googleDriveApi.downloadFile(account, fileId, localTargetDir, fileName, onProgress)
                }
                CloudProvider.DROPBOX -> {
                    val path = if (remotePath.startsWith("/")) remotePath else "/$remotePath"
                    dropboxApi.downloadFile(account, path, localTargetDir, fileName, onProgress)
                }
                CloudProvider.MEGA -> {
                    val masterKeyFromSession = masterKeyCache[account.id] ?: try {
                        val sessionJson = getSessionPayload(account.id, account.sessionHandle)
                        if (sessionJson.isNotBlank()) {
                            val k = org.json.JSONObject(sessionJson).optString("masterKey", "")
                            if (k.isNotBlank()) masterKeyCache[account.id] = k
                            k
                        } else ""
                    } catch (e: Exception) { "" }

                    val effectiveMasterKey = account.refreshToken?.ifBlank { null } ?: masterKeyFromSession.ifBlank { null }
                    val effectiveAccount = if (account.refreshToken.isNullOrBlank() && !effectiveMasterKey.isNullOrBlank()) {
                        account.copy(refreshToken = effectiveMasterKey)
                    } else {
                        account
                    }
                    val nodeHandle = matchingNode?.id
                        ?: folderIdCache[remotePath]
                        ?: folderIdCache[remotePath.trimStart('/')]
                        ?: folderIdCache[fileName]
                        ?: folderIdCache["/$fileName"]
                        ?: remotePath.substringAfterLast("/").ifEmpty { remotePath }
                    val nodeKey = matchingNode?.k?.ifBlank { null }
                        ?: nodeKeyCache[remotePath]
                        ?: nodeKeyCache[remotePath.trimStart('/')]
                        ?: nodeKeyCache[nodeHandle]
                        ?: ""
                    megaApi.downloadFile(effectiveAccount, nodeHandle, localTargetDir, fileName, nodeKey, onProgress)
                }
            }

            if (remoteResult.isSuccess) {
                remoteResult
            } else {
                // Fallback to any matching file in account directory or cache directory if available
                val fallbackFile = accountDir.walkTopDown().find { it.isFile && (it.name == fileName || it.name == remotePath) } ?: if (cacheFile.exists() && cacheFile.length() > 0) cacheFile else null
                if (fallbackFile != null && fallbackFile.exists() && fallbackFile.isFile) {
                    if (destFile.absolutePath != fallbackFile.absolutePath) {
                        if (destFile.exists()) {
                            destFile.delete()
                        }
                        val total = fallbackFile.length()
                        fallbackFile.inputStream().use { input ->
                            destFile.outputStream().use { output ->
                                val buf = ByteArray(64 * 1024)
                                var read: Int
                                var count = 0L
                                while (input.read(buf).also { read = it } != -1) {
                                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                    output.write(buf, 0, read)
                                    count += read
                                    onProgress?.invoke(count, total)
                                }
                            }
                        }
                    } else {
                        onProgress?.invoke(destFile.length(), destFile.length())
                    }
                    Result.success(destFile)
                } else {
                    remoteResult
                }
            }
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
            val targetFolder = getAccountStorageDir(account.id, remoteTargetDir)
            val targetFile = File(targetFolder, srcFile.name)
            targetFolder.mkdirs()

            srcFile.inputStream().use { input ->
                targetFile.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var count = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        output.write(buf, 0, read)
                        count += read
                        onProgress?.invoke(count, totalBytes)
                    }
                }
            }

            var remoteFileId = ""
            // Every provider's real remote failure must be reported — silently keeping the local
            // mirror and returning Result.success(Unit) regardless of what the actual API call did
            // is what caused Dropbox/Drive uploads to appear to "do nothing": the user gets no
            // error, sees no toast, and the file just never shows up in their real cloud account.
            // MEGA additionally deletes its local mirror on failure (a MEGA account with no master
            // key can NEVER succeed later, so keeping a "pending" copy would be actively
            // misleading); Drive/Dropbox keep theirs since a transient OAuth/network error is more
            // plausibly retryable and the local copy is still real, usable data either way.
            var uploadFailure: Exception? = null
            try {
                when (account.provider) {
                    CloudProvider.GOOGLE_DRIVE -> {
                        val parentId = folderIdCache[remoteTargetDir] ?: folderIdCache[remoteTargetDir.trimStart('/')] ?: if (remoteTargetDir == "/" || remoteTargetDir.isBlank()) "root" else remoteTargetDir
                        val driveRes = googleDriveApi.uploadFile(account, srcFile, parentId, onProgress)
                        if (driveRes.isSuccess) {
                            remoteFileId = driveRes.getOrNull() ?: ""
                        } else {
                            uploadFailure = driveRes.exceptionOrNull() as? Exception ?: Exception("Google Drive upload failed")
                        }
                    }
                    CloudProvider.DROPBOX -> {
                        val dbxRes = dropboxApi.uploadFile(account, srcFile, remoteTargetDir, onProgress)
                        if (dbxRes.isSuccess) {
                            remoteFileId = dbxRes.getOrNull() ?: ""
                            // uploadFile() already patches the cached tree in place with the new
                            // entry — invalidating here would throw that away and force the next
                            // listing to do a full recursive re-fetch of the whole account just to
                            // show the one file that changed.
                        } else {
                            uploadFailure = dbxRes.exceptionOrNull() as? Exception ?: Exception("Dropbox upload failed")
                        }
                    }
                    CloudProvider.MEGA -> {
                        // Same master-key fallback chain used by listCloudFiles, so upload works
                        // whether the key came from a normal login or an imported session payload.
                        val masterKeyFromSession = masterKeyCache[account.id] ?: try {
                            val sessionJson = getSessionPayload(account.id, account.sessionHandle)
                            if (sessionJson.isNotBlank()) org.json.JSONObject(sessionJson).optString("masterKey", "") else ""
                        } catch (e: Exception) { "" }
                        val effectiveMasterKey = account.refreshToken?.ifBlank { null } ?: masterKeyFromSession.ifBlank { null }
                        val effectiveAccount = if (account.refreshToken.isNullOrBlank() && !effectiveMasterKey.isNullOrBlank()) {
                            account.copy(refreshToken = effectiveMasterKey)
                        } else {
                            account
                        }
                        val parentHandle = if (remoteTargetDir == "/" || remoteTargetDir.isBlank()) null else folderIdCache[remoteTargetDir] ?: remoteTargetDir
                        val megaRes = megaApi.uploadFile(effectiveAccount, srcFile, parentHandle, onProgress)
                        if (megaRes.isSuccess) {
                            remoteFileId = megaRes.getOrNull()?.id ?: ""
                        } else {
                            uploadFailure = megaRes.exceptionOrNull() as? Exception ?: Exception("MEGA upload failed")
                        }
                    }
                }
            } catch (e: Exception) {
                // Cancellation is not a failure — the caller (or its ViewModel scope) intentionally
                // stopped this transfer, so it must propagate up and cancel this coroutine normally,
                // not get reported to the user as "upload failed".
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("CloudManager", "Remote upload threw for ${account.provider}", e)
                uploadFailure = e
            }

            val failureToReport = uploadFailure
            if (failureToReport != null) {
                if (account.provider == CloudProvider.MEGA) targetFile.delete()
                return@withContext Result.failure(failureToReport)
            }

            val remotePath = if (remoteTargetDir == "/" || remoteTargetDir.isBlank()) "/${srcFile.name}" else "${remoteTargetDir.trimEnd('/')}/${srcFile.name}"
            val effectiveId = remoteFileId.ifBlank { targetFile.absolutePath }
            addOrUpdateSessionPayload(
                accountId = account.id,
                name = srcFile.name,
                remotePath = remotePath,
                fileId = effectiveId,
                parentPath = remoteTargetDir,
                size = srcFile.length(),
                lastModified = srcFile.lastModified(),
                isDir = false
            )

            onProgress?.invoke(totalBytes, totalBytes)
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun createFolder(account: CloudAccount, folderName: String, parentPath: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val parentFolder = getAccountStorageDir(account.id, parentPath)
            val newFolder = File(parentFolder, folderName)
            newFolder.mkdirs()

            val itemPath = if (parentPath == "/" || parentPath.isBlank()) "/$folderName" else "${parentPath.trimEnd('/')}/$folderName"

            // Also attempt remote creation. A failure here must be reported, not swallowed — the
            // caller (e.g. a recursive cloud-to-cloud folder copy) relies on this to actually
            // exist server-side before it starts uploading files into it; silently keeping only
            // the local mirror on failure is what let every "created" MEGA folder look fine here
            // while every upload into it failed downstream.
            var remoteId: String? = null
            var remoteFailure: Exception? = null
            try {
                when (account.provider) {
                    CloudProvider.GOOGLE_DRIVE -> {
                        // Same class of bug as MEGA below: parentPath is a display path, not a
                        // real Drive folder ID — resolve it via the cache first.
                        val resolvedParentId = if (parentPath == "/" || parentPath.isBlank()) {
                            "root"
                        } else {
                            folderIdCache[parentPath] ?: folderIdCache[parentPath.trimStart('/')] ?: parentPath
                        }
                        googleDriveApi.createFolder(account, folderName, resolvedParentId).onSuccess { item ->
                            remoteId = item.id
                            folderIdCache[itemPath] = item.id
                            folderIdCache[item.id] = item.id
                        }.onFailure {
                            remoteFailure = it as? Exception ?: Exception(it.message ?: "Google Drive create folder failed")
                        }
                    }
                    CloudProvider.DROPBOX -> {
                        dropboxApi.createFolder(account, itemPath).also { dropboxApi.invalidateTree(account.id) }
                    }
                    CloudProvider.MEGA -> {
                        // MEGA identifies folders by handle, not path — resolve the display path
                        // the same way listCloudFiles/uploadFile do (cache hit, else walk the tree),
                        // instead of handing the raw path string to megaApi.createFolder as if it
                        // were already a handle.
                        val resolvedParentHandle = if (parentPath == "/" || parentPath.isBlank()) {
                            null
                        } else {
                            folderIdCache[parentPath] ?: megaApi.resolveHandleForDisplayPath(account, parentPath) ?: parentPath
                        }
                        val createResult = megaApi.createFolder(account, folderName, resolvedParentHandle)
                        createResult.onSuccess { item ->
                            remoteId = item.id
                            folderIdCache[itemPath] = item.id
                            folderIdCache[item.id] = item.id
                        }.onFailure {
                            remoteFailure = it as? Exception ?: Exception(it.message ?: "MEGA create folder failed")
                        }
                    }
                }
            } catch (e: Exception) {
                remoteFailure = e
            }
            if (remoteFailure != null) {
                return@withContext Result.failure(remoteFailure!!)
            }

            // fileId here ends up in folderIdCache[itemPath] too (see addOrUpdateSessionPayload
            // below) — passing the local mirror path unconditionally, like this used to, clobbered
            // the correct remote handle/ID this function just resolved above with a path that is
            // meaningless to the actual provider API. Only fall back to the local path when there
            // truly is no remote id (Dropbox, which addresses everything by path anyway, or a
            // provider create that only partially succeeded).
            addOrUpdateSessionPayload(
                accountId = account.id,
                name = folderName,
                remotePath = itemPath,
                fileId = remoteId ?: newFolder.absolutePath,
                parentPath = parentPath,
                size = 0L,
                lastModified = System.currentTimeMillis(),
                isDir = true
            )

            Result.success(
                FileItem(
                    id = remoteId ?: newFolder.absolutePath,
                    name = folderName,
                    path = itemPath,
                    isDirectory = true,
                    itemCount = 0
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun addOrUpdateSessionPayload(
        accountId: String,
        name: String,
        remotePath: String,
        fileId: String,
        parentPath: String,
        size: Long,
        lastModified: Long,
        isDir: Boolean = false
    ) {
        try {
            val cached = sessionNodesCache[accountId]
            val resolvedParentId = if (parentPath == "/" || parentPath.isBlank() || parentPath == "root") {
                cached?.first ?: "root"
            } else {
                folderIdCache[parentPath] ?: folderIdCache[parentPath.trimStart('/')] ?: parentPath
            }

            val cleanId = fileId.ifBlank { remotePath }
            folderIdCache[remotePath] = cleanId
            folderIdCache[remotePath.trimStart('/')] = cleanId
            folderIdCache[name] = cleanId
            folderIdCache[cleanId] = cleanId

            // 1. Update memory cache
            if (cached != null) {
                val allNodes = cached.second.toMutableList()
                allNodes.removeAll { it.id == cleanId || (it.p == resolvedParentId && it.name == name) }
                allNodes.add(
                    MegaNode(
                        id = cleanId,
                        p = resolvedParentId,
                        name = name,
                        size = size,
                        ts = lastModified,
                        isDir = isDir,
                        t = if (isDir) 1 else 0,
                        k = ""
                    )
                )
                sessionNodesCache[accountId] = Pair(cached.first, allNodes)
            }

            // 2. Update persistent session JSON file
            val sessionFile = File(context.filesDir, "cloud_sessions/$accountId.json")
            if (sessionFile.exists()) {
                val text = sessionFile.readText()
                if (text.isNotBlank()) {
                    val json = org.json.JSONObject(text)
                    val key = if (json.has("folders")) "folders" else if (json.has("files")) "files" else "folders"
                    val arr = if (json.has(key)) json.getJSONArray(key) else org.json.JSONArray()
                    val newArr = org.json.JSONArray()
                    var replaced = false
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("id")
                        val p = obj.optString("p", obj.optString("parent"))
                        val n = obj.optString("name")
                        if (id == cleanId || (p == resolvedParentId && n == name)) {
                            val updatedObj = org.json.JSONObject().apply {
                                put("id", cleanId)
                                put("name", name)
                                put("p", resolvedParentId)
                                put("size", size)
                                put("ts", lastModified)
                                put("isDir", isDir)
                            }
                            newArr.put(updatedObj)
                            replaced = true
                        } else {
                            newArr.put(obj)
                        }
                    }
                    if (!replaced) {
                        newArr.put(org.json.JSONObject().apply {
                            put("id", cleanId)
                            put("name", name)
                            put("p", resolvedParentId)
                            put("size", size)
                            put("ts", lastModified)
                            put("isDir", isDir)
                        })
                    }
                    json.put(key, newArr)
                    sessionFile.writeText(json.toString())
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun deleteFromSessionPayload(accountId: String, remotePathOrId: String) {
        try {
            val targetId = folderIdCache[remotePathOrId] ?: folderIdCache[remotePathOrId.trimStart('/')] ?: remotePathOrId
            val fileName = remotePathOrId.substringAfterLast('/')

            // 1. Update memory cache
            val cached = sessionNodesCache[accountId]
            val nodesToRemove = mutableSetOf<String>()
            if (cached != null) {
                val allNodes = cached.second
                allNodes.filter { 
                    it.id == targetId || it.id == remotePathOrId || it.name == fileName || it.name == remotePathOrId.trimStart('/') 
                }.forEach { nodesToRemove.add(it.id) }

                // Find all descendants recursively if it's a directory
                var changed = true
                while (changed) {
                    val sizeBefore = nodesToRemove.size
                    allNodes.forEach { node ->
                        if (nodesToRemove.contains(node.p)) {
                            nodesToRemove.add(node.id)
                        }
                    }
                    changed = nodesToRemove.size > sizeBefore
                }

                val filteredNodes = allNodes.filterNot { nodesToRemove.contains(it.id) }
                sessionNodesCache[accountId] = Pair(cached.first, filteredNodes)
            }

            // 2. Update persistent session payload file
            val sessionFile = File(context.filesDir, "cloud_sessions/$accountId.json")
            if (sessionFile.exists()) {
                val text = sessionFile.readText()
                if (text.isNotBlank()) {
                    val json = org.json.JSONObject(text)
                    val key = if (json.has("folders")) "folders" else if (json.has("files")) "files" else null
                    if (key != null) {
                        val arr = json.getJSONArray(key)
                        val newArr = org.json.JSONArray()
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val id = obj.optString("id")
                            val name = obj.optString("name")
                            if (!nodesToRemove.contains(id) && id != targetId && id != remotePathOrId && name != fileName && name != remotePathOrId.trimStart('/')) {
                                newArr.put(obj)
                            }
                        }
                        json.put(key, newArr)
                        sessionFile.writeText(json.toString())
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun deleteItem(account: CloudAccount, remotePathOrId: String, moveToTrash: Boolean = true): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val targetId = folderIdCache[remotePathOrId] ?: folderIdCache[remotePathOrId.trimStart('/')] ?: remotePathOrId
            val fileName = remotePathOrId.substringAfterLast('/')

            // 1. Delete from local account storage directory
            val accountDir = getAccountStorageDir(account.id)
            val targetFile = File(accountDir, remotePathOrId.trimStart('/'))
            if (targetFile.exists()) {
                targetFile.deleteRecursively()
            }
            val altFile = File(accountDir, fileName)
            if (altFile.exists()) {
                altFile.deleteRecursively()
            }

            // 2. Delete from cache downloads
            val cacheDir = File(context.cacheDir, "cloud_downloads/${account.id}")
            val cacheFile = File(cacheDir, fileName)
            if (cacheFile.exists()) {
                cacheFile.delete()
            }

            // 3. Remove from session payload and in-memory caches
            deleteFromSessionPayload(account.id, remotePathOrId)
            folderIdCache.remove(remotePathOrId)
            folderIdCache.remove(remotePathOrId.trimStart('/'))
            folderIdCache.remove(targetId)

            // 4. Remote API deletion — moveToTrash routes to each provider's real trash/rubbish
            // bin where one exists (Google Drive, MEGA); Dropbox has no distinct trash API call
            // to make since Dropbox itself already keeps deleted files recoverable from its own
            // web UI for ~30 days regardless of which delete call is used.
            try {
                when (account.provider) {
                    CloudProvider.GOOGLE_DRIVE ->
                        if (moveToTrash) googleDriveApi.trashFile(account, targetId) else googleDriveApi.deleteFile(account, targetId)
                    CloudProvider.DROPBOX -> dropboxApi.delete(account, if (remotePathOrId.startsWith("/")) remotePathOrId else "/$remotePathOrId").also { dropboxApi.invalidateTree(account.id) }
                    CloudProvider.MEGA ->
                        (if (moveToTrash) megaApi.moveToRubbishBin(account, targetId) else megaApi.deleteNode(account, targetId))
                            .also { megaApi.invalidateNodeTreeCache(account.id) }
                }
            } catch (e: Exception) {
                // Ignore
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Restores an item out of the provider's real trash/rubbish bin. Google Drive and MEGA only
     * — Dropbox never routes deletes through a distinct trash API to begin with (see
     * [deleteItem]), so there's nothing here to restore from. */
    suspend fun restoreItem(account: CloudAccount, remotePathOrId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val targetId = folderIdCache[remotePathOrId] ?: folderIdCache[remotePathOrId.trimStart('/')] ?: remotePathOrId
        when (account.provider) {
            CloudProvider.GOOGLE_DRIVE -> googleDriveApi.restoreFromTrash(account, targetId)
            CloudProvider.MEGA -> megaApi.restoreFromRubbishBin(account, targetId).also { megaApi.invalidateNodeTreeCache(account.id) }
            CloudProvider.DROPBOX -> Result.failure(Exception("Restore not supported for Dropbox"))
        }
    }

    suspend fun renameItem(account: CloudAccount, remotePath: String, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        when (account.provider) {
            CloudProvider.DROPBOX -> dropboxApi.renameFile(account, remotePath, newName).also { dropboxApi.invalidateTree(account.id) }
            CloudProvider.GOOGLE_DRIVE -> {
                val targetId = folderIdCache[remotePath] ?: folderIdCache[remotePath.trimStart('/')] ?: remotePath
                googleDriveApi.renameFile(account, targetId, newName).map { FileItem(id = targetId, name = newName, path = remotePath) }
            }
            CloudProvider.MEGA -> {
                val targetId = folderIdCache[remotePath] ?: folderIdCache[remotePath.trimStart('/')] ?: remotePath
                megaApi.renameNode(account, targetId, newName)
                    .also { megaApi.invalidateNodeTreeCache(account.id) }
                    .map { FileItem(id = targetId, name = newName, path = remotePath) }
            }
        }
    }
}
