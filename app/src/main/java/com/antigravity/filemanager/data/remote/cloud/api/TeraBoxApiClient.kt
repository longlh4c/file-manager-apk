package com.antigravity.filemanager.data.remote.cloud.api

import android.webkit.MimeTypeMap
import com.antigravity.filemanager.domain.model.CloudAccount
import com.antigravity.filemanager.domain.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class TeraBoxUserInfo(
    val uname: String,
    val uk: String,
    val avatarUrl: String = "",
    val email: String? = null
)

data class TeraBoxQuota(
    val totalBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long
)

@Singleton
class TeraBoxApiClient @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://www.terabox.com"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val REFERER = "https://www.terabox.com/main"
        const val APP_ID = "250528"
    }

    @Volatile
    private var currentDomainPrefix: String? = null

    fun getBaseUrl(): String {
        val prefix = currentDomainPrefix
        return if (!prefix.isNullOrBlank()) "https://$prefix.terabox.com" else DEFAULT_BASE_URL
    }

    fun setDomainPrefix(prefix: String?) {
        currentDomainPrefix = prefix?.takeIf { it.isNotBlank() }
    }

    fun extractCleanNdus(rawToken: String?): String {
        if (rawToken.isNullOrBlank()) return ""
        val trimmed = rawToken.trim()
        if (trimmed.contains("ndus=")) {
            val parts = trimmed.split(";")
            for (p in parts) {
                val cookie = p.trim()
                if (cookie.startsWith("ndus=")) {
                    return cookie.substringAfter("ndus=").trim()
                }
            }
        }
        return trimmed
    }

    private fun getCookieString(account: CloudAccount): String {
        val session = account.sessionHandle
        if (!session.isNullOrBlank() && session.contains("ndus=")) {
            return session
        }
        val raw = account.accessToken ?: account.sessionHandle ?: ""
        val cleanNdus = extractCleanNdus(raw)
        return if (cleanNdus.isNotBlank()) "ndus=$cleanNdus" else ""
    }

    private fun buildCookieHeader(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.contains("ndus=")) {
            trimmed
        } else if (trimmed.isNotBlank()) {
            "ndus=$trimmed"
        } else {
            ""
        }
    }

    /**
     * Appends standard TeraBox query parameters: app_id=250528, web=1, channel=dubox, clienttype=0
     */
    private fun appendStandardParams(url: String): String {
        val httpUrl = url.toHttpUrlOrNull() ?: return url
        val builder = httpUrl.newBuilder()
        if (httpUrl.queryParameter("app_id") == null) {
            builder.addQueryParameter("app_id", APP_ID)
        }
        if (httpUrl.queryParameter("web") == null) {
            builder.addQueryParameter("web", "1")
        }
        if (httpUrl.queryParameter("channel") == null) {
            builder.addQueryParameter("channel", "dubox")
        }
        if (httpUrl.queryParameter("clienttype") == null) {
            builder.addQueryParameter("clienttype", "0")
        }
        return builder.build().toString()
    }

    private fun buildAuthorizedRequest(url: String, cookieHeader: String, method: String = "GET", body: RequestBody? = null): Request {
        val finalUrl = appendStandardParams(url)
        val finalCookie = buildCookieHeader(cookieHeader)
        val builder = Request.Builder()
            .url(finalUrl)
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Referer", REFERER)
            .addHeader("Accept", "application/json, text/plain, */*")
            .addHeader("X-Requested-With", "XMLHttpRequest")

        if (finalCookie.isNotBlank()) {
            builder.addHeader("Cookie", finalCookie)
        }

        if (method.equals("POST", ignoreCase = true)) {
            builder.post(body ?: FormBody.Builder().build())
        } else {
            builder.get()
        }
        return builder.build()
    }

    /**
     * Executes request with automatic regional domain prefix redirection if errno == -6.
     */
    private fun executeWithRetry(
        pathWithQuery: String,
        cookieHeader: String,
        method: String = "GET",
        body: RequestBody? = null
    ): Pair<Response, String> {
        val fullUrl = "${getBaseUrl()}$pathWithQuery"
        val req = buildAuthorizedRequest(fullUrl, cookieHeader, method, body)
        val resp = okHttpClient.newCall(req).execute()

        // Capture Url-Domain-Prefix header if present in any response
        val domainPrefix = resp.header("Url-Domain-Prefix")
        if (!domainPrefix.isNullOrBlank() && domainPrefix != currentDomainPrefix) {
            currentDomainPrefix = domainPrefix
        }

        val bodyString = resp.body?.string() ?: ""

        // Check if errno == -6 ("user not login" due to wrong domain cluster)
        if (bodyString.contains("\"errno\":-6") || bodyString.contains("\"errno\": -6")) {
            val targetPrefix = domainPrefix?.takeIf { it.isNotBlank() } ?: if (currentDomainPrefix == null) "dm" else null
            if (targetPrefix != null && targetPrefix != currentDomainPrefix) {
                currentDomainPrefix = targetPrefix
                val retryUrl = "${getBaseUrl()}$pathWithQuery"
                val retryReq = buildAuthorizedRequest(retryUrl, cookieHeader, method, body)
                val retryResp = okHttpClient.newCall(retryReq).execute()
                val retryBody = retryResp.body?.string() ?: ""
                return Pair(retryResp, retryBody)
            }
        }

        return Pair(resp, bodyString)
    }

    suspend fun getUserInfo(cookie: String): Result<TeraBoxUserInfo> = withContext(Dispatchers.IO) {
        try {
            val (response, body) = executeWithRetry("/api/user/getinfo", cookie)
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
            }
            val json = JSONObject(body)
            val errno = json.optInt("errno", -1)
            if (errno != 0) {
                return@withContext Result.failure(IOException("TeraBox API error (errno: $errno)"))
            }
            val records = json.optJSONArray("records")
            if (records != null && records.length() > 0) {
                val record = records.getJSONObject(0)
                val uname = record.optString("uname", "TeraBox User")
                val uk = record.optString("uk", "")
                val avatar = record.optString("avatar_url", "")
                val email = record.optString("email", "").takeIf { it.isNotBlank() }
                Result.success(TeraBoxUserInfo(uname = uname, uk = uk, avatarUrl = avatar, email = email))
            } else {
                Result.success(TeraBoxUserInfo(uname = "TeraBox User", uk = "", email = null))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getQuota(cookie: String): Result<TeraBoxQuota> = withContext(Dispatchers.IO) {
        try {
            val (response, body) = executeWithRetry("/api/quota?checkexpire=1&checkfree=1", cookie)
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
            }
            val json = JSONObject(body)
            val errno = json.optInt("errno", -1)
            if (errno != 0) {
                return@withContext Result.failure(IOException("TeraBox API error (errno: $errno)"))
            }
            val total = json.optLong("total", 1024L * 1024 * 1024 * 1024)
            val used = json.optLong("used", 0L)
            val free = json.optLong("free", total - used)
            Result.success(TeraBoxQuota(totalBytes = total, usedBytes = used, freeBytes = free))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listFiles(account: CloudAccount, remotePath: String): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            val cookie = getCookieString(account)
            if (cookie.isBlank()) {
                return@withContext Result.failure(IOException("TeraBox session expired or missing token"))
            }

            val cleanPath = when {
                remotePath.isBlank() || remotePath == "/" -> "/"
                remotePath.startsWith("/") -> remotePath
                else -> "/$remotePath"
            }

            val encodedDir = URLEncoder.encode(cleanPath, "UTF-8")
            val pathWithQuery = "/api/list?dir=$encodedDir&order=name&desc=0&num=1000&page=1&showempty=0"
            val (response, body) = executeWithRetry(pathWithQuery, cookie)
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
            }

            val json = JSONObject(body)
            val errno = json.optInt("errno", -1)
            if (errno != 0) {
                // errno == -9 means directory does not exist or empty
                if (errno == -9) {
                    return@withContext Result.success(emptyList())
                }
                return@withContext Result.failure(IOException("TeraBox listFiles failed (errno: $errno)"))
            }

            val listJson = json.optJSONArray("list") ?: JSONArray()
            val items = mutableListOf<FileItem>()

            for (i in 0 until listJson.length()) {
                val item = listJson.getJSONObject(i)
                val fileName = item.optString("server_filename", "")
                val filePath = item.optString("path", cleanPath.trimEnd('/') + "/" + fileName)
                val isDir = item.optInt("isdir", 0) == 1
                val size = item.optLong("size", 0L)
                val mtimeSec = item.optLong("server_mtime", 0L)
                val lastModified = if (mtimeSec > 0) mtimeSec * 1000L else System.currentTimeMillis()
                val fsId = item.opt("fs_id")?.toString() ?: filePath

                val ext = if (fileName.contains('.')) fileName.substringAfterLast('.').lowercase(Locale.ROOT) else ""
                val mimeType = if (isDir) {
                    "vnd.android.document/directory"
                } else {
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                }

                items.add(
                    FileItem(
                        id = fsId,
                        name = fileName,
                        path = filePath,
                        size = size,
                        isDirectory = isDir,
                        lastModified = lastModified,
                        mimeType = mimeType,
                        itemCount = 0
                    )
                )
            }

            // Sort directories first, then alphabetically
            items.sortWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createFolder(account: CloudAccount, folderName: String, parentPath: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val cookie = getCookieString(account)
            val cleanParent = when {
                parentPath.isBlank() || parentPath == "/" -> ""
                parentPath.endsWith("/") -> parentPath.trimEnd('/')
                else -> parentPath
            }
            val targetPath = "$cleanParent/$folderName"

            val formBody = FormBody.Builder()
                .add("path", targetPath)
                .add("isdir", "1")
                .add("size", "0")
                .add("block_list", "[]")
                .build()

            val (response, body) = executeWithRetry("/api/create", cookie, method = "POST", body = formBody)
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
            }

            val json = JSONObject(body)
            val errno = json.optInt("errno", -1)
            if (errno != 0 && errno != -8) { // errno -8 = already exists
                return@withContext Result.failure(IOException("TeraBox createFolder failed (errno: $errno)"))
            }

            val fsId = json.opt("fs_id")?.toString() ?: targetPath
            Result.success(
                FileItem(
                    id = fsId,
                    name = folderName,
                    path = targetPath,
                    size = 0L,
                    isDirectory = true,
                    lastModified = System.currentTimeMillis(),
                    mimeType = "vnd.android.document/directory",
                    itemCount = 0
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteFile(account: CloudAccount, remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cookie = getCookieString(account)
            val fileListJson = JSONArray().put(remotePath).toString()
            val formBody = FormBody.Builder()
                .add("filelist", fileListJson)
                .build()

            val (response, body) = executeWithRetry("/api/filemanager?opera=delete", cookie, method = "POST", body = formBody)
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
            }

            val json = JSONObject(body)
            val errno = json.optInt("errno", -1)
            if (errno != 0) {
                return@withContext Result.failure(IOException("TeraBox delete failed (errno: $errno)"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun renameFile(account: CloudAccount, oldPath: String, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cookie = getCookieString(account)
            val renameEntry = JSONObject().apply {
                put("path", oldPath)
                put("newname", newName)
            }
            val fileListJson = JSONArray().put(renameEntry).toString()
            val formBody = FormBody.Builder()
                .add("filelist", fileListJson)
                .build()

            val (response, body) = executeWithRetry("/api/filemanager?opera=rename", cookie, method = "POST", body = formBody)
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
            }

            val json = JSONObject(body)
            val errno = json.optInt("errno", -1)
            if (errno != 0) {
                return@withContext Result.failure(IOException("TeraBox rename failed (errno: $errno)"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadFile(
        account: CloudAccount,
        remotePath: String,
        destFile: File,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val cookie = getCookieString(account)
            // 1. Get file download link
            val encodedPath = URLEncoder.encode(remotePath, "UTF-8")
            val downloadApiUrl = "${getBaseUrl()}/rest/2.0/pcs/file?method=download&path=$encodedPath"

            var request = buildAuthorizedRequest(downloadApiUrl, cookie)
            var response = okHttpClient.newCall(request).execute()
            val domainPrefix = response.header("Url-Domain-Prefix")
            if (!domainPrefix.isNullOrBlank() && domainPrefix != currentDomainPrefix) {
                currentDomainPrefix = domainPrefix
            }

            if (!response.isSuccessful) {
                // If failed, try regional baseUrl once
                if (currentDomainPrefix != null) {
                    val retryUrl = "${getBaseUrl()}/rest/2.0/pcs/file?method=download&path=$encodedPath"
                    request = buildAuthorizedRequest(retryUrl, cookie)
                    response = okHttpClient.newCall(request).execute()
                }
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Download failed with HTTP ${response.code}"))
                }
            }

            val responseBody = response.body ?: return@withContext Result.failure(IOException("Empty response body"))
            val totalBytes = responseBody.contentLength()
            var bytesRead = 0L
            var lastProgressTime = 0L

            destFile.parentFile?.mkdirs()
            FileOutputStream(destFile).use { fos ->
                responseBody.byteStream().use { inputStream ->
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        currentCoroutineContext().ensureActive()
                        fos.write(buffer, 0, read)
                        bytesRead += read
                        val now = System.currentTimeMillis()
                        if (now - lastProgressTime >= 100) {
                            lastProgressTime = now
                            onProgress?.invoke(bytesRead, totalBytes)
                        }
                    }
                }
            }
            onProgress?.invoke(bytesRead, totalBytes)
            Result.success(destFile)
        } catch (e: Exception) {
            if (destFile.exists()) destFile.delete()
            Result.failure(e)
        }
    }

    suspend fun uploadFile(
        account: CloudAccount,
        localFile: File,
        remoteParentDir: String,
        onProgress: ((bytesUploaded: Long, totalBytes: Long) -> Unit)? = null
    ): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val cookie = getCookieString(account)
            val cleanParent = when {
                remoteParentDir.isBlank() || remoteParentDir == "/" -> ""
                remoteParentDir.endsWith("/") -> remoteParentDir.trimEnd('/')
                else -> remoteParentDir
            }
            val targetPath = "$cleanParent/${localFile.name}"
            val fileSize = localFile.length()

            // 1. Calculate file MD5
            val md5 = calculateMD5(localFile)
            val blockListJson = JSONArray().put(md5).toString()

            // 2. Precreate file
            val precreateBody = FormBody.Builder()
                .add("path", targetPath)
                .add("size", fileSize.toString())
                .add("isdir", "0")
                .add("autoinit", "1")
                .add("block_list", blockListJson)
                .build()

            val (precreateResp, precreateBodyStr) = executeWithRetry("/api/precreate", cookie, method = "POST", body = precreateBody)
            if (!precreateResp.isSuccessful) {
                return@withContext Result.failure(IOException("Precreate failed: HTTP ${precreateResp.code}"))
            }

            val precreateJson = JSONObject(precreateBodyStr)
            val precreateErr = precreateJson.optInt("errno", -1)
            val returnType = precreateJson.optInt("return_type", 1) // 2 = rapid upload (already exists on server)

            if (precreateErr == 0 && returnType == 2) {
                // Rapid upload completed!
                onProgress?.invoke(fileSize, fileSize)
                val fsId = precreateJson.opt("fs_id")?.toString() ?: targetPath
                return@withContext Result.success(
                    FileItem(
                        id = fsId,
                        name = localFile.name,
                        path = targetPath,
                        size = fileSize,
                        isDirectory = false,
                        lastModified = System.currentTimeMillis()
                    )
                )
            }

            val uploadId = precreateJson.optString("uploadid", "")

            // 3. Upload slice
            val uploadUrl = "${getBaseUrl()}/rest/2.0/pcs/superfile2?method=upload&type=tmpfile&path=${URLEncoder.encode(targetPath, "UTF-8")}&uploadid=$uploadId&partseq=0"

            val fileRequestBody = object : RequestBody() {
                private val fileBody = localFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())
                override fun contentType() = fileBody.contentType()
                override fun contentLength() = fileBody.contentLength()
                override fun writeTo(sink: okio.BufferedSink) {
                    localFile.inputStream().use { inputStream ->
                        val buffer = ByteArray(64 * 1024)
                        var uploaded = 0L
                        var read: Int
                        var lastProgress = 0L
                        while (inputStream.read(buffer).also { read = it } != -1) {
                            sink.write(buffer, 0, read)
                            uploaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastProgress >= 100) {
                                lastProgress = now
                                onProgress?.invoke(uploaded, fileSize)
                            }
                        }
                    }
                }
            }

            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", localFile.name, fileRequestBody)
                .build()

            val uploadReq = buildAuthorizedRequest(uploadUrl, cookie, method = "POST", body = multipartBody)
            val uploadResp = okHttpClient.newCall(uploadReq).execute()
            if (!uploadResp.isSuccessful) {
                return@withContext Result.failure(IOException("Slice upload failed: HTTP ${uploadResp.code}"))
            }

            // 4. Create / Finalize
            val createBody = FormBody.Builder()
                .add("path", targetPath)
                .add("size", fileSize.toString())
                .add("isdir", "0")
                .add("uploadid", uploadId)
                .add("block_list", blockListJson)
                .build()

            val (createResp, createBodyStr) = executeWithRetry("/api/create", cookie, method = "POST", body = createBody)
            if (!createResp.isSuccessful) {
                return@withContext Result.failure(IOException("Create file failed: HTTP ${createResp.code}"))
            }

            val createJson = JSONObject(createBodyStr)
            val createErr = createJson.optInt("errno", -1)
            if (createErr != 0) {
                return@withContext Result.failure(IOException("Create file failed (errno: $createErr)"))
            }

            onProgress?.invoke(fileSize, fileSize)
            val fsId = createJson.opt("fs_id")?.toString() ?: targetPath
            Result.success(
                FileItem(
                    id = fsId,
                    name = localFile.name,
                    path = targetPath,
                    size = fileSize,
                    isDirectory = false,
                    lastModified = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun calculateMD5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { fis ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val bytes = digest.digest()
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
