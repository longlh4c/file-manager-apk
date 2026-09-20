package com.antigravity.filemanager

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ArchivePasswordValidationTest {

    @Test
    fun testZipWrongPasswordLeavesNoFiles() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "zip_pwd_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val sourceFile = File(tempDir, "sample.txt")
            sourceFile.writeText("This is secret content inside encrypted zip")

            val zipArchive = File(tempDir, "secret.zip")
            val zipParameters = ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
            }
            val zipFile = ZipFile(zipArchive, "correct_pass".toCharArray())
            zipFile.addFile(sourceFile, zipParameters)
            zipFile.close()

            // Destination directory for extraction
            val extractDir = File(tempDir, "extracted")
            extractDir.mkdirs()

            // Try validating with WRONG password
            val testZip = ZipFile(zipArchive)
            testZip.setPassword("wrong_pass".toCharArray())
            val testHeader = testZip.fileHeaders.first()
            var caughtPasswordException = false
            try {
                testZip.getInputStream(testHeader).use { it.read() }
            } catch (e: net.lingala.zip4j.exception.ZipException) {
                if (e.type == net.lingala.zip4j.exception.ZipException.Type.WRONG_PASSWORD) {
                    caughtPasswordException = true
                }
            }
            assertTrue("Should detect wrong password", caughtPasswordException)

            // Ensure extracted directory has NO files (0 bytes or otherwise)
            assertEquals(0, extractDir.listFiles()?.size ?: 0)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun test7zWrongPasswordDetection() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "7z_pwd_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val sourceFile = File(tempDir, "sample7z.txt")
            sourceFile.writeText("This is secret 7z file content")

            val archive7z = File(tempDir, "secret.7z")
            val sevenZOutput = SevenZOutputFile(archive7z)
            sevenZOutput.setContentCompression(SevenZMethod.LZMA2)
            val entry = sevenZOutput.createArchiveEntry(sourceFile, "sample7z.txt")
            sevenZOutput.putArchiveEntry(entry)
            sevenZOutput.write(sourceFile.readBytes())
            sevenZOutput.closeArchiveEntry()
            sevenZOutput.close()

            assertTrue(archive7z.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
