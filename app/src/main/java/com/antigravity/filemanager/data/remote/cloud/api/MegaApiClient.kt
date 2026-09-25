package com.antigravity.filemanager.data.remote.cloud.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.antigravity.filemanager.domain.model.CloudAccount
import com.antigravity.filemanager.domain.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.Key
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.RSAPrivateKeySpec
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MegaApiClient @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val okHttpClient: OkHttpClient
) {
    private val megaApiUrl = "https://g.api.mega.co.nz/cs"

    private companion object {
        const val NODE_TREE_TTL_MS = 5 * 60 * 1000L
    }

    // The MEGA "f" command always returns the ENTIRE account node tree (there is no
    // "children of X" endpoint), so every listFiles() call pays a full download + AES
    // decrypt of every node name. Cache the parsed tree per account so repeated navigation
    // (and the N+1 folder-item-count lookups) reuse it instead of re-fetching/re-decrypting the
    // whole account on every call. It used to be kept until a manual refresh at the account root,
    // so changes made from another device or the MEGA app never showed up; it now expires after
    // [NODE_TREE_TTL_MS] (this app's own changes patch or invalidate it immediately).
    private data class NodeTreeCache(val allNodes: List<MegaNode>, val rootHandle: String, val timestamp: Long)
    private val nodeTreeCache = ConcurrentHashMap<String, NodeTreeCache>()
    private val nodeTreeMutexes = ConcurrentHashMap<String, Mutex>()

    /** Drops the cached node tree for this account so the next [listFiles] re-fetches from network. */
    fun invalidateNodeTreeCache(accountId: String) {
        nodeTreeCache.remove(accountId)
    }

    // MEGA's "p" command (create-node — used by both createFolder and uploadFile's finalize
    // step) is NOT safe to send concurrently on the same session: firing several "p" requests
    // in parallel (e.g. a multi-file paste running with several files in flight at once) made
    // every single one come back with error -11 (EACCESS), even though the raw byte-upload "u"
    // step right before it is perfectly fine to parallelize. Serializing just this one command
    // per account keeps the actual data transfer concurrent while avoiding that failure.
    private val nodeCreationMutexes = ConcurrentHashMap<String, Mutex>()
    private fun nodeCreationMutexFor(accountId: String): Mutex = nodeCreationMutexes.getOrPut(accountId) { Mutex() }

    private fun nodeTreeMutexFor(accountId: String): Mutex =
        nodeTreeMutexes.getOrPut(accountId) { Mutex() }

    /** Adds a freshly created folder node into the cached tree in place, instead of the full
     * [invalidateNodeTreeCache] wipe createFolder used to always do. A recursive cloud-to-cloud
     * folder copy/move calls createFolder once per subfolder — on an account with tens of
     * thousands of nodes, invalidating on every single one meant every subfolder paid for a full
     * re-fetch + re-decrypt of the WHOLE account tree (each took several seconds here), which is
     * exactly what made "move a folder with nested subfolders" feel like it hung. No-ops if
     * nothing is cached yet; the next listFiles() fetches fresh anyway. */
    private fun patchNodeCacheAfterFolderCreate(accountId: String, handle: String, parentHandle: String, name: String, encodedKey: String) {
        val cached = nodeTreeCache[accountId] ?: return
        val newNode = MegaNode(
            handle = handle,
            parentHandle = parentHandle,
            type = 1,
            size = 0L,
            timestamp = System.currentTimeMillis(), // fetched nodes carry milliseconds too
            name = name,
            keyStr = encodedKey
        )
        val updated = cached.allNodes.filterNot { it.handle == handle } + newNode
        nodeTreeCache[accountId] = NodeTreeCache(updated, cached.rootHandle, cached.timestamp)
    }

    /** Same idea for a freshly uploaded file — this one matters even more than the folder-create
     * patch above, since a folder copy/move usually contains far more files than subfolders, and
     * this used to be an invalidateNodeTreeCache() on every single file: uploading N files into a
     * large MEGA account paid for N full account-tree re-fetches, which is what made "move a
     * folder" feel like it hung on an account with tens of thousands of nodes. keyStr is kept
     * functional (not left blank) — it's the encoded per-file key needed to decrypt this node's
     * name/thumbnail/content on any read before a real re-fetch eventually happens. */
    private fun patchNodeCacheAfterFileUpload(
        accountId: String,
        handle: String,
        parentHandle: String,
        name: String,
        size: Long,
        encodedKey: String,
        fileAttrStr: String
    ) {
        val cached = nodeTreeCache[accountId] ?: return
        val newNode = MegaNode(
            handle = handle,
            parentHandle = parentHandle,
            type = 0,
            size = size,
            timestamp = System.currentTimeMillis(), // fetched nodes carry milliseconds too
            name = name,
            keyStr = encodedKey,
            fileAttrStr = fileAttrStr
        )
        val updated = cached.allNodes.filterNot { it.handle == handle } + newNode
        nodeTreeCache[accountId] = NodeTreeCache(updated, cached.rootHandle, cached.timestamp)
    }

    fun resolveSid(account: CloudAccount): String {
        val session = account.sessionHandle ?: account.accessToken ?: ""
        if (session.isNotBlank() && session != "session_active" && !session.startsWith("{")) {
            return session
        }
        try {
            val sessionFile = File(context.filesDir, "cloud_sessions/${account.id}.json")
            if (sessionFile.exists()) {
                val json = JSONObject(sessionFile.readText())
                val sid = json.optString("sid", "")
                if (sid.isNotBlank()) return sid
            }
        } catch (e: Exception) {}
        return if (session != "session_active" && !session.startsWith("{")) session else ""
    }

    /** Command endpoint for this account's session. Every command goes through [resolveSid] —
     * several used the raw sessionHandle, which for an imported session is the placeholder
     * "session_active", so those commands all failed for such accounts. */
    private fun apiUrl(account: CloudAccount): String {
        val sid = resolveSid(account)
        return if (sid.isNotBlank() && !sid.contains("@")) "$megaApiUrl?sid=$sid" else megaApiUrl
    }

    private fun masterKey(account: CloudAccount): ByteArray? =
        account.refreshToken?.takeIf { it.isNotBlank() }?.let {
            try { base64UrlDecode(it) } catch (e: Exception) { null }
        }

    // Authenticates with MEGA CS API using Email & Password. Returns Pair(sid, masterKeyBase64).
    suspend fun login(email: String, password: String): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val cleanEmail = email.trim().lowercase(Locale.getDefault())
            
            // 1. Request user salt & version. NOTE: this is "us0" (version/salt lookup), a
            // different command from "us" (the actual login below) — they were previously
            // swapped with a third command name ("u"), which doesn't perform login at all.
            val userSaltReq = JSONArray().apply {
                put(JSONObject().apply {
                    put("a", "us0")
                    put("user", cleanEmail)
                })
            }.toString()

            val saltResponse = sendMegaPost("$megaApiUrl", userSaltReq)
            val saltStrRes = saltResponse.getOrNull()?.trim() ?: "[]"
            
            var v = 1
            var saltStr = ""
            if (!saltStrRes.startsWith("-") && saltStrRes.startsWith("[")) {
                try {
                    val saltArr = JSONArray(saltStrRes)
                    val saltObj = saltArr.optJSONObject(0) ?: JSONObject()
                    v = saltObj.optInt("v", 1)
                    saltStr = saltObj.optString("s", "")
                } catch (e: Exception) {
                    v = 1
                }
            }

            // 2. Derive Password Key + 3. Compute user hash (uh) — V1 and V2 accounts use
            // different algorithms for both, and mixing them (e.g. V1's stringHash on a V2 key)
            // produces a syntactically valid but wrong "uh" that MEGA rejects outright.
            val passwordKeyBytes: ByteArray
            val uh: String
            if (v == 2 && saltStr.isNotEmpty()) {
                val saltBytes = base64UrlDecode(saltStr)
                val fullKey = deriveKeyV2Full(password, saltBytes)
                passwordKeyBytes = fullKey.copyOfRange(0, 16)
                uh = base64UrlEncode(fullKey.copyOfRange(16, 32))
            } else {
                passwordKeyBytes = a32ToBytes(prepareKeyV1(password))
                uh = stringHash(cleanEmail, passwordKeyBytes)
            }
            val passwordKeyInts = bytesToA32(passwordKeyBytes)

            // 4. Authenticate user — the real login command is "us" (User Session).
            val authReq = JSONArray().apply {
                put(JSONObject().apply {
                    put("a", "us")
                    put("user", cleanEmail)
                    put("uh", uh)
                })
            }.toString()

            val authResponse = sendMegaPost("$megaApiUrl", authReq)
            val authStr = authResponse.getOrNull() ?: "[]"
            
            if (authStr.startsWith("-") || authStr.contains("[-")) {
                val errCode = authStr.replace("[", "").replace("]", "").trim()
                return@withContext Result.failure(Exception("MEGA login rejected (Error code: $errCode). Please verify your Email and Password."))
            }

            val authArr = JSONArray(authStr)
            val authObj = authArr.optJSONObject(0) ?: JSONObject()
            
            val directSid = authObj.optString("sid", "")
            val kStr = authObj.optString("k", "")
            val csidStr = authObj.optString("csid", "")
            val privkStr = authObj.optString("privk", "")

            var masterKeyA32: IntArray = passwordKeyInts
            var resolvedSid = directSid

            if (kStr.isNotEmpty()) {
                val encMasterKey = base64UrlDecode(kStr)
                val decMasterKey = aesEcbDecrypt(encMasterKey, passwordKeyBytes)
                masterKeyA32 = bytesToA32(decMasterKey)
            }

            if (resolvedSid.isEmpty() && csidStr.isNotEmpty() && privkStr.isNotEmpty()) {
                try {
                    // privk is AES-ECB encrypted (with the master key) MPI-encoded RSA private
                    // key components [p, q, d, u]; csid is that same account's RSA-encrypted
                    // (raw, unpadded) session id. Real RSA decryption is required here — there
                    // is no shortcut, MEGA's login response never exposes the session id in the
                    // clear.
                    val encPrivk = base64UrlDecode(privkStr)
                    val decPrivk = aesEcbDecrypt(encPrivk, a32ToBytes(masterKeyA32))
                    val rsaComponents = extractRsaPrivateKeyComponents(decPrivk)
                    val p = rsaComponents[0]
                    val q = rsaComponents[1]
                    val d = rsaComponents[2]
                    val csidMpi = mpiToBigInteger(base64UrlDecode(csidStr))
                    val rawSid = rsaDecryptRaw(csidMpi, p, q, d)
                    resolvedSid = base64UrlEncode(rawSid.copyOfRange(0, minOf(43, rawSid.size)))
                } catch (e: Exception) {
                    android.util.Log.e("MegaApiClient", "login: could not decrypt the session id", e)
                }
            }

            // A made-up sid used to be returned here as a "successful" login — every later call
            // then failed with confusing session errors instead of the login reporting it.
            if (resolvedSid.isEmpty()) {
                return@withContext Result.failure(Exception("MEGA login failed: no session id could be established"))
            }

            val masterKeyBase64 = base64UrlEncode(a32ToBytes(masterKeyA32))
            Result.success(Pair(resolvedSid, masterKeyBase64))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listFiles(account: CloudAccount, parentHandle: String? = null): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            val treeResult = getOrFetchNodeTree(account)
            val (allNodes, rootHandle) = treeResult.getOrElse {
                return@withContext Result.failure(it)
            }

            val isRootRequest = parentHandle.isNullOrBlank() || parentHandle == "root" || parentHandle == "/"
            val targetParent = if (isRootRequest) {
                rootHandle.ifEmpty { allNodes.firstOrNull { it.type == 2 }?.handle ?: "" }
            } else {
                parentHandle
            }

            // Group once (O(N)) instead of re-scanning the whole account tree per folder to get its
            // item count — the old `allNodes.count { it.parentHandle == node.handle }` inside the
            // .map below was O(folders_in_view × total_nodes_in_account), which got slow fast on
            // accounts with more than a few thousand nodes.
            val childrenByParent = allNodes.groupBy { it.parentHandle }

            val filtered = (childrenByParent[targetParent] ?: emptyList()).filter { it.type == 0 || it.type == 1 }
            val result = filtered.map { node ->
                val isDir = node.type == 1
                val ext = if (!isDir) node.name.substringAfterLast(".", "") else ""
                val children = if (isDir) childrenByParent[node.handle] else null
                val subfolders = children?.count { it.type == 1 } ?: 0
                val childFiles = children?.count { it.type == 0 } ?: 0
                FileItem(
                    id = node.handle,
                    name = node.name,
                    path = node.handle,
                    size = node.size,
                    lastModified = node.timestamp,
                    isDirectory = isDir,
                    itemCount = subfolders + childFiles,
                    subfolderCount = subfolders,
                    fileChildCount = childFiles,
                    extension = ext
                )
            }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.getDefault()) }))

            // The Rubbish Bin is a real node (type 4) already in the tree, but excluded from the
            // filter above like any other special node — surface it as a normal-looking folder
            // pinned at the end of the root listing (not part of the alphabetical sort) so it
            // behaves exactly like navigating into any other folder from here on.
            val withRubbish = if (isRootRequest) {
                val rubbish = allNodes.firstOrNull { it.type == 4 }
                if (rubbish != null) {
                    val rubbishChildren = childrenByParent[rubbish.handle]
                    val subfolders = rubbishChildren?.count { it.type == 1 } ?: 0
                    val childFiles = rubbishChildren?.count { it.type == 0 } ?: 0
                    result + FileItem(
                        id = rubbish.handle,
                        name = "Rubbish Bin",
                        path = rubbish.handle,
                        isDirectory = true,
                        itemCount = subfolders + childFiles,
                        subfolderCount = subfolders,
                        fileChildCount = childFiles,
                        folderBadgeType = com.antigravity.filemanager.domain.model.FolderBadgeType.TRASH
                    )
                } else result
            } else result

            Result.success(withRubbish)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Resolves a "/Name/Name" display path (built by CloudManager to mimic Dropbox/Drive's
     * real-path model, since MEGA identifies nodes by opaque handle, not path) back to the real
     * handle by walking the node tree name-by-name from root. CloudManager caches this result in
     * folderIdCache so it's normally an O(1) hit — this is only the fallback for a cold cache
     * (e.g. process just started and the user's first navigation lands directly on a nested
     * folder, such as via the "resume last folder" feature, without replaying every parent level
     * first). Without this, a cache miss fell back to treating the display-path STRING itself as
     * a handle, which never matches any real node and made the folder appear empty. */
    suspend fun resolveHandleForDisplayPath(account: CloudAccount, displayPath: String): String? {
        val segments = displayPath.trim('/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        val (allNodes, rootHandle) = getOrFetchNodeTree(account).getOrNull() ?: return null
        val childrenByParent = allNodes.groupBy { it.parentHandle }
        // "/Rubbish Bin/..." is the virtual entry listFiles() pins at the root, backed by the
        // type-4 node rather than a real child of the root folder.
        val rubbish = allNodes.firstOrNull { it.type == 4 }
        val startsInRubbish = segments.first() == "Rubbish Bin" && rubbish != null
        var currentHandle = if (startsInRubbish) rubbish!!.handle else rootHandle
        for (segment in if (startsInRubbish) segments.drop(1) else segments) {
            val match = childrenByParent[currentHandle]?.firstOrNull { it.type == 1 && it.name == segment } ?: return null
            currentHandle = match.handle
        }
        return currentHandle
    }

    /** Returns the cached (parsed, decrypted) node tree for this account, fetching it only on a
     * cold cache — the cache never expires on its own, callers must [invalidateNodeTreeCache]
     * (a manual refresh at the account root) to force a re-fetch. */
    private suspend fun getOrFetchNodeTree(account: CloudAccount): Result<Pair<List<MegaNode>, String>> {
        fun isFresh(cache: NodeTreeCache) = System.currentTimeMillis() - cache.timestamp < NODE_TREE_TTL_MS
        nodeTreeCache[account.id]?.takeIf(::isFresh)?.let { cached ->
            android.util.Log.d("MegaApiClient", "getOrFetchNodeTree: cache HIT (${cached.allNodes.size} nodes) for ${account.id}")
            return Result.success(cached.allNodes to cached.rootHandle)
        }
        // Serialize concurrent misses for the same account (e.g. N+1 folder-count lookups)
        // so they share one network fetch instead of each re-downloading the whole tree.
        return nodeTreeMutexFor(account.id).withLock {
            val stale = nodeTreeCache[account.id]
            stale?.takeIf(::isFresh)?.let { recheck ->
                android.util.Log.d("MegaApiClient", "getOrFetchNodeTree: cache HIT after lock (${recheck.allNodes.size} nodes) for ${account.id}")
                return@withLock Result.success(recheck.allNodes to recheck.rootHandle)
            }
            android.util.Log.d("MegaApiClient", "getOrFetchNodeTree: cache ${if (stale == null) "MISS" else "EXPIRED"} — fetching tree from network for ${account.id}")
            val fetched = fetchNodeTreeFromNetwork(account)
            fetched.onSuccess { (nodes, root) ->
                nodeTreeCache[account.id] = NodeTreeCache(nodes, root, System.currentTimeMillis())
            }
            // An expired tree still beats an error while offline or rate-limited.
            if (fetched.isFailure && stale != null) Result.success(stale.allNodes to stale.rootHandle) else fetched
        }
    }

    private fun fetchNodeTreeFromNetwork(account: CloudAccount): Result<Pair<List<MegaNode>, String>> {
        try {
            val url = apiUrl(account)

            // Request node tree from Mega CS API
            val commandJson = JSONArray().apply {
                put(JSONObject().apply {
                    put("a", "f")
                    put("c", 1)
                    put("r", 1)
                })
            }.toString()

            val response = sendMegaPost(url, commandJson)
            if (!response.isSuccess) {
                return Result.failure(Exception("Mega API Error: ${response.exceptionOrNull()?.message}"))
            }

            val bodyStr = response.getOrNull() ?: "[]"
            if (bodyStr.startsWith("-") || bodyStr.contains("[-")) {
                return Result.failure(Exception("MEGA session expired or invalid: $bodyStr"))
            }

            val jsonArray = JSONArray(bodyStr)
            if (jsonArray.length() == 0) return Result.success(emptyList<MegaNode>() to "")

            val firstObj = jsonArray.optJSONObject(0) ?: JSONObject()
            val nodes = firstObj.optJSONArray("f") ?: JSONArray()

            var rootHandle = ""
            val allNodes = mutableListOf<MegaNode>()

            // Master key for decrypting file/folder names
            val masterKeyBytes = masterKey(account)

            for (i in 0 until nodes.length()) {
                val nodeObj = nodes.getJSONObject(i)
                val handle = nodeObj.optString("h")
                val parent = nodeObj.optString("p")
                val type = nodeObj.optInt("t") // 0: file, 1: folder, 2: root, 3: inbox, 4: trash
                val size = nodeObj.optLong("s", 0L)
                val ts = nodeObj.optLong("ts", 0L) * 1000L
                val attrStr = nodeObj.optString("a", "")
                val keyStr = nodeObj.optString("k", "")
                val faStr = nodeObj.optString("fa", "")

                var name = "Item_$handle"

                // Decrypt node name
                if (attrStr.isNotEmpty()) {
                    name = decryptNodeAttributes(attrStr, keyStr, masterKeyBytes) ?: if (type == 1 || type == 2) "Folder_$handle" else "File_$handle"
                }

                if (type == 2) {
                    rootHandle = handle
                }

                allNodes.add(
                    MegaNode(
                        handle = handle,
                        parentHandle = parent,
                        type = type,
                        size = size,
                        timestamp = ts,
                        name = name,
                        keyStr = keyStr,
                        fileAttrStr = faStr
                    )
                )
            }

            return Result.success(allNodes to rootHandle)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    suspend fun getStorageQuota(account: CloudAccount): Result<Pair<Long, Long>> = withContext(Dispatchers.IO) {
        try {
            val url = apiUrl(account)

            val commandJson = JSONArray().apply {
                put(JSONObject().apply {
                    put("a", "uq")
                    put("strg", 1)
                })
            }.toString()

            val bodyStr = sendMegaPost(url, commandJson).getOrElse { return@withContext Result.failure(it) }
            // Errors (expired session etc.) used to come back as a made-up 20 GB / 0 used success.
            val obj = JSONArray(bodyStr).optJSONObject(0)
                ?: return@withContext Result.failure(Exception("MEGA quota request failed: $bodyStr"))
            val total = obj.optLong("mstrg", 0L)
            if (total <= 0L) return@withContext Result.failure(Exception("MEGA quota missing: $bodyStr"))
            Result.success(Pair(total, obj.optLong("cstrg", 0L)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUserEmail(account: CloudAccount): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = apiUrl(account)

            val commandJson = JSONArray().apply {
                put(JSONObject().apply {
                    put("a", "ug")
                })
            }.toString()

            val response = sendMegaPost(url, commandJson)
            val bodyStr = response.getOrNull() ?: "[]"
            val jsonArray = JSONArray(bodyStr)
            val obj = jsonArray.optJSONObject(0) ?: JSONObject()
            val email = obj.optString("email", "").ifBlank {
                val emails = obj.optJSONArray("emails")
                if (emails != null && emails.length() > 0) emails.optString(0, "") else ""
            }
            if (email.isNotBlank()) {
                Result.success(email)
            } else {
                Result.failure(Exception("Email not found in MEGA response"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Downloading several files in parallel (the paste-flow's Semaphore(8)) occasionally hits
    // "Unexpected status line: <garbage bytes>" — OkHttp misreading leftover body bytes from a
    // reused connection as the start of a new HTTP response. It's transient (a fresh attempt
    // reliably succeeds), so retry a couple of times instead of failing the file outright, same
    // idea as the Dropbox rate-limit retry in DropboxApiClient.uploadFile.
    suspend fun downloadFile(
        account: CloudAccount,
        nodeHandle: String,
        localTargetDir: String,
        fileName: String,
        explicitNodeKey: String = "",
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> {
        var lastFailure: Result<File>? = null
        repeat(3) { attempt ->
            val result = downloadFileAttempt(account, nodeHandle, localTargetDir, fileName, explicitNodeKey, onProgress)
            if (result.isSuccess) return result
            val e = result.exceptionOrNull()
            if (e is java.io.IOException) {
                android.util.Log.d("MegaApiClient", "downloadFile: transient failure for '$fileName' (attempt ${attempt + 1}/3), retrying: ${e.message}")
                lastFailure = result
            } else {
                return result
            }
        }
        return lastFailure ?: Result.failure(Exception("downloadFile: exhausted retries for '$fileName'"))
    }

    private suspend fun downloadFileAttempt(
        account: CloudAccount,
        nodeHandle: String,
        localTargetDir: String,
        fileName: String,
        explicitNodeKey: String = "",
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val url = apiUrl(account)

            val commandJson = JSONArray().apply {
                put(JSONObject().apply {
                    put("a", "g")
                    put("g", 1)
                    put("n", nodeHandle)
                })
            }.toString()

            val response = sendMegaPost(url, commandJson)
            val bodyStr = response.getOrNull() ?: "[]"
            val jsonArray = JSONArray(bodyStr)
            val obj = jsonArray.optJSONObject(0) ?: JSONObject()
            val downloadUrl = obj.optString("g")
            val reportedSize = obj.optLong("s", 0L)

            if (downloadUrl.isBlank()) {
                android.util.Log.e("MegaApiClient", "downloadFile: no download URL in response for node=$nodeHandle file='$fileName': $bodyStr")
                return@withContext Result.failure(Exception("Download URL not found in Mega response: $bodyStr"))
            }

            val dlRequest = Request.Builder().url(downloadUrl).get().build()
            val dlResponse = okHttpClient.newCall(dlRequest).execute()
            if (!dlResponse.isSuccessful) {
                android.util.Log.e("MegaApiClient", "downloadFile: HTTP ${dlResponse.code} streaming node=$nodeHandle file='$fileName'")
                dlResponse.close()
                return@withContext Result.failure(Exception("Failed to stream Mega file: ${dlResponse.code}"))
            }

            val targetFile = File(localTargetDir, fileName)
            targetFile.parentFile?.mkdirs()
            if (targetFile.exists()) {
                targetFile.delete()
            }

            val totalBytes = if (reportedSize > 0) reportedSize else (dlResponse.body?.contentLength() ?: 0L)

            val masterKey = masterKey(account)

            val candidateKey = explicitNodeKey.ifBlank { obj.optString("k", "") }

            suspend fun saveDecrypted(input: java.io.InputStream, cipher: Cipher) {
                CipherInputStream(input, cipher).use { cipherIn ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead = 0L
                        var read: Int
                        while (cipherIn.read(buffer).also { read = it } != -1) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            output.write(buffer, 0, read)
                            bytesRead += read
                            onProgress?.invoke(bytesRead, totalBytes)
                        }
                    }
                }
                onProgress?.invoke(targetFile.length(), targetFile.length())
            }

            // Primary path: resolve the node's real key+nonce from the cached account tree (the
            // authoritative source — same lookup downloadThumbnail() uses). MEGA's "g" response
            // never actually contains "k" (confirmed against other MEGA clients), and nothing in
            // the current login/listing flow ever populates explicitNodeKey either, so without
            // this every real download would fall through to the empty legacy fallbacks below and
            // fail with "Could not resolve a decryption key".
            if (masterKey != null) {
                val treeResult = getOrFetchNodeTree(account)
                val node = treeResult.getOrNull()?.first?.find { it.handle == nodeHandle }
                if (node != null && node.keyStr.isNotBlank()) {
                    val resolved = deriveNodeContentKeyAndNonce(node.keyStr, masterKey)
                    if (resolved != null) {
                        val (aesKey, nonce) = resolved
                        val ivBytes = ByteArray(16)
                        nonce.copyInto(ivBytes, 0)
                        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(ivBytes))
                        dlResponse.body?.byteStream()?.use { input ->
                            saveDecrypted(input, cipher)
                        }
                        return@withContext Result.success(targetFile)
                    }
                }
            }

            if (candidateKey.isNotEmpty()) {
                // 1. Comma-separated decrypted integer array (e.g. from JS window.M.d[id].k)
                if (candidateKey.contains(",")) {
                    try {
                        val a32 = candidateKey.split(",").mapNotNull { it.trim().toIntOrNull() }.toIntArray()
                        if (a32.size >= 8) {
                            val keyInts = intArrayOf(
                                a32[0] xor a32[4],
                                a32[1] xor a32[5],
                                a32[2] xor a32[6],
                                a32[3] xor a32[7]
                            )
                            val aesKey = a32ToBytes(keyInts)
                            val ivBytes = ByteArray(16)
                            val ivInts = intArrayOf(a32[4], a32[5], 0, 0)
                            val ivRaw = a32ToBytes(ivInts)
                            System.arraycopy(ivRaw, 0, ivBytes, 0, 16)

                            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(ivBytes))

                            dlResponse.body?.byteStream()?.use { input ->
                                saveDecrypted(input, cipher)
                            }
                            return@withContext Result.success(targetFile)
                        } else if (a32.size >= 4) {
                            val aesKey = a32ToBytes(a32.take(4).toIntArray())
                            val ivBytes = ByteArray(16)
                            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(ivBytes))

                            dlResponse.body?.byteStream()?.use { input ->
                                saveDecrypted(input, cipher)
                            }
                            return@withContext Result.success(targetFile)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // 2. Base64-encoded encrypted node key
                if (masterKey != null) {
                    try {
                        val cleanKey = if (candidateKey.contains(":")) candidateKey.substringAfter(":") else candidateKey
                        val encNodeKey = base64UrlDecode(cleanKey)
                        if (encNodeKey.isNotEmpty() && encNodeKey.size % 16 == 0) {
                            val nodeKeyBytes = aesEcbDecrypt(encNodeKey, masterKey)
                            val nodeKeyA32 = bytesToA32(nodeKeyBytes)
                            if (nodeKeyA32.size >= 8) {
                                val keyInts = intArrayOf(
                                    nodeKeyA32[0] xor nodeKeyA32[4],
                                    nodeKeyA32[1] xor nodeKeyA32[5],
                                    nodeKeyA32[2] xor nodeKeyA32[6],
                                    nodeKeyA32[3] xor nodeKeyA32[7]
                                )
                                val aesKey = a32ToBytes(keyInts)
                                val ivBytes = ByteArray(16)
                                val ivInts = intArrayOf(nodeKeyA32[4], nodeKeyA32[5], 0, 0)
                                val ivRaw = a32ToBytes(ivInts)
                                System.arraycopy(ivRaw, 0, ivBytes, 0, 16)

                                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(ivBytes))

                                dlResponse.body?.byteStream()?.use { input ->
                                    saveDecrypted(input, cipher)
                                }
                                return@withContext Result.success(targetFile)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            dlResponse.close()
            android.util.Log.e("MegaApiClient", "downloadFile: could not resolve a decryption key for node $nodeHandle (file='$fileName')")
            Result.failure(Exception("Could not resolve a decryption key for node $nodeHandle"))
        } catch (e: Exception) {
            // Never leave a truncated file behind that looks like a finished download.
            File(localTargetDir, fileName).delete()
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("MegaApiClient", "downloadFile: FAILED for node=$nodeHandle file='$fileName'", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads only the first [maxBytes] of a file's ciphertext (via an HTTP Range request) and
     * decrypts them in place. Fallback for [openThumbnailDataSource] when the on-demand path
     * fails for some reason — AES-CTR is a keystream cipher, so decrypting bytes [0, maxBytes)
     * doesn't require the rest of the file. Best-effort only: fails gracefully (Result.failure)
     * if key resolution fails or the server ignores the Range header.
     */
    suspend fun downloadFilePartial(
        account: CloudAccount,
        nodeHandle: String,
        localTargetFile: File,
        maxBytes: Long
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val url = apiUrl(account)

            val commandJson = JSONArray().apply {
                put(JSONObject().apply {
                    put("a", "g")
                    put("g", 1)
                    put("n", nodeHandle)
                })
            }.toString()

            val response = sendMegaPost(url, commandJson)
            val bodyStr = response.getOrNull() ?: "[]"
            val jsonArray = JSONArray(bodyStr)
            val obj = jsonArray.optJSONObject(0) ?: JSONObject()
            val downloadUrl = obj.optString("g")
            if (downloadUrl.isBlank()) {
                return@withContext Result.failure(Exception("Download URL not found in Mega response: $bodyStr"))
            }

            val masterKeyOrNull = masterKey(account)
            val masterKey = masterKeyOrNull
                ?: return@withContext Result.failure(Exception("No master key available"))

            val treeResult = getOrFetchNodeTree(account)
            val node = treeResult.getOrNull()?.first?.find { it.handle == nodeHandle }
                ?: return@withContext Result.failure(Exception("Node not found in cached tree"))
            val (aesKey, nonce) = deriveNodeContentKeyAndNonce(node.keyStr, masterKey)
                ?: return@withContext Result.failure(Exception("Could not resolve a decryption key for node $nodeHandle"))

            val rangeEnd = ((maxBytes + 15) / 16) * 16 - 1

            val dlRequest = Request.Builder()
                .url(downloadUrl)
                .header("Range", "bytes=0-$rangeEnd")
                .get()
                .build()
            val dlResponse = okHttpClient.newCall(dlRequest).execute()
            if (!dlResponse.isSuccessful) {
                dlResponse.close()
                return@withContext Result.failure(Exception("Failed to stream partial Mega file: ${dlResponse.code}"))
            }

            val ivBytes = ByteArray(16)
            nonce.copyInto(ivBytes, 0)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(ivBytes))

            localTargetFile.parentFile?.mkdirs()
            if (localTargetFile.exists()) localTargetFile.delete()

            val body = dlResponse.body ?: return@withContext Result.failure(Exception("Empty response body"))
            body.byteStream().use { input ->
                CipherInputStream(input, cipher).use { cipherIn ->
                    FileOutputStream(localTargetFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var totalRead = 0L
                        var read = 0
                        while (totalRead < maxBytes && cipherIn.read(buffer).also { read = it } != -1) {
                            currentCoroutineContext().ensureActive()
                            val toWrite = minOf(read.toLong(), maxBytes - totalRead).toInt()
                            output.write(buffer, 0, toWrite)
                            totalRead += toWrite
                        }
                    }
                }
            }

            Result.success(localTargetFile)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Resolves everything needed to read a node's ciphertext on demand — the temporary download
     * URL, content key/nonce, and total size — without downloading anything. Feeds
     * [MegaDecryptingDataSource], which fetches+decrypts only the byte ranges actually requested
     * (e.g. by MediaMetadataRetriever while probing for a thumbnail frame), instead of eagerly
     * pulling a fixed-size prefix. Typically far cheaper: a retriever probing a well-formed video
     * usually only touches a small header region plus one keyframe — tens/hundreds of KB, not a
     * flat multi-MB block.
     */
    suspend fun openThumbnailDataSource(
        account: CloudAccount,
        nodeHandle: String,
        fetchWindowBytes: Long = MegaDecryptingDataSource.DEFAULT_FETCH_WINDOW
    ): Result<android.media.MediaDataSource> =
        withContext(Dispatchers.IO) {
            try {
                val url = apiUrl(account)

                val commandJson = JSONArray().apply {
                    put(JSONObject().apply {
                        put("a", "g")
                        put("g", 1)
                        put("n", nodeHandle)
                    })
                }.toString()

                val response = sendMegaPost(url, commandJson)
                val bodyStr = response.getOrNull() ?: "[]"
                val jsonArray = JSONArray(bodyStr)
                val obj = jsonArray.optJSONObject(0) ?: JSONObject()
                val downloadUrl = obj.optString("g")
                val totalSize = obj.optLong("s", 0L)
                if (downloadUrl.isBlank() || totalSize <= 0L) {
                    return@withContext Result.failure(Exception("Download URL/size not found in Mega response: $bodyStr"))
                }

                val masterKeyOrNull = masterKey(account)
                val masterKey = masterKeyOrNull
                    ?: return@withContext Result.failure(Exception("No master key available"))

                val treeResult = getOrFetchNodeTree(account)
                val node = treeResult.getOrNull()?.first?.find { it.handle == nodeHandle }
                    ?: return@withContext Result.failure(Exception("Node not found in cached tree"))
                val (aesKey, nonce) = deriveNodeContentKeyAndNonce(node.keyStr, masterKey)
                    ?: return@withContext Result.failure(Exception("Could not resolve a decryption key for node $nodeHandle"))

                Result.success(MegaDecryptingDataSource(downloadUrl, aesKey, nonce, totalSize, okHttpClient, fetchWindowBytes))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Result.failure(e)
            }
        }

    suspend fun createFolder(account: CloudAccount, name: String, parentHandle: String?): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val url = apiUrl(account)

            // Same root-fallback as uploadFile(): a blank/"/" parentHandle means "account root",
            // which is a real handle from the node tree, never the literal string "/" — sending
            // that raw string as "t" targets a node that doesn't exist, so MEGA rejects the whole
            // command (this was silently producing folders whose real parent was garbage, and
            // every file uploaded into "them" afterwards then failed with -11 EACCESS).
            val resolvedParentHandle = if (parentHandle.isNullOrBlank() || parentHandle == "root" || parentHandle == "/") {
                val (_, rootHandle) = getOrFetchNodeTree(account).getOrElse {
                    return@withContext Result.failure(it)
                }
                rootHandle
            } else {
                parentHandle
            }
            if (resolvedParentHandle.isBlank()) {
                return@withContext Result.failure(Exception("Could not resolve MEGA parent folder"))
            }

            // Like any MEGA node, a folder needs its own random key, its attributes encrypted with
            // that key, and the key itself sent wrapped with the master key. The name used to go
            // out as plaintext JSON with no key at all: official MEGA clients showed such folders
            // as undecryptable, and a name containing a quote produced invalid attribute JSON.
            val masterKey = masterKey(account) ?: return@withContext Result.failure(Exception("No master key available"))
            val folderKey = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val attrJson = JSONObject().apply { put("n", name) }.toString()
            val encodedAttr = base64UrlEncode(aesCbcEncrypt(padTo16("MEGA$attrJson".toByteArray(StandardCharsets.UTF_8)), folderKey, ByteArray(16)))
            val encodedKey = base64UrlEncode(aesEcbEncrypt(folderKey, masterKey))
            val commandJson = JSONArray().apply {
                put(JSONObject().apply {
                    put("a", "p")
                    put("t", resolvedParentHandle)
                    put("n", JSONArray().apply {
                        put(JSONObject().apply {
                            put("h", "xxxxxxxx")
                            put("t", 1)
                            put("a", encodedAttr)
                            put("k", encodedKey)
                        })
                    })
                })
            }.toString()

            val realHandle = nodeCreationMutexFor(account.id).withLock {
                val respBody = sendMegaPost(url, commandJson).getOrNull() ?: "[]"
                if (respBody.startsWith("-") || respBody.contains("[-")) {
                    return@withContext Result.failure(Exception("MEGA create folder failed: $respBody"))
                }

                // The server assigns the real handle; it's only available in the "p" response body
                // (response[0].f[0].h) — a client-fabricated id would be unusable as a parentHandle
                // for any node created inside this folder afterwards (e.g. recursive folder upload).
                val respArr = JSONArray(respBody)
                val fArr = respArr.optJSONObject(0)?.optJSONArray("f")
                val handle = fArr?.optJSONObject(0)?.optString("h") ?: ""
                if (handle.isNotBlank()) {
                    patchNodeCacheAfterFolderCreate(account.id, handle, resolvedParentHandle, name, encodedKey)
                }
                handle
            }
            if (realHandle.isBlank()) {
                return@withContext Result.failure(Exception("MEGA did not return a folder handle"))
            }

            Result.success(
                FileItem(
                    id = realHandle,
                    name = name,
                    path = realHandle,
                    isDirectory = true
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Renames a node via MEGA's "a":"a" (setattr) command — the node's encrypted attribute block
     * ("MEGA" + JSON, AES-CBC with the node's own content key, zero IV) is entirely replaced, so
     * this only sets `{"n": newName}` rather than trying to preserve/merge other attributes
     * (fingerprint "c" etc. aren't required for the node to keep working). The node's key itself
     * doesn't change on a plain rename, so it isn't resent.
     */
    suspend fun renameNode(account: CloudAccount, nodeHandle: String, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val masterKeyOrNull = masterKey(account)
            val masterKey = masterKeyOrNull ?: return@withContext Result.failure(Exception("No master key available"))

            val treeResult = getOrFetchNodeTree(account)
            val allNodes = treeResult.getOrElse { return@withContext Result.failure(it) }.first
            val node = allNodes.firstOrNull { it.handle == nodeHandle }
                ?: return@withContext Result.failure(Exception("Node not found in cached tree"))
            val nodeKeyBytes = deriveNodeKeyBytes(node.keyStr, masterKey)
                ?: return@withContext Result.failure(Exception("Could not resolve a decryption key for node $nodeHandle"))

            val attrJson = JSONObject().apply { put("n", newName) }.toString()
            val attrBytes = padTo16("MEGA$attrJson".toByteArray(StandardCharsets.UTF_8))
            val encodedAttr = base64UrlEncode(aesCbcEncrypt(attrBytes, nodeKeyBytes, ByteArray(16)))

            val url = apiUrl(account)

            val commandJson = JSONArray().apply {
                put(JSONObject().apply {
                    put("a", "a")
                    put("n", nodeHandle)
                    put("at", encodedAttr)
                })
            }.toString()

            val response = sendMegaPost(url, commandJson)
            val bodyStr = response.getOrElse { return@withContext Result.failure(it) }.trim()
            if (megaErrorCode(bodyStr) != null) {
                return@withContext Result.failure(Exception("MEGA rename failed: $bodyStr"))
            }
            invalidateNodeTreeCache(account.id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteNode(account: CloudAccount, nodeHandle: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // A "/"-prefixed value here means the caller never resolved a real MEGA handle and
            // fell back to the raw display path — sending that as "n" doesn't error out on MEGA's
            // side, it silently no-ops, which is exactly what made "delete"/"move" look like they
            // succeeded while the node was untouched. Fail loudly instead.
            if (nodeHandle.startsWith("/")) {
                return@withContext Result.failure(Exception("Could not resolve a MEGA handle for '$nodeHandle'"))
            }
            val url = apiUrl(account)

            val commandJson = JSONArray().apply {
                put(JSONObject().apply {
                    put("a", "d")
                    put("n", nodeHandle)
                })
            }.toString()

            val response = sendMegaPost(url, commandJson)
            val bodyStr = response.getOrElse { return@withContext Result.failure(it) }.trim()
            if (megaErrorCode(bodyStr) != null) {
                return@withContext Result.failure(Exception("MEGA delete failed: $bodyStr"))
            }
            invalidateNodeTreeCache(account.id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Deletes many nodes in as few HTTP round trips as possible — MEGA's command endpoint takes
     * an array of commands and executes all of them server-side per request, so batching many
     * "a":"d" commands into one POST turns what would be N sequential round trips (the original
     * per-node [deleteNode] loop, unusable for a large Rubbish Bin — 800+ items took over an hour)
     * into roughly N/batchSize. Chunked rather than one giant array so a single oversized request
     * doesn't risk a timeout or a server-side response-size limit. Returns a per-handle Result so
     * one bad node (e.g. a stale/orphaned handle) doesn't hide the outcome of the rest of the
     * batch it shipped with. */
    suspend fun deleteNodesBatch(account: CloudAccount, nodeHandles: List<String>): Map<String, Result<Unit>> = withContext(Dispatchers.IO) {
        val results = LinkedHashMap<String, Result<Unit>>()
        val (validHandles, invalidHandles) = nodeHandles.partition { !it.startsWith("/") }
        // Same "never send a raw display path as if it were a handle" guard as deleteNode.
        invalidHandles.forEach { results[it] = Result.failure(Exception("Could not resolve a MEGA handle for '$it'")) }
        if (validHandles.isEmpty()) return@withContext results

        val url = apiUrl(account)

        validHandles.chunked(25).forEach { batch ->
            val commandJson = JSONArray().apply {
                batch.forEach { handle ->
                    put(JSONObject().apply {
                        put("a", "d")
                        put("n", handle)
                    })
                }
            }.toString()
            val response = sendMegaPost(url, commandJson)
            if (response.isFailure) {
                val error = response.exceptionOrNull() ?: Exception("MEGA batch delete request failed")
                batch.forEach { results[it] = Result.failure(error) }
                return@forEach
            }
            try {
                val jsonArray = JSONArray(response.getOrNull() ?: "[]")
                batch.forEachIndexed { index, handle ->
                    // A success entry is 0 (or occasionally another non-negative value); MEGA
                    // reports a per-command failure as a negative error code at that same index.
                    val code = if (index < jsonArray.length()) jsonArray.optDouble(index, 0.0) else -1.0
                    results[handle] = if (code < 0) {
                        Result.failure(Exception("MEGA delete failed: $code"))
                    } else {
                        Result.success(Unit)
                    }
                }
            } catch (e: Exception) {
                // Response wasn't the expected array shape at all — treat the whole batch as
                // failed rather than guessing.
                batch.forEach { results[it] = Result.failure(e) }
            }
        }
        invalidateNodeTreeCache(account.id)
        results
    }

    /**
     * MEGA's node-key encryption doesn't depend on the parent folder (unlike a shared folder's
     * re-keying), so moving a node within one's own account tree is a plain "a":"m" move command,
     * no re-encryption needed. Shared by [moveToRubbishBin] and [restoreFromRubbishBin].
     */
    /** Resolves the account's root folder handle (never a null-means-root convention like
     * createFolder/uploadFile accept — callers that need an actual handle value, e.g. moveNode,
     * use this instead of duplicating the tree-fetch dance themselves). */
    suspend fun resolveRootHandle(account: CloudAccount): String? =
        getOrFetchNodeTree(account).getOrNull()?.second

    suspend fun moveNode(account: CloudAccount, nodeHandle: String, newParentHandle: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (nodeHandle.startsWith("/")) {
                return@withContext Result.failure(Exception("Could not resolve a MEGA handle for '$nodeHandle'"))
            }
            val url = apiUrl(account)

            val commandJson = JSONArray().apply {
                put(JSONObject().apply {
                    put("a", "m")
                    put("n", nodeHandle)
                    put("t", newParentHandle)
                })
            }.toString()

            val response = sendMegaPost(url, commandJson)
            val bodyStr = response.getOrElse { return@withContext Result.failure(it) }.trim()
            if (megaErrorCode(bodyStr) != null) {
                return@withContext Result.failure(Exception("MEGA move failed: $bodyStr"))
            }
            invalidateNodeTreeCache(account.id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Real "move to Rubbish Bin" — the Rubbish Bin is just another top-level node (type 4)
     * already present in the cached tree, no separate lookup/API call needed to find its handle. */
    suspend fun moveToRubbishBin(account: CloudAccount, nodeHandle: String): Result<Unit> {
        val treeResult = getOrFetchNodeTree(account)
        val allNodes = treeResult.getOrElse { return Result.failure(it) }.first
        val rubbishHandle = allNodes.firstOrNull { it.type == 4 }?.handle
            ?: return Result.failure(Exception("Could not resolve Rubbish Bin handle"))
        return moveNode(account, nodeHandle, rubbishHandle)
    }

    /** Restores a node out of the Rubbish Bin back to the Cloud Drive root — MEGA doesn't track
     * "original location" once a node is moved, so restore always lands at the account root,
     * same simplification most third-party clients make. */
    suspend fun restoreFromRubbishBin(account: CloudAccount, nodeHandle: String): Result<Unit> {
        val treeResult = getOrFetchNodeTree(account)
        val (allNodes, rootHandle) = treeResult.getOrElse { return Result.failure(it) }
        val targetRoot = rootHandle.ifEmpty { allNodes.firstOrNull { it.type == 2 }?.handle ?: "" }
        if (targetRoot.isBlank()) return Result.failure(Exception("Could not resolve Cloud Drive root handle"))
        return moveNode(account, nodeHandle, targetRoot)
    }

    /**
     * MEGA's API returns "-3" (EAGAIN) when it's temporarily rate-limiting/congested — expected
     * under normal concurrent use (e.g. several thumbnail fetches at once), NOT a real error. But
     * callers up the stack (fetchNodeTreeFromNetwork etc.) treat ANY body starting with "-" as a
     * hard failure, which — without a retry here — can wipe out an already-displayed file list
     * the moment MEGA returns one -3 under load. Retry with backoff at this lowest layer so every
     * caller gets a real response instead.
     */
    /** The MEGA error code in a command's reply, or null when it succeeded. A single command
     * answers either a bare "-N" or, as usual, a one-element array "[-N]"; rename, delete and move
     * only checked for the bare form, so a failed command in the array form (e.g. "[-9]" for a
     * node that no longer exists) was reported as success. */
    internal fun megaErrorCode(body: String): Int? {
        val trimmed = body.trim()
        val first = if (trimmed.startsWith("[")) trimmed.removePrefix("[").substringBefore(']').substringBefore(',').trim() else trimmed
        return first.toIntOrNull()?.takeIf { it < 0 }
    }

    private fun sendMegaPost(url: String, json: String, retriesLeft: Int = 4): Result<String> {
        return try {
            val request = Request.Builder()
                .url(url)
                .post(json.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                // An error page used to be handed on as the reply and fail later as unparseable JSON.
                val code = response.code
                response.close()
                if (retriesLeft > 0 && code >= 500) {
                    Thread.sleep((500L * (5 - retriesLeft)).coerceAtMost(3000L))
                    return sendMegaPost(url, json, retriesLeft - 1)
                }
                return Result.failure(java.io.IOException("MEGA request failed: HTTP $code"))
            }
            val body = (response.body?.string() ?: "[]").trim()
            if (retriesLeft > 0 && (body == "-3" || body == "[-3]")) {
                Thread.sleep((500L * (5 - retriesLeft)).coerceAtMost(3000L))
                return sendMegaPost(url, json, retriesLeft - 1)
            }
            Result.success(body)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- MEGA CRYPTOGRAPHIC PROTOCOL IMPLEMENTATION ---

    private fun prepareKeyV1(password: String): IntArray {
        val a32 = strToA32(password)
        var key = intArrayOf(0x93C467E3.toInt(), 0x7B0AF0A5.toInt(), 0x549F30EC.toInt(), 0x0D5163A0.toInt())
        for (r in 0 until 65536) {
            for (j in 0 until a32.size step 4) {
                var keyPart = intArrayOf(0, 0, 0, 0)
                for (k in 0 until 4) {
                    if (j + k < a32.size) keyPart[k] = a32[j + k]
                }
                key = aesEcbEncryptInts(key, keyPart)
            }
        }
        return key
    }

    /**
     * V2 accounts need 256 bits of PBKDF2 output, not 128: the first 16 bytes are the password
     * AES key, the last 16 bytes become "uh" directly (base64url-encoded, no further hashing —
     * unlike V1's stringHash()). Requesting only 128 bits (as this used to) silently produced a
     * key with no bytes left for "uh" at all, so login always sent a wrong "uh" and MEGA
     * rejected every V2 account with EARGS (-2), regardless of whether the password was correct.
     */
    private fun deriveKeyV2Full(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, 100000, 256)
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        return f.generateSecret(spec).encoded
    }

    private fun stringHash(email: String, keyBytes: ByteArray): String {
        val a32 = strToA32(email)
        var h32 = intArrayOf(0, 0, 0, 0)
        for (i in a32.indices) {
            h32[i % 4] = h32[i % 4] xor a32[i]
        }
        for (r in 0 until 16384) {
            h32 = aesEcbEncryptInts(h32, bytesToA32(keyBytes))
        }
        return base64UrlEncode(a32ToBytes(intArrayOf(h32[0], h32[2])))
    }

    /**
     * Like [deriveNodeKeyBytes] but also returns the 8-byte CTR nonce (bytes 16-23 of the
     * decrypted node key) needed to decrypt file CONTENT — attribute/thumbnail decryption only
     * needs the folded 16-byte key (CBC, zero IV), but content is AES-CTR and needs the nonce too.
     */
    private fun deriveNodeContentKeyAndNonce(keyStr: String, masterKey: ByteArray): Pair<ByteArray, ByteArray>? {
        val firstKey = keyStr.split("/").firstOrNull { it.isNotBlank() } ?: keyStr
        val cleanKey = if (firstKey.contains(":")) firstKey.substringAfter(":") else firstKey
        val encNodeKey = try { base64UrlDecode(cleanKey) } catch (e: Exception) { return null }
        if (encNodeKey.isEmpty() || encNodeKey.size % 16 != 0) return null

        val nodeKeyBytes = aesEcbDecrypt(encNodeKey, masterKey)
        val nodeKeyA32 = bytesToA32(nodeKeyBytes)
        if (nodeKeyA32.size < 8) return null

        val keyInts = intArrayOf(
            nodeKeyA32[0] xor nodeKeyA32[4],
            nodeKeyA32[1] xor nodeKeyA32[5],
            nodeKeyA32[2] xor nodeKeyA32[6],
            nodeKeyA32[3] xor nodeKeyA32[7]
        )
        val aesKey = a32ToBytes(keyInts)
        val nonce = a32ToBytes(intArrayOf(nodeKeyA32[4], nodeKeyA32[5]))
        return aesKey to nonce
    }

    private fun deriveNodeKeyBytes(keyStr: String, masterKey: ByteArray): ByteArray? {
        val firstKey = keyStr.split("/").firstOrNull { it.isNotBlank() } ?: keyStr
        val cleanKey = if (firstKey.contains(":")) firstKey.substringAfter(":") else firstKey
        val encNodeKey = try { base64UrlDecode(cleanKey) } catch (e: Exception) { return null }
        if (encNodeKey.isEmpty() || encNodeKey.size % 16 != 0) return null

        val nodeKeyBytes = aesEcbDecrypt(encNodeKey, masterKey)
        // For files, node key is 32 bytes (key + iv + mac). We fold to 16 bytes:
        val nodeKeyA32 = bytesToA32(nodeKeyBytes)
        return if (nodeKeyA32.size >= 8) {
            val keyInts = intArrayOf(
                nodeKeyA32[0] xor nodeKeyA32[4],
                nodeKeyA32[1] xor nodeKeyA32[5],
                nodeKeyA32[2] xor nodeKeyA32[6],
                nodeKeyA32[3] xor nodeKeyA32[7]
            )
            a32ToBytes(keyInts)
        } else {
            nodeKeyBytes.copyOf(16)
        }
    }

    /**
     * Downloads MEGA's pre-generated thumbnail (120x120 JPEG) for a node, if one exists.
     * Thumbnails are NOT generated server-side — they only exist if the uploading client
     * (e.g. the official MEGA apps) created and attached one at upload time. Returns failure
     * (not an exception) when the node has no thumbnail attribute, so callers can fall back
     * to downloading the full file.
     */
    suspend fun downloadThumbnail(account: CloudAccount, nodeHandle: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val treeResult = getOrFetchNodeTree(account)
            val (allNodes, _) = treeResult.getOrElse { return@withContext Result.failure(it) }
            val node = allNodes.find { it.handle == nodeHandle }
                ?: return@withContext Result.failure(Exception("Node not found: $nodeHandle"))

            // "fa" format: "0:0*<handle>/1:1*<handle>" — type 0 is the thumbnail, 1 is the preview.
            val thumbEntry = node.fileAttrStr.split("/").firstOrNull { it.startsWith("0:") }
                ?: return@withContext Result.failure(Exception("No thumbnail attribute for $nodeHandle"))
            val fah = thumbEntry.substringAfter("*", "")
            if (fah.isBlank()) {
                return@withContext Result.failure(Exception("Malformed fa entry: $thumbEntry"))
            }

            val masterKeyBytes = masterKey(account) ?: return@withContext Result.failure(Exception("No master key available"))
            val nodeKeyBytes = deriveNodeKeyBytes(node.keyStr, masterKeyBytes)
                ?: return@withContext Result.failure(Exception("Cannot derive node key for $nodeHandle"))

            val url = apiUrl(account)
            val commandJson = JSONArray().apply {
                put(JSONObject().apply {
                    put("a", "ufa")
                    put("fah", fah)
                    put("ssl", 2)
                    put("r", 1)
                })
            }.toString()

            val response = sendMegaPost(url, commandJson)
            val bodyStr = response.getOrNull() ?: "[]"
            if (bodyStr.startsWith("-") || bodyStr.contains("[-")) {
                return@withContext Result.failure(Exception("MEGA thumbnail unavailable: $bodyStr"))
            }
            val jsonArray = JSONArray(bodyStr)
            val obj = jsonArray.optJSONObject(0) ?: JSONObject()
            val baseUrl = obj.optString("p", "")
            if (baseUrl.isBlank()) {
                return@withContext Result.failure(Exception("No thumbnail URL returned"))
            }

            val fahBytes = base64UrlDecode(fah)
            val request = Request.Builder()
                .url("$baseUrl/0")
                .post(fahBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
                .build()
            val raw = okHttpClient.newCall(request).execute().use { httpResponse ->
                if (!httpResponse.isSuccessful) {
                    return@withContext Result.failure(Exception("Thumbnail download failed: ${httpResponse.code}"))
                }
                httpResponse.body?.bytes()
            }
            if (raw == null || raw.size <= 12) {
                return@withContext Result.failure(Exception("Empty/short thumbnail response"))
            }

            // Response layout: 8-byte echoed handle + 4-byte little-endian size + encrypted data.
            val echoedHandle = raw.copyOfRange(0, 8)
            if (!echoedHandle.contentEquals(fahBytes)) {
                return@withContext Result.failure(Exception("Thumbnail handle mismatch"))
            }
            val dataSize = ByteBuffer.wrap(raw, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val encrypted = raw.copyOfRange(12, raw.size)
            if (dataSize != encrypted.size || encrypted.size % 16 != 0) {
                return@withContext Result.failure(Exception("Thumbnail size mismatch"))
            }

            Result.success(aesCbcDecrypt(encrypted, nodeKeyBytes, ByteArray(16)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Uploads a local file to MEGA for real (previously this provider only mirrored files into
     * app-local storage). Generates and attaches a 120x120 JPEG thumbnail for image files so the
     * fast [downloadThumbnail] path has something to fetch on next listing, instead of only
     * benefiting files that already had one from the official MEGA apps.
     *
     * Whole-file, single-chunk upload: correct per MEGA's chunk-boundary rules (any contiguous
     * range starting at 0 is valid), but holds the encrypted file in memory — acceptable for a
     * mobile file manager's typical uploads, not suited to multi-GB files.
     */
    suspend fun uploadFile(
        account: CloudAccount,
        localFile: File,
        parentHandle: String?,
        onProgress: ((bytesUploaded: Long, totalBytes: Long) -> Unit)? = null
    ): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val masterKeyBytes = masterKey(account) ?: return@withContext Result.failure(Exception("No master key available"))

            val url = apiUrl(account)

            val resolvedParentHandle = if (parentHandle.isNullOrBlank() || parentHandle == "root" || parentHandle == "/") {
                val treeResult = getOrFetchNodeTree(account)
                val (_, rootHandle) = treeResult.getOrElse {
                    android.util.Log.e("MegaApiClient", "uploadFile: failed to resolve root handle", it)
                    return@withContext Result.failure(it)
                }
                rootHandle
            } else {
                parentHandle
            }
            android.util.Log.d("MegaApiClient", "uploadFile: parentHandle param='$parentHandle' resolved='$resolvedParentHandle'")
            if (resolvedParentHandle.isBlank()) {
                return@withContext Result.failure(Exception("Could not resolve MEGA parent folder"))
            }

            val totalBytes = localFile.length()

            // Fresh per-upload key: 16-byte AES key + 8-byte CTR nonce (MEGA's "192-bit" upload key).
            val random = SecureRandom()
            val aesKey = ByteArray(16).also { random.nextBytes(it) }
            val nonce = ByteArray(8).also { random.nextBytes(it) }

            currentCoroutineContext().ensureActive()

            val uploadUrlCommand = JSONArray().apply {
                put(JSONObject().apply {
                    put("a", "u")
                    put("ssl", 2)
                    put("s", totalBytes)
                    put("ms", 0)
                    put("r", 0)
                    put("e", 0)
                    put("v", 2)
                })
            }.toString()
            val uploadUrlResp = sendMegaPost(url, uploadUrlCommand)
            val uploadUrlBody = uploadUrlResp.getOrNull() ?: "[]"
            android.util.Log.d("MegaApiClient", "uploadFile: 'u' response = $uploadUrlBody")
            if (uploadUrlBody.startsWith("-") || uploadUrlBody.contains("[-")) {
                return@withContext Result.failure(Exception("MEGA upload URL request failed: $uploadUrlBody"))
            }
            val uploadUrl = (JSONArray(uploadUrlBody).optJSONObject(0) ?: JSONObject()).optString("p", "")
            if (uploadUrl.isBlank()) {
                return@withContext Result.failure(Exception("No upload URL returned"))
            }

            // Encrypted and MAC-ed while streaming: holding the plaintext and ciphertext of the
            // whole file in memory at once ran a large video straight into OutOfMemoryError.
            val job = currentCoroutineContext()[kotlinx.coroutines.Job]
            val (completionHandleBytes, macBytes) = postEncryptedFile(uploadUrl, localFile, aesKey, nonce, job) { uploaded ->
                onProgress?.invoke(uploaded, totalBytes)
            }
            if (completionHandleBytes.isEmpty()) {
                return@withContext Result.failure(Exception("MEGA upload did not return a completion handle"))
            }
            // A failed upload answers with an error code in plain text ("-4") instead of the binary
            // completion token; that text used to be sent on as if it were the token.
            if (completionHandleBytes.size < 16) {
                String(completionHandleBytes, StandardCharsets.US_ASCII).trim().toIntOrNull()?.let { code ->
                    return@withContext Result.failure(Exception("MEGA upload failed (error $code)"))
                }
            }
            val fileHandleB64 = base64UrlEncode(completionHandleBytes)

            // Best-effort thumbnail: failures here must not fail the whole upload.
            var faField: String? = null
            try {
                val thumbnailBytes = generateImageThumbnailJpeg(localFile)
                if (thumbnailBytes != null && thumbnailBytes.isNotEmpty()) {
                    val encryptedThumb = aesCbcEncrypt(padTo16(thumbnailBytes), aesKey, ByteArray(16))
                    val thumbUrlCommand = JSONArray().apply {
                        put(JSONObject().apply {
                            put("a", "ufa")
                            put("ssl", 2)
                            put("s", encryptedThumb.size)
                        })
                    }.toString()
                    val thumbUrlResp = sendMegaPost(url, thumbUrlCommand)
                    val thumbUrlBody = thumbUrlResp.getOrNull() ?: "[]"
                    if (!thumbUrlBody.startsWith("-") && !thumbUrlBody.contains("[-")) {
                        val thumbUrl = (JSONArray(thumbUrlBody).optJSONObject(0) ?: JSONObject()).optString("p", "")
                        if (thumbUrl.isNotBlank()) {
                            val thumbHashBytes = postChunk(thumbUrl, 0, encryptedThumb, null)
                            if (thumbHashBytes.isNotEmpty()) {
                                faField = "0*" + base64UrlEncode(thumbHashBytes)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Thumbnail generation/attachment is a nice-to-have; the file upload already succeeded.
            }

            // MEGA's node key ("k") is NOT a plain concatenation of aesKey+nonce+mac: the first
            // 16 bytes must be aesKey folded (XORed) with (nonce||mac), followed by nonce and mac
            // in the clear — this is exactly what deriveNodeKeyBytes() elsewhere in this file
            // un-folds (bytes[0:16] xor bytes[16:32]) when reading other clients' nodes. Sending
            // a plain concatenation here produces a "k" that every other MEGA client — including
            // this app's own read path — recovers the wrong AES key from, which decrypts to
            // garbage and fails MAC verification on download.
            val rawKey = ByteArray(32)
            aesKey.copyInto(rawKey, 0)
            nonce.copyInto(rawKey, 16)
            macBytes.copyInto(rawKey, 24)
            val compressedKey = ByteArray(16) { i -> (rawKey[i].toInt() xor rawKey[16 + i].toInt()).toByte() }
            val mergedKey = ByteArray(32)
            compressedKey.copyInto(mergedKey, 0)
            nonce.copyInto(mergedKey, 16)
            macBytes.copyInto(mergedKey, 24)

            val fingerprint = computeMegaFingerprint(localFile, localFile.lastModified() / 1000)
            val attrJson = JSONObject().apply {
                put("n", localFile.name)
                put("c", fingerprint)
            }.toString()
            val attrBytes = padTo16("MEGA$attrJson".toByteArray(StandardCharsets.UTF_8))
            // Attribute name is always encrypted with the raw content aesKey (same key used for
            // CTR), never the folded "k"-wrapping value — matches what deriveNodeKeyBytes()
            // recovers when reading nodes back (it unfolds "k" to the original aesKey).
            val encodedAttr = base64UrlEncode(aesCbcEncrypt(attrBytes, aesKey, ByteArray(16)))
            val encodedKey = base64UrlEncode(aesEcbEncrypt(mergedKey, masterKeyBytes))

            val nodeObj = JSONObject().apply {
                put("h", fileHandleB64)
                put("t", 0)
                put("a", encodedAttr)
                put("k", encodedKey)
                if (faField != null) put("fa", faField)
            }
            val createNodeCommand = JSONArray().apply {
                put(JSONObject().apply {
                    put("a", "p")
                    put("t", resolvedParentHandle)
                    put("n", JSONArray().apply { put(nodeObj) })
                })
            }.toString()

            val nodeHandle = nodeCreationMutexFor(account.id).withLock {
                val createNodeResp = sendMegaPost(url, createNodeCommand)
                val body = createNodeResp.getOrNull() ?: "[]"
                android.util.Log.d("MegaApiClient", "uploadFile: 'p' response = $body")
                if (body.startsWith("-") || body.contains("[-")) {
                    return@withContext Result.failure(Exception("MEGA node creation failed: $body"))
                }
                // The new node's real handle is only in this response (response[0].f[0].h). The
                // upload completion token (fileHandleB64) used to be kept as the handle instead, so
                // a freshly uploaded file couldn't be downloaded, viewed or thumbnailed ("g" -> -2)
                // until the whole tree was re-fetched.
                val realHandle = try {
                    JSONArray(body).optJSONObject(0)?.optJSONArray("f")?.optJSONObject(0)?.optString("h").orEmpty()
                } catch (e: Exception) { "" }
                if (realHandle.isBlank()) {
                    // The node exists server-side; let the next listing pick it up for real.
                    invalidateNodeTreeCache(account.id)
                } else patchNodeCacheAfterFileUpload(
                    accountId = account.id,
                    handle = realHandle,
                    parentHandle = resolvedParentHandle,
                    name = localFile.name,
                    size = totalBytes,
                    encodedKey = encodedKey,
                    fileAttrStr = faField ?: ""
                )
                realHandle
            }
            onProgress?.invoke(totalBytes, totalBytes)

            Result.success(
                FileItem(
                    id = nodeHandle,
                    name = localFile.name,
                    path = nodeHandle,
                    size = totalBytes,
                    lastModified = System.currentTimeMillis(),
                    isDirectory = false,
                    extension = localFile.extension
                )
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("MegaApiClient", "uploadFile failed for ${localFile.name}", e)
            Result.failure(e)
        }
    }

    /** Streams [file] to `<baseUrl>/0`, AES-CTR encrypting and computing MEGA's chunked MAC over
     * the plaintext on the fly. Returns the completion handle and the 8-byte condensed MAC. The
     * MAC/cipher state is created inside writeTo(), so an OkHttp retry that replays the body
     * starts from a clean state instead of MAC-ing the file twice. */
    private fun postEncryptedFile(
        baseUrl: String,
        file: File,
        aesKey: ByteArray,
        nonce: ByteArray,
        job: kotlinx.coroutines.Job?,
        onProgress: ((Long) -> Unit)?
    ): Pair<ByteArray, ByteArray> {
        var macResult: ByteArray? = null
        val requestBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
            override fun contentLength() = file.length()
            override fun writeTo(sink: BufferedSink) {
                val mac = MegaChunkedMac(aesKey, nonce)
                val iv = ByteArray(16).also { nonce.copyInto(it, 0) }
                val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
                }
                // A multiple of 16, and only the final read may come up short, so feeding the MAC
                // chunk by chunk is identical to feeding it the whole file at once.
                val buffer = ByteArray(64 * 1024)
                var written = 0L
                file.inputStream().use { input ->
                    while (true) {
                        if (job?.isActive == false) throw java.io.IOException("Upload cancelled")
                        val read = readFully(input, buffer)
                        if (read <= 0) break
                        val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                        mac.update(chunk)
                        sink.write(cipher.update(chunk, 0, read))
                        written += read
                        onProgress?.invoke(written)
                    }
                }
                cipher.doFinal().takeIf { it.isNotEmpty() }?.let { sink.write(it) }
                macResult = mac.condense()
            }
        }
        val request = Request.Builder().url("$baseUrl/0").post(requestBody).build()
        val handle = okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("MEGA upload failed: HTTP ${response.code}")
            response.body?.bytes() ?: ByteArray(0)
        }
        return handle to (macResult ?: throw Exception("MEGA upload body was never written"))
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    /** POSTs one contiguous chunk to `<baseUrl>/<offset>` and returns the raw response bytes. */
    private fun postChunk(baseUrl: String, offset: Long, data: ByteArray, onProgress: ((Long) -> Unit)?): ByteArray {
        val requestBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
            override fun contentLength() = data.size.toLong()
            override fun writeTo(sink: BufferedSink) {
                val chunkSize = 64 * 1024
                var written = 0
                while (written < data.size) {
                    val len = minOf(chunkSize, data.size - written)
                    sink.write(data, written, len)
                    written += len
                    onProgress?.invoke(written.toLong())
                }
            }
        }
        val request = Request.Builder().url("$baseUrl/$offset").post(requestBody).build()
        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("MEGA chunk upload failed: HTTP ${response.code}")
            response.body?.bytes() ?: ByteArray(0)
        }
    }

    /** Generates MEGA's standard 120x120 JPEG thumbnail for an image file, or null if not an image / decode fails. */
    private fun generateImageThumbnailJpeg(file: File): ByteArray? {
        val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
        if (file.extension.lowercase(Locale.getDefault()) !in imageExtensions) return null

        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= 120 && bounds.outHeight / (sampleSize * 2) >= 120) {
                sampleSize *= 2
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sampleSize })
                ?: return null

            val srcW = decoded.width
            val srcH = decoded.height
            val cropSize = minOf(srcW, srcH)
            val cropX = (srcW - cropSize) / 2
            val cropY = (srcH - cropSize) / 2
            val cropped = Bitmap.createBitmap(decoded, cropX, cropY, cropSize, cropSize)
            val thumb = Bitmap.createScaledBitmap(cropped, 120, 120, true)

            val out = ByteArrayOutputStream()
            thumb.compress(Bitmap.CompressFormat.JPEG, 70, out)

            if (thumb !== decoded) thumb.recycle()
            if (cropped !== decoded) cropped.recycle()
            decoded.recycle()

            out.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    private fun padTo16(data: ByteArray): ByteArray {
        val len = ((data.size + 15) / 16) * 16
        return data.copyOf(len)
    }

    private fun aesEcbEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    private fun aesCbcEncrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.copyOf(16), "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /**
     * Faithful port of MEGA's chunked-MAC algorithm (verified against the actively-maintained
     * megajs client): a CBC-MAC-like running digest over 16-byte blocks of PLAINTEXT, re-seeded
     * with the nonce every growing "segment" (128KB, then +128KB each time up to a 1MB cap), with
     * the per-segment MACs finally folded together and compressed to 8 bytes. Any deviation here
     * produces a "k" field only THIS bug would produce — other MEGA clients would then fail MAC
     * verification when downloading the file, so this must not be changed casually.
     */
    private class MegaChunkedMac(aesKey: ByteArray, nonce: ByteArray) {
        private val nonce8 = nonce.copyOf(8)
        private val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"))
        }
        private var mac = freshMac()
        private val macs = mutableListOf<ByteArray>()
        private var pos = 0L
        private var posNext = 131072L
        private var increment = 131072L

        private fun freshMac(): ByteArray {
            val m = ByteArray(16)
            nonce8.copyInto(m, 0)
            nonce8.copyInto(m, 8)
            return m
        }

        fun update(buffer: ByteArray) {
            var i = 0
            while (i < buffer.size) {
                for (j in 0 until 16) {
                    val b: Byte = if (i + j < buffer.size) buffer[i + j] else 0
                    mac[j] = (mac[j].toInt() xor b.toInt()).toByte()
                }
                mac = cipher.doFinal(mac)
                checkBounding()
                i += 16
            }
        }

        private fun checkBounding() {
            pos += 16
            if (pos >= posNext) {
                macs.add(mac.copyOf())
                mac = freshMac()
                if (increment < 1048576L) increment += 131072L
                posNext += increment
            }
        }

        fun condense(): ByteArray {
            macs.add(mac.copyOf())

            var result = ByteArray(16)
            for (item in macs) {
                for (j in 0 until 16) {
                    result[j] = (result[j].toInt() xor item[j].toInt()).toByte()
                }
                result = cipher.doFinal(result)
            }

            val buf = ByteBuffer.wrap(result)
            val a = buf.getInt(0) xor buf.getInt(4)
            val b = buf.getInt(8) xor buf.getInt(12)
            val out = ByteArray(8)
            ByteBuffer.wrap(out).putInt(a).putInt(b)
            return out
        }
    }

    private fun decryptNodeAttributes(attrEncStr: String, keyStr: String, masterKey: ByteArray?): String? {
        try {
            if (attrEncStr.startsWith("{")) {
                val obj = JSONObject(attrEncStr)
                return obj.optString("n")
            }

            val encBytes = base64UrlDecode(attrEncStr)
            if (masterKey != null && keyStr.isNotEmpty()) {
                val fileKeyBytes = deriveNodeKeyBytes(keyStr, masterKey)
                if (fileKeyBytes != null) {
                    // Decrypt attribute with node key (IV is 16 zero bytes)
                    val decBytes = aesCbcDecrypt(encBytes, fileKeyBytes, ByteArray(16))
                    val str = String(decBytes, StandardCharsets.UTF_8).trim('\u0000', ' ')
                    if (str.contains("\"n\":")) {
                        val jsonStart = str.indexOf('{')
                        val jsonEnd = str.lastIndexOf('}')
                        if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                            val json = JSONObject(str.substring(jsonStart, jsonEnd + 1))
                            val n = json.optString("n")
                            if (n.isNotBlank()) return n
                        }
                    }
                }
            }

            // Fallback: try raw decode
            val raw = String(encBytes, StandardCharsets.UTF_8)
            if (raw.contains("\"n\":")) {
                val jsonStart = raw.indexOf('{')
                val jsonEnd = raw.lastIndexOf('}')
                if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                    val json = JSONObject(raw.substring(jsonStart, jsonEnd + 1))
                    val n = json.optString("n")
                    if (n.isNotBlank()) return n
                }
            }
        } catch (e: Exception) {
            // Ignore decryption error
        }
        return null
    }

    // MEGA's own file fingerprint (node attribute "c"): 16-byte CRC + serialized mtime,
    // base64url-encoded. Official clients (MEGAsync) use this for sync/dedup and flag a
    // node as "File fingerprint missing" if it's absent, ported from MEGA SDK's
    // FileFingerprint::genfingerprint / serializefingerprint (filefingerprint.cpp).
    private fun computeMegaFingerprint(file: File, mtimeSeconds: Long): String {
        val crc = ByteArray(16)
        val maxFull = 8192
        val sizeL = file.length()
        // Small files are read whole; a large one only needs its 128 sampled 64-byte blocks.
        val fileBytes = if (sizeL <= maxFull) file.readBytes() else ByteArray(0)
        val size = if (sizeL <= maxFull) fileBytes.size else Int.MAX_VALUE
        val raf = if (sizeL > maxFull) java.io.RandomAccessFile(file, "r") else null
        val block = ByteArray(64)
        try {

        when {
            size <= crc.size -> {
                // Tiny file: raw bytes verbatim, zero-padded.
                System.arraycopy(fileBytes, 0, crc, 0, size)
            }
            size <= maxFull -> {
                // Small file: full coverage, four full CRC32s over equal segments.
                for (i in 0 until 4) {
                    val begin = i * size / 4
                    val end = (i + 1) * size / 4
                    val crc32 = java.util.zip.CRC32()
                    crc32.update(fileBytes, begin, end - begin)
                    writeCrcBigEndian(crc, i * 4, crc32.value.toInt())
                }
            }
            else -> {
                // Large file: sparse coverage, four sparse CRC32s over 32 x 64-byte blocks each.
                val blockBytes = 4 * crc.size // 64
                val blocks = maxFull / (blockBytes * 4) // 32
                for (i in 0 until 4) {
                    val crc32 = java.util.zip.CRC32()
                    for (j in 0 until blocks) {
                        val idx64 = i.toLong() * blocks + j
                        val numer = (sizeL - blockBytes) * idx64
                        val denom = 4L * blocks - 1
                        var offset = if (denom != 0L) numer / denom else 0L
                        val clampMax = sizeL - blockBytes
                        if (offset > clampMax) offset = clampMax
                        raf!!.seek(offset)
                        raf.readFully(block)
                        crc32.update(block, 0, blockBytes)
                    }
                    writeCrcBigEndian(crc, i * 4, crc32.value.toInt())
                }
            }
        }
        } finally {
            raf?.close()
        }

        return base64UrlEncode(crc + serialize64Mtime(mtimeSeconds))
    }

    private fun writeCrcBigEndian(dest: ByteArray, offset: Int, value: Int) {
        dest[offset] = (value ushr 24).toByte()
        dest[offset + 1] = (value ushr 16).toByte()
        dest[offset + 2] = (value ushr 8).toByte()
        dest[offset + 3] = value.toByte()
    }

    // MEGA's Serialize64: length-prefix byte followed by that many little-endian value bytes.
    private fun serialize64Mtime(value: Long): ByteArray {
        var v = value
        val temp = mutableListOf<Byte>()
        while (v != 0L) {
            temp.add((v and 0xFF).toByte())
            v = v ushr 8
        }
        val result = ByteArray(temp.size + 1)
        result[0] = temp.size.toByte()
        for (idx in temp.indices) result[idx + 1] = temp[idx]
        return result
    }

    // Parses one MEGA MPI-encoded big integer: a 2-byte big-endian bit length
    // prefix followed by ceil(bitLen/8) magnitude bytes.
    private fun mpiToBigInteger(mpiBytes: ByteArray): BigInteger {
        val magnitude = mpiBytes.copyOfRange(2, mpiBytes.size)
        return BigInteger(1, magnitude)
    }

    // rsaData is the AES-ECB-decrypted "privk" blob: four concatenated MPI values [p, q, d, u].
    private fun extractRsaPrivateKeyComponents(rsaData: ByteArray): List<BigInteger> {
        val components = mutableListOf<BigInteger>()
        var offset = 0
        repeat(4) {
            val bitLen = (rsaData[offset].toInt() and 0xFF) * 256 + (rsaData[offset + 1].toInt() and 0xFF)
            val byteLen = (bitLen + 7) / 8
            val l = byteLen + 2
            components.add(mpiToBigInteger(rsaData.copyOfRange(offset, offset + l)))
            offset += l
        }
        return components
    }

    // Raw textbook RSA decryption (no PKCS1 padding) as MEGA's protocol requires.
    private fun rsaDecryptRaw(encData: BigInteger, p: BigInteger, q: BigInteger, d: BigInteger): ByteArray {
        val modulus = p.multiply(q)
        val spec = RSAPrivateKeySpec(modulus, d)
        val factory = KeyFactory.getInstance("RSA")
        val privateKey = factory.generatePrivate(spec)
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)

        var encBytes = encData.toByteArray()
        if (encBytes.isNotEmpty() && encBytes[0].toInt() == 0) {
            encBytes = encBytes.copyOfRange(1, encBytes.size)
        }
        var plain = cipher.doFinal(encBytes)
        if (plain.isNotEmpty() && plain[0].toInt() == 0) {
            plain = plain.copyOfRange(1, plain.size)
        }
        return plain
    }

    // --- AES & A32 HELPERS ---

    private fun aesEcbEncryptInts(data: IntArray, key: IntArray): IntArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(a32ToBytes(key), "AES"))
        val encrypted = cipher.doFinal(a32ToBytes(data))
        return bytesToA32(encrypted)
    }

    private fun aesEcbDecrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    private fun aesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOf(16), "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun strToA32(str: String): IntArray {
        val bytes = str.toByteArray(StandardCharsets.UTF_8)
        val len = (bytes.size + 3) / 4 * 4
        val padded = ByteArray(len)
        System.arraycopy(bytes, 0, padded, 0, bytes.size)
        return bytesToA32(padded)
    }

    private fun bytesToA32(bytes: ByteArray): IntArray {
        val ints = IntArray(bytes.size / 4)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        for (i in ints.indices) {
            ints[i] = buffer.int
        }
        return ints
    }

    private fun a32ToBytes(ints: IntArray): ByteArray {
        val buffer = ByteBuffer.allocate(ints.size * 4).order(ByteOrder.BIG_ENDIAN)
        for (i in ints) {
            buffer.putInt(i)
        }
        return buffer.array()
    }

    private fun base64UrlDecode(str: String): ByteArray {
        var s = str.replace('-', '+').replace('_', '/')
        while (s.length % 4 != 0) {
            s += "="
        }
        return Base64.getDecoder().decode(s)
    }

    private fun base64UrlEncode(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private data class MegaNode(
        val handle: String,
        val parentHandle: String,
        val type: Int,
        val size: Long,
        val timestamp: Long,
        val name: String,
        val keyStr: String = "",
        val fileAttrStr: String = ""
    )
}

/**
 * A [android.media.MediaDataSource] that lazily fetches+decrypts only the byte ranges a reader
 * (MediaMetadataRetriever) actually asks for, via HTTP Range requests against a MEGA temporary
 * download URL. AES-CTR is a keystream cipher, so any 16-byte-aligned range can be decrypted
 * independently given the right counter (see [MegaApiClient.openThumbnailDataSource]) — this is
 * what lets a video-frame probe cost tens/hundreds of KB instead of a flat multi-MB prefix
 * download, matching how Dropbox's real HTTP-Range streamable link already behaves.
 */
class MegaDecryptingDataSource(
    private val downloadUrl: String,
    private val aesKey: ByteArray,
    private val nonce: ByteArray,
    private val totalSize: Long,
    private val okHttpClient: OkHttpClient,
    private val fetchWindow: Long = DEFAULT_FETCH_WINDOW
) : android.media.MediaDataSource() {

    // Single-slot window cache: MediaMetadataRetriever tends to re-read overlapping/nearby
    // regions (header parsing, then the frame itself) — avoids a fresh HTTP round-trip per call.
    private var cacheStart = -1L
    private var cacheBytes: ByteArray? = null

    override fun close() {}

    override fun getSize(): Long = totalSize

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || position >= totalSize || size <= 0) return -1
        val wantEnd = minOf(position + size, totalSize)

        val cStart = cacheStart
        val cData = cacheBytes
        if (cStart >= 0 && cData != null && position >= cStart && wantEnd <= cStart + cData.size) {
            val srcOffset = (position - cStart).toInt()
            val toCopy = (wantEnd - position).toInt()
            System.arraycopy(cData, srcOffset, buffer, offset, toCopy)
            return toCopy
        }

        val alignedStart = (position / 16) * 16
        // Fetch a slightly bigger window than requested (at least [fetchWindow]) so nearby
        // follow-up reads hit the cache instead of firing another round-trip each time.
        val fetchEnd = minOf(maxOf(alignedStart + fetchWindow, wantEnd), totalSize)
        val rangeEndInclusive = minOf(((fetchEnd + 15) / 16) * 16 - 1, totalSize - 1)

        val plain = try {
            fetchAndDecrypt(alignedStart, rangeEndInclusive)
        } catch (t: Throwable) {
            null
        } ?: return -1

        cacheStart = alignedStart
        cacheBytes = plain

        val srcOffset = (position - alignedStart).toInt()
        if (srcOffset < 0 || srcOffset >= plain.size) return -1
        val toCopy = minOf(size, plain.size - srcOffset)
        System.arraycopy(plain, srcOffset, buffer, offset, toCopy)
        return toCopy
    }

    private fun fetchAndDecrypt(alignedStart: Long, rangeEndInclusive: Long): ByteArray? {
        val request = Request.Builder()
            .url(downloadUrl)
            .header("Range", "bytes=$alignedStart-$rangeEndInclusive")
            .get()
            .build()
        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            return null
        }
        val cipherBytes = response.body?.bytes() ?: return null

        val counterBlock = alignedStart / 16
        val iv = ByteArray(16)
        nonce.copyInto(iv, 0)
        for (i in 0 until 8) {
            iv[15 - i] = ((counterBlock shr (8 * i)) and 0xFF).toByte()
        }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(cipherBytes)
    }

    companion object {
        /** Small windows suit probing for a thumbnail frame (a few scattered reads). */
        const val DEFAULT_FETCH_WINDOW = 256L * 1024
        /** Playback reads sequentially, so fewer, larger requests keep up with the bitrate. */
        const val PLAYBACK_FETCH_WINDOW = 2L * 1024 * 1024
    }
}
