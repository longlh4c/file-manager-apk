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
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

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
