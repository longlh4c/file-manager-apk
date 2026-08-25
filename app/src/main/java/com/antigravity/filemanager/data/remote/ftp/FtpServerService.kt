package com.antigravity.filemanager.data.remote.ftp

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.antigravity.filemanager.MainActivity
import com.antigravity.filemanager.R
import com.antigravity.filemanager.domain.model.FtpServerState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class FtpServerService : Service() {

    @Inject
    lateinit var ftpServer: EmbeddedFtpServer

    private var wakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val ACTION_START = "ACTION_START_FTP"
        const val ACTION_STOP = "ACTION_STOP_FTP"
        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_PASSWORD = "EXTRA_PASSWORD"
        const val EXTRA_RANDOM_PASS = "EXTRA_RANDOM_PASS"
        const val EXTRA_SHOW_HIDDEN = "EXTRA_SHOW_HIDDEN"
        const val NOTIFICATION_CHANNEL_ID = "ftp_server_channel"
        const val NOTIFICATION_ID = 1524

        private val _ftpState = MutableStateFlow(FtpServerState())
        val ftpState: StateFlow<FtpServerState> = _ftpState.asStateFlow()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FileManager::FtpWakeLock")
        @Suppress("DEPRECATION")
        screenWakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "FileManager::FtpScreenWakeLock"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, 1524)
                val randomPass = intent.getBooleanExtra(EXTRA_RANDOM_PASS, false)
                val password = if (randomPass) generateRandomPassword() else intent.getStringExtra(EXTRA_PASSWORD) ?: ""
                val showHidden = intent.getBooleanExtra(EXTRA_SHOW_HIDDEN, false)

                startServer(port, password, randomPass, showHidden)
            }
            ACTION_STOP -> {
                stopServer()
            }
        }
        return START_NOT_STICKY
    }

    private fun startServer(port: Int, pass: String, random: Boolean, showHidden: Boolean) {
        serviceScope.launch {
            val ip = getLocalIpAddress()
            val success = ftpServer.start(port, pass, showHidden)
            if (success) {
                wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24h max
                screenWakeLock?.acquire(24 * 60 * 60 * 1000L) // keep screen from timing out while FTP is on
                _ftpState.value = FtpServerState(
                    isRunning = true,
                    ipAddress = ip,
                    port = port,
                    password = pass,
                    isRandomPassword = random,
                    showHiddenFiles = showHidden
                )
                startForegroundNotification("ftp://$ip:$port")
            } else {
                _ftpState.value = _ftpState.value.copy(isRunning = false)
                stopSelf()
            }
        }
    }

    private fun stopServer() {
        serviceScope.launch {
            ftpServer.stop()
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            if (screenWakeLock?.isHeld == true) {
                screenWakeLock?.release()
            }
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

    private fun getLocalIpAddress(): String {
        try {
            // 1. Check WifiManager first if connected to Wi-Fi
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null && wifiManager.isWifiEnabled) {
                val ipInt = wifiManager.connectionInfo.ipAddress
                if (ipInt != 0) {
                    return String.format(
                        Locale.US,
                        "%d.%d.%d.%d",
                        ipInt and 0xff,
                        ipInt shr 8 and 0xff,
                        ipInt shr 16 and 0xff,
                        ipInt shr 24 and 0xff
                    )
                }
            }

            // 2. Iterate network interfaces prioritizing Wi-Fi, Ethernet, Hotspot
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())

            // Priority 1: wlan, eth, ap, rndis interfaces
            for (intf in interfaces) {
                val name = intf.name.lowercase(Locale.US)
                if (intf.isUp && (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap") || name.startsWith("rndis"))) {
                    for (addr in Collections.list(intf.inetAddresses)) {
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            val host = addr.hostAddress
                            if (host != null && !host.startsWith("127.")) {
                                return host
                            }
                        }
                    }
                }
            }

            // Priority 2: Standard private subnets (192.168.x.x, 10.x.x.x, 172.16-31.x.x)
            for (intf in interfaces) {
                if (intf.isUp && !intf.isLoopback) {
                    for (addr in Collections.list(intf.inetAddresses)) {
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            val host = addr.hostAddress ?: continue
                            if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                                return host
                            }
                        }
                    }
                }
            }

            // Priority 3: Any non-loopback IPv4
            for (intf in interfaces) {
                if (intf.isUp && !intf.isLoopback) {
                    for (addr in Collections.list(intf.inetAddresses)) {
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            return addr.hostAddress ?: "127.0.0.1"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }

    private fun generateRandomPassword(): String {
        val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        return (1..6).map { chars.random() }.joinToString("")
    }

    override fun onDestroy() {
        ftpServer.stop()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (screenWakeLock?.isHeld == true) screenWakeLock?.release()
        _ftpState.value = _ftpState.value.copy(isRunning = false)
        serviceScope.cancel()
        super.onDestroy()
    }
}
