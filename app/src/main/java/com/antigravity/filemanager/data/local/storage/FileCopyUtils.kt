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

/** Why an archive can't be written to [targetArchivePath] from [sourcePaths], or null when it
 * can. Compressing a single "backup.zip" offers "backup.zip" as the archive name; confirming the
 * overwrite prompt deleted the source and produced an empty archive in its place. */
fun archiveTargetConflictReason(targetArchivePath: String, sourcePaths: List<String>): String? {
    val target = File(targetArchivePath).canonicalFile
    val clash = sourcePaths.map { File(it).canonicalFile }.firstOrNull { source ->
        target == source || target.path.startsWith(source.path + File.separator)
    } ?: return null
    return if (clash == target) "\"${target.name}\" is one of the items being compressed. Choose another name."
    else "The archive can't be saved inside \"${clash.name}\", which is being compressed."
}

/** True for a path outside the device's main shared storage, such as a USB drive or SD card.
 * The Recycle Bin lives on main storage, so moving such a file there really means copying all of
 * it across (which fails on a nearly full phone); files there are deleted permanently instead. */
fun isOutsidePrimaryStorage(path: String): Boolean {
    val primary = android.os.Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
    val p = File(path).absolutePath
    val onPrimary = p == primary || p.startsWith("$primary/") || p == "/sdcard" || p.startsWith("/sdcard/")
    // The app-clone space shares the phone's own storage, so trashing from it costs no space.
    return !onPrimary && !isCloneStoragePath(p)
}

/** User ids of the "App clone" / "Dual apps" space: 999 on Vivo, Xiaomi, OPPO and realme, 95 for
 * Samsung's Dual Messenger. Private spaces (Vivo XSpace, Xiaomi Second Space) are deliberately not
 * listed: their files belong to a separate, locked profile. */
private val CLONE_USER_IDS = listOf("999", "95")

/** Storage roots of the app-clone space that exist beside this user's storage, e.g.
 * /storage/emulated/999 next to /storage/emulated/0. */
private fun cloneStorageRoots(): List<File> {
    val primary = android.os.Environment.getExternalStorageDirectory()
    val parent = primary.parentFile ?: return emptyList()
    return CLONE_USER_IDS.filter { it != primary.name }.map { File(parent, it) }
}

/** True for a path inside the app-clone space's storage. */
fun isCloneStoragePath(path: String): Boolean {
    val p = File(path).absolutePath
    return cloneStorageRoots().any { p.startsWith(it.absolutePath + "/") }
}

/** The app-clone space's Download folders that can be read. A cloned app (a second Messenger or
 * Zalo, say) saves its downloads there instead of in the main Download folder, where they were
 * only ever found through the Documents/Images category lists. */
fun cloneDownloadDirs(): List<File> = cloneStorageRoots()
    .map { File(it, android.os.Environment.DIRECTORY_DOWNLOADS) }
    .filter { it.isDirectory && it.canRead() }

/** The main Download folder, whose listing also shows [cloneDownloadDirs]. */
fun isPrimaryDownloadDir(path: String): Boolean =
    File(path).absolutePath.trimEnd('/') ==
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).absolutePath.trimEnd('/')

/** Why [name] can't be used as a single file/folder name, or null when it's fine. */
fun invalidFileNameReason(name: String): String? = when {
    name.isBlank() -> "Name cannot be empty"
    name == "." || name == ".." -> "\"$name\" is not a valid name"
    name.contains('/') || name.contains(0.toChar()) -> "Name cannot contain \"/\""
    name.toByteArray().size > 255 -> "Name is too long"
    else -> null
}
