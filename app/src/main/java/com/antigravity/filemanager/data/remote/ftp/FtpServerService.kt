package com.antigravity.filemanager.data.remote.ftp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.antigravity.filemanager.MainActivity
import com.antigravity.filemanager.R
import com.antigravity.filemanager.data.local.preferences.PreferenceManager
import com.antigravity.filemanager.domain.model.FtpServerState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Foreground service wrapping [EmbeddedFtpServer] — owns everything about staying alive and
 * reachable while it runs (wake/WiFi locks via [FtpPowerLocks], the persistent notification, LAN
 * IP resolution) that the embedded server itself has no business knowing about. */
@AndroidEntryPoint
class FtpServerService : Service() {

    @Inject
    lateinit var ftpServer: EmbeddedFtpServer

    @Inject
    lateinit var preferenceManager: PreferenceManager

    private lateinit var powerLocks: FtpPowerLocks
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val ACTION_START = "ACTION_START_FTP"
        const val ACTION_STOP = "ACTION_STOP_FTP"
        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_PASSWORD = "EXTRA_PASSWORD"
        const val EXTRA_RANDOM_PASS = "EXTRA_RANDOM_PASS"
        const val NOTIFICATION_CHANNEL_ID = "ftp_server_channel"
        const val NOTIFICATION_ID = 1524

        private val _ftpState = MutableStateFlow(FtpServerState())
        val ftpState: StateFlow<FtpServerState> = _ftpState.asStateFlow()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        powerLocks = FtpPowerLocks(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, 1524)
                val randomPass = intent.getBooleanExtra(EXTRA_RANDOM_PASS, false)
                val password = if (randomPass) generateRandomFtpPassword() else intent.getStringExtra(EXTRA_PASSWORD) ?: ""

                startServer(port, password, randomPass)
            }
            ACTION_STOP -> {
                stopServer()
            }
            else -> {
                // A null (or otherwise unrecognized) intent is how Android redelivers a
                // START_STICKY service after the system killed its process — there's no
                // ACTION_START intent to read port/password from this time, only whatever was
                // last persisted. Re-launch automatically ONLY if the server was actually left
                // running (not explicitly stopped) before the kill — otherwise every ordinary
                // app-swipe-to-close would resurrect a server the user turned off on purpose.
                if (intent == null) {
                    serviceScope.launch {
                        if (preferenceManager.ftpWasRunningFlow.first()) {
                            val port = preferenceManager.ftpPortFlow.first()
                            val password = preferenceManager.ftpPasswordFlow.first()
                            startServer(port, password, random = false)
                        }
                    }
                }
            }
        }
        // Was START_NOT_STICKY: if the OS (or an OEM battery manager) killed this process while
        // the FTP server was on, nothing brought the listening socket back — the app's own UI
        // still showed "running" from whatever it last observed, but WinSCP (or any client)
        // trying to connect got a flat "connection refused" since nothing was actually listening
        // anymore, with no way to tell without checking logcat. START_STICKY tells Android to
        // relaunch this service after such a kill (redelivering a null intent, handled above);
        // this alone can't help against an OEM-specific kill that also blocks that relaunch
        // outright (that needs the battery/background-permission settings already advised
        // elsewhere), but it does recover from an ordinary Android low-memory kill on its own.
        return START_STICKY
    }

    private fun startServer(port: Int, pass: String, random: Boolean) {
        serviceScope.launch {
            val ip = resolveLocalIpAddress(this@FtpServerService)
            val success = ftpServer.start(port, pass, externalIpAddress = ip)
            if (success) {
                powerLocks.acquire()
                _ftpState.value = FtpServerState(
                    isRunning = true,
                    ipAddress = ip,
                    port = port,
                    password = pass,
                    isRandomPassword = random
                )
                preferenceManager.setFtpWasRunning(true)
                startForegroundNotification("ftp://$ip:$port")
            } else {
                _ftpState.value = _ftpState.value.copy(isRunning = false)
                preferenceManager.setFtpWasRunning(false)
                stopSelf()
            }
        }
    }

    private fun stopServer() {
        serviceScope.launch {
            ftpServer.stop()
            preferenceManager.setFtpWasRunning(false)
            powerLocks.release()
            _ftpState.value = _ftpState.value.copy(isRunning = false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startForegroundNotification(url: String) {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.service_running_notification))
            .setContentText(url)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
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
                "FTP Server Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification while FTP Server is running"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        ftpServer.stop()
        powerLocks.release()
        _ftpState.value = _ftpState.value.copy(isRunning = false)
        serviceScope.cancel()
        super.onDestroy()
    }
}
