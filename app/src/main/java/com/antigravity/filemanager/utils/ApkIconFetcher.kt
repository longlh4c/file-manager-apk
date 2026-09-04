package com.antigravity.filemanager.utils

import android.content.pm.PackageManager
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Loads the real app icon embedded inside an .apk file on disk — same idea as
 * [VideoThumbnailFetcher]/AudioArtFetcher, registered the same way in FileManagerApp's
 * ImageLoader. Without this every .apk in a grid/list showed the same generic Android robot
 * icon regardless of what app it actually was. */
class ApkIconFetcher(
    private val file: File,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        if (file.extension.lowercase() != "apk" || !file.exists() || file.length() == 0L) {
            return@withContext null
        }
        try {
            val pm = options.context.packageManager
            @Suppress("DEPRECATION")
            val packageInfo = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_ACTIVITIES) ?: return@withContext null
            val appInfo = packageInfo.applicationInfo ?: return@withContext null
            // Not actually installed — the archive's own paths have to be patched in manually
            // before loadIcon can resolve any resources out of it.
            appInfo.sourceDir = file.absolutePath
            appInfo.publicSourceDir = file.absolutePath
            val icon = appInfo.loadIcon(pm)
            DrawableResult(drawable = icon, isSampled = false, dataSource = DataSource.DISK)
        } catch (e: Exception) {
            null
        }
    }

    class FileFactory : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.extension.lowercase() != "apk") return null
            return ApkIconFetcher(data, options)
        }
    }

    class UriFactory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val path = when (data.scheme) {
                "file" -> data.path
                null -> data.toString()
                else -> if (data.path != null && File(data.path!!).exists()) data.path else null
            } ?: return null
            val file = File(path)
            if (file.extension.lowercase() != "apk" || !file.exists()) return null
            return ApkIconFetcher(file, options)
        }
    }

    class StringFactory : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? {
            val path = if (data.startsWith("file://")) data.removePrefix("file://") else data
            val file = File(path)
            if (file.extension.lowercase() != "apk" || !file.exists()) return null
            return ApkIconFetcher(file, options)
        }
    }
}
