package com.antigravity.filemanager.data.remote.ftp

import android.content.Context
import android.net.wifi.WifiManager
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

/** Best-effort resolution of the device's own LAN-reachable IPv4 address, tried in order of how
 * likely it is to actually be the address a client on the same network needs to connect back to
 * (used both for the "ftp://ip:port" shown to the user and to pin MINA's PASV replies — see
 * [EmbeddedFtpServer.start]'s externalIpAddress doc comment for why that pinning matters). */
fun resolveLocalIpAddress(context: Context): String {
    try {
        // 1. Ask WifiManager directly first — cheapest and most reliable when actually on WiFi.
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
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

        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())

        // 2. wlan/eth/ap/rndis interfaces — WiFi, Ethernet, and hotspot/tethering links.
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

        // 3. Any interface with a standard private-subnet address (192.168.x.x, 10.x.x.x,
        // 172.16-31.x.x), regardless of its name.
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

        // 4. Last resort: any non-loopback IPv4 address at all.
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

fun generateRandomFtpPassword(): String {
    val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    // SecureRandom: kotlin's default Random is not meant for secrets.
    val random = java.security.SecureRandom()
    return (1..6).map { chars[random.nextInt(chars.length)] }.joinToString("")
}
