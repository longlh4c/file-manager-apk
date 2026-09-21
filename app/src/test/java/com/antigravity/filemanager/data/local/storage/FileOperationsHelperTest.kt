package com.antigravity.filemanager.data.local.storage

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class FileOperationsHelperTest {

    private lateinit var root: File
    // android.jar stubs return defaults in unit tests, so a bare ContextWrapper is enough for the
    // MediaScannerConnection calls to be harmless no-ops.
    private val helper = FileOperationsHelper(ContextWrapper(null))

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "fops_test_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun moveFolderIntoItsOwnSubfolderIsRejectedAndKeepsData() = runBlocking {
        val folder = File(root, "A").apply { mkdirs() }
        File(folder, "keep.txt").writeText("data")
        val sub = File(folder, "sub").apply { mkdirs() }

        val result = helper.move(listOf(folder.absolutePath), sub.absolutePath)

        assertTrue(result.isFailure)
        assertEquals("data", File(folder, "keep.txt").readText())
    }

    @Test
    fun copyKeepsBothOnNameClash() = runBlocking {
        val src = File(root, "src").apply { mkdirs() }
        val dst = File(root, "dst").apply { mkdirs() }
        File(src, "a.txt").writeText("new")
        File(dst, "a.txt").writeText("old")

        val result = helper.copy(listOf(File(src, "a.txt").absolutePath), dst.absolutePath)

        assertTrue(result.isSuccess)
        assertEquals("old", File(dst, "a.txt").readText())
        assertEquals("new", File(dst, "a (1).txt").readText())
    }

    @Test
    fun copyOntoItselfWithOverwriteIsANoOp() = runBlocking {
        val file = File(root, "same.txt").apply { writeText("content") }

        val result = helper.copy(listOf(file.absolutePath), root.absolutePath, overwriteNames = setOf("same.txt"))

        assertTrue(result.isSuccess)
        assertEquals("content", file.readText())
    }

    @Test
    fun moveRelocatesFolderTree() = runBlocking {
        val folder = File(root, "tree").apply { mkdirs() }
        File(folder, "nested").mkdirs()
        File(folder, "nested/f.txt").writeText("x")
        val dst = File(root, "dst").apply { mkdirs() }

        val result = helper.move(listOf(folder.absolutePath), dst.absolutePath)

        assertTrue(result.isSuccess)
        assertFalse(folder.exists())
        assertEquals("x", File(dst, "tree/nested/f.txt").readText())
    }

    @Test
    fun zipRoundTripAndConflictDetection() = runBlocking {
        val src = File(root, "docs").apply { mkdirs() }
        File(src, "one.txt").writeText("1")
        File(src, "empty").mkdirs()
        val archive = File(root, "docs.zip")

        assertTrue(helper.compressFiles(listOf(src.absolutePath), archive.absolutePath).isSuccess)

        val out = File(root, "out").apply { mkdirs() }
        val extracted = helper.extractArchive(archive.absolutePath, out.absolutePath)
        assertTrue(extracted.isSuccess)
        assertEquals("1", File(out, "docs/one.txt").readText())

        val conflicts = helper.getArchiveConflicts(archive.absolutePath, out.absolutePath)
        assertEquals(listOf("docs/one.txt"), conflicts.map { it.name })
    }

    @Test
    fun encryptedZipRequiresTheRightPassword() = runBlocking {
        val plain = File(root, "secret.txt").apply { writeText("s3cret") }
        val archive = File(root, "secret.zip")
        ZipFile(archive, "pw".toCharArray()).use {
            it.addFile(plain, ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
            })
        }
        val out = File(root, "out").apply { mkdirs() }

        assertTrue(helper.isArchiveEncrypted(archive.absolutePath))
        assertTrue(helper.extractArchive(archive.absolutePath, out.absolutePath).exceptionOrNull() is ArchivePasswordRequiredException)
        assertTrue(helper.extractArchive(archive.absolutePath, out.absolutePath, "nope").exceptionOrNull() is ArchiveInvalidPasswordException)
        assertEquals(0, out.listFiles()?.size ?: 0)

        assertTrue(helper.extractArchive(archive.absolutePath, out.absolutePath, "pw").isSuccess)
        assertEquals("s3cret", File(out, "secret.txt").readText())
    }

    @Test
    fun renameOntoExistingNameIsRejectedAndKeepsBothFiles() = runBlocking {
        val a = File(root, "a.txt").apply { writeText("A") }
        val b = File(root, "b.txt").apply { writeText("B") }

        val result = helper.rename(a.absolutePath, "b.txt")

        assertTrue(result.isFailure)
        assertEquals("A", a.readText())
        assertEquals("B", b.readText())
    }

    @Test
    fun renameRejectsInvalidNames() = runBlocking {
        val a = File(root, "a.txt").apply { writeText("A") }
        for (bad in listOf("", "  ", ".", "..", "x/y")) {
            assertTrue("'$bad'", helper.rename(a.absolutePath, bad).isFailure)
        }
        assertTrue(a.exists())
        assertTrue(helper.rename(a.absolutePath, "renamed.txt").isSuccess)
        assertEquals("A", File(root, "renamed.txt").readText())
    }

    @Test
    fun createDirectoryFailsWhenAFileHasThatName() = runBlocking {
        File(root, "taken").writeText("file")
        assertTrue(helper.createDirectory(root.absolutePath, "taken").isFailure)
        assertTrue(helper.createDirectory(root.absolutePath, "a/b").isFailure)
        assertTrue(helper.createDirectory(root.absolutePath, "fresh").isSuccess)
    }

    @Test
    fun uniqueFileAppendsCounterBeforeExtension() {
        File(root, "x.tar.gz").writeText("")
        File(root, "x.tar (1).gz").writeText("")
        assertEquals("x.tar (2).gz", uniqueFile(root, "x.tar.gz").name)
        assertEquals("fresh", uniqueFile(root, "fresh").name)
    }
}
