package com.antigravity.filemanager.data.remote.ftp

import android.os.Environment
import org.apache.ftpserver.ConnectionConfigFactory
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
        showHidden: Boolean = false
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
                // NOT a perf knob to raise carelessly: apache-ftpserver 1.2.0 (unmaintained since
                // 2011) shares a single CharsetEncoder across MINA's IO worker threads when
                // encoding responses (e.g. "227 Entering Passive Mode ..." for PASV).
                // CharsetEncoder is NOT thread-safe, so >1 thread means two connections (or
                // parallel data-connection requests from one client) can encode a response at the
                // same time, corrupt the shared encoder's internal state, and trigger a native
                // ICU NullPointerException that JNI escalates into an uncatchable process abort
                // (SIGABRT) — killing the app mid-transfer. maxThreads=1 serializes all response
                // encoding so this race can't happen; it only limits how many FTP protocol events
                // are processed at once, not file transfer throughput (each connection's data
                // socket still moves bytes independently of this pool).
                maxThreads = 1
                isAnonymousLoginEnabled = true
            }
            serverFactory.connectionConfig = connectionConfigFactory.createConnectionConfig()

            val listenerFactory = ListenerFactory().apply {
                this.port = port
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
