package com.antigravity.filemanager.utils

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import com.antigravity.filemanager.core.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private val FAST_IMAGE_EXTENSIONS = setOf("jpg", "jpeg")

/**
 * Fast embedded EXIF thumbnail extractor for camera photos.
 * Reads the 160x120 or 320x240 IFD1 thumbnail directly from EXIF headers via Rust core in < 0.1ms,
 * bypassing full-resolution bitmap decoding (12MP - 108MP) during rapid gallery scrolling.
 * If the image does not embed an EXIF thumbnail, returns null so Coil falls back to standard decoding.
 */
class FastImageThumbnailFetcher(
    private val file: File,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) return@withContext null

        val thumbBytes = NativeEngine.getEmbeddedThumbnail(file.absolutePath) ?: return@withContext null
        if (thumbBytes.isEmpty()) return@withContext null

        val bitmap = BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size) ?: return@withContext null

        DrawableResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = true,
            dataSource = DataSource.DISK
        )
    }

    class FileFactory : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? {
            val ext = data.extension.lowercase(Locale.getDefault())
            if (ext in FAST_IMAGE_EXTENSIONS) return FastImageThumbnailFetcher(data, options)
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
            if (ext in FAST_IMAGE_EXTENSIONS && file.exists()) return FastImageThumbnailFetcher(file, options)
            return null
        }
    }

    class StringFactory : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? {
            val path = if (data.startsWith("file://")) data.removePrefix("file://") else data
            val file = File(path)
            val ext = file.extension.lowercase(Locale.getDefault())
            if (ext in FAST_IMAGE_EXTENSIONS && file.exists()) return FastImageThumbnailFetcher(file, options)
            return null
        }
    }
}
