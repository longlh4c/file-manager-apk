package com.antigravity.filemanager.utils

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Where a cloud file's full local copy (viewer/preview cache) lives. Keyed by the item's remote
 * path, not just its name: two folders routinely hold same-named files (every camera's
 * IMG_0001.jpg), and a name-only key showed one folder's file as the other's thumbnail/preview. */
object CloudDownloadCache {

    fun accountDir(context: Context, accountId: String): File =
        File(context.cacheDir, "cloud_downloads/$accountId")

    /** Directory holding the cached copy of [remotePath]; download into it to populate the cache. */
    fun dirFor(context: Context, accountId: String, remotePath: String): File =
        File(accountDir(context, accountId), pathKey(remotePath))

    /** The cached copy of [remotePath] (named after the file itself, so openers see a real name). */
    fun fileFor(context: Context, accountId: String, remotePath: String, fileName: String): File =
        File(dirFor(context, accountId, remotePath), fileName)

    private fun pathKey(remotePath: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(remotePath.toByteArray())
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }
}
