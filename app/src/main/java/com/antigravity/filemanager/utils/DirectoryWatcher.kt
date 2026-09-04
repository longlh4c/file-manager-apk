package com.antigravity.filemanager.utils

import android.os.Build
import android.os.FileObserver
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

private const val WATCH_MASK =
    FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.MODIFY

/**
 * Emits whenever a file is added, removed, renamed, or modified directly inside [path] — not
 * subdirectories, FileObserver never recurses. Used to silently refresh the local file browser
 * (Downloads/Main storage/any folder) while it's on screen, so a file dropped in by another app
 * (a completed download, an FTP transfer, ...) shows up without the user pulling to refresh.
 */
fun observeDirectoryChanges(path: String): Flow<Unit> = callbackFlow {
    val dir = File(path)
    if (!dir.exists() || !dir.isDirectory) {
        awaitClose {}
        return@callbackFlow
    }

    @Suppress("DEPRECATION")
    val observer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        object : FileObserver(dir, WATCH_MASK) {
            override fun onEvent(event: Int, path: String?) {
                trySend(Unit)
            }
        }
    } else {
        object : FileObserver(path, WATCH_MASK) {
            override fun onEvent(event: Int, path: String?) {
                trySend(Unit)
            }
        }
    }

    observer.startWatching()
    awaitClose { observer.stopWatching() }
}
