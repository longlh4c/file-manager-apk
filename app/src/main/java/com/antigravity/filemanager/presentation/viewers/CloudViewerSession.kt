package com.antigravity.filemanager.presentation.viewers

import com.antigravity.filemanager.domain.model.FileItem

/**
 * In-memory hand-off from CloudExplorerScreen to the media viewers.
 *
 * Cloud files aren't sitting in a local directory the way local files are, so the viewer
 * can't rediscover its siblings by scanning a filesystem folder. The explorer screen already
 * has the correctly sorted/filtered folder listing in memory, so it stashes it here right
 * before navigating; the viewer reads it once and matches by account id. If the process was
 * killed and restored (session empty), the viewer falls back to showing just the tapped file.
 */
object CloudViewerSession {
    private data class Entry(val accountId: String, val files: List<FileItem>)

    private var entry: Entry? = null

    fun set(accountId: String, files: List<FileItem>) {
        entry = Entry(accountId, files)
    }

    fun get(accountId: String): List<FileItem>? =
        entry?.takeIf { it.accountId == accountId }?.files
}

/**
 * In-memory side-channel carrying the HTTP headers (e.g. a Google Drive bearer token) a
 * streamed media URL needs, from the ViewModel that resolved it to the ExoPlayer/Coil request
 * that plays it. Kept out of the navigation route on purpose — an access token has no business
 * sitting in a nav back-stack argument (visible to process-death state restoration, logs, etc).
 */
object CloudStreamHeaders {
    private val headersByUrl = boundedMap<Map<String, String>>(MAX_REMEMBERED_STREAMS)

    fun put(url: String, headers: Map<String, String>) {
        if (headers.isNotEmpty()) synchronized(headersByUrl) { headersByUrl[url] = headers }
    }

    fun get(url: String): Map<String, String> = synchronized(headersByUrl) { headersByUrl[url] } ?: emptyMap()
}

/**
 * Registry for cloud media that can only be read through a [android.media.MediaDataSource]
 * rather than an HTTP URL (MEGA: content is client-side encrypted, so the player must fetch and
 * decrypt byte ranges itself). A viewer is handed a synthetic "cloud-stream://" URL, and the
 * player looks the real source up here — the same URL-keyed hand-off [CloudStreamHeaders] uses.
 */
object CloudMediaDataSources {
    private const val PREFIX = "cloud-stream://"
    // Bounded: entries were never removed, and each MEGA source keeps its ~256 KB read-ahead
    // buffer, so every video opened during a session stayed in memory.
    private val sources = boundedMap<android.media.MediaDataSource>(MAX_REMEMBERED_STREAMS)

    // What ExoPlayer can play progressively; anything else keeps the download-then-open path.
    private val STREAMABLE_VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "3gp", "mkv", "webm", "ts", "flv")

    fun isStreamableVideo(extension: String): Boolean = extension.lowercase() in STREAMABLE_VIDEO_EXTENSIONS

    fun register(accountId: String, nodeId: String, source: android.media.MediaDataSource): String {
        val url = "$PREFIX$accountId/$nodeId"
        synchronized(sources) { sources[url] = source }
        return url
    }

    fun get(url: String): android.media.MediaDataSource? = synchronized(sources) { sources[url] }

    fun isRegisteredUrl(path: String): Boolean = path.startsWith(PREFIX)

    /** True for anything a viewer should treat as a stream rather than a filesystem path. */
    fun isStreamPath(path: String): Boolean =
        path.startsWith("http://") || path.startsWith("https://") || isRegisteredUrl(path)
}

/** How many recently opened streams keep their headers/source; far more than a viewer pager
 * has alive at once, so an entry is only dropped long after its viewer is gone. */
private const val MAX_REMEMBERED_STREAMS = 16

/** Access-ordered LRU map; callers synchronize on it. */
private fun <V> boundedMap(max: Int): LinkedHashMap<String, V> =
    object : LinkedHashMap<String, V>(max, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = size > max
    }
