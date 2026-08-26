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
    private val headersByUrl = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

    fun put(url: String, headers: Map<String, String>) {
        if (headers.isNotEmpty()) headersByUrl[url] = headers
    }

    fun get(url: String): Map<String, String> = headersByUrl[url] ?: emptyMap()
}
