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
import com.antigravity.filemanager.data.remote.http.EmbeddedHttpServer
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@AndroidEntryPoint
class FtpServerService : Service() {

    @Inject
    lateinit var ftpServer: EmbeddedFtpServer

    @Inject
    lateinit var httpServer: EmbeddedHttpServer

    @Inject
    lateinit var preferenceManager: PreferenceManager

    private lateinit var powerLocks: FtpPowerLocks
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Start and stop run as separate coroutines; this keeps a quick stop->start (or a start with
    // new settings) from interleaving halfway through each other.
    private val lifecycleMutex = Mutex()

    companion object {
        const val ACTION_START = "ACTION_START_FTP"
        const val ACTION_STOP = "ACTION_STOP_FTP"
        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_HTTP_PORT = "EXTRA_HTTP_PORT"
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
        (getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)
            ?.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, 1524)
                val httpPort = intent.getIntExtra(EXTRA_HTTP_PORT, 8080)
                val randomPass = intent.getBooleanExtra(EXTRA_RANDOM_PASS, false)
                val password = if (randomPass) generateRandomFtpPassword() else intent.getStringExtra(EXTRA_PASSWORD) ?: ""

                startServer(port, httpPort, password, randomPass)
            }
            ACTION_STOP -> {
                stopServer()
            }
            else -> {
                if (intent == null) {
                    serviceScope.launch {
                        if (preferenceManager.ftpWasRunningFlow.first()) {
                            val port = preferenceManager.ftpPortFlow.first()
                            val httpPort = preferenceManager.httpPortFlow.first()
                            val password = preferenceManager.ftpPasswordFlow.first()
                            startServer(port, httpPort, password, random = false)
                        } else {
                            // Sticky restart with nothing to resume: don't linger as an idle service.
                            stopSelf()
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startServer(port: Int, httpPort: Int, pass: String, random: Boolean) {
        launchLocked { startLocked(port, httpPort, pass, random) }
    }

    /** Must run under [lifecycleMutex] (see [launchLocked]). */
    private suspend fun startLocked(port: Int, httpPort: Int, pass: String, random: Boolean) {
        run {
            // start() on an already-running server is a no-op that keeps the OLD port/password,
            // while the state below would advertise the new ones — restart so they match.
            if (ftpServer.isRunning) ftpServer.stop()
            if (httpServer.isRunning) httpServer.stop()
            val effectivePort = if (port in 1024..65535) port else 1524
            var effectiveHttpPort = if (httpPort in 1024..65535) httpPort else 8080
            if (effectiveHttpPort == effectivePort) {
                effectiveHttpPort = if (effectivePort == 8080) 8081 else 8080
            }
            val ip = resolveLocalIpAddress(this@FtpServerService)
            val ftpSuccess = ftpServer.start(effectivePort, pass, externalIpAddress = ip)
            val httpSuccess = httpServer.start(effectiveHttpPort, pass)
            // A failed start used to just stop the service: the button flipped back to START with
            // no hint why (typically another app holding the port).
            val error = when {
                !ftpSuccess && !httpSuccess -> "Couldn't start: ports $effectivePort and $effectiveHttpPort may be in use by another app"
                !ftpSuccess -> "FTP couldn't start: port $effectivePort may be in use by another app"
                !httpSuccess -> "Web access couldn't start: port $effectiveHttpPort may be in use by another app"
                else -> null
            }
            val errorId = if (error != null) System.currentTimeMillis() else _ftpState.value.errorId

            if (ftpSuccess || httpSuccess) {
                runningConfig = RunningConfig(port, httpPort, pass, random)
                powerLocks.acquire()
                _ftpState.value = FtpServerState(
                    isRunning = true,
                    ipAddress = ip,
                    port = effectivePort,
                    httpPort = effectiveHttpPort,
                    password = pass,
                    isRandomPassword = random,
                    error = error,
                    errorId = errorId,
                    ftpRunning = ftpSuccess,
                    httpRunning = httpSuccess
                )
                preferenceManager.setFtpWasRunning(true)
                val lines = listOfNotNull(
                    "Web: http://$ip:$effectiveHttpPort".takeIf { httpSuccess },
                    "FTP: ftp://$ip:$effectivePort".takeIf { ftpSuccess }
                )
                startForegroundNotification(lines.joinToString("\n"))
            } else {
                runningConfig = null
                powerLocks.release()
                _ftpState.value = _ftpState.value.copy(isRunning = false, error = error, errorId = errorId)
                preferenceManager.setFtpWasRunning(false)
                stopSelf()
            }
        }
    }

    private data class RunningConfig(val port: Int, val httpPort: Int, val password: String, val random: Boolean)
    @Volatile private var runningConfig: RunningConfig? = null

    // The LAN address is resolved once at start and pinned into FTP's passive-mode replies; after
    // switching WiFi networks (new IP) every FTP transfer dialed the old address and timed out,
    // and the shown URLs were stale. Restart on the new address when it changes.
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: android.net.Network, linkProperties: android.net.LinkProperties) = checkAddress()
        override fun onLost(network: android.net.Network) = checkAddress()
    }

    private fun checkAddress() {
        if (runningConfig == null) return
        // Re-checked under the lock: one network change fires several callbacks in a row, and
        // each would otherwise queue its own restart.
        launchLocked {
            val config = runningConfig ?: return@launchLocked
            val newIp = resolveLocalIpAddress(this@FtpServerService)
            if (newIp == "127.0.0.1" || newIp == _ftpState.value.ipAddress) return@launchLocked
            android.util.Log.i("FtpServerService", "LAN address changed to $newIp — restarting servers")
            startLocked(config.port, config.httpPort, config.password, config.random)
        }
    }

    private fun launchLocked(block: suspend () -> Unit) {
        serviceScope.launch { lifecycleMutex.withLock { block() } }
    }

    private fun stopServer() {
        launchLocked {
            runningConfig = null
            ftpServer.stop()
            httpServer.stop()
            preferenceManager.setFtpWasRunning(false)
            powerLocks.release()
            _ftpState.value = _ftpState.value.copy(isRunning = false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startForegroundNotification(content: String) {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.service_running_notification))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
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
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)
                ?.unregisterNetworkCallback(networkCallback)
        } catch (e: IllegalArgumentException) {
            // was never registered
        }
        runningConfig = null
        ftpServer.stop()
        httpServer.stop()
        powerLocks.release()
        _ftpState.value = _ftpState.value.copy(isRunning = false)
        serviceScope.cancel()
        super.onDestroy()
    }
}
