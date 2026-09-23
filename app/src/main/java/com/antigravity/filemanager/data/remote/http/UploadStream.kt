package com.antigravity.filemanager.data.remote.http

import com.antigravity.filemanager.data.local.storage.uniqueFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** Not enough room on the device for an upload of this size. */
class NotEnoughSpaceException(val required: Long, val available: Long) :
    IOException("Needs ${required / (1024 * 1024)} MB, only ${available / (1024 * 1024)} MB free")

/**
 * Writes [length] bytes of [input] into [targetDir] under [fileName], or a numbered name when
 * that one is taken — the whole upload, straight from the connection.
 *
 * This is what a large upload needs: the alternative (letting the HTTP library parse a multipart
 * body) buffers the entire request into a temp file and then memory-maps it, which needs twice
 * the free space and cannot handle a body of 2 GiB or more at all — FileChannel.map() rejects
 * anything that big, so the request died without a reply and the browser sat at 100% forever.
 *
 * A stream that ends early, or any failure, leaves no partial file behind.
 */
fun writeUploadStream(targetDir: File, fileName: String, input: InputStream, length: Long): File {
    if (!targetDir.exists()) targetDir.mkdirs()
    val available = targetDir.usableSpace
    // A little room to spare, so the device isn't left completely full.
    if (length > 0 && available in 0 until length + 8L * 1024 * 1024) {
        throw NotEnoughSpaceException(length, available)
    }
    val destination = uniqueFile(targetDir, fileName)
    var written = 0L
    try {
        FileOutputStream(destination).use { out ->
            val buffer = ByteArray(256 * 1024)
            while (written < length) {
                val wanted = minOf(buffer.size.toLong(), length - written).toInt()
                val read = input.read(buffer, 0, wanted)
                if (read < 0) throw IOException("Upload ended after $written of $length bytes")
                out.write(buffer, 0, read)
                written += read
            }
        }
    } catch (e: Throwable) {
        destination.delete()
        throw e
    }
    return destination
}
