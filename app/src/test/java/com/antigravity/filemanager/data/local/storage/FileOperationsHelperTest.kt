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
        assertTrue("nested empty folder survives", File(out, "docs/empty").isDirectory)

        val conflicts = helper.getArchiveConflicts(archive.absolutePath, out.absolutePath)
        // One conflict for the whole top-level folder, not one per file inside it.
        assertEquals(listOf("docs"), conflicts.map { it.name })
        assertTrue(conflicts.single().isDirectory)
    }

    /** An archive holding FolderA/inner.txt, extracted where FolderA/inner.txt already exists. */
    private fun folderArchiveWithExistingFolder(): Pair<File, File> {
        val src = File(root, "src/FolderA").apply { mkdirs() }
        File(src, "inner.txt").writeText("from-archive")
        File(src, "sub").mkdirs()
        val archive = File(root, "FolderA.zip")
        runBlocking { assertTrue(helper.compressFiles(listOf(src.absolutePath), archive.absolutePath).isSuccess) }
        val out = File(root, "out").apply { mkdirs() }
        File(out, "FolderA").mkdirs()
        File(out, "FolderA/inner.txt").writeText("existing")
        return archive to out
    }

    @Test
    fun extractKeepBothPutsTheFolderUnderANewName() = runBlocking {
        val (archive, out) = folderArchiveWithExistingFolder()

        val result = helper.extractArchive(archive.absolutePath, out.absolutePath)

        assertTrue(result.isSuccess)
        assertEquals("existing", File(out, "FolderA/inner.txt").readText())
        assertEquals(listOf("inner.txt"), File(out, "FolderA").list()!!.toList())
        assertEquals("from-archive", File(out, "FolderA (1)/inner.txt").readText())
        assertTrue(File(out, "FolderA (1)/sub").isDirectory)
    }

    @Test
    fun extractOverwriteMergesIntoTheExistingFolder() = runBlocking {
        val (archive, out) = folderArchiveWithExistingFolder()

        val result = helper.extractArchive(archive.absolutePath, out.absolutePath, overwriteNames = setOf("FolderA"))

        assertTrue(result.isSuccess)
        assertEquals("from-archive", File(out, "FolderA/inner.txt").readText())
        assertFalse(File(out, "FolderA (1)").exists())
    }

    @Test
    fun extractSkipLeavesTheWholeTreeOut() = runBlocking {
        val (archive, out) = folderArchiveWithExistingFolder()

        val result = helper.extractArchive(archive.absolutePath, out.absolutePath, skipNames = setOf("FolderA"))

        assertTrue(result.isSuccess)
        assertEquals("existing", File(out, "FolderA/inner.txt").readText())
        assertFalse(File(out, "FolderA/sub").exists())
        assertFalse(File(out, "FolderA (1)").exists())
    }

    @Test
    fun extractKeepBothRenamesATopLevelFile() = runBlocking {
        val plain = File(root, "note.txt").apply { writeText("new") }
        val archive = File(root, "note.zip")
        assertTrue(helper.compressFiles(listOf(plain.absolutePath), archive.absolutePath).isSuccess)
        val out = File(root, "out").apply { mkdirs() }
        File(out, "note.txt").writeText("old")

        assertTrue(helper.extractArchive(archive.absolutePath, out.absolutePath).isSuccess)

        assertEquals("old", File(out, "note.txt").readText())
        assertEquals("new", File(out, "note (1).txt").readText())
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

    /** docs/a.txt, docs/deep/b.txt and top.txt, zipped. */
    private fun browseArchive(): File {
        val src = File(root, "pack").apply { mkdirs() }
        File(src, "docs/deep").mkdirs()
        File(src, "docs/a.txt").writeText("A")
        File(src, "docs/deep/b.txt").writeText("B")
        File(src, "top.txt").writeText("T")
        val archive = File(root, "pack.zip")
        runBlocking {
            assertTrue(helper.compressFiles(listOf(File(src, "docs").absolutePath, File(src, "top.txt").absolutePath), archive.absolutePath).isSuccess)
        }
        return archive
    }

    @Test
    fun listArchiveEntriesReturnsEveryEntryWithoutTrailingSlashes() = runBlocking {
        val entries = helper.listArchiveEntries(browseArchive().absolutePath)

        val files = entries.filter { !it.isDirectory }.map { it.path }.toSet()
        assertEquals(setOf("docs/a.txt", "docs/deep/b.txt", "top.txt"), files)
        assertTrue(entries.none { it.path.endsWith("/") })
        assertEquals(1L, entries.single { it.path == "top.txt" }.size)
    }

    @Test
    fun extractEntriesWritesOnlyTheSelectionRelativeToTheBrowsedFolder() = runBlocking {
        val out = File(root, "out").apply { mkdirs() }

        val result = helper.extractArchiveEntries(browseArchive().absolutePath, listOf("docs/deep"), "docs", out.absolutePath)

        assertTrue(result.isSuccess)
        assertEquals(listOf("deep"), out.list()!!.toList())
        assertEquals("B", File(out, "deep/b.txt").readText())
    }

    @Test
    fun extractEntriesKeepsAnExistingItemAndUsesANumberedName() = runBlocking {
        val out = File(root, "out").apply { mkdirs() }
        File(out, "top.txt").writeText("mine")

        val result = helper.extractArchiveEntries(browseArchive().absolutePath, listOf("top.txt"), "", out.absolutePath)

        assertTrue(result.isSuccess)
        assertEquals("mine", File(out, "top.txt").readText())
        assertEquals("T", File(out, "top (1).txt").readText())
        assertEquals(listOf("top (1).txt"), result.getOrThrow().map { it.name })
    }
}
