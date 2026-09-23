package com.antigravity.filemanager.data.remote.http

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class UploadStreamTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "upload_test_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun writesExactlyTheAnnouncedBytesAndLeavesTheRestOfTheStream() {
        val body = ByteArray(300 * 1024) { (it % 251).toByte() }
        val input = ByteArrayInputStream(body + "NEXT REQUEST".toByteArray())

        val written = writeUploadStream(dir, "photo.jpg", input, body.size.toLong())

        assertEquals("photo.jpg", written.name)
        assertArrayEquals(body, written.readBytes())
        assertEquals("NEXT REQUEST", input.readBytes().decodeToString())
    }

    @Test
    fun keepsAnExistingFileAndUsesANumberedName() {
        File(dir, "a.txt").writeText("mine")

        val written = writeUploadStream(dir, "a.txt", ByteArrayInputStream("new".toByteArray()), 3)

        assertEquals("a (1).txt", written.name)
        assertEquals("mine", File(dir, "a.txt").readText())
        assertEquals("new", written.readText())
    }

    @Test
    fun aConnectionCutShortLeavesNoPartialFile() {
        val cutOff: InputStream = ByteArrayInputStream(ByteArray(10))

        try {
            writeUploadStream(dir, "big.bin", cutOff, 5_000)
            error("expected the short stream to fail")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("ended after"))
        }

        assertFalse(File(dir, "big.bin").exists())
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun anUploadLargerThanFreeSpaceIsRejectedBeforeWriting() {
        try {
            writeUploadStream(dir, "huge.bin", ByteArrayInputStream(ByteArray(0)), Long.MAX_VALUE / 2)
            error("expected it to refuse")
        } catch (e: NotEnoughSpaceException) {
            assertEquals(emptyList<String>(), dir.list()!!.toList())
        }
    }
}
