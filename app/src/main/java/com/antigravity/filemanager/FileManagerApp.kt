package com.antigravity.filemanager

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.antigravity.filemanager.utils.AudioArtFetcher
import com.antigravity.filemanager.utils.VideoThumbnailFetcher
import dagger.hilt.android.HiltAndroidApp

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
            .crossfade(true)
            .build()
    }
}
