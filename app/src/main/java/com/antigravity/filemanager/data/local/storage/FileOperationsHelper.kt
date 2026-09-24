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
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import com.github.junrar.Archive
import java.io.File
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
    @ApplicationContext private val context: Context
) {

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
                    existingSize = directorySize(dest),
                    newSize = directorySize(source),
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
            unsafeDestination(sourcePaths, targetFolder, overwriteNames, skipNames, "copy")?.let {
                return@withContext Result.failure(it)
            }
            if (!targetFolder.exists()) targetFolder.mkdirs()

            // overwrite is decided once per top-level source (matching the pre-existing conflict
            // dialog, which only ever resolves top-level name clashes) and carried down to every
            // file underneath it — same effective behavior copyRecursively(overwrite=...) had.
            val fileEntries = mutableListOf<FileCopyEntry>()
            val dirEntries = mutableListOf<FileCopyEntry>()
            for (path in sourcePaths) {
                val source = File(path)
                if (!source.exists() || source.name in skipNames) continue
                val dest = resolveDestination(targetFolder, source, overwriteNames)
                // Copying a file onto itself (same-folder paste with overwrite chosen) is a
                // no-op: doing it for real would truncate the source before it's read.
                if (dest.absolutePath == source.absolutePath) continue
                collectFileCopyEntries(source, dest, fileEntries, dirEntries)
            }

            val outcome = copyEntries(dirEntries, fileEntries, deleteSources = false, onProgress = onProgress)
            scanMedia(outcome.copiedPaths)
            if (outcome.failedSources.isNotEmpty()) {
                Result.failure(IOException("Failed to copy ${outcome.failedSources.size} file(s)"))
            } else {
                Result.success(Unit)
            }
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
            unsafeDestination(sourcePaths, targetFolder, overwriteNames, skipNames, "move")?.let {
                return@withContext Result.failure(it)
            }
            if (!targetFolder.exists()) targetFolder.mkdirs()
            val targetCanonical = targetFolder.canonicalFile
            val scannedPaths = mutableListOf<String>()

            // Phase 1: renameTo() is atomic and effectively instant for a same-filesystem move —
            // even a folder with thousands of files inside moves in one O(1) call, so there's
            // nothing meaningful to report progress on here. Only a genuine cross-filesystem move
            // (internal storage <-> SD card, say) falls through to it failing, handled for real
            // in phase 2 below.
            val renameFailures = mutableListOf<FileCopyEntry>()
            val mergedSources = mutableListOf<File>()
            for (path in sourcePaths) {
                currentCoroutineContext().ensureActive()
                val source = File(path)
                if (!source.exists() || source.name in skipNames) continue
                // Already in the target folder: nothing to move. It used to be renamed to
                // "name (1)" by the keep-both naming below.
                if (source.canonicalFile.parentFile == targetCanonical) continue
                val dest = resolveDestination(targetFolder, source, overwriteNames)
                if (dest.isDirectory && source.isDirectory) {
                    // Overwriting a folder merges into it, as copy does: only same-named files
                    // are replaced. It used to delete the whole existing folder first, losing
                    // everything in it that the moved folder didn't have.
                    mergeByRename(source, dest, renameFailures)
                    mergedSources.add(source)
                    scannedPaths.add(source.absolutePath)
                    scannedPaths.add(dest.absolutePath)
                    continue
                }
                if (dest.exists()) {
                    if (dest.isDirectory) dest.deleteRecursively() else dest.delete()
                }
                if (source.renameTo(dest)) {
                    scannedPaths.add(source.absolutePath)
                    scannedPaths.add(dest.absolutePath)
                } else {
                    renameFailures.add(FileCopyEntry(source, dest))
                }
            }

            // Phase 2: genuine cross-filesystem fallback, flattened into per-file copy work the
            // same way copy() is above. Each file's source is deleted only once its copy landed.
            val fileEntries = mutableListOf<FileCopyEntry>()
            val dirEntries = mutableListOf<FileCopyEntry>()
            for (item in renameFailures) {
                collectFileCopyEntries(item.source, item.dest, fileEntries, dirEntries)
            }
            val outcome = copyEntries(dirEntries, fileEntries, deleteSources = true, onProgress = onProgress)
            for (item in renameFailures) {
                val prefix = item.source.absolutePath + File.separator
                val anyFailed = outcome.failedSources.any { it == item.source.absolutePath || it.startsWith(prefix) }
                // A source with files that failed to copy keeps whatever is left, instead of
                // being wiped together with files that never landed anywhere.
                if (!anyFailed && item.source.exists()) item.source.deleteRecursively()
                scannedPaths.add(item.source.absolutePath)
                scannedPaths.add(item.dest.absolutePath)
            }
            // A merged folder is left holding only empty subfolders once all its contents moved;
            // anything that failed to move keeps it (and the failed files) in place.
            for (source in mergedSources) {
                val prefix = source.absolutePath + File.separator
                if (outcome.failedSources.none { it.startsWith(prefix) } && source.exists()) source.deleteRecursively()
            }
            scanMedia(scannedPaths)
            if (outcome.failedSources.isNotEmpty()) {
                Result.failure(IOException("Failed to move ${outcome.failedSources.size} file(s)"))
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /** Moves the contents of folder [source] into the existing folder [dest]: subfolders present
     * in both are merged the same way, anything else replaces its same-named counterpart. Items
     * renameTo() can't move (another filesystem) are queued in [fallbacks] for copy-then-delete. */
    private suspend fun mergeByRename(source: File, dest: File, fallbacks: MutableList<FileCopyEntry>) {
        for (child in source.listFiles().orEmpty()) {
            currentCoroutineContext().ensureActive()
            val target = File(dest, child.name)
            if (child.isDirectory && target.isDirectory) {
                mergeByRename(child, target, fallbacks)
                continue
            }
            if (target.exists()) {
                if (target.isDirectory) target.deleteRecursively() else target.delete()
            }
            if (!child.renameTo(target)) fallbacks.add(FileCopyEntry(child, target))
        }
    }

    private class CopyOutcome(val copiedPaths: List<String>, val failedSources: List<String>)

    // Creates every directory first so subfolders always exist, then copies each file with error
    // isolation so one failing file does not abort the remaining ones.
    private suspend fun copyEntries(
        dirEntries: List<FileCopyEntry>,
        fileEntries: List<FileCopyEntry>,
        deleteSources: Boolean,
        onProgress: ((currentFile: String, currentIndex: Int, totalFiles: Int) -> Unit)?
    ): CopyOutcome {
        val total = fileEntries.size + dirEntries.size
        var current = 0
        for (entry in dirEntries) {
            currentCoroutineContext().ensureActive()
            current++
            onProgress?.invoke(entry.source.name, current, total)
            if (!entry.dest.exists()) entry.dest.mkdirs()
        }

        val copiedPaths = mutableListOf<String>()
        val failedSources = mutableListOf<String>()
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
                if (deleteSources) entry.source.delete()
                copiedPaths.add(entry.dest.absolutePath)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failedSources.add(entry.source.absolutePath)
                android.util.Log.e("FileOperationsHelper", "Failed to copy file: ${entry.source.absolutePath} -> ${entry.dest.absolutePath}", e)
            }
        }
        return CopyOutcome(copiedPaths, failedSources)
    }

    /**
     * Why copying/moving [sourcePaths] into [targetFolder] would destroy data, or null when it's
     * safe. Checked for every item before any of them is touched, so a rejected batch leaves
     * everything as it was:
     * - a folder into itself or its own subtree: renameTo() fails there, and move's copy-then-
     *   delete fallback would then delete the fresh copy together with the source;
     * - overwriting a folder that contains the source (moving "Photos/Photos" up onto "Photos"):
     *   deleting the overwritten folder deleted the source with it.
     */
    private fun unsafeDestination(
        sourcePaths: List<String>,
        targetFolder: File,
        overwriteNames: Set<String>,
        skipNames: Set<String>,
        verb: String
    ): IOException? {
        for (path in sourcePaths) {
            val source = File(path)
            if (!source.exists() || source.name in skipNames) continue
            if (source.isDirectory && isSameOrDescendant(targetFolder, source)) {
                return IOException("Cannot $verb a folder into itself: ${source.name}")
            }
            if (source.name in overwriteNames) {
                val dest = File(targetFolder, source.name)
                if (dest.isDirectory && dest.canonicalPath != source.canonicalPath && isSameOrDescendant(source, dest)) {
                    val participle = if (verb == "move") "moved" else "copied"
                    return IOException("Cannot overwrite \"${dest.name}\": it contains the item being $participle")
                }
            }
        }
        return null
    }

    private fun resolveDestination(targetFolder: File, source: File, overwriteNames: Set<String>): File =
        if (source.name in overwriteNames) File(targetFolder, source.name) else uniqueFile(targetFolder, source.name)

    private fun isSameOrDescendant(candidate: File, ancestor: File): Boolean {
        val candidatePath = candidate.canonicalPath
        val ancestorPath = ancestor.canonicalPath
        return candidatePath == ancestorPath || candidatePath.startsWith(ancestorPath + File.separator)
    }

    private fun fileItemOf(file: File): FileItem {
        val isDir = file.isDirectory
        return FileItem(
            id = file.absolutePath,
            name = file.name,
            path = file.absolutePath,
            size = if (isDir) 0L else file.length(),
            lastModified = file.lastModified(),
            isDirectory = isDir,
            extension = if (isDir) "" else file.extension
        )
    }

    private fun scanMedia(paths: List<String>) {
        if (paths.isEmpty()) return
        try {
            android.media.MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
        } catch (e: Exception) {}
    }

    suspend fun rename(filePath: String, newName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) return@withContext Result.failure(Exception("File does not exist"))
            invalidFileNameReason(newName)?.let { return@withContext Result.failure(IOException(it)) }

            val dest = File(file.parentFile, newName)
            if (newName == file.name) return@withContext Result.success(fileItemOf(file))
            // renameTo() silently replaces an existing file on Android, so renaming onto a taken
            // name destroyed the other file. Exact-name listing check: a case-only rename on the
            // case-insensitive shared storage finds no separate entry and is still allowed.
            if (file.parentFile?.list()?.contains(newName) == true) {
                return@withContext Result.failure(IOException("\"$newName\" already exists"))
            }
            if (file.renameTo(dest)) {
                try {
                    android.media.MediaScannerConnection.scanFile(context, arrayOf(filePath, dest.absolutePath), null, null)
                } catch (e: Exception) {}
                Result.success(fileItemOf(dest))
            } else {
                Result.failure(Exception("Could not rename file"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createDirectory(parentPath: String, name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            invalidFileNameReason(name)?.let { return@withContext Result.failure(IOException(it)) }
            val dir = File(parentPath, name)
            if (dir.exists() && !dir.isDirectory) {
                return@withContext Result.failure(IOException("A file named \"$name\" already exists"))
            }
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

    // Archives are streamed manually on the calling coroutine (never zip4j's isRunInThread mode,
    // whose detached thread ignores coroutine cancellation and once filled a device's disk), so
    // byte-level progress and cancellation are both checked between buffer writes.

    suspend fun compressFiles(
        sourcePaths: List<String>,
        targetArchivePath: String,
        onProgress: ((currentFile: String, currentIndex: Int, totalFiles: Int, bytesProcessed: Long, totalBytes: Long) -> Unit)? = null
    ): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            archiveTargetConflictReason(targetArchivePath, sourcePaths)?.let {
                return@withContext Result.failure(IOException(it))
            }
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
        // Entry paths of folders with no files anywhere below them, written as directory entries so
        // they survive the round trip (only a wholly empty top-level folder used to be kept).
        val emptyFolderEntries = mutableListOf<String>()

        fun collectFiles(dir: File, entryPrefix: String) {
            val children = dir.listFiles() ?: return
            if (children.isEmpty()) emptyFolderEntries.add("$entryPrefix/")
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
                    collectFiles(f, f.name)
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
            for (folderPath in emptyFolderEntries) {
                currentCoroutineContext().ensureActive()
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
        return try {
            when (file.extension.lowercase(Locale.ROOT)) {
                "7z" -> SevenZFile(file).use { sz ->
                    var entry = sz.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.hasStream()) {
                            sz.read(ByteArray(1))
                            return false
                        }
                        entry = sz.nextEntry
                    }
                    false
                }
                "rar" -> Archive(file).use { it.isEncrypted || it.isPasswordProtected }
                "zip" -> ZipFile(file).use { it.isEncrypted }
                else -> false
            }
        } catch (e: org.apache.commons.compress.PasswordRequiredException) {
            true
        } catch (_: Exception) {
            false
        }
    }

    // --- Archive opening helpers: each maps the library's own password failures onto
    // ArchivePasswordRequiredException / ArchiveInvalidPasswordException so callers see one
    // consistent pair of exceptions regardless of format. ---

    private fun openSevenZ(archiveFile: File, password: String?): SevenZFile {
        val passwordChars = if (password.isNullOrEmpty()) null else password.toCharArray()
        return try {
            SevenZFile(archiveFile, passwordChars)
        } catch (e: org.apache.commons.compress.PasswordRequiredException) {
            throw ArchivePasswordRequiredException(archiveFile.absolutePath)
        } catch (e: IOException) {
            if (passwordChars != null) throw ArchiveInvalidPasswordException(archiveFile.absolutePath)
            throw e
        }
    }

    private fun openRar(archiveFile: File, password: String?): Archive {
        val archive = try {
            if (!password.isNullOrEmpty()) Archive(archiveFile, password) else Archive(archiveFile)
        } catch (e: Exception) {
            if (looksLikeRarPasswordError(e, includeCorruption = false)) throw rarPasswordException(archiveFile, password)
            throw e
        }
        if ((archive.isEncrypted || archive.isPasswordProtected) && password.isNullOrEmpty()) {
            archive.close()
            throw ArchivePasswordRequiredException(archiveFile.absolutePath)
        }
        return archive
    }

    private fun looksLikeRarPasswordError(e: Throwable, includeCorruption: Boolean): Boolean {
        val msg = e.message?.lowercase(Locale.ROOT) ?: return false
        return msg.contains("password") || msg.contains("encrypted") || msg.contains("header") ||
            (includeCorruption && (msg.contains("crc") || msg.contains("corrupt")))
    }

    private fun rarPasswordException(archiveFile: File, password: String?): IOException =
        if (password.isNullOrEmpty()) ArchivePasswordRequiredException(archiveFile.absolutePath)
        else ArchiveInvalidPasswordException(archiveFile.absolutePath)

    private fun openZip(archiveFile: File, password: String?): ZipFile {
        val zipFile = ZipFile(archiveFile)
        if (zipFile.isEncrypted) {
            if (password.isNullOrEmpty()) {
                zipFile.close()
                throw ArchivePasswordRequiredException(archiveFile.absolutePath)
            }
            zipFile.setPassword(password.toCharArray())
        }
        return zipFile
    }

    private fun isZipPasswordError(e: ZipException, includeChecksum: Boolean = false): Boolean =
        e.type == ZipException.Type.WRONG_PASSWORD ||
            e.message?.contains("password", ignoreCase = true) == true ||
            (includeChecksum && (e.message?.contains("checksum", ignoreCase = true) == true ||
                e.message?.contains("crc", ignoreCase = true) == true))

    private fun zipHeaders(zipFile: ZipFile, archiveFile: File): List<net.lingala.zip4j.model.FileHeader> = try {
        zipFile.fileHeaders ?: emptyList()
    } catch (e: ZipException) {
        if (isZipPasswordError(e)) throw ArchiveInvalidPasswordException(archiveFile.absolutePath)
        throw e
    }

    /** Zip-Slip guard: true when [dest] resolves inside the extraction root. */
    private fun isInsideTarget(dest: File, targetCanonical: String): Boolean {
        val destCanonical = dest.canonicalPath
        return destCanonical == targetCanonical || destCanonical.startsWith(targetCanonical + File.separator)
    }

    private fun normalizeEntryName(name: String): String = name.replace('\\', '/').trimStart('/')

    private fun deleteCreated(createdFiles: List<File>) {
        for (f in createdFiles) {
            try {
                if (f.exists()) {
                    if (f.isDirectory) f.deleteRecursively() else f.delete()
                }
            } catch (_: Exception) {}
        }
    }

    fun validateArchivePassword(archiveFile: File, password: String?) {
        if (!archiveFile.exists() || !archiveFile.isFile) return
        when (archiveFile.extension.lowercase(Locale.ROOT)) {
            "7z" -> openSevenZ(archiveFile, password).use { archive ->
                try {
                    var entry = archive.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.hasStream() && archive.read(ByteArray(32)) != -1) break
                        entry = archive.nextEntry
                    }
                } catch (e: org.apache.commons.compress.PasswordRequiredException) {
                    throw ArchivePasswordRequiredException(archiveFile.absolutePath)
                } catch (e: IOException) {
                    if (!password.isNullOrEmpty()) throw ArchiveInvalidPasswordException(archiveFile.absolutePath)
                    throw e
                }
            }
            "rar" -> openRar(archiveFile, password).use { arc ->
                val testHeader = arc.fileHeaders?.firstOrNull { !it.isDirectory && it.unpSize > 0 }
                    ?: arc.fileHeaders?.firstOrNull { !it.isDirectory }
                if (testHeader != null && (!password.isNullOrEmpty() || arc.isEncrypted || arc.isPasswordProtected)) {
                    try {
                        arc.extractFile(testHeader, DiscardingOutputStream)
                    } catch (e: Exception) {
                        if (e is ArchivePasswordRequiredException) throw e
                        if (looksLikeRarPasswordError(e, includeCorruption = true)) throw rarPasswordException(archiveFile, password)
                        throw e
                    }
                }
            }
            else -> openZip(archiveFile, password).use { zipFile ->
                val headers = zipHeaders(zipFile, archiveFile)
                val testHeader = headers.firstOrNull { !it.isDirectory && it.uncompressedSize > 0 }
                    ?: headers.firstOrNull { !it.isDirectory }
                if (testHeader != null && (testHeader.isEncrypted || zipFile.isEncrypted)) {
                    try {
                        zipFile.getInputStream(testHeader).use { it.read(ByteArray(32)) }
                    } catch (e: ZipException) {
                        if (isZipPasswordError(e, includeChecksum = true)) throw ArchiveInvalidPasswordException(archiveFile.absolutePath)
                        throw e
                    }
                }
            }
        }
    }

    /** Every entry of the archive, for browsing it like a folder. Throws the same password
     * exceptions as extraction when the archive needs one. */
    suspend fun listArchiveEntries(
        archiveFilePath: String,
        password: String? = null
    ): List<com.antigravity.filemanager.domain.model.ArchiveEntryInfo> = withContext(Dispatchers.IO) {
        val archiveFile = File(archiveFilePath)
        if (!archiveFile.isFile) throw IOException("Archive not found")
        validateArchivePassword(archiveFile, password)
        val entries = when (archiveFile.extension.lowercase(Locale.ROOT)) {
            "7z" -> openSevenZ(archiveFile, password).use { archive ->
                archive.entries.map {
                    com.antigravity.filemanager.domain.model.ArchiveEntryInfo(
                        path = normalizeEntryName(it.name).trimEnd('/'),
                        size = if (it.isDirectory) 0L else it.size,
                        isDirectory = it.isDirectory,
                        lastModified = if (it.hasLastModifiedDate) it.lastModifiedDate.time else 0L
                    )
                }
            }
            "rar" -> openRar(archiveFile, password).use { archive ->
                (archive.fileHeaders ?: emptyList()).map {
                    com.antigravity.filemanager.domain.model.ArchiveEntryInfo(
                        path = normalizeEntryName(it.fileName).trimEnd('/'),
                        size = if (it.isDirectory) 0L else it.unpSize,
                        isDirectory = it.isDirectory,
                        lastModified = it.mTime?.time ?: 0L
                    )
                }
            }
            else -> openZip(archiveFile, password).use { zipFile ->
                zipHeaders(zipFile, archiveFile).map {
                    com.antigravity.filemanager.domain.model.ArchiveEntryInfo(
                        path = normalizeEntryName(it.fileName).trimEnd('/'),
                        size = if (it.isDirectory) 0L else it.uncompressedSize,
                        isDirectory = it.isDirectory,
                        lastModified = it.lastModifiedTimeEpoch
                    )
                }
            }
        }
        entries.filter { it.path.isNotEmpty() }
    }

    /**
     * Extracts only [selectedPaths] (entries and/or folders, as listed by [listArchiveEntries])
     * into [targetDir]. Paths are made relative to [baseDir], the archive folder being browsed,
     * so picking "docs/report.pdf" while inside "docs" writes "report.pdf", not "docs/report.pdf".
     * A selected item whose name is already taken in [targetDir] is saved under a numbered name
     * (keep both), never over the existing one. Returns the extracted files.
     */
    suspend fun extractArchiveEntries(
        archiveFilePath: String,
        selectedPaths: List<String>,
        baseDir: String,
        targetDir: String,
        password: String? = null
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        val createdFiles = mutableListOf<File>()
        try {
            val archiveFile = File(archiveFilePath)
            validateArchivePassword(archiveFile, password)
            val destDir = File(targetDir).apply { mkdirs() }
            val destCanonical = destDir.canonicalPath
            val base = baseDir.trim('/')
            val basePrefix = if (base.isEmpty()) "" else "$base/"
            // Each selected item's name in the destination, renamed if that name is taken.
            val selected = selectedPaths.map { it.trim('/') }.filter { it.isNotEmpty() }
            val renamed = selected.associateWith { sel ->
                val name = sel.removePrefix(basePrefix)
                if (File(destDir, name).exists()) uniqueFile(destDir, name).name else name
            }
            fun destFor(entryPath: String): File? {
                val sel = selected.firstOrNull { entryPath == it || entryPath.startsWith("$it/") } ?: return null
                val relative = renamed.getValue(sel) + entryPath.removePrefix(sel)
                val dest = File(destDir, relative)
                if (!isInsideTarget(dest, destCanonical)) throw SecurityException("Zip Slip detected in archive: $entryPath")
                return dest
            }
            val extracted = mutableListOf<File>()
            // Records the outermost folder mkdirs() actually creates, so a failed extraction
            // removes the whole new branch, not just its innermost folder.
            fun mkdirTracked(dir: File) {
                var outermost: File? = null
                var p: File? = dir
                while (p != null && !p.exists()) { outermost = p; p = p.parentFile }
                dir.mkdirs()
                outermost?.let { createdFiles.add(it) }
            }
            fun write(dest: File, input: java.io.InputStream) {
                dest.parentFile?.let { mkdirTracked(it) }
                createdFiles.add(dest)
                FileOutputStream(dest).use { input.copyTo(it, 64 * 1024) }
                extracted.add(dest)
            }
            when (archiveFile.extension.lowercase(Locale.ROOT)) {
                "7z" -> openSevenZ(archiveFile, password).use { archive ->
                    var entry = archive.nextEntry
                    while (entry != null) {
                        currentCoroutineContext().ensureActive()
                        val dest = destFor(normalizeEntryName(entry.name).trimEnd('/'))
                        if (dest != null) {
                            if (entry.isDirectory) mkdirTracked(dest)
                            else write(dest, object : java.io.InputStream() {
                                override fun read(): Int = archive.read()
                                override fun read(b: ByteArray, off: Int, len: Int): Int = archive.read(b, off, len)
                            })
                        }
                        entry = archive.nextEntry
                    }
                }
                "rar" -> openRar(archiveFile, password).use { archive ->
                    for (header in archive.fileHeaders ?: emptyList()) {
                        currentCoroutineContext().ensureActive()
                        val dest = destFor(normalizeEntryName(header.fileName).trimEnd('/')) ?: continue
                        if (header.isDirectory) { mkdirTracked(dest); continue }
                        dest.parentFile?.let { mkdirTracked(it) }
                        createdFiles.add(dest)
                        FileOutputStream(dest).use { archive.extractFile(header, it) }
                        extracted.add(dest)
                    }
                }
                else -> openZip(archiveFile, password).use { zipFile ->
                    for (header in zipHeaders(zipFile, archiveFile)) {
                        currentCoroutineContext().ensureActive()
                        val dest = destFor(normalizeEntryName(header.fileName).trimEnd('/')) ?: continue
                        if (header.isDirectory) { mkdirTracked(dest); continue }
                        zipFile.getInputStream(header).use { write(dest, it) }
                    }
                }
            }
            if (extracted.isNotEmpty()) {
                try {
                    android.media.MediaScannerConnection.scanFile(context, extracted.map { it.absolutePath }.toTypedArray(), null, null)
                } catch (_: Exception) {}
            }
            Result.success(extracted)
        } catch (e: Exception) {
            // Nothing half-written is left behind, whatever failed (cancel, disk full, bad data).
            deleteCreated(createdFiles.asReversed())
            if (e is CancellationException) throw e
            Result.failure(e)
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

        // (entry name, uncompressed size, is directory) for every entry, whatever the format
        val entries: List<Triple<String, Long, Boolean>> = when (archiveFile.extension.lowercase(Locale.ROOT)) {
            "7z" -> openSevenZ(archiveFile, password).use { archive ->
                archive.entries.map { Triple(it.name, if (it.isDirectory) 0L else it.size, it.isDirectory) }
            }
            "rar" -> openRar(archiveFile, password).use { archive ->
                (archive.fileHeaders ?: emptyList()).map { Triple(it.fileName, if (it.isDirectory) 0L else it.unpSize, it.isDirectory) }
            }
            else -> openZip(archiveFile, password).use { zipFile ->
                zipHeaders(zipFile, archiveFile).map { Triple(it.fileName, if (it.isDirectory) 0L else it.uncompressedSize, it.isDirectory) }
            }
        }

        // Conflicts are resolved per top-level item, like copy/move: an archive holding
        // "FolderA/..." clashes once with an existing "FolderA" folder, and the user's choice
        // (overwrite / skip / keep both as "FolderA (1)") then applies to that whole tree.
        val destDir = File(targetDir)
        val targetCanonical = destDir.canonicalPath
        entries
            .map { (rawName, size, isDir) -> Triple(normalizeEntryName(rawName), size, isDir) }
            .filter { it.first.isNotEmpty() }
            .groupBy { ExtractTargets.topOf(it.first) }
            .mapNotNull { (top, items) ->
                val existing = File(destDir, top)
                if (!isInsideTarget(existing, targetCanonical) || !existing.exists()) return@mapNotNull null
                val isDir = items.any { it.third || it.first.length > top.length }
                com.antigravity.filemanager.domain.model.OverwriteConflict(
                    name = top,
                    existingSize = directorySize(existing),
                    newSize = items.sumOf { it.second },
                    isDirectory = isDir || existing.isDirectory
                )
            }
    }

    /** Where each archive entry lands, given the per-top-level conflict choices: skipped trees
     * return null, overwritten ones merge into the existing item, and any other top-level name
     * that already exists is extracted under a fresh "name (1)" instead (Keep both). */
    private class ExtractTargets(
        private val destDir: File,
        private val targetCanonical: String,
        private val overwriteNames: Set<String>,
        private val skipNames: Set<String>
    ) {
        private val renamedTops = HashMap<String, String>()

        fun resolve(normName: String): File? {
            val top = topOf(normName)
            if (top in skipNames) return null
            // Decided on the first entry of each top-level item, before anything of it is written.
            val mappedTop = if (top in overwriteNames) top else renamedTops.getOrPut(top) {
                if (File(destDir, top).exists()) uniqueFile(destDir, top).name else top
            }
            val target = File(destDir, mappedTop + normName.substring(top.length))
            if (!isInside(target)) throw SecurityException("Zip Slip detected in archive: $normName")
            return target
        }

        fun overwrites(normName: String) = topOf(normName) in overwriteNames

        private fun isInside(file: File): Boolean {
            val path = file.canonicalPath
            return path == targetCanonical || path.startsWith(targetCanonical + File.separator)
        }

        companion object {
            fun topOf(normName: String) = normName.trimEnd('/').substringBefore('/')
        }
    }

    /** Where an entry for [dest] is written: [dest] itself when it's new, a temp file beside it
     * when it already exists. Writing straight over an existing file truncated it first, so a
     * cancelled or failed extraction (disk full, corrupt entry) destroyed the file being
     * overwritten, and the cleanup then deleted what was left of it. */
    private fun stagingFileFor(dest: File): File =
        if (dest.exists()) File(dest.parentFile, ".${dest.name}.extracting") else dest

    /** Puts a completely written [staged] file in place of [dest]. */
    private fun commitStaged(staged: File, dest: File) {
        if (staged == dest) return
        if (dest.isDirectory) dest.deleteRecursively() else dest.delete()
        if (!staged.renameTo(dest)) {
            staged.copyTo(dest, overwrite = true)
            staged.delete()
        }
    }

    /** Cleans up after a failed entry: removes the partial [staged] file, but never when it is
     * the only copy left (commitStaged failed after the old [dest] was already gone). */
    private fun discardStaged(staged: File, dest: File) {
        if (staged.exists() && (staged == dest || dest.exists())) staged.delete()
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

            val destDir = File(targetDir)
            if (!destDir.exists()) destDir.mkdirs()

            when (archiveFile.extension.lowercase(Locale.ROOT)) {
                "7z" -> extract7z(archiveFile, destDir, password, overwriteNames, skipNames, onProgress)
                "rar" -> extractRar(archiveFile, destDir, password, overwriteNames, skipNames, onProgress)
                else -> extractZipInternal(archiveFile, destDir, password, overwriteNames, skipNames, onProgress)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    private suspend fun extract7z(
        archiveFile: File,
        targetDir: File,
        password: String?,
        overwriteNames: Set<String> = emptySet(),
        skipNames: Set<String> = emptySet(),
        onProgress: ((currentEntry: String, currentIndex: Int, totalEntries: Int, bytesProcessed: Long, totalBytes: Long) -> Unit)? = null
    ): Result<com.antigravity.filemanager.domain.model.ExtractResult> {
        val targetCanonical = targetDir.canonicalPath
        val targets = ExtractTargets(targetDir, targetCanonical, overwriteNames, skipNames)
        val sevenZFile = openSevenZ(archiveFile, password)

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
                    val normName = normalizeEntryName(entry.name)
                    val defaultDestFile = File(targetDir, normName)
                    // Zip-Slip protection
                    if (!isInsideTarget(defaultDestFile, targetCanonical)) {
                        throw SecurityException("Zip Slip detected in archive: ${entry.name}")
                    }
                    val resolvedDest = targets.resolve(normName)

                    if (entry.isDirectory) {
                        if (resolvedDest != null && !resolvedDest.exists()) {
                            resolvedDest.mkdirs()
                            createdFiles.add(resolvedDest)
                        }
                    } else {
                        if (resolvedDest == null) {
                            skippedCount++
                            currentIndex++
                            bytesProcessed += entry.size
                            onProgress?.invoke(normName, currentIndex, totalEntries, bytesProcessed, totalBytes)
                            entry = archive.nextEntry
                            continue
                        }
                        currentIndex++
                        val overwriteThis = targets.overwrites(normName)
                        val destFile = if (overwriteThis || !resolvedDest.exists()) {
                            resolvedDest
                        } else {
                            val parent = resolvedDest.parentFile ?: targetDir
                            uniqueFile(parent, resolvedDest.name)
                        }

                        if (!destFile.exists()) {
                            createdFiles.add(destFile)
                        }
                        destFile.parentFile?.mkdirs()
                        val staged = stagingFileFor(destFile)
                        try {
                            FileOutputStream(staged).use { out ->
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
                            commitStaged(staged, destFile)
                            extractedCount++
                            onProgress?.invoke(normName, currentIndex, totalEntries, bytesProcessed, totalBytes)
                        } catch (e: org.apache.commons.compress.PasswordRequiredException) {
                            discardStaged(staged, destFile)
                            throw ArchivePasswordRequiredException(archiveFile.absolutePath)
                        } catch (t: Throwable) {
                            // The password was already validated up front, so any other I/O
                            // failure here (disk full, permission, corrupt entry) is reported as
                            // itself rather than as "incorrect password".
                            discardStaged(staged, destFile)
                            throw t
                        }
                    }
                    entry = archive.nextEntry
                }
                return Result.success(com.antigravity.filemanager.domain.model.ExtractResult(totalEntries, extractedCount, skippedCount))
            }
        } catch (t: Throwable) {
            deleteCreated(createdFiles)
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
            val targets = ExtractTargets(targetDir, targetCanonical, overwriteNames, skipNames)
            openRar(archiveFile, password).use { arc ->
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
                    val normName = normalizeEntryName(header.fileName)
                    val defaultDestFile = File(targetDir, normName)
                    if (!isInsideTarget(defaultDestFile, targetCanonical)) {
                        throw SecurityException("Zip Slip detected in archive: ${header.fileName}")
                    }
                    val resolvedDest = targets.resolve(normName)

                    if (header.isDirectory) {
                        if (resolvedDest != null && !resolvedDest.exists()) {
                            resolvedDest.mkdirs()
                            createdFiles.add(resolvedDest)
                        }
                    } else {
                        if (resolvedDest == null) {
                            skippedCount++
                            currentIndex++
                            bytesProcessed += header.unpSize
                            onProgress?.invoke(normName, currentIndex, totalEntries, bytesProcessed, totalBytes)
                            continue
                        }
                        currentIndex++
                        val overwriteThis = targets.overwrites(normName)
                        val destFile = if (overwriteThis || !resolvedDest.exists()) {
                            resolvedDest
                        } else {
                            val parent = resolvedDest.parentFile ?: targetDir
                            uniqueFile(parent, resolvedDest.name)
                        }
                        if (!destFile.exists()) {
                            createdFiles.add(destFile)
                        }
                        destFile.parentFile?.mkdirs()
                        val staged = stagingFileFor(destFile)
                        try {
                            val countingOut = object : java.io.OutputStream() {
                                private val fos = FileOutputStream(staged)
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
                            commitStaged(staged, destFile)
                            extractedCount++
                            onProgress?.invoke(normName, currentIndex, totalEntries, bytesProcessed, totalBytes)
                        } catch (t: Throwable) {
                            discardStaged(staged, destFile)
                            throw t
                        }
                    }
                }
                return Result.success(com.antigravity.filemanager.domain.model.ExtractResult(totalEntries, extractedCount, skippedCount))
            }
        } catch (e: Throwable) {
            deleteCreated(createdFiles)
            if (e is ArchivePasswordRequiredException || e is ArchiveInvalidPasswordException || e is CancellationException) throw e
            if (looksLikeRarPasswordError(e, includeCorruption = true)) throw rarPasswordException(archiveFile, password)
            throw e
        }
    }

    private suspend fun extractZipInternal(
        archiveFile: File,
        destDir: File,
        password: String?,
        overwriteNames: Set<String> = emptySet(),
        skipNames: Set<String> = emptySet(),
        onProgress: ((currentEntry: String, currentIndex: Int, totalEntries: Int, bytesProcessed: Long, totalBytes: Long) -> Unit)? = null
    ): Result<com.antigravity.filemanager.domain.model.ExtractResult> {
        val createdFiles = mutableListOf<File>()
        val zipFile = openZip(archiveFile, password)
        try {
            val targetCanonical = destDir.canonicalPath
            val targets = ExtractTargets(destDir, targetCanonical, overwriteNames, skipNames)
            val allHeaders = zipHeaders(zipFile, archiveFile)
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
                val normName = normalizeEntryName(header.fileName)
                val defaultDestFile = File(destDir, normName)
                if (!isInsideTarget(defaultDestFile, targetCanonical)) {
                    throw SecurityException("Zip Slip detected in archive: ${header.fileName}")
                }
                val resolvedDest = targets.resolve(normName)

                if (header.isDirectory) {
                    if (resolvedDest != null && !resolvedDest.exists()) {
                        resolvedDest.mkdirs()
                        createdFiles.add(resolvedDest)
                    }
                } else {
                    if (resolvedDest == null) {
                        skippedCount++
                        currentIndex++
                        bytesProcessed += header.uncompressedSize
                        onProgress?.invoke(normName, currentIndex, totalEntries, bytesProcessed, totalBytes)
                        continue
                    }
                    currentIndex++
                    val overwriteThis = targets.overwrites(normName)
                    val destFile = if (overwriteThis || !resolvedDest.exists()) {
                        resolvedDest
                    } else {
                        val parent = resolvedDest.parentFile ?: destDir
                        uniqueFile(parent, resolvedDest.name)
                    }
                    if (!destFile.exists()) {
                        createdFiles.add(destFile)
                    }
                    destFile.parentFile?.mkdirs()
                    val staged = stagingFileFor(destFile)
                    try {
                        zipFile.getInputStream(header).use { inStream ->
                            FileOutputStream(staged).use { outStream ->
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
                        commitStaged(staged, destFile)
                        extractedCount++
                        onProgress?.invoke(normName, currentIndex, totalEntries, bytesProcessed, totalBytes)
                    } catch (e: ZipException) {
                        discardStaged(staged, destFile)
                        if (isZipPasswordError(e)) {
                            throw ArchiveInvalidPasswordException(archiveFile.absolutePath)
                        }
                        throw e
                    } catch (t: Throwable) {
                        discardStaged(staged, destFile)
                        throw t
                    }
                }
            }
            return Result.success(com.antigravity.filemanager.domain.model.ExtractResult(totalEntries, extractedCount, skippedCount))
        } catch (t: Throwable) {
            deleteCreated(createdFiles)
            if (t is ZipException && isZipPasswordError(t)) throw ArchiveInvalidPasswordException(archiveFile.absolutePath)
            throw t
        } finally {
            try { zipFile.close() } catch (_: Exception) {}
        }
    }
}

private object DiscardingOutputStream : java.io.OutputStream() {
    override fun write(b: Int) {}
    override fun write(b: ByteArray, off: Int, len: Int) {}
}
