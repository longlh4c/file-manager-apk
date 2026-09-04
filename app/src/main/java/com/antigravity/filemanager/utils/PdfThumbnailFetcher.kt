package com.antigravity.filemanager.utils

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Renders a .pdf's first page as its thumbnail, via the platform's built-in PdfRenderer —
 * same Fetcher pattern as VideoThumbnailFetcher/ApkIconFetcher, registered the same way in
 * FileManagerApp's ImageLoader. Without this every PDF showed the same generic red icon
 * regardless of what was actually on the page. */
class PdfThumbnailFetcher(
    private val file: File,
    private val options: Options
) : Fetcher {

    // Same cap as VideoThumbnailFetcher's requested frame size — plenty sharp at grid-card size,
    // cheap to rasterize.
    private val maxDimensionPx = 256

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        if (file.extension.lowercase() != "pdf" || !file.exists() || file.length() == 0L) {
            return@withContext null
        }
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount == 0) return@withContext null
                    renderer.openPage(0).use { page ->
                        val scale = maxDimensionPx.toFloat() / maxOf(page.width, page.height)
                        val width = (page.width * scale).toInt().coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        // PDF pages are transparent where nothing is drawn — without a white
                        // backing they'd render as black squares against this app's dark theme.
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        DrawableResult(
                            drawable = BitmapDrawable(options.context.resources, bitmap),
                            isSampled = true,
                            dataSource = DataSource.DISK
                        )
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    class FileFactory : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.extension.lowercase() != "pdf") return null
            return PdfThumbnailFetcher(data, options)
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
            if (file.extension.lowercase() != "pdf" || !file.exists()) return null
            return PdfThumbnailFetcher(file, options)
        }
    }

    class StringFactory : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? {
            val path = if (data.startsWith("file://")) data.removePrefix("file://") else data
            val file = File(path)
            if (file.extension.lowercase() != "pdf" || !file.exists()) return null
            return PdfThumbnailFetcher(file, options)
        }
    }
}
