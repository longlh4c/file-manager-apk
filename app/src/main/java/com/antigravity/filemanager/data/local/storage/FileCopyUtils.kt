package com.antigravity.filemanager.data.local.storage

import java.io.File

/** One real file (or a wholly empty directory, which has no file to represent it and must be
 * mkdirs()'d directly instead) paired with the exact destination it lands at. Shared by every
 * "copy/move a folder tree" call site (FileOperationsHelper.copy/move, RecycleBinRepositoryImpl's
 * move-to-trash fallback) so a single large folder reports real per-file progress instead of one
 * opaque copyRecursively() call with nothing visible happening inside it. */
data class FileCopyEntry(val source: File, val dest: File)

/** Recursively flattens [source] (a lone file, or an entire folder tree) into [fileEntries] and
 * [dirEntries], mirroring the corresponding paths under [dest]. */
fun collectFileCopyEntries(source: File, dest: File, fileEntries: MutableList<FileCopyEntry>, dirEntries: MutableList<FileCopyEntry>) {
    if (source.isDirectory) {
        dirEntries.add(FileCopyEntry(source, dest))
        val children = source.listFiles()
        if (children != null) {
            for (child in children) {
                collectFileCopyEntries(child, File(dest, child.name), fileEntries, dirEntries)
            }
        } else {
            android.util.Log.w("FileCopyUtils", "source.listFiles() returned null for directory: ${source.absolutePath}")
        }
    } else if (source.exists()) {
        fileEntries.add(FileCopyEntry(source, dest))
    }
}

/** A collision-free file in [folder] for [name], appending " (1)", " (2)", ... before the
 * extension as needed. */
fun uniqueFile(folder: File, name: String): File {
    var candidate = File(folder, name)
    if (!candidate.exists()) return candidate
    val dotIndex = name.lastIndexOf('.')
    val base = if (dotIndex > 0) name.substring(0, dotIndex) else name
    val ext = if (dotIndex > 0) name.substring(dotIndex) else ""
    var counter = 1
    while (candidate.exists()) {
        candidate = File(folder, "$base ($counter)$ext")
        counter++
    }
    return candidate
}

/** Total size of every file under [dir] (or [dir]'s own length for a plain file); 0 on failure. */
fun directorySize(dir: File): Long = try {
    if (dir.isDirectory) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else dir.length()
} catch (e: Exception) {
    0L
}

/** Why [name] can't be used as a single file/folder name, or null when it's fine. */
fun invalidFileNameReason(name: String): String? = when {
    name.isBlank() -> "Name cannot be empty"
    name == "." || name == ".." -> "\"$name\" is not a valid name"
    name.contains('/') || name.contains(0.toChar()) -> "Name cannot contain \"/\""
    name.toByteArray().size > 255 -> "Name is too long"
    else -> null
}
