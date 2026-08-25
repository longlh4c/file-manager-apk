package com.antigravity.filemanager.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.antigravity.filemanager.domain.model.FileItem
import java.io.File
import java.util.Locale

object FileOpener {

    fun openFile(context: Context, fileItem: FileItem) {
        val file = File(fileItem.path)
        if (!file.exists()) return

        val ext = file.extension.lowercase(Locale.getDefault())

        // Dedicated Handling for APK Installation & Package Installer
        if (ext == "apk") {
            try {
                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )

                // Check permission to install unknown apps on Android 8.0+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (!context.packageManager.canRequestPackageInstalls()) {
                        val permIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(permIntent)
                        return
                    }
                }

                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(installIntent)
            } catch (e: Exception) {
                e.printStackTrace()
                // Fallback to chooser if direct package installer fails
                openWith(context, fileItem)
            }
            return
        }

        val mimeType = getMimeType(file)
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Direct launch allows Android to use the user's default app (e.g. Gallery / Always)
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to chooser if no default handler found
            openWith(context, fileItem)
        }
    }

    fun openWith(context: Context, fileItem: FileItem) {
        val file = File(fileItem.path)
        if (!file.exists()) return

        val mimeType = getMimeType(file)
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(intent, "Mở bằng...")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun shareFiles(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        try {
            if (paths.size == 1) {
                val file = File(paths.first())
                if (!file.exists()) return
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val mime = getMimeType(file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = Intent.createChooser(intent, "Chia sẻ tệp tin")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } else {
                val uris = ArrayList<Uri>()
                for (p in paths) {
                    val f = File(p)
                    if (f.exists()) {
                        uris.add(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f))
                    }
                }
                if (uris.isEmpty()) return

                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = Intent.createChooser(intent, "Chia sẻ ${uris.size} tệp tin")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getMimeType(file: File): String {
        val ext = file.extension.lowercase(Locale.getDefault())
        return when (ext) {
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "txt" -> "text/plain"
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "apk" -> "application/vnd.android.package-archive"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        }
    }
}
