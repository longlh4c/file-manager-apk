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

    private var previousUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null

    // Defense in depth for the shared-CharsetEncoder race described where maxThreads is set,
    // below — if it ever fires again despite that, this stops it from being fatal for the whole
    // app. Only swallows exceptions whose stack trace actually runs through org.apache.ftpserver
    // or org.apache.mina (this library's own packages), so it can only ever mask a bug in this
    // embedded FTP server — never a real crash elsewhere in the app, which still falls through to
    // whatever handler was already installed (Android's default process-kill included).
    private val ftpUncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, throwable ->
        val isFromFtpLibrary = generateSequence(throwable) { it.cause }
            .any { t -> t.stackTrace.any { frame -> frame.className.startsWith("org.apache.ftpserver") || frame.className.startsWith("org.apache.mina") } }
        if (isFromFtpLibrary) {
            android.util.Log.e("EmbeddedFtpServer", "Caught (non-fatal) exception on FTP worker thread '${thread.name}' — dropping this connection instead of the whole app", throwable)
        } else {
            previousUncaughtExceptionHandler?.uncaughtException(thread, throwable)
                ?: android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

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

        previousUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(ftpUncaughtExceptionHandler)

        return try {
            val serverFactory = FtpServerFactory()

            // Configure unlimited connections & remove login limits
            val connectionConfigFactory = ConnectionConfigFactory().apply {
                maxLogins = 100
                maxAnonymousLogins = 100
                maxLoginFailures = 100
                loginFailureDelay = 0
                // apache-ftpserver 1.2.0 (unmaintained since 2011) shares a single CharsetEncoder
                // across this pool's worker threads when encoding responses (e.g. "227 Entering
                // Passive Mode ..." for PASV, or STOR's own reply). CharsetEncoder is NOT
                // thread-safe, so >1 thread means two connections (or parallel data-connection
                // requests from one client) can encode a response at the same time and corrupt the
                // shared encoder's internal state. maxThreads=1 (tried right before this) avoids
                // that race by fully serializing every command through this one pool — but that
                // pool ALSO runs each command's own handling, including STOR/RETR's file-copy loop,
                // not just response encoding. With only 1 thread, any other command the client
                // sends while a transfer is in progress (many clients poll/refresh during a
                // transfer) sits blocked until the transfer finishes, and can hit the CLIENT's own
                // timeout waiting — trading the crash for "connection times out mid-transfer".
                // Back to a real thread pool so commands can actually run concurrently; the
                // encoder race this used to risk is now caught (not fatal) by the
                // UncaughtExceptionHandler installed in start(), below — it targets exactly
                // exceptions from org.apache.ftpserver/org.apache.mina, dropping just that one
                // connection instead of the whole app, while genuine app bugs elsewhere still
                // crash normally.
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
            Thread.setDefaultUncaughtExceptionHandler(previousUncaughtExceptionHandler)
            false
        }
    }

    fun stop() {
        try {
            server?.stop()
            server = null
            isRunning = false
            Thread.setDefaultUncaughtExceptionHandler(previousUncaughtExceptionHandler)
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
