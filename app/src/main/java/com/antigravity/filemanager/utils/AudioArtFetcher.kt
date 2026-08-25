package com.antigravity.filemanager.utils

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
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
import java.util.Locale

private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "wma", "opus", "amr")

/** Extracts an audio file's embedded ID3/cover-art picture (if any) for use as its thumbnail —
 * mirrors VideoThumbnailFetcher's shape so it plugs into the same AsyncImage calls that already
 * pass a file's own path/URI as the thumbnail model. Returns null (letting Coil fall through to
 * an error/empty state) when the file has no embedded art, rather than showing a broken image. */
class AudioArtFetcher(
    private val file: File,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) return@withContext null

        val artBytes = try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.embeddedPicture
            } finally {
                try { retriever.release() } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            null
        } ?: return@withContext null

        val bitmap = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size) ?: return@withContext null

        DrawableResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = true,
            dataSource = DataSource.DISK
        )
    }

    class FileFactory : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? {
            val ext = data.extension.lowercase(Locale.getDefault())
            if (ext in AUDIO_EXTENSIONS) return AudioArtFetcher(data, options)
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
            if (ext in AUDIO_EXTENSIONS && file.exists()) return AudioArtFetcher(file, options)
            return null
        }
    }

    class StringFactory : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? {
            val path = if (data.startsWith("file://")) data.removePrefix("file://") else data
            val file = File(path)
            val ext = file.extension.lowercase(Locale.getDefault())
            if (ext in AUDIO_EXTENSIONS && file.exists()) return AudioArtFetcher(file, options)
            return null
        }
    }
}
