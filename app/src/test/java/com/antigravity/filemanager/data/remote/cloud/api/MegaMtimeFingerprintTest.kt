package com.antigravity.filemanager.data.remote.cloud.api

import android.content.ContextWrapper
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class MegaMtimeFingerprintTest {

    private val client = MegaApiClient(ContextWrapper(null), OkHttpClient())

    @Test
    fun parseMtimeFromFingerprintReturnsExpectedMtime() {
        val testFile = File.createTempFile("mega_mtime_test", ".tmp").apply {
            writeText("Hello MEGA mtime test content")
            deleteOnExit()
        }

        val expectedMtimeSec = 1712345678L // Fri Apr 05 2024 19:34:38 GMT
        // Invoke private computeMegaFingerprint via reflection
        val method = MegaApiClient::class.java.getDeclaredMethod(
            "computeMegaFingerprint",
            File::class.java,
            Long::class.javaPrimitiveType
        )
        method.isAccessible = true
        val fingerprint = method.invoke(client, testFile, expectedMtimeSec) as String

        assertNotNull(fingerprint)
        val extractedMtime = client.parseMtimeFromFingerprint(fingerprint)
        assertEquals(expectedMtimeSec, extractedMtime)
    }

    @Test
    fun parseMtimeHandlesEmptyOrInvalidFingerprints() {
        assertNull(client.parseMtimeFromFingerprint(""))
        assertNull(client.parseMtimeFromFingerprint("invalid-base64!!!"))
        assertNull(client.parseMtimeFromFingerprint("AAAA")) // Too short
    }
}
