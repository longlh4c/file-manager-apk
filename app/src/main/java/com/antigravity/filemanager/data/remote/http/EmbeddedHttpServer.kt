package com.antigravity.filemanager.data.remote.http

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

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

        override fun serve(session: IHTTPSession): Response {
            val method = session.method
            val uri = session.uri

            if (method == Method.OPTIONS) {
                return addCorsHeaders(newFixedLengthResponse(Response.Status.OK, "text/plain", ""))
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
                    return addCorsHeaders(resp)
                }
            }

            return try {
                when {
                    uri == "/" || uri == "/index.html" -> {
                        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", WebShareAssets.getIndexHtml(appContext))
                    }
                    uri == "/api/list" -> handleList(session)
                    uri == "/api/download" -> handleDownload(session)
                    uri == "/api/upload" && method == Method.POST -> handleUpload(session)
                    uri == "/api/mkdir" && method == Method.POST -> handleMkdir(session)
                    uri == "/api/delete" && method == Method.POST -> handleDelete(session)
                    else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
                }
            } catch (e: Exception) {
                android.util.Log.e("EmbeddedHttpServer", "Error handling HTTP request: $uri", e)
                addCorsHeaders(newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    "{\"error\":\"${e.message?.replace("\"", "\\\"") ?: "Internal Server Error"}\"}"
                ))
            }
        }

        private fun resolveSafeFile(relativePath: String): File? {
            val cleanRel = relativePath.trim().removePrefix("/").replace("\\", "/")
            val file = if (cleanRel.isEmpty()) storageRoot else File(storageRoot, cleanRel)
            // Path traversal prevention: verify canonical path starts with storageRoot
            return if (file.canonicalPath.startsWith(storageRoot.canonicalPath)) file else null
        }

        private fun handleList(session: IHTTPSession): Response {
            val relPath = session.parms["path"] ?: ""
            val targetDir = resolveSafeFile(relPath) ?: return addCorsHeaders(
                newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Invalid path\"}")
            )

            if (!targetDir.exists() || !targetDir.isDirectory) {
                return addCorsHeaders(
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"Folder not found\"}")
                )
            }

            val rawFiles = targetDir.listFiles() ?: emptyArray()
            val sorted = rawFiles.sortedWith(
                compareBy<File> { !it.isDirectory }
                    .thenBy { it.name.lowercase() }
            )

            val jsonArray = JSONArray()
            for (f in sorted) {
                val obj = JSONObject().apply {
                    put("name", f.name)
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

            return addCorsHeaders(newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                resObj.toString()
            ))
        }

        private fun handleDownload(session: IHTTPSession): Response {
            val relPath = session.parms["path"] ?: ""
            val targetFile = resolveSafeFile(relPath) ?: return addCorsHeaders(
                newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid path")
            )

            if (!targetFile.exists() || !targetFile.isFile) {
                return addCorsHeaders(
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
                )
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
                    if (startStr.isNotEmpty()) rangeStart = startStr.toLongOrNull() ?: 0L
                    if (endStr.isNotEmpty()) rangeEnd = endStr.toLongOrNull() ?: (fileLen - 1L)
                }

                if (rangeStart > rangeEnd || rangeStart >= fileLen) {
                    val errResp = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
                    errResp.addHeader("Content-Range", "bytes */$fileLen")
                    return addCorsHeaders(errResp)
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
                return addCorsHeaders(response)
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
                return addCorsHeaders(response)
            }
        }

        private fun handleUpload(session: IHTTPSession): Response {
            val relPath = session.parms["path"] ?: ""
            val targetDir = resolveSafeFile(relPath) ?: return addCorsHeaders(
                newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Invalid path\"}")
            )

            if (!targetDir.exists()) targetDir.mkdirs()

            val files = HashMap<String, String>()
            session.parseBody(files)

            val uploadedPaths = mutableListOf<String>()

            for ((field, tempFilePath) in files) {
                val originalFileName = session.parms[field] ?: "upload_${System.currentTimeMillis()}"
                val safeFileName = File(originalFileName).name
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

            return addCorsHeaders(newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                "{\"success\":true,\"count\":${uploadedPaths.size}}"
            ))
        }

        private fun handleMkdir(session: IHTTPSession): Response {
            val relPath = session.parms["path"] ?: ""
            val folderName = session.parms["name"]?.trim() ?: ""

            if (folderName.isEmpty() || folderName.contains("/") || folderName.contains("\\")) {
                return addCorsHeaders(newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    "{\"error\":\"Invalid folder name\"}"
                ))
            }

            val parentDir = resolveSafeFile(relPath) ?: return addCorsHeaders(
                newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Invalid parent path\"}")
            )

            val newDir = File(parentDir, folderName)
            val created = newDir.mkdirs()

            return addCorsHeaders(newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                "{\"success\":$created}"
            ))
        }

        private fun handleDelete(session: IHTTPSession): Response {
            val relPath = session.parms["path"] ?: ""
            val target = resolveSafeFile(relPath) ?: return addCorsHeaders(
                newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Invalid path\"}")
            )

            if (!target.exists() || target.canonicalPath == storageRoot.canonicalPath) {
                return addCorsHeaders(newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    "{\"error\":\"Cannot delete root directory or item does not exist\"}"
                ))
            }

            val deleted = target.deleteRecursively()
            return addCorsHeaders(newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                "{\"success\":$deleted}"
            ))
        }

        private fun addCorsHeaders(response: Response): Response {
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            response.addHeader("Access-Control-Allow-Headers", "Content-Type, X-Auth-Token")
            return response
        }
    }
}
