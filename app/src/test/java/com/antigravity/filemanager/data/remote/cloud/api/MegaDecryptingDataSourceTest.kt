package com.antigravity.filemanager.data.remote.cloud.api

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MegaDecryptingDataSourceTest {

    private val key = ByteArray(16) { (it * 7 + 3).toByte() }
    private val nonce = ByteArray(8) { (it + 100).toByte() }

    private fun plaintext(size: Int) = ByteArray(size) { ((it * 31) xor (it shr 8)).toByte() }

    // Encrypts the way MEGA does (AES-CTR, counter in the low 8 bytes after the nonce).
    private fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(16)
        nonce.copyInto(iv, 0)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(plain)
    }

    /** A fake MEGA download host: serves slices of [cipher] for "Range: bytes=a-b" requests. */
    private fun fakeHost(cipher: ByteArray, requests: MutableList<String>): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val range = req.header("Range")!!
            requests.add(range)
            val (a, b) = range.removePrefix("bytes=").split("-").map { it.toInt() }
            val slice = cipher.copyOfRange(a, minOf(b + 1, cipher.size))
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(206)
                .message("Partial Content")
                .body(slice.toResponseBody("application/octet-stream".toMediaTypeOrNull()))
                .build()
        }.build()

    private fun source(plain: ByteArray, window: Long, requests: MutableList<String>) =
        MegaDecryptingDataSource("https://mega.test/dl", key, nonce, plain.size.toLong(), fakeHost(encrypt(plain), requests), window)

    private fun MegaDecryptingDataSource.read(position: Long, size: Int): ByteArray {
        val buf = ByteArray(size)
        val n = readAt(position, buf, 0, size)
        return buf.copyOf(maxOf(n, 0))
    }

    @Test
    fun readsDecryptToOriginalBytesAtUnalignedPositions() {
        val plain = plaintext(300_000)
        val ds = source(plain, MegaDecryptingDataSource.DEFAULT_FETCH_WINDOW, mutableListOf())
        for (pos in listOf(0L, 1L, 15L, 16L, 17L, 4_099L, 123_457L)) {
            assertArrayEquals("position $pos", plain.copyOfRange(pos.toInt(), pos.toInt() + 5_000), ds.read(pos, 5_000))
        }
    }

    @Test
    fun sequentialReadsAcrossWindowsReproduceTheWholeFile() {
        val plain = plaintext(1_000_003) // not a multiple of 16 on purpose
        val requests = mutableListOf<String>()
        val window = 64L * 1024
        val ds = source(plain, window, requests)
        val out = java.io.ByteArrayOutputStream()
        var pos = 0L
        while (pos < plain.size) {
            val chunk = ds.read(pos, 10_000)
            if (chunk.isEmpty()) break
            out.write(chunk)
            pos += chunk.size
        }
        assertArrayEquals(plain, out.toByteArray())
        // Reads are served from the fetched window, so requests stay far below one per read.
        assertEquals(true, requests.size <= plain.size / window + 2)
    }

    @Test
    fun readPastTheEndReturnsEndOfData() {
        val plain = plaintext(1_000)
        val ds = source(plain, MegaDecryptingDataSource.PLAYBACK_FETCH_WINDOW, mutableListOf())
        assertEquals(-1, ds.readAt(1_000, ByteArray(10), 0, 10))
        assertEquals(1_000L, ds.size)
    }
}
