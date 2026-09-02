package com.antigravity.filemanager.data.remote.ftp

import android.os.Environment
import org.apache.ftpserver.ConnectionConfigFactory
import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authentication
import org.apache.ftpserver.ftplet.AuthenticationFailedException
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.AnonymousAuthentication
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.TransferRatePermission
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EmbeddedFtpServer @Inject constructor() {

    private var server: FtpServer? = null
    var isRunning: Boolean = false
        private set

    fun start(
        port: Int = 1524,
        password: String = "",
        showHidden: Boolean = false,
        // The LAN IP FtpServerService already computes for the "ftp://ip:port" it shows the user
        // — reused here for the exact same reason it needed getLocalIpAddress() in the first
        // place: MINA's default PASV configuration announces whatever local address the JVM's
        // socket auto-detects for the "227 Entering Passive Mode (...)" reply, which on Android
        // (multiple interfaces — WiFi, mobile data, VPN, hotspot AP — all up at once) can easily
        // be the wrong one. The control connection (login, PWD, LIST headers) works fine either
        // way since it's already an established socket, but every PASV data transfer (a real
        // directory listing's contents, upload, download) then dials an address the client can't
        // actually reach and just times out — which is exactly "service says active, but every
        // real operation times out". Passing the real LAN IP here pins the announced address to
        // one the client, on the same LAN, can actually reach.
        externalIpAddress: String? = null
    ): Boolean {
        if (isRunning) return true

        return try {
            val serverFactory = FtpServerFactory()

            // Configure unlimited connections & remove login limits
            val connectionConfigFactory = ConnectionConfigFactory().apply {
                maxLogins = 100
                maxAnonymousLogins = 100
                maxLoginFailures = 100
                loginFailureDelay = 0
                // Trade-off, not a free perf knob: apache-ftpserver 1.2.0 (unmaintained since
                // 2011) shares a single CharsetEncoder across this pool's worker threads when
                // encoding responses (e.g. "227 Entering Passive Mode ..." for PASV).
                // CharsetEncoder is NOT thread-safe, so >1 thread means two connections (or
                // parallel data-connection requests from one client) can encode a response at the
                // same time, corrupt the shared encoder's internal state, and trigger a native
                // ICU NullPointerException that JNI escalates into an uncatchable process abort
                // (SIGABRT) — killing the app mid-transfer. This pool ALSO runs each command's own
                // handling (including STOR/RETR's actual file-copy loop, not just response
                // encoding) — maxThreads=1 fully serializes that too, so a client uploading/
                // downloading several files at once (common for FTP clients with a transfer queue)
                // has every connection but the active one sit idle waiting for a turn, and can hit
                // its own client-side timeout waiting — "server says active, still times out".
                // Raised to 4 to let a few connections make real progress in parallel — this
                // narrows the crash race's window but does not eliminate it (still shared, still
                // not thread-safe, just less contended). If SIGABRT reproduces again, drop back to
                // 1 and have the client limit itself to one simultaneous transfer instead.
                maxThreads = 4
                isAnonymousLoginEnabled = true
            }
            serverFactory.connectionConfig = connectionConfigFactory.createConnectionConfig()

            val listenerFactory = ListenerFactory().apply {
                this.port = port
                if (!externalIpAddress.isNullOrBlank()) {
                    dataConnectionConfiguration = DataConnectionConfigurationFactory().apply {
                        // Pin PASV replies to the real LAN address instead of letting MINA guess
                        // from the local socket — see the parameter's doc comment above.
                        passiveExternalAddress = externalIpAddress
                        // Leaving passivePorts at its "0" default (any free ephemeral port) is
                        // fine here: on the same LAN there's no NAT/router port-forwarding step
                        // in the way, so nothing needs those ports pre-opened externally, only the
                        // announced address needs to be correct.
                    }.createDataConnectionConfiguration()
                }
            }
            serverFactory.addListener("default", listenerFactory.createListener())

            val homeDir = Environment.getExternalStorageDirectory().absolutePath
            val userManager = CustomFtpUserManager(
                homeDirectory = homeDir,
                expectedPassword = password.trim()
            )

            serverFactory.userManager = userManager

            val createdServer = serverFactory.createServer()
            createdServer.start()
            server = createdServer
            isRunning = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            isRunning = false
            false
        }
    }

    fun stop() {
        try {
            server?.stop()
            server = null
            isRunning = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

class CustomFtpUserManager(
    private val homeDirectory: String,
    private val expectedPassword: String
) : UserManager {

    private val authorities: List<Authority> = listOf(
        WritePermission(),
        ConcurrentLoginPermission(100, 100),
        TransferRatePermission(0, 0)
    )

    private fun createUser(name: String): BaseUser {
        val user = BaseUser()
        user.name = name
        user.password = expectedPassword
        user.homeDirectory = this@CustomFtpUserManager.homeDirectory
        user.authorities = this@CustomFtpUserManager.authorities
        user.maxIdleTime = 600
        user.setEnabled(true)
        return user
    }

    override fun getUserByName(username: String?): User? {
        val name = if (username.isNullOrBlank()) "anonymous" else username
        return createUser(name)
    }

    override fun getAllUserNames(): Array<String> {
        return arrayOf("admin", "anonymous")
    }

    override fun delete(username: String?) {}

    override fun save(user: User?) {}

    override fun doesExist(username: String?): Boolean {
        return true
    }

    override fun authenticate(authentication: Authentication?): User {
        if (authentication == null) {
            throw AuthenticationFailedException("Authentication required")
        }

        // Case 1: Anonymous Authentication
        if (authentication is AnonymousAuthentication) {
            if (expectedPassword.isEmpty()) {
                return createUser("anonymous")
            } else {
                throw AuthenticationFailedException("Password is required for this server")
            }
        }

        // Case 2: Username & Password Authentication
        if (authentication is UsernamePasswordAuthentication) {
            val username = authentication.username?.trim() ?: "admin"
            val password = authentication.password ?: ""

            if (expectedPassword.isEmpty()) {
                // No password set on server: accept ANY username and password!
                return createUser(username)
            } else {
                // Server has password: verify exact match
                if (password == expectedPassword) {
                    return createUser(username)
                } else {
                    throw AuthenticationFailedException("Invalid password for user $username")
                }
            }
        }

        throw AuthenticationFailedException("Unsupported authentication method")
    }

    override fun getAdminName(): String {
        return "admin"
    }

    override fun isAdmin(username: String?): Boolean {
        return true
    }
}
