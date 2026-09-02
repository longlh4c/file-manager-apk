package com.antigravity.filemanager.data.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Snapshot of the currently running transfer, shown as a progress bar on [TransferService]'s
 * notification. `null` means no progress info is available yet (service falls back to its static
 * "Transfer running" text) — callers update this from the same throttled callback they already
 * use to drive their own in-app progress UI, so it costs nothing extra to keep it current. */
data class TransferProgressInfo(
    val currentFileName: String,
    val currentIndex: Int,
    val totalFiles: Int,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val isUpload: Boolean,
    // Same override as CloudTransferProgress.operationLabel — a non-upload/download operation
    // (Deleting, Restoring, Moving...) that still reuses this notification/progress plumbing sets
    // this so the notification text says what's actually happening instead of defaulting to the
    // generic "Uploading"/"Downloading" derived from `isUpload` alone.
    val operationLabel: String? = null
)

/**
 * Reference-counts in-flight copy/move/upload/download operations and keeps [TransferService]
 * running as a foreground service for as long as at least one is active, so the app process
 * survives being backgrounded mid-transfer. Concurrent transfers share the same service.
 */
@Singleton
class TransferGuard @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val activeCount = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingStop: Job? = null

    private val _progress = MutableStateFlow<TransferProgressInfo?>(null)
    val progress: StateFlow<TransferProgressInfo?> = _progress.asStateFlow()

    /** Reports current-file progress for the notification's progress bar. Safe to call from any
     * throttled progress callback — this is just a StateFlow write, no I/O. */
    fun updateProgress(info: TransferProgressInfo) {
        _progress.value = info
    }

    // Without this grace period, back-to-back operations (paste a batch, then immediately paste
    // another) each tear the service down and start a new one — and if a fresh begin() lands
    // while the previous end()'s stopSelf() is still being processed, Android can decide the
    // service instance is "being brought down" while a startForeground() obligation from the new
    // start is still outstanding, and kills the whole app with
    // ForegroundServiceDidNotStartInTimeException. Delaying the actual stop lets a near-immediate
    // next begin() cancel it instead of racing a stop against a start on the same service.
    private val stopGraceMs = 1500L

    fun begin() {
        pendingStop?.cancel()
        pendingStop = null
        if (activeCount.getAndIncrement() == 0) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = TransferService.ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    fun end() {
        val remaining = activeCount.decrementAndGet()
        if (remaining <= 0) {
            activeCount.set(0)
            // Clear progress the moment the last transfer actually ends — not after the
            // service-stop grace delay below. That delay exists purely to avoid tearing down and
            // restarting the foreground service on back-to-back transfers; it has nothing to do
            // with whether stale progress should still be displayed. Leaving this on the delayed
            // path meant cancelling a transfer (which calls end() for whatever was in flight) left
            // the last-known progress sitting in this StateFlow for up to stopGraceMs — long enough
            // for a screen that mirrors this flow into its own UI (see CloudExplorerViewModel/
            // FileBrowserViewModel) to redraw the "cancelled" progress bar right back onto screen.
            _progress.value = null
            pendingStop?.cancel()
            pendingStop = scope.launch {
                delay(stopGraceMs)
                if (activeCount.get() <= 0) {
                    val intent = Intent(context, TransferService::class.java).apply {
                        action = TransferService.ACTION_STOP
                    }
                    context.startService(intent)
                }
            }
        }
    }
}
