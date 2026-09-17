package com.antigravity.filemanager.data.local.storage

import android.content.Context
import com.antigravity.filemanager.domain.model.FileItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileOperationsHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanner: LocalFileScanner
) {

    // Returns a collision-free destination file, appending " (1)", " (2)", etc. as needed.
    private fun uniqueDestination(targetFolder: File, name: String): File {
        var candidate = File(targetFolder, name)
        if (!candidate.exists()) return candidate
        val dotIndex = name.lastIndexOf('.')
        val base = if (dotIndex > 0) name.substring(0, dotIndex) else name
        val ext = if (dotIndex > 0) name.substring(dotIndex) else ""
        var counter = 1
        while (candidate.exists()) {
            candidate = File(targetFolder, "$base ($counter)$ext")
            counter++
        }
        return candidate
    }

    // Finds conflicting file/folder names in targetDir before copy/move.
    suspend fun findConflicts(sourcePaths: List<String>, targetDir: String): List<com.antigravity.filemanager.domain.model.OverwriteConflict> = withContext(Dispatchers.IO) {
        val targetFolder = File(targetDir)
        sourcePaths.mapNotNull { path ->
            val source = File(path)
            val dest = File(targetFolder, source.name)
            if (dest.exists() && dest.absolutePath != source.absolutePath) {
                com.antigravity.filemanager.domain.model.OverwriteConflict(
                    name = source.name,
                    existingSize = dest.length(),
                    newSize = source.length()
                )
            } else null
        }
    }

    suspend fun copy(
        sourcePaths: List<String>,
        targetDir: String,
        overwriteNames: Set<String> = emptySet(),
        skipNames: Set<String> = emptySet(),
        onProgress: ((currentFile: String, currentIndex: Int, totalFiles: Int) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val targetFolder = File(targetDir)
            if (!targetFolder.exists()) targetFolder.mkdirs()
            val scannedPaths = mutableListOf<String>()

            // overwrite is decided once per top-level source (matching the pre-existing conflict
            // dialog, which only ever resolves top-level name clashes) and carried down to every
            // file underneath it — same effective behavior copyRecursively(overwrite=...) had.
            val fileEntries = mutableListOf<FileCopyEntry>()
            val emptyDirEntries = mutableListOf<FileCopyEntry>()
            for (path in sourcePaths) {
                val source = File(path)
                if (!source.exists() || source.name in skipNames) continue
                val overwriteThis = source.name in overwriteNames
                val dest = if (overwriteThis) File(targetFolder, source.name) else uniqueDestination(targetFolder, source.name)
                // Copying a file onto itself (same-folder paste with overwrite chosen) is a
                // no-op: doing it for real would truncate the source before it's read.
                if (dest.absolutePath == source.absolutePath) continue
                collectFileCopyEntries(source, dest, fileEntries, emptyDirEntries)
            }

            val total = fileEntries.size + emptyDirEntries.size
            var current = 0
            for (entry in fileEntries) {
                currentCoroutineContext().ensureActive()
                current++
                onProgress?.invoke(entry.source.name, current, total)
                entry.dest.parentFile?.mkdirs()
                // Always safe to pass overwrite=true here: when the top-level source wasn't
                // marked for overwrite, uniqueDestination() already gave it a brand-new,
                // collision-free folder name above, so nothing pre-existing can be at entry.dest
                // regardless. When it WAS marked for overwrite, replacing whatever's already
                // there is exactly the point.
                entry.source.copyTo(entry.dest, overwrite = true)
                scannedPaths.add(entry.dest.absolutePath)
            }
            for (entry in emptyDirEntries) {
                currentCoroutineContext().ensureActive()
                current++
                onProgress?.invoke(entry.source.name, current, total)
                entry.dest.mkdirs()
            }
            if (scannedPaths.isNotEmpty()) {
                try {
                    android.media.MediaScannerConnection.scanFile(context, scannedPaths.toTypedArray(), null, null)
                } catch (e: Exception) {}
            }
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun move(
        sourcePaths: List<String>,
        targetDir: String,
        overwriteNames: Set<String> = emptySet(),
        skipNames: Set<String> = emptySet(),
        onProgress: ((currentFile: String, currentIndex: Int, totalFiles: Int) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val targetFolder = File(targetDir)
            if (!targetFolder.exists()) targetFolder.mkdirs()
            val scannedPaths = mutableListOf<String>()

            // Phase 1: renameTo() is atomic and effectively instant for a same-filesystem move —
            // even a folder with thousands of files inside moves in one O(1) call, so there's
            // nothing meaningful to report progress on here (same reasoning as moveToTrash's own
            // renameTo() above). Only a genuine cross-filesystem move (internal storage <-> SD
            // card, say) falls through to it failing, handled for real in phase 2 below.
            data class PendingItem(val source: File, val dest: File)
            val renameFailures = mutableListOf<PendingItem>()
            for (path in sourcePaths) {
                currentCoroutineContext().ensureActive()
                val source = File(path)
                if (!source.exists() || source.name in skipNames) continue
                val overwriteThis = source.name in overwriteNames
                val dest = if (overwriteThis) File(targetFolder, source.name) else uniqueDestination(targetFolder, source.name)
                // Moving a file onto itself is a no-op.
                if (dest.absolutePath == source.absolutePath) continue
                if (overwriteThis && dest.exists()) {
                    if (dest.isDirectory) dest.deleteRecursively() else dest.delete()
                }
                if (source.renameTo(dest)) {
                    scannedPaths.add(source.absolutePath)
                    scannedPaths.add(dest.absolutePath)
                } else {
                    renameFailures.add(PendingItem(source, dest))
                }
            }

            // Phase 2: genuine cross-filesystem fallback, flattened into per-file copy work the
            // same way copy() is above — a single large folder that can't use renameTo() used to
            // report "1/1" for the entire copy+delete, no matter how long it actually took (same
            // bug, same fix as zipFiles' addFolder() replacement elsewhere in this codebase).
            // Deleting the now-copied source trees afterward is comparatively fast, so only the
            // copy half needs per-file visibility.
            val fileEntries = mutableListOf<FileCopyEntry>()
            val emptyDirEntries = mutableListOf<FileCopyEntry>()
            for (item in renameFailures) {
                collectFileCopyEntries(item.source, item.dest, fileEntries, emptyDirEntries)
            }
            val total = fileEntries.size + emptyDirEntries.size
            var current = 0
            for (entry in fileEntries) {
                currentCoroutineContext().ensureActive()
                current++
                onProgress?.invoke(entry.source.name, current, total)
                entry.dest.parentFile?.mkdirs()
                entry.source.copyTo(entry.dest, overwrite = true)
            }
            for (entry in emptyDirEntries) {
                currentCoroutineContext().ensureActive()
                current++
                onProgress?.invoke(entry.source.name, current, total)
                entry.dest.mkdirs()
            }
            for (item in renameFailures) {
                item.source.deleteRecursively()
                scannedPaths.add(item.source.absolutePath)
                scannedPaths.add(item.dest.absolutePath)
            }

            if (scannedPaths.isNotEmpty()) {
                try {
                    android.media.MediaScannerConnection.scanFile(context, scannedPaths.toTypedArray(), null, null)
                } catch (e: Exception) {}
            }
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun rename(filePath: String, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) return@withContext Result.failure(Exception("File does not exist"))

            val dest = File(file.parentFile, newName)
            if (file.renameTo(dest)) {
                try {
                    android.media.MediaScannerConnection.scanFile(context, arrayOf(filePath, dest.absolutePath), null, null)
                } catch (e: Exception) {}
                val isDir = dest.isDirectory
                val item = FileItem(
                    id = dest.absolutePath,
                    name = dest.name,
                    path = dest.absolutePath,
                    size = if (isDir) 0L else dest.length(),
                    lastModified = dest.lastModified(),
                    isDirectory = isDir,
                    extension = if (isDir) "" else dest.extension
                )
                Result.success(item)
            } else {
                Result.failure(Exception("Could not rename file"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createDirectory(parentPath: String, name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val dir = File(parentPath, name)
            if (dir.exists() || dir.mkdirs()) {
                val item = FileItem(
                    id = dir.absolutePath,
                    name = dir.name,
                    path = dir.absolutePath,
                    size = 0L,
                    lastModified = dir.lastModified(),
                    isDirectory = true
                )
                Result.success(item)
            } else {
                Result.failure(Exception("Failed to create folder"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // zip4j's isRunInThread=true + ProgressMonitor polling was tried here for live byte-level
    // progress, twice, and both times it hung on-device: the ProgressMonitor's state never
    // reliably settled back to READY, the polling loop sat there forever, and — much worse —
    // zip4j's detached background thread kept writing to the archive indefinitely with nothing
    // to stop it (cancelling the polling coroutine does NOT cancel that thread, since it isn't
    // tied to coroutine cancellation at all). One run grew a compress target to 3.9 GB and filled
    // the device's disk before it was caught. Given that failure mode is silent disk exhaustion,
    // not just a stuck spinner, this reverts to plain synchronous addFile/addFolder/extractAll —
    // same as before progress reporting existed — with progress reported only between whole
    // files (index/total), never live within one, since that's the only part of this that was
    // ever actually safe.

    suspend fun zipFiles(
        sourcePaths: List<String>,
        targetZipPath: String,
        onProgress: ((currentFile: String, currentIndex: Int, totalFiles: Int) -> Unit)? = null
    ): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val zipFile = ZipFile(targetZipPath)

            // Flattened per-file work list instead of one addFolder(f) call per top-level source:
            // addFolder() zips an entire folder tree in a single opaque call with no progress
            // inside it, so compressing one large folder (a single source, addFolder's whole job)
            // used to report "1/1" for the entire operation — no percentage the whole time, no
            // matter how long it took. Walking the tree ourselves and calling addFile() once per
            // real file (still fully synchronous, still cancellable via ensureActive() — no
            // isRunInThread/ProgressMonitor, see this function's own history above for exactly why
            // not) gives a real per-file count from the very first tick.
            data class FileEntry(val file: File, val entryPathInZip: String)
            val fileEntries = mutableListOf<FileEntry>()
            // A folder that turns out to be wholly empty (or contains only empty subfolders) has
            // no file for addFile() to represent at all — addFolder() is the only way to still
            // preserve that (empty) structure in the archive, so those fall back to it, one
            // opaque-but-cheap call each.
            val emptyFolderFallbacks = mutableListOf<File>()

            fun collectFiles(dir: File, entryPrefix: String) {
                val children = dir.listFiles() ?: return
                for (child in children) {
                    val childEntryPath = "$entryPrefix/${child.name}"
                    if (child.isDirectory) {
                        collectFiles(child, childEntryPath)
                    } else if (child.isFile) {
                        fileEntries.add(FileEntry(child, childEntryPath))
                    }
                }
            }

            for (path in sourcePaths) {
                val f = File(path)
                when {
                    f.isDirectory -> {
                        val before = fileEntries.size
                        collectFiles(f, f.name)
                        if (fileEntries.size == before) emptyFolderFallbacks.add(f)
                    }
                    f.isFile -> fileEntries.add(FileEntry(f, f.name))
                }
            }

            val total = fileEntries.size + emptyFolderFallbacks.size
            var current = 0
            for (entry in fileEntries) {
                currentCoroutineContext().ensureActive()
                current++
                onProgress?.invoke(entry.file.name, current, total)
                val params = net.lingala.zip4j.model.ZipParameters().apply { fileNameInZip = entry.entryPathInZip }
                zipFile.addFile(entry.file, params)
            }
            for (emptyFolder in emptyFolderFallbacks) {
                currentCoroutineContext().ensureActive()
                current++
                onProgress?.invoke(emptyFolder.name, current, total)
                zipFile.addFolder(emptyFolder)
            }
            val created = File(targetZipPath)
            val item = FileItem(
                id = created.absolutePath,
                name = created.name,
                path = created.absolutePath,
                size = created.length(),
                lastModified = created.lastModified(),
                isDirectory = false,
                extension = "zip"
            )
            Result.success(item)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun extractZip(zipFilePath: String, targetDir: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val zipFile = ZipFile(zipFilePath)
            zipFile.extractAll(targetDir)
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }
}
