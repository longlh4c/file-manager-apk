package com.antigravity.filemanager.data.remote.cloud.api

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.util.Random

/** uploadFile streams the file through MegaChunkedMac in 64 KB pieces instead of one buffer;
 * MEGA rejects/corrupts the node if the resulting MAC differs, so the two must agree exactly. */
class MegaChunkedMacTest {

    private val macClass = Class.forName("com.antigravity.filemanager.data.remote.cloud.api.MegaApiClient\$MegaChunkedMac")

    private fun newMac(key: ByteArray, nonce: ByteArray): Any =
        macClass.getDeclaredConstructor(ByteArray::class.java, ByteArray::class.java)
            .apply { isAccessible = true }
            .newInstance(key, nonce)

    private fun update(mac: Any, data: ByteArray) {
        macClass.getDeclaredMethod("update", ByteArray::class.java).apply { isAccessible = true }.invoke(mac, data)
    }

    private fun condense(mac: Any): ByteArray =
        macClass.getDeclaredMethod("condense").apply { isAccessible = true }.invoke(mac) as ByteArray

    @Test
    fun chunkedUpdatesMatchSingleUpdate() {
        val random = Random(7)
        val key = ByteArray(16).also { random.nextBytes(it) }
        val nonce = ByteArray(8).also { random.nextBytes(it) }
        // Spans several MAC segments (128 KB, 256 KB, ...) and ends on a partial block.
        for (size in listOf(0, 5, 16, 64 * 1024, 3 * 1024 * 1024 + 37)) {
            val data = ByteArray(size).also { random.nextBytes(it) }

            val whole = newMac(key, nonce).also { update(it, data) }.let { condense(it) }

            val streamed = newMac(key, nonce)
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + 64 * 1024, data.size)
                update(streamed, data.copyOfRange(offset, end))
                offset = end
            }

            assertArrayEquals("size=$size", whole, condense(streamed))
        }
    }
}
