package com.antigravity.filemanager.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
            assert(result == null)
            try {
                NativeEngine.resizeImage("/src", "/dst", 100, 100, 85, "jpg", 1)
            } catch (e: UnsatisfiedLinkError) {
                // Expected when native lib is not loaded in host JVM test
            }
        }
    }
}
