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
