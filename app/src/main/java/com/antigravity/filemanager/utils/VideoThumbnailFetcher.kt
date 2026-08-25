package com.antigravity.filemanager.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class VideoThumbnailFetcher(
    private val file: File,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val ext = file.extension.lowercase(Locale.getDefault())
        val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "ts", "m4v", "mpg", "mpeg", "vob", "ogv", "f4v")
        if (ext !in videoExtensions || !file.exists() || file.length() == 0L) {
            return@withContext null
        }

        val bitmap: Bitmap? = run {
            // 1. Android Q+ (API 29+) Hardware-accelerated native ThumbnailUtils
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val b = ThumbnailUtils.createVideoThumbnail(file, Size(512, 512), null)
                    if (b != null) return@run b
                } catch (e: Exception) {}
            }

            // 2. Legacy ThumbnailUtils
            try {
                @Suppress("DEPRECATION")
                val b = ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
                if (b != null) return@run b
            } catch (e: Exception) {}

            // 3. Fallback to MediaMetadataRetriever at 1 second offset
            try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    retriever.getFrameAtTime(1000000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.frameAtTime
                } finally {
                    try { retriever.release() } catch (e: Exception) {}
                }
            } catch (e: Exception) {
                null
            }
        }

        if (bitmap != null) {
            DrawableResult(
                drawable = BitmapDrawable(options.context.resources, bitmap),
                isSampled = true,
                dataSource = DataSource.DISK
            )
        } else {
            null
        }
    }

    class FileFactory : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? {
            val ext = data.extension.lowercase(Locale.getDefault())
            val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "ts", "m4v", "mpg", "mpeg", "vob", "ogv", "f4v")
            if (ext in videoExtensions) {
                return VideoThumbnailFetcher(data, options)
            }
            return null
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
            val ext = file.extension.lowercase(Locale.getDefault())
            val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "ts", "m4v", "mpg", "mpeg", "vob", "ogv", "f4v")
            if (ext in videoExtensions && file.exists()) {
                return VideoThumbnailFetcher(file, options)
            }
            return null
        }
    }

    class StringFactory : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? {
            val path = if (data.startsWith("file://")) data.removePrefix("file://") else data
            val file = File(path)
            val ext = file.extension.lowercase(Locale.getDefault())
            val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "ts", "m4v", "mpg", "mpeg", "vob", "ogv", "f4v")
            if (ext in videoExtensions && file.exists()) {
                return VideoThumbnailFetcher(file, options)
            }
            return null
        }
    }
}
