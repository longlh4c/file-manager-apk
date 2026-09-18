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
