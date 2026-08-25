package com.antigravity.filemanager.data.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.antigravity.filemanager.MainActivity
import com.antigravity.filemanager.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * Foreground service that keeps the app process alive while a copy/move/upload/download is in
 * flight, so Android doesn't kill it mid-transfer when backgrounded. Started/stopped exclusively
 * through [TransferGuard], which reference-counts concurrent transfers.
 */
@AndroidEntryPoint
class TransferService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val ACTION_START = "ACTION_START_TRANSFER"
        const val ACTION_STOP = "ACTION_STOP_TRANSFER"
        const val NOTIFICATION_CHANNEL_ID = "transfer_channel"
        const val NOTIFICATION_ID = 1525
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FileManager::TransferWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (wakeLock?.isHeld != true) {
                    wakeLock?.acquire(6 * 60 * 60 * 1000L) // 6h safety cap
                }
                startForegroundNotification()
            }
            ACTION_STOP -> {
                if (wakeLock?.isHeld == true) wakeLock?.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.transfer_running_notification))
            .setContentText(getString(R.string.transfer_running_notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "File Transfers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification while a copy/move/upload/download is in progress"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }
}
