package com.antigravity.filemanager.data.repository

import android.content.Context
import com.antigravity.filemanager.data.local.db.RecentFileDao
import com.antigravity.filemanager.data.local.storage.LocalFileScanner
import com.antigravity.filemanager.data.local.storage.isInsideHiddenOrSystemFolder
import com.antigravity.filemanager.domain.model.FileItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recent > Opened: the local files opened in the app, most recent first, and Recent > Added: the
 * newest files on the device. Only files on this device are recorded — not cloud files, folders,
 * hidden files or the app's own temporary copies.
 */
@Singleton
class RecentFilesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: RecentFileDao,
    private val scanner: LocalFileScanner
) {
    companion object {
        /** How many opened files are remembered; the oldest drops off beyond that. */
        const val MAX_ENTRIES = 200

        private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "svg", "raw", "dng")
        private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "ts", "m4v")

        /** "image" or "video" for media files (the ones kept once per folder), else null. */
        internal fun mediaKindOf(path: String): String? {
            val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
            return when (ext) {
                in imageExtensions -> "image"
                in videoExtensions -> "video"
                else -> null
            }
        }

        /**
         * [items] (most recent first) with just one image and one video per folder: the most
         * recent. Swiping through a folder's photos otherwise listed a dozen of them in Recent,
         * burying everything else; the folder's latest one is enough to get back to it.
         */
        internal fun <T> keepLatestMediaPerFolder(items: List<T>, pathOf: (T) -> String): List<T> {
            val seen = HashSet<Pair<String, String>>()
            return items.filter { item ->
                val path = pathOf(item)
                val kind = mediaKindOf(path) ?: return@filter true
                seen.add(path.substringBeforeLast('/') to kind)
            }
        }

        /** How many of [pathsByRecency] Recent > Opened shows: on disk, one media file per folder. */
        fun countShown(pathsByRecency: List<String>): Int =
            keepLatestMediaPerFolder(pathsByRecency.filter { File(it).isFile }) { it }.size
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Records that [path] was opened. Returns at once: opening a file never waits on this. */
    fun recordOpened(path: String) {
        scope.launch { record(path) }
    }

    internal suspend fun record(path: String) {
        if (!shouldRecord(path)) return
        val file = File(path)
        if (!file.isFile) return
        val path = file.absolutePath
        dao.recordOpen(path, file.name, System.currentTimeMillis())
        // This folder's previous image (or video) makes way for this one.
        mediaKindOf(path)?.let { kind ->
            val folder = path.substringBeforeLast('/')
            val replaced = dao.getPathsIn(folder).filter {
                it != path && it.substringBeforeLast('/') == folder && mediaKindOf(it) == kind
            }
            if (replaced.isNotEmpty()) dao.deleteByPaths(replaced)
        }
        dao.trimTo(MAX_ENTRIES)
    }

    private fun shouldRecord(path: String): Boolean {
        if (path.startsWith("http://") || path.startsWith("https://") || path.startsWith("content:")) return false
        val name = path.substringAfterLast('/')
        if (name.startsWith(".") || isInsideHiddenOrSystemFolder(path, isFolder = false)) return false
        // Temporary copies (cloud downloads, files previewed from inside an archive) aren't the
        // user's files and vanish on their own.
        val privateRoots = listOfNotNull(context.cacheDir, context.filesDir, context.externalCacheDir)
            .map { it.absolutePath + "/" }
        return privateRoots.none { path.startsWith(it) }
    }

    /** Opened files, most recent first, with the time each was opened as its date. Files no
     * longer on disk are dropped from the history as they're found. */
    fun observeOpened(): Flow<List<FileItem>> = dao.observeAll().map { entries ->
        val (present, missing) = entries.partition { File(it.path).isFile }
        if (missing.isNotEmpty()) dao.deleteByPaths(missing.map { it.path })
        keepLatestMediaPerFolder(present) { it.path }
            .map { scanner.fileItemOf(File(it.path), shownTime = it.lastOpenedAt) }
    }.flowOn(Dispatchers.IO)

    /** How many opened files are shown (the dashboard card's count). */
    fun observeOpenedCount(): Flow<Int> =
        dao.observeAll().map { entries -> countShown(entries.map { it.path }) }.flowOn(Dispatchers.IO)

    suspend fun remove(paths: Collection<String>) = dao.deleteByPaths(paths.toList())

    suspend fun clear() = dao.clearAll()

    /** A file or folder renamed or moved in the app: its history entries follow it. */
    suspend fun onMoved(oldPath: String, newPath: String) {
        dao.rename(oldPath, newPath, File(newPath).name)
        dao.moveFolder(oldPath.trimEnd('/'), newPath.trimEnd('/'))
    }

    /** The newest files on the device whose extension passes [acceptExtension] — filtered while
     * reading, so a type filter shows that type's newest files rather than whichever of the
     * newest files overall happen to be of that type (often none). */
    suspend fun recentlyAdded(limit: Int = MAX_ENTRIES, acceptExtension: (String) -> Boolean = { true }): List<FileItem> =
        scanner.getRecentlyAddedFiles(limit, acceptExtension)
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface RecentFilesEntryPoint {
    fun recentFiles(): RecentFilesRepository
}

/** For the places a file is opened that have no ViewModel of their own to inject into (the open
 * handler shared by every screen, the viewers, FileOpener). */
fun Context.recentFiles(): RecentFilesRepository =
    dagger.hilt.android.EntryPointAccessors.fromApplication(applicationContext, RecentFilesEntryPoint::class.java).recentFiles()
