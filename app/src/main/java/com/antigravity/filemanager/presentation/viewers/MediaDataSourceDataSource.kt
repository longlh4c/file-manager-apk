package com.antigravity.filemanager.presentation.viewers

import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * Lets ExoPlayer read from an [android.media.MediaDataSource] — used for MEGA, whose content is
 * client-side encrypted so there is no plain URL to stream: the source fetches and decrypts just
 * the byte ranges requested (see MegaDecryptingDataSource), and this adapts it to ExoPlayer's
 * pull-based DataSource contract, including seeking to arbitrary positions.
 */
class MediaDataSourceDataSource(
    private val source: MediaDataSource,
    private val uri: Uri
) : BaseDataSource(/* isNetwork = */ true) {

    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val size = source.size
        if (dataSpec.position > size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        position = dataSpec.position
        val available = size - position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, available) else available
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = minOf(bytesRemaining, length.toLong()).toInt()
        val read = source.readAt(position, buffer, offset, toRead)
        // The source reports both "past the end" and "download failed" as -1; with bytes still
        // expected here it can only be a failure, which must not look like a clean end of file.
        if (read < 0) throw IOException("Failed to read $toRead bytes at $position")
        position += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri = uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
