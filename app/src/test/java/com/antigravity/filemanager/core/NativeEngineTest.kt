package com.antigravity.filemanager.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NativeEngineTest {

    @Test
    fun testNativeEngineSafety() {
        // NativeEngine should not throw an exception on load failure on host JVM
        assertNotNull(NativeEngine)
        // On host Windows JVM running unit test, android .so is not loaded
        // NativeEngine.isAvailable should cleanly be boolean without crashing
        val available = NativeEngine.isAvailable
        if (!available) {
            val result = NativeEngine.analyzeStorage("/dummy/path", 1024L)
            assertNull(result)
            assertNull(NativeEngine.listArchive("/dummy.zip", null))
            assertNull(NativeEngine.extract("/dummy.zip", "/out", null))
            assertNull(NativeEngine.compress(listOf("/src"), "/out.zip"))
            assertNull(NativeEngine.scanDirectory("/dummy", false, false))
            assertNull(NativeEngine.searchStorage("/dummy", "test"))
            assertNull(NativeEngine.calculateChecksums("/dummy"))
            assertNull(NativeEngine.inspectApk("/dummy.apk"))
            assertNull(NativeEngine.scanDocumentsFast(listOf("/dummy"), setOf("pdf")))
            assertNull(NativeEngine.inspectAudio("/dummy.mp3"))
            assertNull(NativeEngine.inspectImage("/dummy.jpg"))
            assertNull(NativeEngine.inspectVideo("/dummy.mp4"))
            assertNull(NativeEngine.getEmbeddedThumbnail("/dummy.jpg"))
            assertNull(NativeEngine.getVideoPoster("/dummy.mp4"))
            assertNull(NativeEngine.getAudioCover("/dummy.mp3"))

            try {
                NativeEngine.resizeImage("/src", "/dst", 100, 100, 85, "jpg", 1)
            } catch (e: UnsatisfiedLinkError) {
                // Expected when native lib is not loaded in host JVM test
            }
        }
    }
}
