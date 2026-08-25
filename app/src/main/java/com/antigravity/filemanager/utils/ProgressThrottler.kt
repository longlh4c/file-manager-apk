package com.antigravity.filemanager.utils

/**
 * Coalesces a high-frequency progress callback (fired per I/O chunk, e.g. every 64KB) down to a
 * UI-friendly rate. Without this, transferring a large file recomposes/updates state on every
 * chunk — far more often than any UI can usefully render.
 */
class ProgressThrottler(private val minIntervalMs: Long = 80L) {
    private var lastEmitMs = 0L

    /** Call once per chunk; returns true only when this update should actually be published. */
    fun shouldEmit(bytesTransferred: Long, totalBytes: Long): Boolean {
        val now = System.currentTimeMillis()
        val isComplete = totalBytes > 0 && bytesTransferred >= totalBytes
        if (isComplete || now - lastEmitMs >= minIntervalMs) {
            lastEmitMs = now
            return true
        }
        return false
    }
}
