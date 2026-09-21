package com.antigravity.filemanager.data.remote.ftp

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/** The three locks an always-listening foreground service like the FTP server needs held for as
 * long as it's running, bundled behind one acquire()/release() pair instead of three separate
 * nullable fields with their own isHeld checks scattered across start/stop/onDestroy:
 * - CPU wake lock: keeps the process itself from being suspended.
 * - Screen wake lock: stops the screen timing out and the device going to sleep mid-transfer.
 * - WiFi lock (WIFI_MODE_FULL_HIGH_PERF): neither wake lock above keeps the WiFi radio itself
 *   awake — Android independently puts WiFi into a low-power/sleep state once the screen turns
 *   off or the app is backgrounded, unless something holds this. Without it, the process (and
 *   this foreground service) keeps running fine, but incoming packets on the FTP listening socket
 *   get delayed/dropped by the radio itself — exactly "service says running, but switching to
 *   another app drops the connection".
 */
class FtpPowerLocks(context: Context) {
    private val wakeLock: PowerManager.WakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FileManager::FtpWakeLock")
        .apply { setReferenceCounted(false) } // a repeated acquire() must not outlive one release()

    @Suppress("DEPRECATION")
    private val screenWakeLock: PowerManager.WakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "FileManager::FtpScreenWakeLock")
        .apply { setReferenceCounted(false) }

    @Suppress("DEPRECATION")
    private val wifiLock: WifiManager.WifiLock? = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
        ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FileManager::FtpWifiLock")

    fun acquire() {
        wakeLock.acquire(24 * 60 * 60 * 1000L) // 24h max
        screenWakeLock.acquire(24 * 60 * 60 * 1000L) // keep screen from timing out while FTP is on
        if (wifiLock?.isHeld != true) wifiLock?.acquire()
    }

    fun release() {
        if (wakeLock.isHeld) wakeLock.release()
        if (screenWakeLock.isHeld) screenWakeLock.release()
        if (wifiLock?.isHeld == true) wifiLock.release()
    }
}
