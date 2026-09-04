package com.antigravity.filemanager

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.antigravity.filemanager.presentation.viewers.CloudStreamHeaders
import com.antigravity.filemanager.utils.ApkIconFetcher
import com.antigravity.filemanager.utils.AudioArtFetcher
import com.antigravity.filemanager.utils.PdfThumbnailFetcher
import com.antigravity.filemanager.utils.VideoThumbnailFetcher
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient

@HiltAndroidApp
class FileManagerApp : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoThumbnailFetcher.FileFactory())
                add(VideoThumbnailFetcher.UriFactory())
                add(VideoThumbnailFetcher.StringFactory())
                add(VideoFrameDecoder.Factory())
                add(AudioArtFetcher.FileFactory())
                add(AudioArtFetcher.UriFactory())
                add(AudioArtFetcher.StringFactory())
                add(ApkIconFetcher.FileFactory())
                add(ApkIconFetcher.UriFactory())
                add(ApkIconFetcher.StringFactory())
                add(PdfThumbnailFetcher.FileFactory())
                add(PdfThumbnailFetcher.UriFactory())
                add(PdfThumbnailFetcher.StringFactory())
                // Coil 2.x doesn't decode animated GIFs by default (coil-base only decodes the
                // first frame as a static bitmap) — these are the artifact's own decoders,
                // registered explicitly the same way the fetchers above are.
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .okHttpClient {
                // Cloud image streaming (e.g. Google Drive) needs a bearer token attached to
                // every request — a *network* interceptor (not an application one) so it still
                // runs if Drive ever 302s the response, which application interceptors don't see.
                OkHttpClient.Builder()
                    .addNetworkInterceptor { chain ->
                        val request = chain.request()
                        val extraHeaders = CloudStreamHeaders.get(request.url.toString())
                        val finalRequest = if (extraHeaders.isEmpty()) {
                            request
                        } else {
                            val builder = request.newBuilder()
                            extraHeaders.forEach { (key, value) -> builder.header(key, value) }
                            builder.build()
                        }
                        chain.proceed(finalRequest)
                    }
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            // Was crossfade(true): a 200ms fade-in on every thumbnail. Harmless for a single
            // image, but a grid of dozens of thumbnails (exactly what Images/Videos categories
            // show) made the whole screen feel like it was still "arriving" well after the data
            // was actually ready. Instant paint reads as faster even though nothing about the
            // underlying scan/decode time changed.
            .crossfade(false)
            .build()
    }
}
