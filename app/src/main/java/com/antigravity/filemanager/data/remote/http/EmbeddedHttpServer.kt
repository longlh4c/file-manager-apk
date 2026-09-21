package com.antigravity.filemanager.data.remote.http

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

@Singleton
class EmbeddedHttpServer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var server: InternalServer? = null
    var isRunning: Boolean = false
        private set

    fun start(port: Int = 8080, password: String = ""): Boolean {
        if (isRunning) return true
        val validPort = if (port in 1024..65535) port else 8080
        return try {
            val s = InternalServer(validPort, password, context)
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = s
            isRunning = true
            android.util.Log.i("EmbeddedHttpServer", "HTTP Web Share server started on port $validPort")
            true
        } catch (e: Throwable) {
            android.util.Log.e("EmbeddedHttpServer", "Failed to start HTTP Web Share server on port $validPort", e)
            isRunning = false
            server = null
            false
        }
    }

    fun stop() {
        try {
            server?.stop()
        } catch (e: Exception) {
            android.util.Log.e("EmbeddedHttpServer", "Error stopping HTTP Web Share server", e)
        } finally {
            server = null
            isRunning = false
            android.util.Log.i("EmbeddedHttpServer", "HTTP Web Share server stopped")
        }
    }

    private class InternalServer(
        port: Int,
        private val expectedPassword: String,
        private val appContext: Context
    ) : NanoHTTPD(port) {

        private val storageRoot: File = Environment.getExternalStorageDirectory()

        private val systemFolderNames = setOf(
            "android",
            "lost.dir",
            "system volume information",
            "\$recycle.bin",
            "__macosx"
        )

        private fun isSystemOrHiddenName(name: String, isDirectory: Boolean = false): Boolean {
            if (name.startsWith(".")) return true
            if (isDirectory && name.lowercase(java.util.Locale.ROOT) in systemFolderNames) return true
            return false
        }

        private fun isSystemOrHidden(file: File): Boolean {
            return isSystemOrHiddenName(file.name, file.isDirectory)
        }

        private fun isInsideSystemOrHiddenFolder(file: File): Boolean {
            var curr: File? = file
            while (curr != null && curr.canonicalPath != storageRoot.canonicalPath) {
                if (isSystemOrHidden(curr)) return true
                curr = curr.parentFile
            }
            return false
        }

        override fun serve(session: IHTTPSession): Response {
            val method = session.method
            val uri = session.uri

            if (method == Method.OPTIONS) {
                return finalizeResponse(newFixedLengthResponse(Response.Status.OK, "text/plain", ""))
            }

            // Authentication check (except for root page which serves the UI containing the prompt)
            if (expectedPassword.isNotBlank() && uri.startsWith("/api/")) {
                val token = session.headers["x-auth-token"] ?: session.parms["auth"] ?: ""
                if (token != expectedPassword) {
                    val resp = newFixedLengthResponse(
                        Response.Status.UNAUTHORIZED,
                        "application/json",
                        "{\"error\":\"Unauthorized\",\"requireAuth\":true}"
                    )
                    return finalizeResponse(resp)
                }
            }

            return try {
                when {
                    uri == "/" || uri == "/index.html" -> {
                        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", WebShareAssets.getIndexHtml(appContext))
                    }
                    uri == "/api/list" -> handleList(session)
                    uri == "/api/search" -> handleSearch(session)
                    uri == "/api/download" -> handleDownload(session)
                    uri == "/api/upload" && method == Method.POST -> handleUpload(session)
                    uri == "/api/mkdir" && method == Method.POST -> handleMkdir(session)
                    uri == "/api/delete" && method == Method.POST -> handleDelete(session)
                    else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
                }
            } catch (e: Exception) {
                android.util.Log.e("EmbeddedHttpServer", "Error handling HTTP request: $uri", e)
                finalizeResponse(newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    "{\"error\":\"${e.message?.replace("\"", "\\\"") ?: "Internal Server Error"}\"}"
                ))
            }
        }

        private fun resolveSafeFile(relativePath: String): File? {
            val cleanRel = relativePath.trim().removePrefix("/").replace("\\", "/")
            val file = if (cleanRel.isEmpty()) storageRoot else File(storageRoot, cleanRel)
            // Path traversal prevention. The separator matters: a bare prefix check let
            // "../10" through to a sibling volume like /storage/emulated/10.
            val canonical = file.canonicalFile
            val rootPath = storageRoot.canonicalPath
            return if (canonical.path == rootPath || canonical.path.startsWith(rootPath + File.separator)) file else null
        }

        private fun handleList(session: IHTTPSession): Response {
            val relPath = session.parms["path"] ?: ""
            val targetDir = resolveSafeFile(relPath) ?: return finalizeResponse(
                newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Invalid path\"}")
            )

            if (!targetDir.exists() || !targetDir.isDirectory) {
                return finalizeResponse(
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"Folder not found\"}")
                )
            }

            if (targetDir != storageRoot && isInsideSystemOrHiddenFolder(targetDir)) {
                return finalizeResponse(
                    newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"error\":\"Access to system directory is restricted\"}")
                )
            }

            val rawFiles = targetDir.listFiles() ?: emptyArray()
            val visibleFiles = rawFiles.filterNot { isSystemOrHidden(it) }
            val sorted = visibleFiles.sortedWith(
                compareBy<File> { !it.isDirectory }
                    .thenBy { it.name.lowercase(java.util.Locale.ROOT) }
            )

            val jsonArray = JSONArray()
            for (f in sorted) {
                val childRel = if (relPath.isEmpty()) f.name else "$relPath/${f.name}"
                val obj = JSONObject().apply {
                    put("name", f.name)
                    put("path", childRel)
                    put("isDir", f.isDirectory)
                    put("size", if (f.isDirectory) 0L else f.length())
                    put("lastModified", f.lastModified())
                }
                jsonArray.put(obj)
            }

            val resObj = JSONObject().apply {
                put("currentPath", relPath)
                put("items", jsonArray)
            }

            return finalizeResponse(newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                resObj.toString()
            ))
        }

        private fun handleSearch(session: IHTTPSession): Response {
            val relPath = session.parms["path"] ?: ""
            val query = (session.parms["q"] ?: "").trim()
            val targetDir = resolveSafeFile(relPath) ?: return finalizeResponse(
                newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Invalid path\"}")
            )

            if (!targetDir.exists() || !targetDir.isDirectory) {
                return finalizeResponse(
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"Folder not found\"}")
                )
            }

            if (targetDir != storageRoot && isInsideSystemOrHiddenFolder(targetDir)) {
                return finalizeResponse(
                    newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"error\":\"Access to system directory is restricted\"}")
                )
            }

            if (query.isEmpty()) {
                return handleList(session)
            }

            val jsonArray = JSONArray()
            var count = 0
            val maxResults = 300

            fun walk(dir: File, currentRel: String) {
                if (count >= maxResults) return
                val files = dir.listFiles() ?: return
                val sortedFiles = files.sortedWith(
                    compareBy<File> { !it.isDirectory }
                        .thenBy { it.name.lowercase(java.util.Locale.ROOT) }
                )
                for (f in sortedFiles) {
                    if (count >= maxResults) break
                    if (isSystemOrHidden(f)) continue
                    val childRel = if (currentRel.isEmpty()) f.name else "$currentRel/${f.name}"
                    if (f.name.contains(query, ignoreCase = true)) {
                        val obj = JSONObject().apply {
                            put("name", f.name)
                            put("path", childRel)
                            put("isDir", f.isDirectory)
                            put("size", if (f.isDirectory) 0L else f.length())
                            put("lastModified", f.lastModified())
                        }
                        jsonArray.put(obj)
                        count++
                    }
                    if (f.isDirectory) {
                        walk(f, childRel)
                    }
                }
            }

            walk(targetDir, relPath)

            val resObj = JSONObject().apply {
                put("currentPath", relPath)
                put("query", query)
                put("isSearchResult", true)
                put("items", jsonArray)
            }

            return finalizeResponse(newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                resObj.toString()
            ))
        }

        private fun handleDownload(session: IHTTPSession): Response {
            val relPath = session.parms["path"] ?: ""
            val targetFile = resolveSafeFile(relPath) ?: return finalizeResponse(
                newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid path")
            )

            if (!targetFile.exists()) {
                return finalizeResponse(
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
                )
            }

            if (targetFile != storageRoot && isInsideSystemOrHiddenFolder(targetFile)) {
                return finalizeResponse(
                    newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Access to system files or folders is restricted")
                )
            }

            if (targetFile.isDirectory) {
                val rawName = if (relPath.isEmpty() || targetFile == storageRoot) "Storage" else targetFile.name
                val cleanName = rawName.replace("\"", "").replace("\\", "").trim().ifEmpty { "folder" }
                val zipName = "$cleanName.zip"

                val pos = PipedOutputStream()
                val pis = PipedInputStream(pos, 64 * 1024)

                thread(name = "ZipFolderThread", isDaemon = true) {
                    try {
                        ZipOutputStream(BufferedOutputStream(pos, 64 * 1024)).use { zos ->
                            fun addDirToZip(dir: File, basePath: String) {
                                val files = dir.listFiles() ?: return
                                for (file in files) {
                                    if (isSystemOrHidden(file)) continue
                                    val entryPath = if (basePath.isEmpty()) file.name else "$basePath/${file.name}"
                                    if (file.isDirectory) {
                                        val zipEntry = ZipEntry("$entryPath/")
                                        zipEntry.time = file.lastModified()
                                        zos.putNextEntry(zipEntry)
                                        zos.closeEntry()
                                        addDirToZip(file, entryPath)
                                    } else if (file.isFile) {
                                        val zipEntry = ZipEntry(entryPath)
                                        zipEntry.time = file.lastModified()
                                        zos.putNextEntry(zipEntry)
                                        file.inputStream().buffered(32 * 1024).use { fis ->
                                            fis.copyTo(zos, bufferSize = 32 * 1024)
                                        }
                                        zos.closeEntry()
                                    }
                                }
                            }
                            addDirToZip(targetFile, "")
                            zos.finish()
                        }
                    } catch (e: Exception) {
                        // Client aborted download or pipe was closed
                    }
                }

                val response = newChunkedResponse(
                    Response.Status.OK,
                    "application/zip",
                    pis
                )
                response.addHeader("Content-Disposition", "attachment; filename=\"$zipName\"")
                return finalizeResponse(response)
            }

            val fileLen = targetFile.length()
            val mime = getMimeTypeForFile(targetFile.name) ?: "application/octet-stream"
            val rangeHeader = session.headers["range"]

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                var rangeStart = 0L
                var rangeEnd = fileLen - 1L

                val rangeVal = rangeHeader.removePrefix("bytes=").trim()
                val dashIdx = rangeVal.indexOf('-')
                if (dashIdx != -1) {
                    val startStr = rangeVal.substring(0, dashIdx).trim()
                    val endStr = rangeVal.substring(dashIdx + 1).trim()
                    if (startStr.isEmpty()) {
                        // Suffix range "bytes=-N": the last N bytes.
                        val suffix = endStr.toLongOrNull() ?: 0L
                        rangeStart = (fileLen - suffix).coerceAtLeast(0L)
                    } else {
                        rangeStart = startStr.toLongOrNull() ?: 0L
                        if (endStr.isNotEmpty()) rangeEnd = endStr.toLongOrNull() ?: (fileLen - 1L)
                    }
                }

                if (rangeStart > rangeEnd || rangeStart >= fileLen) {
                    val errResp = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
                    errResp.addHeader("Content-Range", "bytes */$fileLen")
                    return finalizeResponse(errResp)
                }

                if (rangeEnd >= fileLen) rangeEnd = fileLen - 1L
                val sendLength = rangeEnd - rangeStart + 1L

                val fis = FileInputStream(targetFile)
                fis.skip(rangeStart)

                val response = newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT,
                    mime,
                    fis,
                    sendLength
                )
                response.addHeader("Content-Range", "bytes $rangeStart-$rangeEnd/$fileLen")
                response.addHeader("Accept-Ranges", "bytes")
                response.addHeader("Content-Disposition", "inline; filename=\"${targetFile.name}\"")
                return finalizeResponse(response)
            } else {
                val fis = FileInputStream(targetFile)
                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    mime,
                    fis,
                    fileLen
                )
                response.addHeader("Accept-Ranges", "bytes")
                response.addHeader("Content-Disposition", "inline; filename=\"${targetFile.name}\"")
                return finalizeResponse(response)
            }
        }

        private fun decodeUrlSafe(value: String): String {
            return try {
                java.net.URLDecoder.decode(value, "UTF-8")
            } catch (e: Exception) {
                value
            }
        }

        private fun handleUpload(session: IHTTPSession): Response {
            val relPath = session.parms["path"] ?: ""
            val targetDir = resolveSafeFile(relPath) ?: return finalizeResponse(
                newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Invalid path\"}")
            )

            if (targetDir != storageRoot && isInsideSystemOrHiddenFolder(targetDir)) {
                return finalizeResponse(
                    newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"error\":\"Upload to system directory is restricted\"}")
                )
            }

            if (!targetDir.exists()) targetDir.mkdirs()

            // Resolve explicit file name from query parameter or custom header to bypass NanoHTTPD's
            // multipart regex bug where filenames containing single quotes are truncated (e.g. "Sid Meier's" -> "Sid Meier")
            val explicitFileName = session.headers["x-file-name"]?.let { decodeUrlSafe(it) }?.takeIf { it.isNotBlank() }
                ?: session.parms["filename"]?.let { decodeUrlSafe(it) }?.takeIf { it.isNotBlank() }

            val files = HashMap<String, String>()
            session.parseBody(files)

            val uploadedPaths = mutableListOf<String>()

            for ((field, tempFilePath) in files) {
                val resolvedName = if (!explicitFileName.isNullOrBlank() && (files.size == 1 || field == "file")) {
                    explicitFileName
                } else {
                    session.parms[field]?.takeIf { it.isNotBlank() } ?: explicitFileName ?: "upload_${System.currentTimeMillis()}"
                }
                val safeFileName = File(resolvedName).name
                if (isSystemOrHiddenName(safeFileName, isDirectory = false)) {
                    File(tempFilePath).delete()
                    continue
                }
                val destFile = File(targetDir, safeFileName)

                val tempFile = File(tempFilePath)
                if (tempFile.exists()) {
                    FileInputStream(tempFile).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    tempFile.delete()
                    uploadedPaths.add(destFile.absolutePath)
                }
            }

            // Notify Android MediaStore of new files
            if (uploadedPaths.isNotEmpty()) {
                MediaScannerConnection.scanFile(
                    appContext,
                    uploadedPaths.toTypedArray(),
                    null,
                    null
                )
            }

            return finalizeResponse(newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                "{\"success\":true,\"count\":${uploadedPaths.size}}"
            ))
        }

        private fun handleMkdir(session: IHTTPSession): Response {
            val relPath = session.parms["path"] ?: ""
            val folderName = session.parms["name"]?.trim() ?: ""

            if (folderName.isEmpty() || folderName.contains("/") || folderName.contains("\\")) {
                return finalizeResponse(newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    "{\"error\":\"Invalid folder name\"}"
                ))
            }

            if (isSystemOrHiddenName(folderName, isDirectory = true)) {
                return finalizeResponse(newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    "{\"error\":\"Cannot create system or hidden folder\"}"
                ))
            }

            val parentDir = resolveSafeFile(relPath) ?: return finalizeResponse(
                newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Invalid parent path\"}")
            )

            if (parentDir != storageRoot && isInsideSystemOrHiddenFolder(parentDir)) {
                return finalizeResponse(
                    newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"error\":\"Cannot create folder inside system directory\"}")
                )
            }

            val newDir = File(parentDir, folderName)
            val created = newDir.mkdirs()

            return finalizeResponse(newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                "{\"success\":$created}"
            ))
        }

        private fun handleDelete(session: IHTTPSession): Response {
            val relPath = session.parms["path"] ?: ""
            val target = resolveSafeFile(relPath) ?: return finalizeResponse(
                newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Invalid path\"}")
            )

            if (!target.exists() || target.canonicalPath == storageRoot.canonicalPath || isInsideSystemOrHiddenFolder(target)) {
                return finalizeResponse(newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    "{\"error\":\"Cannot delete system folder, hidden item, or root directory\"}"
                ))
            }

            val deleted = target.deleteRecursively()
            return finalizeResponse(newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                "{\"success\":$deleted}"
            ))
        }

        // The web UI is served from this same origin, so no CORS headers are sent: a wildcard
        // Access-Control-Allow-Origin let any website opened by anyone on the LAN read (and, with
        // no password set, list/download) the device's files through the visitor's browser.
        private fun finalizeResponse(response: Response): Response {
            response.addHeader("X-Content-Type-Options", "nosniff")
            return response
        }
    }
}
