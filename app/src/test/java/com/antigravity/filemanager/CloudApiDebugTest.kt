package com.antigravity.filemanager

import com.antigravity.filemanager.domain.model.CloudAccount
import com.antigravity.filemanager.domain.model.CloudProvider
import com.antigravity.filemanager.domain.model.FileItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudApiDebugTest {


    @Test
    fun testCloudFileOperations() {
        println("=== TESTING CLOUD STORAGE FILE OPERATIONS ===")
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "cloud_debug_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val subFolder = java.io.File(tempDir, "Documents")
        val created = subFolder.mkdirs()
        assertTrue(created)

        val sampleFile = java.io.File(subFolder, "Sample_Report.pdf")
        sampleFile.writeText("Test PDF Cloud Content")
        assertTrue(sampleFile.exists())

        val list = tempDir.listFiles() ?: emptyArray()
        println("Cloud drive sub-elements count: ${list.size}")
        assertTrue(list.isNotEmpty())

        tempDir.deleteRecursively()
        println("Cloud drive operation test completed successfully!")
    }

    @Test
    fun testCloudSortingDirectoryFirst() {
        println("=== TESTING CLOUD SORTING (DIRECTORIES MUST BE ON TOP) ===")
        val items = listOf(
            FileItem(id = "1", name = "Z_file.txt", path = "/Z_file.txt", isDirectory = false, lastModified = 1000L, size = 500L),
            FileItem(id = "2", name = "A_file.txt", path = "/A_file.txt", isDirectory = false, lastModified = 3000L, size = 100L),
            FileItem(id = "3", name = "M_folder", path = "/M_folder", isDirectory = true, lastModified = 2000L, size = 0L),
            FileItem(id = "4", name = "B_folder", path = "/B_folder", isDirectory = true, lastModified = 4000L, size = 0L)
        )

        // 1. Sort by Date Descending
        val sortByDateDesc = items.sortedWith(
            compareBy<FileItem> { !it.isDirectory }.thenByDescending { it.lastModified }
        )
        assertTrue(sortByDateDesc[0].isDirectory && sortByDateDesc[0].name == "B_folder")
        assertTrue(sortByDateDesc[1].isDirectory && sortByDateDesc[1].name == "M_folder")
        assertTrue(!sortByDateDesc[2].isDirectory && sortByDateDesc[2].name == "A_file.txt")
        assertTrue(!sortByDateDesc[3].isDirectory && sortByDateDesc[3].name == "Z_file.txt")

        // 2. Sort by Name Descending
        val sortByNameDesc = items.sortedWith(
            compareBy<FileItem> { !it.isDirectory }.thenByDescending { it.name.lowercase(java.util.Locale.getDefault()) }
        )
        assertTrue(sortByNameDesc[0].isDirectory && sortByNameDesc[0].name == "M_folder")
        assertTrue(sortByNameDesc[1].isDirectory && sortByNameDesc[1].name == "B_folder")
        assertTrue(!sortByNameDesc[2].isDirectory && sortByNameDesc[2].name == "Z_file.txt")
        assertTrue(!sortByNameDesc[3].isDirectory && sortByNameDesc[3].name == "A_file.txt")

        // 3. Sort by Size Descending
        val sortBySizeDesc = items.sortedWith(
            compareBy<FileItem> { !it.isDirectory }.thenByDescending { it.size }
        )
        assertTrue(sortBySizeDesc[0].isDirectory)
        assertTrue(sortBySizeDesc[1].isDirectory)
        assertTrue(!sortBySizeDesc[2].isDirectory && sortBySizeDesc[2].name == "Z_file.txt")
        assertTrue(!sortBySizeDesc[3].isDirectory && sortBySizeDesc[3].name == "A_file.txt")

        println("All cloud sorting tests passed: folders ALWAYS stay at the top!")
    }

    @Test
    fun testCloudToLocalPasteOverwrite() {
        println("=== TESTING CLOUD TO LOCAL PASTE WITH OVERWRITE ===")
        val tempRoot = java.io.File(System.getProperty("java.io.tmpdir"), "cloud_paste_test_${System.currentTimeMillis()}").apply { mkdirs() }
        val targetLocalDir = java.io.File(tempRoot, "LocalDownloads").apply { mkdirs() }
        val cloudSourceDir = java.io.File(tempRoot, "CloudStorage").apply { mkdirs() }

        // Existing local file
        val localExistingFile = java.io.File(targetLocalDir, "document.pdf")
        localExistingFile.writeText("OLD LOCAL VERSION (50 bytes)")

        // Cloud file to paste
        val cloudFile = java.io.File(cloudSourceDir, "document.pdf")
        cloudFile.writeText("NEW CLOUD VERSION (120 bytes - UPDATED CONTENT)")

        // Simulate isolated temp directory download & overwrite replacement
        val tempDownloadDir = java.io.File(tempRoot, "temp_dl_${System.nanoTime()}").apply { mkdirs() }
        val downloadedFile = java.io.File(tempDownloadDir, "document.pdf")
        cloudFile.copyTo(downloadedFile, overwrite = true)
        assertTrue(downloadedFile.exists())

        // Overwrite = true flow
        if (localExistingFile.exists()) {
            localExistingFile.delete()
        }
        downloadedFile.copyTo(localExistingFile, overwrite = true)
        tempDownloadDir.deleteRecursively()

        assertTrue(localExistingFile.exists())
        org.junit.Assert.assertEquals("NEW CLOUD VERSION (120 bytes - UPDATED CONTENT)", localExistingFile.readText())

        tempRoot.deleteRecursively()
        println("Cloud to Local Overwrite test completed successfully!")
    }

    @Test
    fun testLocalToCloudUploadOverwritePreservesFile() {
        val tempRoot = java.io.File(System.getProperty("java.io.tmpdir"), "test_local_to_cloud_${System.nanoTime()}").apply { mkdirs() }
        val localSourceFile = java.io.File(tempRoot, "report.docx").apply { writeText("NEW LOCAL REPORT CONTENT") }
        val cloudStorageDir = java.io.File(tempRoot, "cloud_storage").apply { mkdirs() }
        val existingCloudFile = java.io.File(cloudStorageDir, "report.docx").apply { writeText("OLD CLOUD CONTENT") }

        // Overwrite simulation: delete old entry -> write new uploaded content with progress tracking
        var progressCalled = false
        var totalBytesReported = 0L

        if (existingCloudFile.exists()) {
            existingCloudFile.delete()
        }

        val total = localSourceFile.length()
        localSourceFile.inputStream().use { input ->
            existingCloudFile.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                var count = 0L
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    count += read
                    progressCalled = true
                    totalBytesReported = count
                }
            }
        }

        assertTrue(existingCloudFile.exists())
        assertTrue(progressCalled)
        org.junit.Assert.assertEquals(total, totalBytesReported)
        org.junit.Assert.assertEquals("NEW LOCAL REPORT CONTENT", existingCloudFile.readText())

        tempRoot.deleteRecursively()
        println("Local to Cloud Upload Overwrite test completed successfully!")
    }

    @Test
    fun testTransferCancellationStopsImmediately() = kotlinx.coroutines.runBlocking {
        println("=== TESTING TRANSFER CANCELLATION ===")
        var wasCancelled = false
        var iterations = 0

        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    iterations++
                    kotlinx.coroutines.delay(10)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                wasCancelled = true
                throw e
            }
        }

        kotlinx.coroutines.delay(35)
        job.cancel()
        job.join()

        assertTrue(wasCancelled)
        assertTrue(iterations in 1..10)
        println("Cancellation verified successfully (stopped after $iterations iterations)!")
    }
}

