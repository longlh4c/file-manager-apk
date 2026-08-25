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

    suspend fun copy(sourcePaths: List<String>, targetDir: String, overwriteNames: Set<String> = emptySet(), skipNames: Set<String> = emptySet()): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val targetFolder = File(targetDir)
            if (!targetFolder.exists()) targetFolder.mkdirs()
            val scannedPaths = mutableListOf<String>()

            for (path in sourcePaths) {
                val source = File(path)
                if (!source.exists()) continue
                if (source.name in skipNames) continue

                val overwriteThis = source.name in overwriteNames
                val dest = if (overwriteThis) File(targetFolder, source.name) else uniqueDestination(targetFolder, source.name)
                // Copying a file onto itself (same-folder paste with overwrite chosen) is a no-op:
                // doing it for real would truncate the source before it's read.
                if (dest.absolutePath == source.absolutePath) continue

                if (source.isDirectory) {
                    source.copyRecursively(dest, overwrite = overwriteThis)
                } else {
                    source.copyTo(dest, overwrite = overwriteThis)
                }
                scannedPaths.add(dest.absolutePath)
            }
            if (scannedPaths.isNotEmpty()) {
                try {
                    android.media.MediaScannerConnection.scanFile(context, scannedPaths.toTypedArray(), null, null)
                } catch (e: Exception) {}
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun move(sourcePaths: List<String>, targetDir: String, overwriteNames: Set<String> = emptySet(), skipNames: Set<String> = emptySet()): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val targetFolder = File(targetDir)
            if (!targetFolder.exists()) targetFolder.mkdirs()
            val scannedPaths = mutableListOf<String>()

            for (path in sourcePaths) {
                val source = File(path)
                if (!source.exists()) continue
                if (source.name in skipNames) continue

                val overwriteThis = source.name in overwriteNames
                val dest = if (overwriteThis) File(targetFolder, source.name) else uniqueDestination(targetFolder, source.name)
                // Moving a file onto itself is a no-op.
                if (dest.absolutePath == source.absolutePath) continue

                if (overwriteThis && dest.exists()) {
                    if (dest.isDirectory) dest.deleteRecursively() else dest.delete()
                }

                if (!source.renameTo(dest)) {
                    // Fallback to copy and delete
                    if (source.isDirectory) {
                        source.copyRecursively(dest, overwrite = false)
                        source.deleteRecursively()
                    } else {
                        source.copyTo(dest, overwrite = false)
                        source.delete()
                    }
                }
                scannedPaths.add(source.absolutePath)
                scannedPaths.add(dest.absolutePath)
            }
            if (scannedPaths.isNotEmpty()) {
                try {
                    android.media.MediaScannerConnection.scanFile(context, scannedPaths.toTypedArray(), null, null)
                } catch (e: Exception) {}
            }
            Result.success(Unit)
        } catch (e: Exception) {
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
            val total = sourcePaths.size
            sourcePaths.forEachIndexed { index, path ->
                currentCoroutineContext().ensureActive()
                val f = File(path)
                onProgress?.invoke(f.name, index + 1, total)
                if (f.isDirectory) {
                    zipFile.addFolder(f)
                } else if (f.isFile) {
                    zipFile.addFile(f)
                }
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
