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
import coil.size.Dimension
import coil.size.Size
import com.antigravity.filemanager.core.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private val FAST_IMAGE_EXTENSIONS = setOf("jpg", "jpeg")

/** Maximum width or height considered for fast embedded EXIF thumbnail extraction. */
const val MAX_THUMBNAIL_DIMENSION_PX = 512

/**
 * Fast embedded EXIF thumbnail extractor for camera photos.
 * Reads the 160x120 or 320x240 IFD1 thumbnail directly from EXIF headers via Rust core in < 0.1ms,
 * bypassing full-resolution bitmap decoding (12MP - 108MP) during rapid gallery scrolling.
 *
 * Crucial constraint: Only serves thumbnail requests (size <= 512px). Full-resolution requests
 * (e.g., ImageViewerScreen, Size.ORIGINAL, or is_full_viewer flag) return null so Coil uses
 * the standard full-resolution bitmap decoder without degrading picture quality.
 */
class FastImageThumbnailFetcher(
    private val file: File,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        if (!isThumbnailRequest(options)) return@withContext null
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

    companion object {
        fun isThumbnailRequest(size: Size, isFullViewer: Boolean = false): Boolean {
            // Bypass thumbnail extractor if explicitly flagged as full-screen viewer
            if (isFullViewer) return false

            // Size.ORIGINAL demands full raw resolution
            if (size == Size.ORIGINAL) return false

            val width = (size.width as? Dimension.Pixels)?.px
            val height = (size.height as? Dimension.Pixels)?.px

            // If dimensions are undefined/unbounded or exceed max thumbnail size, fallback to full decoder
            if (width == null || height == null) return false
            if (width > MAX_THUMBNAIL_DIMENSION_PX || height > MAX_THUMBNAIL_DIMENSION_PX) return false

            return true
        }

        fun isThumbnailRequest(options: Options): Boolean {
            val isFullViewer = options.parameters.value<Boolean>("is_full_viewer") == true
            return isThumbnailRequest(options.size, isFullViewer)
        }
    }

    class FileFactory : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (!isThumbnailRequest(options)) return null
            val ext = data.extension.lowercase(Locale.getDefault())
            if (ext in FAST_IMAGE_EXTENSIONS) return FastImageThumbnailFetcher(data, options)
            return null
        }
    }

    class UriFactory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (!isThumbnailRequest(options)) return null
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
            if (!isThumbnailRequest(options)) return null
            val path = if (data.startsWith("file://")) data.removePrefix("file://") else data
            val file = File(path)
            val ext = file.extension.lowercase(Locale.getDefault())
            if (ext in FAST_IMAGE_EXTENSIONS && file.exists()) return FastImageThumbnailFetcher(file, options)
            return null
        }
    }
}
