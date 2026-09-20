package com.antigravity.filemanager.data.local.storage

import android.content.Context
import com.antigravity.filemanager.domain.model.FileItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import com.github.junrar.Junrar
import com.github.junrar.Archive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

class ArchivePasswordRequiredException(
    val archivePath: String,
    message: String = "Password required for archive: ${File(archivePath).name}"
) : IOException(message)

class ArchiveInvalidPasswordException(
    val archivePath: String,
    message: String = "Incorrect password for archive: ${File(archivePath).name}"
) : IOException(message)

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

    private fun getFolderContentSize(file: File): Long {
        if (!file.isDirectory) return file.length()
        var size = 0L
        try {
            file.walkTopDown().maxDepth(5).forEach { child ->
                if (child.isFile) size += child.length()
            }
        } catch (e: Exception) {}
        return size
    }

    // Finds conflicting file/folder names in targetDir before copy/move.
    suspend fun findConflicts(sourcePaths: List<String>, targetDir: String): List<com.antigravity.filemanager.domain.model.OverwriteConflict> = withContext(Dispatchers.IO) {
        val targetFolder = File(targetDir)
        sourcePaths.mapNotNull { path ->
            val source = File(path)
            val dest = File(targetFolder, source.name)
            if (dest.exists() && dest.absolutePath != source.absolutePath) {
                val isDir = source.isDirectory || dest.isDirectory
                com.antigravity.filemanager.domain.model.OverwriteConflict(
                    name = source.name,
                    existingSize = if (dest.isDirectory) getFolderContentSize(dest) else dest.length(),
                    newSize = if (source.isDirectory) getFolderContentSize(source) else source.length(),
                    isDirectory = isDir
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
            val dirEntries = mutableListOf<FileCopyEntry>()
            for (path in sourcePaths) {
                val source = File(path)
                if (!source.exists() || source.name in skipNames) continue
                val overwriteThis = source.name in overwriteNames
                val dest = if (overwriteThis) File(targetFolder, source.name) else uniqueDestination(targetFolder, source.name)
                // Copying a file onto itself (same-folder paste with overwrite chosen) is a
                // no-op: doing it for real would truncate the source before it's read.
                if (dest.absolutePath == source.absolutePath) continue
                collectFileCopyEntries(source, dest, fileEntries, dirEntries)
            }

            val total = fileEntries.size + dirEntries.size
            var current = 0

            // 1. Create all directory trees first so subfolders always exist
            for (entry in dirEntries) {
                currentCoroutineContext().ensureActive()
                current++
                onProgress?.invoke(entry.source.name, current, total)
                if (!entry.dest.exists()) {
                    entry.dest.mkdirs()
                }
            }

            // 2. Copy files with error isolation so one failing file does not abort remaining files/folders
            for (entry in fileEntries) {
                currentCoroutineContext().ensureActive()
                current++
                onProgress?.invoke(entry.source.name, current, total)
                try {
                    entry.dest.parentFile?.mkdirs()
                    if (entry.dest.exists()) {
                        if (entry.dest.isDirectory) {
                            entry.dest.deleteRecursively()
                        } else {
                            entry.dest.setWritable(true)
                            entry.dest.delete()
                        }
                    }
                    entry.source.copyTo(entry.dest, overwrite = true)
                    scannedPaths.add(entry.dest.absolutePath)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    android.util.Log.e("FileOperationsHelper", "Failed to copy file: ${entry.source.absolutePath} -> ${entry.dest.absolutePath}", e)
                }
            }

            try {
                val logFile = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "file_ops_debug.log")
                logFile.appendText("[COPY] Sources: $sourcePaths, Target: $targetDir, Dirs: ${dirEntries.size}, Files: ${fileEntries.size}\n")
            } catch (e: Exception) {}

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
            // same way copy() is above
            val fileEntries = mutableListOf<FileCopyEntry>()
            val dirEntries = mutableListOf<FileCopyEntry>()
            for (item in renameFailures) {
                collectFileCopyEntries(item.source, item.dest, fileEntries, dirEntries)
            }
            val total = fileEntries.size + dirEntries.size
            var current = 0

            // 1. Create all directory trees first
            for (entry in dirEntries) {
                currentCoroutineContext().ensureActive()
                current++
                onProgress?.invoke(entry.source.name, current, total)
                if (!entry.dest.exists()) {
                    entry.dest.mkdirs()
                }
            }

            // 2. Copy files with error isolation
            for (entry in fileEntries) {
                currentCoroutineContext().ensureActive()
                current++
                onProgress?.invoke(entry.source.name, current, total)
                try {
                    entry.dest.parentFile?.mkdirs()
                    if (entry.dest.exists()) {
                        if (entry.dest.isDirectory) {
                            entry.dest.deleteRecursively()
                        } else {
                            entry.dest.setWritable(true)
                            entry.dest.delete()
                        }
                    }
                    entry.source.copyTo(entry.dest, overwrite = true)
                    entry.source.delete()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    android.util.Log.e("FileOperationsHelper", "Failed to move file: ${entry.source.absolutePath} -> ${entry.dest.absolutePath}", e)
                }
            }
            for (item in renameFailures) {
                if (item.source.exists()) {
                    item.source.deleteRecursively()
                }
                scannedPaths.add(item.source.absolutePath)
                scannedPaths.add(item.dest.absolutePath)
            }

            try {
                val logFile = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "file_ops_debug.log")
                logFile.appendText("[MOVE] Sources: $sourcePaths, Target: $targetDir, Dirs: ${dirEntries.size}, Files: ${fileEntries.size}\n")
            } catch (e: Exception) {}

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

    suspend fun compressFiles(
        sourcePaths: List<String>,
        targetArchivePath: String,
        onProgress: ((currentFile: String, currentIndex: Int, totalFiles: Int, bytesProcessed: Long, totalBytes: Long) -> Unit)? = null
    ): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            if (targetArchivePath.endsWith(".7z", ignoreCase = true)) {
                compress7z(sourcePaths, targetArchivePath, onProgress)
            } else {
                compressZip(sourcePaths, targetArchivePath, onProgress)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun zipFiles(
        sourcePaths: List<String>,
        targetZipPath: String,
        onProgress: ((currentFile: String, currentIndex: Int, totalFiles: Int, bytesProcessed: Long, totalBytes: Long) -> Unit)? = null
    ): Result<FileItem> = compressFiles(sourcePaths, targetZipPath, onProgress)

    private suspend fun compressZip(
        sourcePaths: List<String>,
        targetZipPath: String,
        onProgress: ((currentFile: String, currentIndex: Int, totalFiles: Int, bytesProcessed: Long, totalBytes: Long) -> Unit)?
    ): Result<FileItem> {
        val targetFile = File(targetZipPath)
        targetFile.parentFile?.mkdirs()
        if (targetFile.exists()) targetFile.delete()

        data class FileEntry(val file: File, val entryPathInZip: String)
        val fileEntries = mutableListOf<FileEntry>()
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

        val totalFiles = fileEntries.size
        val totalBytes = fileEntries.sumOf { it.file.length() }
        var currentFileIndex = 0
        var bytesProcessed = 0L
        var lastEmitTime = 0L

        if (fileEntries.isNotEmpty()) {
            onProgress?.invoke(fileEntries.first().file.name, 0, totalFiles, 0L, totalBytes)
        }

        val fos = FileOutputStream(targetFile)
        val zos = net.lingala.zip4j.io.outputstream.ZipOutputStream(fos)
        try {
            val buffer = ByteArray(64 * 1024)
            for (entry in fileEntries) {
                currentCoroutineContext().ensureActive()
                currentFileIndex++
                val params = net.lingala.zip4j.model.ZipParameters().apply {
                    fileNameInZip = entry.entryPathInZip
                }
                zos.putNextEntry(params)
                entry.file.inputStream().buffered(64 * 1024).use { input ->
                    var count: Int
                    while (input.read(buffer).also { count = it } != -1) {
                        currentCoroutineContext().ensureActive()
                        zos.write(buffer, 0, count)
                        bytesProcessed += count
                        val now = System.currentTimeMillis()
                        if (now - lastEmitTime >= 100 || bytesProcessed == totalBytes) {
                            lastEmitTime = now
                            onProgress?.invoke(entry.file.name, currentFileIndex, totalFiles, bytesProcessed, totalBytes)
                        }
                    }
                }
                zos.closeEntry()
            }
            for (emptyFolder in emptyFolderFallbacks) {
                currentCoroutineContext().ensureActive()
                val folderPath = if (emptyFolder.name.endsWith("/")) emptyFolder.name else "${emptyFolder.name}/"
                val params = net.lingala.zip4j.model.ZipParameters().apply {
                    fileNameInZip = folderPath
                }
                zos.putNextEntry(params)
                zos.closeEntry()
            }
            zos.close()
            fos.close()
        } catch (e: Exception) {
            try { zos.close() } catch (_: Throwable) {}
            try { fos.close() } catch (_: Throwable) {}
            if (targetFile.exists()) targetFile.delete()
            throw e
        }

        val item = FileItem(
            id = targetFile.absolutePath,
            name = targetFile.name,
            path = targetFile.absolutePath,
            size = targetFile.length(),
            lastModified = targetFile.lastModified(),
            isDirectory = false,
            extension = "zip"
        )
        return Result.success(item)
    }

    private suspend fun compress7z(
        sourcePaths: List<String>,
        target7zPath: String,
        onProgress: ((currentFile: String, currentIndex: Int, totalFiles: Int, bytesProcessed: Long, totalBytes: Long) -> Unit)?
    ): Result<FileItem> {
        val targetFile = File(target7zPath)
        targetFile.parentFile?.mkdirs()
        if (targetFile.exists()) targetFile.delete()

        data class FileEntry7z(val file: File, val entryPath: String, val isDirectory: Boolean)
        val entries = mutableListOf<FileEntry7z>()

        fun collectFiles(dir: File, entryPrefix: String) {
            entries.add(FileEntry7z(dir, entryPrefix, true))
            val children = dir.listFiles() ?: return
            for (child in children) {
                val childEntryPath = "$entryPrefix/${child.name}"
                if (child.isDirectory) {
                    collectFiles(child, childEntryPath)
                } else if (child.isFile) {
                    entries.add(FileEntry7z(child, childEntryPath, false))
                }
            }
        }

        for (path in sourcePaths) {
            val f = File(path)
            when {
                f.isDirectory -> collectFiles(f, f.name)
                f.isFile -> entries.add(FileEntry7z(f, f.name, false))
            }
        }

        val fileEntries = entries.filter { !it.isDirectory }
        val totalFiles = fileEntries.size
        val totalBytes = fileEntries.sumOf { it.file.length() }
        var currentFileIndex = 0
        var bytesProcessed = 0L
        var lastEmitTime = 0L

        if (fileEntries.isNotEmpty()) {
            onProgress?.invoke(fileEntries.first().file.name, 0, totalFiles, 0L, totalBytes)
        }

        val sevenZOutput = SevenZOutputFile(targetFile)
        try {
            sevenZOutput.setContentCompression(SevenZMethod.LZMA2)
            val buffer = ByteArray(64 * 1024)

            for (item in entries) {
                currentCoroutineContext().ensureActive()

                val archiveEntry = sevenZOutput.createArchiveEntry(item.file, item.entryPath)
                archiveEntry.isDirectory = item.isDirectory
                sevenZOutput.putArchiveEntry(archiveEntry)

                if (!item.isDirectory) {
                    currentFileIndex++
                    item.file.inputStream().buffered(64 * 1024).use { input ->
                        var count: Int
                        while (input.read(buffer).also { count = it } != -1) {
                            currentCoroutineContext().ensureActive()
                            sevenZOutput.write(buffer, 0, count)
                            bytesProcessed += count
                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime >= 100 || bytesProcessed == totalBytes) {
                                lastEmitTime = now
                                onProgress?.invoke(item.file.name, currentFileIndex, totalFiles, bytesProcessed, totalBytes)
                            }
                        }
                    }
                }
                sevenZOutput.closeArchiveEntry()
            }
            sevenZOutput.close()
        } catch (e: Exception) {
            try { sevenZOutput.close() } catch (_: Throwable) {}
            if (targetFile.exists()) targetFile.delete()
            throw e
        }

        val item = FileItem(
            id = targetFile.absolutePath,
            name = targetFile.name,
            path = targetFile.absolutePath,
            size = targetFile.length(),
            lastModified = targetFile.lastModified(),
            isDirectory = false,
            extension = "7z"
        )
        return Result.success(item)
    }

    fun isArchiveEncrypted(archiveFilePath: String): Boolean {
        val file = File(archiveFilePath)
        if (!file.exists() || !file.isFile) return false
        val ext = file.extension.lowercase(Locale.ROOT)
        return try {
            when (ext) {
                "7z" -> {
                    try {
                        SevenZFile(file).use { sz ->
                            var entry = sz.nextEntry
                            while (entry != null) {
                                if (!entry.isDirectory && entry.hasStream()) {
                                    val buf = ByteArray(1)
                                    sz.read(buf)
                                    return false
                                }
                                entry = sz.nextEntry
                            }
                            false
                        }
                    } catch (e: org.apache.commons.compress.PasswordRequiredException) {
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                "rar" -> {
                    try {
                        Archive(file).use { it.isEncrypted || it.isPasswordProtected }
                    } catch (_: Exception) {
                        false
                    }
                }
                "zip" -> {
                    try {
                        ZipFile(file).isEncrypted
                    } catch (_: Exception) {
                        false
                    }
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun validateArchivePassword(archiveFile: File, password: String?) {
        if (!archiveFile.exists() || !archiveFile.isFile) return
        val ext = archiveFile.extension.lowercase(Locale.ROOT)
        when (ext) {
            "7z" -> {
                val passwordChars = if (password.isNullOrEmpty()) null else password.toCharArray()
                val sevenZFile = try {
                    SevenZFile(archiveFile, passwordChars)
                } catch (e: org.apache.commons.compress.PasswordRequiredException) {
                    throw ArchivePasswordRequiredException(archiveFile.absolutePath)
                } catch (e: IOException) {
                    if (passwordChars != null) {
                        throw ArchiveInvalidPasswordException(archiveFile.absolutePath)
                    }
                    throw e
                }

                sevenZFile.use { archive ->
                    try {
                        var entry = archive.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && entry.hasStream()) {
                                val buf = ByteArray(32)
                                val count = archive.read(buf)
                                if (count != -1) {
                                    break
                                }
                            }
                            entry = archive.nextEntry
                        }
                    } catch (e: org.apache.commons.compress.PasswordRequiredException) {
                        throw ArchivePasswordRequiredException(archiveFile.absolutePath)
                    } catch (e: IOException) {
                        if (passwordChars != null) {
                            throw ArchiveInvalidPasswordException(archiveFile.absolutePath)
                        }
                        throw e
                    }
                }
            }
            "rar" -> {
                val archive = try {
                    if (!password.isNullOrEmpty()) {
                        Archive(archiveFile, password)
                    } else {
                        Archive(archiveFile)
                    }
                } catch (e: Exception) {
                    val msg = e.message?.lowercase(Locale.ROOT) ?: ""
                    if (msg.contains("password") || msg.contains("encrypted") || msg.contains("header")) {
                        if (password.isNullOrEmpty()) {
                            throw ArchivePasswordRequiredException(archiveFile.absolutePath)
                        } else {
                            throw ArchiveInvalidPasswordException(archiveFile.absolutePath)
                        }
                    }
                    throw e
                }
                archive.use { arc ->
                    if ((arc.isEncrypted || arc.isPasswordProtected) && password.isNullOrEmpty()) {
                        throw ArchivePasswordRequiredException(archiveFile.absolutePath)
                    }
                    val testHeader = arc.fileHeaders?.firstOrNull { !it.isDirectory && it.unpSize > 0 }
                        ?: arc.fileHeaders?.firstOrNull { !it.isDirectory }
                    if (testHeader != null && (!password.isNullOrEmpty() || arc.isEncrypted || arc.isPasswordProtected)) {
                        try {
                            val dummyOut = object : java.io.OutputStream() {
                                override fun write(b: Int) {}
                                override fun write(b: ByteArray, off: Int, len: Int) {}
                            }
                            arc.extractFile(testHeader, dummyOut)
                        } catch (e: Exception) {
                            if (e is ArchivePasswordRequiredException) throw e
                            val msg = e.message?.lowercase(Locale.ROOT) ?: ""
                            if (msg.contains("password") || msg.contains("encrypted") || msg.contains("header") || msg.contains("crc") || msg.contains("corrupt")) {
                                if (password.isNullOrEmpty()) {
                                    throw ArchivePasswordRequiredException(archiveFile.absolutePath)
                                } else {
                                    throw ArchiveInvalidPasswordException(archiveFile.absolutePath)
                                }
                            }
                            throw e
                        }
                    }
                }
            }
            else -> {
                val zipFile = ZipFile(archiveFile)
                if (zipFile.isEncrypted) {
                    if (password.isNullOrEmpty()) {
                        throw ArchivePasswordRequiredException(archiveFile.absolutePath)
                    }
                    zipFile.setPassword(password.toCharArray())
                }
                val headers = try {
                    zipFile.fileHeaders ?: emptyList()
                } catch (e: ZipException) {
                    if (e.type == ZipException.Type.WRONG_PASSWORD || e.message?.contains("password", ignoreCase = true) == true) {
                        throw ArchiveInvalidPasswordException(archiveFile.absolutePath)
                    }
                    throw e
                }
                val testHeader = headers.firstOrNull { !it.isDirectory && it.uncompressedSize > 0 }
                    ?: headers.firstOrNull { !it.isDirectory }
                if (testHeader != null && (testHeader.isEncrypted || zipFile.isEncrypted)) {
                    try {
                        zipFile.getInputStream(testHeader).use { stream ->
                            val buf = ByteArray(32)
                            stream.read(buf)
                        }
                    } catch (e: ZipException) {
                        if (e.type == ZipException.Type.WRONG_PASSWORD ||
                            e.message?.contains("password", ignoreCase = true) == true ||
                            e.message?.contains("checksum", ignoreCase = true) == true ||
                            e.message?.contains("crc", ignoreCase = true) == true
                        ) {
                            throw ArchiveInvalidPasswordException(archiveFile.absolutePath)
                        }
                        throw e
                    }
                }
            }
        }
    }

    suspend fun getArchiveConflicts(
        archiveFilePath: String,
        targetDir: String,
        password: String? = null
    ): List<com.antigravity.filemanager.domain.model.OverwriteConflict> = withContext(Dispatchers.IO) {
        val archiveFile = File(archiveFilePath)
        if (!archiveFile.exists()) return@withContext emptyList()
        // Pre-validate password first so invalid password triggers immediately without creating any conflict/extract state
        validateArchivePassword(archiveFile, password)

        val ext = archiveFile.extension.lowercase(Locale.ROOT)
        val destDir = File(targetDir)
        val targetCanonical = destDir.canonicalPath

        when (ext) {
            "7z" -> {
                val passwordChars = if (password.isNullOrEmpty()) null else password.toCharArray()
                val sevenZFile = try {
                    SevenZFile(archiveFile, passwordChars)
                } catch (e: org.apache.commons.compress.PasswordRequiredException) {
                    throw ArchivePasswordRequiredException(archiveFile.absolutePath)
                } catch (e: IOException) {
                    if (passwordChars != null) {
                        throw ArchiveInvalidPasswordException(archiveFile.absolutePath)
                    }
                    throw e
                }
                sevenZFile.use { archive ->
                    val conflicts = mutableListOf<com.antigravity.filemanager.domain.model.OverwriteConflict>()
                    for (entry in archive.entries) {
                        if (entry.isDirectory) continue
                        val normName = entry.name.replace('\\', '/').trimStart('/')
                        val destFile = File(destDir, normName)
                        if (!destFile.canonicalPath.startsWith(targetCanonical + File.separator) && destFile.canonicalPath != targetCanonical) {
                            continue
                        }
                        if (destFile.exists()) {
                            conflicts.add(
                                com.antigravity.filemanager.domain.model.OverwriteConflict(
                                    name = normName,
                                    existingSize = destFile.length(),
                                    newSize = entry.size,
                                    isDirectory = false
                                )
                            )
                        }
                    }
                    conflicts
                }
            }
            "rar" -> {
                val arc = try {
                    if (!password.isNullOrEmpty()) {
                        Archive(archiveFile, password)
                    } else {
                        Archive(archiveFile)
                    }
                } catch (e: Exception) {
                    val msg = e.message?.lowercase(Locale.ROOT) ?: ""
                    if (msg.contains("password") || msg.contains("encrypted") || msg.contains("header")) {
                        if (password.isNullOrEmpty()) {
                            throw ArchivePasswordRequiredException(archiveFile.absolutePath)
                        } else {
                            throw ArchiveInvalidPasswordException(archiveFile.absolutePath)
                        }
                    }
                    throw e
                }
                arc.use { archive ->
                    if ((archive.isEncrypted || archive.isPasswordProtected) && password.isNullOrEmpty()) {
                        throw ArchivePasswordRequiredException(archiveFile.absolutePath)
                    }
                    val conflicts = mutableListOf<com.antigravity.filemanager.domain.model.OverwriteConflict>()
                    val headers = archive.fileHeaders ?: emptyList()
                    for (header in headers) {
                        if (header.isDirectory) continue
                        val normName = header.fileName.replace('\\', '/').trimStart('/')
                        val destFile = File(destDir, normName)
                        if (!destFile.canonicalPath.startsWith(targetCanonical + File.separator) && destFile.canonicalPath != targetCanonical) {
                            continue
                        }
                        if (destFile.exists()) {
                            conflicts.add(
                                com.antigravity.filemanager.domain.model.OverwriteConflict(
                                    name = normName,
                                    existingSize = destFile.length(),
                                    newSize = header.unpSize,
                                    isDirectory = false
                                )
                            )
                        }
                    }
                    conflicts
                }
            }
            else -> {
                val zipFile = ZipFile(archiveFile)
                if (zipFile.isEncrypted) {
                    if (password.isNullOrEmpty()) {
                        throw ArchivePasswordRequiredException(archiveFile.absolutePath)
                    }
                    zipFile.setPassword(password.toCharArray())
                }
                val conflicts = mutableListOf<com.antigravity.filemanager.domain.model.OverwriteConflict>()
                val headers = try {
                    zipFile.fileHeaders ?: emptyList()
                } catch (e: ZipException) {
                    if (e.type == ZipException.Type.WRONG_PASSWORD || e.message?.contains("password", ignoreCase = true) == true) {
                        throw ArchiveInvalidPasswordException(archiveFile.absolutePath)
                    }
                    throw e
                }
                for (header in headers) {
                    if (header.isDirectory) continue
                    val normName = header.fileName.replace('\\', '/').trimStart('/')
                    val destFile = File(destDir, normName)
                    if (!destFile.canonicalPath.startsWith(targetCanonical + File.separator) && destFile.canonicalPath != targetCanonical) {
                        continue
                    }
                    if (destFile.exists()) {
                        conflicts.add(
                            com.antigravity.filemanager.domain.model.OverwriteConflict(
                                name = normName,
                                existingSize = destFile.length(),
                                newSize = header.uncompressedSize,
                                isDirectory = false
                            )
                        )
                    }
                }
                conflicts
            }
        }
    }

    suspend fun extractArchive(
        archiveFilePath: String,
        targetDir: String,
        password: String? = null,
        overwriteNames: Set<String> = emptySet(),
        skipNames: Set<String> = emptySet(),
        onProgress: ((currentEntry: String, currentIndex: Int, totalEntries: Int, bytesProcessed: Long, totalBytes: Long) -> Unit)? = null
    ): Result<com.antigravity.filemanager.domain.model.ExtractResult> = withContext(Dispatchers.IO) {
        try {
            val archiveFile = File(archiveFilePath)
            // Pre-validate password first before touching target filesystem!
            validateArchivePassword(archiveFile, password)

            val ext = archiveFile.extension.lowercase(Locale.ROOT)
            val destDir = File(targetDir)
            if (!destDir.exists()) destDir.mkdirs()

            when (ext) {
                "7z" -> extract7z(archiveFile, destDir, password, overwriteNames, skipNames, onProgress)
                "rar" -> extractRar(archiveFile, destDir, password, overwriteNames, skipNames, onProgress)
                else -> extractZipInternal(archiveFile, targetDir, password, overwriteNames, skipNames, onProgress)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun extractZip(zipFilePath: String, targetDir: String): Result<com.antigravity.filemanager.domain.model.ExtractResult> =
        extractArchive(zipFilePath, targetDir, null)

    private suspend fun extract7z(
        archiveFile: File,
        targetDir: File,
        password: String?,
        overwriteNames: Set<String> = emptySet(),
        skipNames: Set<String> = emptySet(),
        onProgress: ((currentEntry: String, currentIndex: Int, totalEntries: Int, bytesProcessed: Long, totalBytes: Long) -> Unit)? = null
    ): Result<com.antigravity.filemanager.domain.model.ExtractResult> {
        val targetCanonical = targetDir.canonicalPath
        val passwordChars = if (password.isNullOrEmpty()) null else password.toCharArray()

        val sevenZFile = try {
            SevenZFile(archiveFile, passwordChars)
        } catch (e: org.apache.commons.compress.PasswordRequiredException) {
            throw ArchivePasswordRequiredException(archiveFile.absolutePath)
        } catch (e: IOException) {
            if (passwordChars != null) {
                throw ArchiveInvalidPasswordException(archiveFile.absolutePath)
            }
            throw e
        }

        val createdFiles = mutableListOf<File>()
        try {
            sevenZFile.use { archive ->
                val fileEntries = archive.entries.filter { !it.isDirectory }
                val totalEntries = fileEntries.size
                val totalBytes = fileEntries.sumOf { it.size }
                var currentIndex = 0
                var bytesProcessed = 0L
                var lastProgressTime = 0L
                var extractedCount = 0
                var skippedCount = 0

                onProgress?.invoke("", 0, totalEntries, 0L, totalBytes)

                val buffer = ByteArray(64 * 1024)
                var entry = archive.nextEntry
                while (entry != null) {
                    currentCoroutineContext().ensureActive()
                    val normName = entry.name.replace('\\', '/').trimStart('/')
                    val defaultDestFile = File(targetDir, normName)
                    // Zip-Slip protection
                    if (!defaultDestFile.canonicalPath.startsWith(targetCanonical + File.separator) && defaultDestFile.canonicalPath != targetCanonical) {
                        throw SecurityException("Zip Slip detected in archive: ${entry.name}")
                    }

                    if (entry.isDirectory) {
                        if (!defaultDestFile.exists()) {
                            defaultDestFile.mkdirs()
                            createdFiles.add(defaultDestFile)
                        }
                    } else {
                        if (normName in skipNames) {
                            skippedCount++
                            currentIndex++
                            bytesProcessed += entry.size
                            onProgress?.invoke(normName, currentIndex, totalEntries, bytesProcessed, totalBytes)
                            entry = archive.nextEntry
                            continue
                        }
                        currentIndex++
                        val overwriteThis = normName in overwriteNames
                        val destFile = if (overwriteThis || !defaultDestFile.exists()) {
                            defaultDestFile
                        } else {
                            val parent = defaultDestFile.parentFile ?: targetDir
                            uniqueDestination(parent, defaultDestFile.name)
                        }

                        if (!destFile.exists()) {
                            createdFiles.add(destFile)
                        }
                        destFile.parentFile?.mkdirs()
                        try {
                            FileOutputStream(destFile).use { out ->
                                var count: Int
                                while (archive.read(buffer).also { count = it } != -1) {
                                    currentCoroutineContext().ensureActive()
                                    out.write(buffer, 0, count)
                                    bytesProcessed += count
                                    val now = System.currentTimeMillis()
                                    if (now - lastProgressTime >= 100) {
                                        lastProgressTime = now
                                        onProgress?.invoke(normName, currentIndex, totalEntries, bytesProcessed, totalBytes)
                                    }
                                }
                            }
                            extractedCount++
                            onProgress?.invoke(normName, currentIndex, totalEntries, bytesProcessed, totalBytes)
                        } catch (e: org.apache.commons.compress.PasswordRequiredException) {
                            if (destFile.exists()) destFile.delete()
                            throw ArchivePasswordRequiredException(archiveFile.absolutePath)
                        } catch (e: IOException) {
                            if (destFile.exists()) destFile.delete()
                            if (passwordChars != null) {
                                throw ArchiveInvalidPasswordException(archiveFile.absolutePath)
                            }
                            throw e
                        } catch (t: Throwable) {
                            if (destFile.exists()) destFile.delete()
                            throw t
                        }
                    }
                    entry = archive.nextEntry
                }
                return Result.success(com.antigravity.filemanager.domain.model.ExtractResult(totalEntries, extractedCount, skippedCount))
            }
        } catch (t: Throwable) {
            for (f in createdFiles) {
                try {
                    if (f.exists()) {
                        if (f.isDirectory) f.deleteRecursively() else f.delete()
                    }
                } catch (_: Exception) {}
            }
            throw t
        }
    }

    private suspend fun extractRar(
        archiveFile: File,
        targetDir: File,
        password: String?,
        overwriteNames: Set<String> = emptySet(),
        skipNames: Set<String> = emptySet(),
        onProgress: ((currentEntry: String, currentIndex: Int, totalEntries: Int, bytesProcessed: Long, totalBytes: Long) -> Unit)? = null
    ): Result<com.antigravity.filemanager.domain.model.ExtractResult> {
        val job = currentCoroutineContext().job
        val createdFiles = mutableListOf<File>()
        try {
            val targetCanonical = targetDir.canonicalPath
            val archive = if (!password.isNullOrEmpty()) {
                Archive(archiveFile, password)
            } else {
                Archive(archiveFile)
            }
            archive.use { arc ->
                if ((arc.isEncrypted || arc.isPasswordProtected) && password.isNullOrEmpty()) {
                    throw ArchivePasswordRequiredException(archiveFile.absolutePath)
                }
                val allHeaders = arc.fileHeaders ?: emptyList()
                val fileHeaders = allHeaders.filter { !it.isDirectory }
                val totalEntries = fileHeaders.size
                val totalBytes = fileHeaders.sumOf { it.unpSize }
                var currentIndex = 0
                var bytesProcessed = 0L
                var lastProgressTime = 0L
                var extractedCount = 0
                var skippedCount = 0

                onProgress?.invoke("", 0, totalEntries, 0L, totalBytes)

                for (header in allHeaders) {
                    job.ensureActive()
                    val normName = header.fileName.replace('\\', '/').trimStart('/')
                    val defaultDestFile = File(targetDir, normName)
                    if (!defaultDestFile.canonicalPath.startsWith(targetCanonical + File.separator) && defaultDestFile.canonicalPath != targetCanonical) {
                        throw SecurityException("Zip Slip detected in archive: ${header.fileName}")
                    }

                    if (header.isDirectory) {
                        if (!defaultDestFile.exists()) {
                            defaultDestFile.mkdirs()
                            createdFiles.add(defaultDestFile)
                        }
                    } else {
                        if (normName in skipNames) {
                            skippedCount++
                            currentIndex++
                            bytesProcessed += header.unpSize
                            onProgress?.invoke(normName, currentIndex, totalEntries, bytesProcessed, totalBytes)
                            continue
                        }
                        currentIndex++
                        val overwriteThis = normName in overwriteNames
                        val destFile = if (overwriteThis || !defaultDestFile.exists()) {
                            defaultDestFile
                        } else {
                            val parent = defaultDestFile.parentFile ?: targetDir
                            uniqueDestination(parent, defaultDestFile.name)
                        }
                        if (!destFile.exists()) {
                            createdFiles.add(destFile)
                        }
                        destFile.parentFile?.mkdirs()
                        try {
                            val countingOut = object : java.io.OutputStream() {
                                private val fos = FileOutputStream(destFile)
                                override fun write(b: Int) {
                                    job.ensureActive()
                                    fos.write(b)
                                    bytesProcessed++
                                    notifyProgress()
                                }
                                override fun write(b: ByteArray, off: Int, len: Int) {
                                    job.ensureActive()
                                    fos.write(b, off, len)
                                    bytesProcessed += len
                                    notifyProgress()
                                }
                                private fun notifyProgress() {
                                    val now = System.currentTimeMillis()
                                    if (now - lastProgressTime >= 100) {
                                        lastProgressTime = now
                                        onProgress?.invoke(normName, currentIndex, totalEntries, bytesProcessed, totalBytes)
                                    }
                                }
                                override fun flush() = fos.flush()
                                override fun close() = fos.close()
                            }
                            countingOut.use { out ->
                                arc.extractFile(header, out)
                            }
                            extractedCount++
                            onProgress?.invoke(normName, currentIndex, totalEntries, bytesProcessed, totalBytes)
                        } catch (t: Throwable) {
                            if (destFile.exists()) destFile.delete()
                            throw t
                        }
                    }
                }
                return Result.success(com.antigravity.filemanager.domain.model.ExtractResult(totalEntries, extractedCount, skippedCount))
            }
        } catch (e: Throwable) {
            for (f in createdFiles) {
                try {
                    if (f.exists()) {
                        if (f.isDirectory) f.deleteRecursively() else f.delete()
                    }
                } catch (_: Exception) {}
            }
            if (e is ArchivePasswordRequiredException || e is ArchiveInvalidPasswordException) throw e
            val msg = e.message?.lowercase(Locale.ROOT) ?: ""
            if (msg.contains("password") || msg.contains("encrypted") || msg.contains("header") || msg.contains("crc") || msg.contains("corrupt")) {
                if (password.isNullOrEmpty()) {
                    throw ArchivePasswordRequiredException(archiveFile.absolutePath)
                } else {
                    throw ArchiveInvalidPasswordException(archiveFile.absolutePath)
                }
            }
            throw e
        }
    }

    private suspend fun extractZipInternal(
        archiveFile: File,
        targetDir: String,
        password: String?,
        overwriteNames: Set<String> = emptySet(),
        skipNames: Set<String> = emptySet(),
        onProgress: ((currentEntry: String, currentIndex: Int, totalEntries: Int, bytesProcessed: Long, totalBytes: Long) -> Unit)? = null
    ): Result<com.antigravity.filemanager.domain.model.ExtractResult> {
        val zipFile = ZipFile(archiveFile)
        if (zipFile.isEncrypted) {
            if (password.isNullOrEmpty()) {
                throw ArchivePasswordRequiredException(archiveFile.absolutePath)
            }
            zipFile.setPassword(password.toCharArray())
        }
        val createdFiles = mutableListOf<File>()
        try {
            val destDir = File(targetDir)
            val targetCanonical = destDir.canonicalPath
            val allHeaders = zipFile.fileHeaders ?: emptyList()
            val fileHeaders = allHeaders.filter { !it.isDirectory }
            val totalEntries = fileHeaders.size
            val totalBytes = fileHeaders.sumOf { it.uncompressedSize }
            var currentIndex = 0
            var bytesProcessed = 0L
            var lastProgressTime = 0L
            var extractedCount = 0
            var skippedCount = 0

            onProgress?.invoke("", 0, totalEntries, 0L, totalBytes)

            val buffer = ByteArray(64 * 1024)
            for (header in allHeaders) {
                val normName = header.fileName.replace('\\', '/').trimStart('/')
                val defaultDestFile = File(destDir, normName)
                if (!defaultDestFile.canonicalPath.startsWith(targetCanonical + File.separator) && defaultDestFile.canonicalPath != targetCanonical) {
                    throw SecurityException("Zip Slip detected in archive: ${header.fileName}")
                }

                if (header.isDirectory) {
                    if (!defaultDestFile.exists()) {
                        defaultDestFile.mkdirs()
                        createdFiles.add(defaultDestFile)
                    }
                } else {
                    if (normName in skipNames) {
                        skippedCount++
                        currentIndex++
                        bytesProcessed += header.uncompressedSize
                        onProgress?.invoke(normName, currentIndex, totalEntries, bytesProcessed, totalBytes)
                        continue
                    }
                    currentIndex++
                    val overwriteThis = normName in overwriteNames
                    val destFile = if (overwriteThis || !defaultDestFile.exists()) {
                        defaultDestFile
                    } else {
                        val parent = defaultDestFile.parentFile ?: destDir
                        uniqueDestination(parent, defaultDestFile.name)
                    }
                    if (!destFile.exists()) {
                        createdFiles.add(destFile)
                    }
                    destFile.parentFile?.mkdirs()
                    try {
                        zipFile.getInputStream(header).use { inStream ->
                            FileOutputStream(destFile).use { outStream ->
                                var count: Int
                                while (inStream.read(buffer).also { count = it } != -1) {
                                    currentCoroutineContext().ensureActive()
                                    outStream.write(buffer, 0, count)
                                    bytesProcessed += count
                                    val now = System.currentTimeMillis()
                                    if (now - lastProgressTime >= 100) {
                                        lastProgressTime = now
                                        onProgress?.invoke(normName, currentIndex, totalEntries, bytesProcessed, totalBytes)
                                    }
                                }
                            }
                        }
                        extractedCount++
                        onProgress?.invoke(normName, currentIndex, totalEntries, bytesProcessed, totalBytes)
                    } catch (e: ZipException) {
                        if (destFile.exists()) destFile.delete()
                        if (e.type == ZipException.Type.WRONG_PASSWORD || e.message?.contains("password", ignoreCase = true) == true) {
                            throw ArchiveInvalidPasswordException(archiveFile.absolutePath)
                        }
                        throw e
                    } catch (t: Throwable) {
                        if (destFile.exists()) destFile.delete()
                        throw t
                    }
                }
            }
            return Result.success(com.antigravity.filemanager.domain.model.ExtractResult(totalEntries, extractedCount, skippedCount))
        } catch (t: Throwable) {
            for (f in createdFiles) {
                try {
                    if (f.exists()) {
                        if (f.isDirectory) f.deleteRecursively() else f.delete()
                    }
                } catch (_: Exception) {}
            }
            if (t is ZipException) {
                if (t.type == ZipException.Type.WRONG_PASSWORD || t.message?.contains("password", ignoreCase = true) == true) {
                    throw ArchiveInvalidPasswordException(archiveFile.absolutePath)
                }
            }
            throw t
        }
    }
}
