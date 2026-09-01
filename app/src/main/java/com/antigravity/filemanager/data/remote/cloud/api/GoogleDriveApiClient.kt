package com.antigravity.filemanager.data.remote.cloud.api

import android.content.Context
import com.antigravity.filemanager.domain.model.CloudAccount
import com.antigravity.filemanager.domain.model.FileItem
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

// Google Drive API v3 client authenticated via Google Sign-In OAuth2.
@Singleton
class GoogleDriveApiClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: okhttp3.OkHttpClient
) {
    private fun buildCredential(account: CloudAccount): GoogleAccountCredential {
        val credential = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE))
        credential.selectedAccountName = account.email
        return credential
    }

    private fun buildDrive(account: CloudAccount): Drive {
        val credential = buildCredential(account)
        // The google-http-client default is a flat 20s connect+read timeout, applied to every
        // request including actual media downloads — fine for small metadata calls, but nowhere
        // near enough to stream a real file over anything but a fast connection (this is exactly
        // what was cutting "Shared with me" downloads short: metadata calls squeaked under 20s,
        // the media body read didn't). Wrap the credential's initializer to raise both.
        val requestInitializer = HttpRequestInitializer { request ->
            credential.initialize(request)
            request.connectTimeout = 30_000
            request.readTimeout = 120_000
        }
        return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), requestInitializer)
            .setApplicationName("FileManagerPlus")
            .build()
    }

    /**
     * Fetches Drive's own pre-generated thumbnail (a small JPEG, typically a few KB-hundred KB)
     * instead of the full file — this is what prefetchThumbnails() should always try first. Before
     * this existed, showing a thumbnail for ANY Drive file meant downloading up to 15MB of the
     * real file just to render a preview, which is what made large "Shared with me" items (other
     * people's photos/videos tend to run bigger) feel so slow to browse.
     */
    suspend fun downloadThumbnail(account: CloudAccount, fileId: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val drive = buildDrive(account)
            val meta = drive.files().get(fileId)
                .setFields("thumbnailLink")
                .setSupportsAllDrives(true)
                .execute()
            val thumbnailLink = meta.thumbnailLink
            if (thumbnailLink.isNullOrBlank()) {
                return@withContext Result.failure(Exception("No thumbnailLink for $fileId"))
            }

            val credential = buildCredential(account)
            val token = credential.token
            val request = okhttp3.Request.Builder()
                .url(thumbnailLink)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Thumbnail fetch failed: ${response.code}"))
                }
                val bytes = response.body?.bytes()
                if (bytes == null || bytes.isEmpty()) {
                    Result.failure(Exception("Empty thumbnail body for $fileId"))
                } else {
                    Result.success(bytes)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("GoogleDriveApiClient", "downloadThumbnail failed for $fileId: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Drive's v3 media-download endpoint has no pre-signed/anonymous URL option like Dropbox's
     * `getTemporaryLink` — every request needs a fresh bearer token attached. `credential.token`
     * does a blocking fetch that auto-refreshes an expired token, same call already used above
     * for thumbnails, so this is safe to call from a background dispatcher.
     */
    suspend fun getAuthenticatedMediaUrl(account: CloudAccount, fileId: String): Result<com.antigravity.filemanager.domain.model.CloudStreamSource> =
        withContext(Dispatchers.IO) {
            try {
                val credential = buildCredential(account)
                val token = credential.token
                val url = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media&supportsAllDrives=true"
                Result.success(
                    com.antigravity.filemanager.domain.model.CloudStreamSource(
                        url = url,
                        headers = mapOf("Authorization" to "Bearer $token")
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun toFileItem(f: DriveFile): FileItem {
        val isDir = f.mimeType == "application/vnd.google-apps.folder"
        val rawExt = if (!isDir && f.name.contains(".")) f.name.substringAfterLast(".", "") else ""
        val ext = if (rawExt.isNotBlank() && !rawExt.contains("/")) {
            rawExt
        } else {
            when (f.mimeType) {
                "application/vnd.google-apps.document" -> "gdoc"
                "application/vnd.google-apps.spreadsheet" -> "gsheet"
                "application/vnd.google-apps.presentation" -> "gslides"
                "application/vnd.google-apps.form" -> "gform"
                "application/pdf" -> "pdf"
                else -> ""
            }
        }
        return FileItem(
            id = f.id,
            name = f.name,
            path = f.id,
            size = f.getSize() ?: 0L,
            lastModified = f.modifiedTime?.value ?: 0L,
            isDirectory = isDir,
            mimeType = f.mimeType ?: "*/*",
            extension = ext,
            itemCount = 0
        )
    }

    // includeItemsFromAllDrives/supportsAllDrives are required for any query to see or traverse
    // into Shared Drives content — without them the API silently omits it, so every listing call
    // below sets them rather than only the ones that obviously touch a Shared Drive (folder IDs
    // don't self-identify as "inside a Shared Drive" ahead of the query).
    /** Resolves a "/My Drive/Name/Name" display path (built by CloudManager to mimic Dropbox's
     * real-path model, since Drive identifies folders by opaque ID, not path) back to the real
     * folder ID by walking the tree level by level from root. CloudManager caches this result in
     * folderIdCache so it's normally an O(1) hit — this is only the fallback for a cache miss
     * (process just started, or navigating straight into a nested folder without replaying every
     * parent level first). Without this, a cache miss fell back to treating the display-path
     * STRING itself as a folder ID, which Drive's API rejects with a 404 "File not found: ." —
     * one real API call per path segment, since Drive has no bulk "give me the whole tree" call
     * the way MEGA's resolveHandleForDisplayPath can do entirely in-memory. */
    suspend fun resolveIdForDisplayPath(account: CloudAccount, displayPath: String): String? {
        val segments = displayPath.trim('/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        // "My Drive" is the virtual alias for the real root folder id — not an actual named
        // child to search for, so skip it as a path segment if present.
        val startIndex = if (segments[0].equals("My Drive", ignoreCase = true)) 1 else 0
        var currentId = "root"
        for (i in startIndex until segments.size) {
            val name = segments[i]
            val children = listFiles(account, currentId).getOrNull() ?: return null
            val match = children.find { it.isDirectory && it.name == name } ?: return null
            currentId = match.id
        }
        return currentId
    }

    suspend fun listFiles(account: CloudAccount, parentId: String = "root"): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            val drive = buildDrive(account)
            val files = mutableListOf<DriveFile>()
            var pageToken: String? = null
            do {
                val result = drive.files().list()
                    .setQ("'$parentId' in parents and trashed = false")
                    .setFields("nextPageToken, files(id,name,mimeType,size,modifiedTime)")
                    .setPageSize(200)
                    .setPageToken(pageToken)
                    .setIncludeItemsFromAllDrives(true)
                    .setSupportsAllDrives(true)
                    .execute()
                files.addAll(result.files ?: emptyList())
                pageToken = result.nextPageToken
            } while (pageToken != null)

            Result.success(files.map { toFileItem(it) })
        } catch (e: Exception) {
            android.util.Log.e("GoogleDriveApiClient", "listFiles failed for parentId=$parentId: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** Files/folders the user has starred, regardless of where they live (My Drive or a Shared Drive). */
    suspend fun listStarred(account: CloudAccount): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            val drive = buildDrive(account)
            val files = mutableListOf<DriveFile>()
            var pageToken: String? = null
            do {
                val result = drive.files().list()
                    .setQ("starred = true and trashed = false")
                    .setFields("nextPageToken, files(id,name,mimeType,size,modifiedTime)")
                    .setPageSize(200)
                    .setPageToken(pageToken)
                    .setIncludeItemsFromAllDrives(true)
                    .setSupportsAllDrives(true)
                    .execute()
                files.addAll(result.files ?: emptyList())
                pageToken = result.nextPageToken
            } while (pageToken != null)

            Result.success(files.map { toFileItem(it) })
        } catch (e: Exception) {
            android.util.Log.e("GoogleDriveApiClient", "listStarred failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** Items currently in this account's Drive Trash (see [trashFile]) — the mirror image of
     * every other listing query here, which all exclude `trashed = true`. */
    suspend fun listTrash(account: CloudAccount): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            val drive = buildDrive(account)
            val files = mutableListOf<DriveFile>()
            var pageToken: String? = null
            do {
                val result = drive.files().list()
                    .setQ("trashed = true")
                    .setFields("nextPageToken, files(id,name,mimeType,size,modifiedTime)")
                    .setPageSize(200)
                    .setPageToken(pageToken)
                    .setIncludeItemsFromAllDrives(true)
                    .setSupportsAllDrives(true)
                    .execute()
                files.addAll(result.files ?: emptyList())
                pageToken = result.nextPageToken
            } while (pageToken != null)

            Result.success(files.map { toFileItem(it) })
        } catch (e: Exception) {
            android.util.Log.e("GoogleDriveApiClient", "listTrash failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** Items other people have shared directly with this account (not the user's own tree). */
    suspend fun listSharedWithMe(account: CloudAccount): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            val drive = buildDrive(account)
            val files = mutableListOf<DriveFile>()
            var pageToken: String? = null
            do {
                val result = drive.files().list()
                    .setQ("sharedWithMe = true and trashed = false")
                    .setFields("nextPageToken, files(id,name,mimeType,size,modifiedTime)")
                    .setPageSize(200)
                    .setPageToken(pageToken)
                    .setIncludeItemsFromAllDrives(true)
                    .setSupportsAllDrives(true)
                    .execute()
                files.addAll(result.files ?: emptyList())
                pageToken = result.nextPageToken
            } while (pageToken != null)

            Result.success(files.map { toFileItem(it) })
        } catch (e: Exception) {
            android.util.Log.e("GoogleDriveApiClient", "listSharedWithMe failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** Shared Drives (formerly Team Drives) this account belongs to. A Shared Drive's own id also
     * works directly as a parentId for listFiles() — its "root" IS that id, no special corpora
     * scoping needed once includeItemsFromAllDrives/supportsAllDrives are set. */
    suspend fun listSharedDrives(account: CloudAccount): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            val drive = buildDrive(account)
            val drives = mutableListOf<com.google.api.services.drive.model.Drive>()
            var pageToken: String? = null
            do {
                val result = drive.drives().list()
                    .setPageSize(100)
                    .setPageToken(pageToken)
                    .execute()
                drives.addAll(result.drives ?: emptyList())
                pageToken = result.nextPageToken
            } while (pageToken != null)

            Result.success(
                drives.map { d ->
                    FileItem(
                        id = d.id,
                        name = d.name ?: "Shared Drive",
                        path = d.id,
                        isDirectory = true,
                        mimeType = "application/vnd.google-apps.folder"
                    )
                }
            )
        } catch (e: Exception) {
            android.util.Log.e("GoogleDriveApiClient", "listSharedDrives failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun sanitizeLocalFileName(name: String): String {
        return name
            .replace('/', '-')
            .replace('\\', '-')
            .replace(':', '-')
            .replace('*', '_')
            .replace('?', '_')
            .replace('"', '\'')
            .replace('<', '(')
            .replace('>', ')')
            .replace('|', '_')
            .trim()
    }

    suspend fun getStorageQuota(account: CloudAccount): Result<Pair<Long, Long>> = withContext(Dispatchers.IO) {
        try {
            val drive = buildDrive(account)
            val about = drive.about().get().setFields("storageQuota").execute()
            val quota = about.storageQuota
            val total = quota?.limit ?: (15L * 1024 * 1024 * 1024)
            val used = quota?.usage ?: 0L
            Result.success(Pair(total, used))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadFile(
        account: CloudAccount,
        fileId: String,
        localTargetDir: String,
        fileName: String,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val drive = buildDrive(account)
            var meta: DriveFile? = null
            try {
                meta = drive.files().get(fileId).setFields("id,name,mimeType,size").setSupportsAllDrives(true).execute()
                android.util.Log.d("GoogleDriveApiClient", "get($fileId) meta: name=${meta?.name}, mimeType=${meta?.mimeType}")
            } catch (e: Exception) {
                android.util.Log.w("GoogleDriveApiClient", "Could not fetch metadata for $fileId: ${e.message}")
            }

            val rawName = if (fileName.isNotBlank() && fileName != fileId && fileName != "cloud_file") {
                fileName
            } else {
                meta?.name ?: fileName.ifEmpty { "downloaded_file" }
            }

            val safeName = sanitizeLocalFileName(rawName)
            val mimeType = meta?.mimeType ?: ""
            val totalBytes = meta?.getSize() ?: 0L
            val (actualFileName, exportMimeType) = when {
                mimeType == "application/vnd.google-apps.document" -> Pair(
                    if (safeName.endsWith(".docx", ignoreCase = true)) safeName else "$safeName.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                )
                mimeType == "application/vnd.google-apps.spreadsheet" -> Pair(
                    if (safeName.endsWith(".xlsx", ignoreCase = true)) safeName else "$safeName.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                )
                mimeType == "application/vnd.google-apps.presentation" -> Pair(
                    if (safeName.endsWith(".pptx", ignoreCase = true)) safeName else "$safeName.pptx",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                )
                mimeType.startsWith("application/vnd.google-apps.") && !mimeType.contains("folder") -> Pair(
                    if (safeName.endsWith(".pdf", ignoreCase = true)) safeName else "$safeName.pdf",
                    "application/pdf"
                )
                else -> Pair(safeName, null)
            }

            val targetFile = File(localTargetDir, actualFileName)
            targetFile.parentFile?.mkdirs()
            if (targetFile.exists()) {
                targetFile.delete()
            }

            if (exportMimeType != null) {
                val inputStream = drive.files().export(fileId, exportMimeType).executeMediaAsInputStream()
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead = 0L
                    var read: Int
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        onProgress?.invoke(bytesRead, totalBytes)
                    }
                }
            } else {
                try {
                    val inputStream = drive.files().get(fileId).setSupportsAllDrives(true).executeMediaAsInputStream()
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead = 0L
                        var read: Int
                        while (inputStream.read(buffer).also { read = it } != -1) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            output.write(buffer, 0, read)
                            bytesRead += read
                            onProgress?.invoke(bytesRead, totalBytes)
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    val msg = e.message.orEmpty()
                    if (msg.contains("fileNotDownloadable") || msg.contains("Use Export") || msg.contains("403")) {
                        android.util.Log.i("GoogleDriveApiClient", "Binary download rejected; attempting Google Docs export for $fileId")
                        val exportedFile = if (targetFile.name.contains(".")) targetFile else File(targetFile.parentFile, "${targetFile.name}.docx")
                        if (exportedFile.exists()) exportedFile.delete()
                        val exportStream = try {
                            drive.files().export(fileId, "application/vnd.openxmlformats-officedocument.wordprocessingml.document").executeMediaAsInputStream()
                        } catch (e2: Exception) {
                            drive.files().export(fileId, "application/pdf").executeMediaAsInputStream()
                        }
                        FileOutputStream(exportedFile).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var bytesRead = 0L
                            var read: Int
                            while (exportStream.read(buffer).also { read = it } != -1) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                output.write(buffer, 0, read)
                                bytesRead += read
                                onProgress?.invoke(bytesRead, totalBytes)
                            }
                        }
                        return@withContext Result.success(exportedFile)
                    } else {
                        throw e
                    }
                }
            }
            onProgress?.invoke(targetFile.length(), targetFile.length())
            Result.success(targetFile)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("GoogleDriveApiClient", "Download failed for fileId=$fileId, fileName=$fileName: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun uploadFile(
        account: CloudAccount,
        localFile: File,
        parentId: String = "root",
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val drive = buildDrive(account)
            val effectiveParent = if (parentId == "/" || parentId.isBlank()) "root" else parentId
            val metadata = DriveFile().apply {
                name = localFile.name
                parents = listOf(effectiveParent)
            }
            val mediaContent = FileContent(null, localFile)
            val totalBytes = localFile.length()
            val uploader = drive.files().create(metadata, mediaContent)
            uploader.mediaHttpUploader?.setProgressListener { httpUploader ->
                val progress = httpUploader.progress
                val bytesSent = (progress * totalBytes).toLong()
                onProgress?.invoke(bytesSent, totalBytes)
            }
            val created = uploader.setFields("id").execute()
            onProgress?.invoke(totalBytes, totalBytes)
            Result.success(created.id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createFolder(account: CloudAccount, name: String, parentId: String = "root"): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val drive = buildDrive(account)
            val effectiveParent = if (parentId == "/" || parentId.isBlank()) "root" else parentId
            val metadata = DriveFile().apply {
                this.name = name
                mimeType = "application/vnd.google-apps.folder"
                parents = listOf(effectiveParent)
            }
            val created = drive.files().create(metadata).setFields("id").execute()
            Result.success(
                FileItem(
                    id = created.id,
                    name = name,
                    path = created.id,
                    isDirectory = true,
                    mimeType = "application/vnd.google-apps.folder"
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteFile(account: CloudAccount, fileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val drive = buildDrive(account)
            drive.files().delete(fileId).execute()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Real "move to trash" — Drive's own Trash, restorable from drive.google.com for 30 days,
     * unlike [deleteFile] which is permanent. */
    suspend fun trashFile(account: CloudAccount, fileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val drive = buildDrive(account)
            drive.files().update(fileId, DriveFile().apply { trashed = true }).execute()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Restores a file out of Drive's Trash back to wherever it lived (Drive remembers the
     * original parent(s) itself — untrashing doesn't need us to track/restore a location). */
    suspend fun restoreFromTrash(account: CloudAccount, fileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val drive = buildDrive(account)
            drive.files().update(fileId, DriveFile().apply { trashed = false }).execute()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun renameFile(account: CloudAccount, fileId: String, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val drive = buildDrive(account)
            drive.files().update(fileId, DriveFile().apply { name = newName }).execute()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Relocates a file/folder to a different parent within the SAME account — Drive has no
     * "path", a file's location IS its parent set, so a move is just swapping which folder is
     * listed as parent, entirely server-side (no data transfer). This is what makes a same-
     * account cloud "Move" cheap, unlike the generic cross-provider paste flow's download-then-
     * upload round trip (needed there only because no such API exists across providers). */
    suspend fun moveFile(account: CloudAccount, fileId: String, oldParentId: String, newParentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val drive = buildDrive(account)
            drive.files().update(fileId, null)
                .setAddParents(newParentId)
                .setRemoveParents(oldParentId)
                .setSupportsAllDrives(true)
                .execute()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
