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
            if (!targetFolder.exists()) targetFolder.mkdirs()

            // overwrite is decided once per top-level source (matching the pre-existing conflict
            // dialog, which only ever resolves top-level name clashes) and carried down to every
            // file underneath it — same effective behavior copyRecursively(overwrite=...) had.
            val fileEntries = mutableListOf<FileCopyEntry>()
            val dirEntries = mutableListOf<FileCopyEntry>()
            for (path in sourcePaths) {
                val source = File(path)
                if (!source.exists() || source.name in skipNames) continue
                if (source.isDirectory && isSameOrDescendant(targetFolder, source)) {
                    return@withContext Result.failure(IOException("Cannot copy a folder into itself: ${source.name}"))
                }
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
            if (!targetFolder.exists()) targetFolder.mkdirs()
            val scannedPaths = mutableListOf<String>()

            // Phase 1: renameTo() is atomic and effectively instant for a same-filesystem move —
            // even a folder with thousands of files inside moves in one O(1) call, so there's
            // nothing meaningful to report progress on here. Only a genuine cross-filesystem move
            // (internal storage <-> SD card, say) falls through to it failing, handled for real
            // in phase 2 below.
            val renameFailures = mutableListOf<FileCopyEntry>()
            for (path in sourcePaths) {
                currentCoroutineContext().ensureActive()
                val source = File(path)
                if (!source.exists() || source.name in skipNames) continue
                // renameTo() into its own subtree fails, and phase 2's copy-then-delete fallback
                // would then delete the freshly made copy together with the source.
                if (source.isDirectory && isSameOrDescendant(targetFolder, source)) {
                    return@withContext Result.failure(IOException("Cannot move a folder into itself: ${source.name}"))
                }
                val dest = resolveDestination(targetFolder, source, overwriteNames)
                // Moving a file onto itself is a no-op.
                if (dest.absolutePath == source.absolutePath) continue
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

    suspend fun getArchiveConflicts(
        archiveFilePath: String,
        targetDir: String,
        password: String? = null
    ): List<com.antigravity.filemanager.domain.model.OverwriteConflict> = withContext(Dispatchers.IO) {
        val archiveFile = File(archiveFilePath)
        if (!archiveFile.exists()) return@withContext emptyList()
        // Pre-validate password first so invalid password triggers immediately without creating any conflict/extract state
        validateArchivePassword(archiveFile, password)

        // (entry name, uncompressed size) for every file entry, whatever the format
        val fileEntries: List<Pair<String, Long>> = when (archiveFile.extension.lowercase(Locale.ROOT)) {
            "7z" -> openSevenZ(archiveFile, password).use { archive ->
                archive.entries.filter { !it.isDirectory }.map { it.name to it.size }
            }
            "rar" -> openRar(archiveFile, password).use { archive ->
                (archive.fileHeaders ?: emptyList()).filter { !it.isDirectory }.map { it.fileName to it.unpSize }
            }
            else -> openZip(archiveFile, password).use { zipFile ->
                zipHeaders(zipFile, archiveFile).filter { !it.isDirectory }.map { it.fileName to it.uncompressedSize }
            }
        }

        val destDir = File(targetDir)
        val targetCanonical = destDir.canonicalPath
        fileEntries.mapNotNull { (rawName, size) ->
            val normName = normalizeEntryName(rawName)
            val destFile = File(destDir, normName)
            if (!isInsideTarget(destFile, targetCanonical) || !destFile.exists()) return@mapNotNull null
            com.antigravity.filemanager.domain.model.OverwriteConflict(
                name = normName,
                existingSize = destFile.length(),
                newSize = size,
                isDirectory = false
            )
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
                            uniqueFile(parent, defaultDestFile.name)
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
                        } catch (t: Throwable) {
                            // The password was already validated up front, so any other I/O
                            // failure here (disk full, permission, corrupt entry) is reported as
                            // itself rather than as "incorrect password".
                            if (destFile.exists()) destFile.delete()
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
                            uniqueFile(parent, defaultDestFile.name)
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
                        uniqueFile(parent, defaultDestFile.name)
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
                        if (isZipPasswordError(e)) {
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
