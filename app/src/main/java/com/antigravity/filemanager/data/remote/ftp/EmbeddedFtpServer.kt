package com.antigravity.filemanager.data.remote.ftp

import android.os.Environment
import org.apache.ftpserver.ConnectionConfigFactory
import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import javax.inject.Inject
import javax.inject.Singleton

// Number of STOR/RETR transfers allowed to actually run their file-copy loop at once — see
// TransferConcurrencyFtplet's own doc comment for why this exists separately from maxThreads.
private const val MAX_CONCURRENT_TRANSFERS = 4

// apache-ftpserver 1.2.0 (unmaintained since 2011) shares a single CharsetEncoder across this
// pool's worker threads when encoding responses (e.g. "227 Entering Passive Mode ..." for PASV,
// or STOR's own reply). CharsetEncoder is NOT thread-safe, so >1 thread means two connections (or
// parallel data-connection requests from one client) can encode a response at the same time and
// corrupt the shared encoder's internal state. maxThreads=1 was tried once to avoid that race by
// fully serializing every command through this one pool — but that pool ALSO runs each command's
// own handling, including STOR/RETR's file-copy loop, not just response encoding. With only 1
// thread, any other command the client sends while a transfer is in progress (many clients
// poll/refresh during a transfer) sits blocked until the transfer finishes, and can hit the
// CLIENT's own timeout waiting — trading the crash for "connection times out mid-transfer". A
// real thread pool lets commands run concurrently instead; the encoder race this risks is caught
// (not fatal) by [ftpUncaughtExceptionHandler] below, which targets exactly exceptions from
// org.apache.ftpserver/org.apache.mina, dropping just that one connection instead of the whole
// app, while genuine app bugs elsewhere still crash normally.
//
// 4 wasn't enough on its own either: since each STOR/RETR's own file-copy loop runs synchronously
// on one of these worker threads for as long as that transfer takes (not just the response
// encoding), a client running N parallel transfer jobs needs N worker threads tied up in blocking
// I/O simultaneously, for however long the transfers last — not just briefly. A WinSCP session
// with 5 parallel 1GB uploads occupied all 4 threads with nothing left to process ANY other
// command (control-connection keepalives, the 5th job's own PASV/STOR, directory listings) until
// one transfer finished minutes later — which looked exactly like the app freezing, and was slow
// enough that WinSCP's own client-side timeout gave up and dropped the connection. Raised well
// above what a real transfer client is likely to run in parallel at once; each thread is only
// blocked on I/O (not spinning the CPU), so holding more of them idle-but-blocked is cheap. See
// [MAX_CONCURRENT_TRANSFERS] for the separate, tighter cap on actual concurrent storage I/O.
private const val MAX_WORKER_THREADS = 16

/** Embedded FTP server (apache-ftpserver over MINA) exposing the device's storage over the LAN.
 * Owns only the server's own lifecycle — networking (LAN IP, wake/WiFi locks) and the foreground
 * service wrapper live in [FtpServerService]. */
@Singleton
class EmbeddedFtpServer @Inject constructor() {

    private var server: FtpServer? = null
    var isRunning: Boolean = false
        private set

    private var previousUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null

    // Defense in depth for the shared-CharsetEncoder race described at MAX_WORKER_THREADS above —
    // if it ever fires again despite that, this stops it from being fatal for the whole app. Only
    // swallows exceptions whose stack trace actually runs through org.apache.ftpserver or
    // org.apache.mina (this library's own packages), so it can only ever mask a bug in this
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
        // The LAN IP FtpServerService already computes for the "ftp://ip:port" it shows the user
        // — reused here for the exact same reason it needed that resolution in the first place:
        // MINA's default PASV configuration announces whatever local address the JVM's socket
        // auto-detects for the "227 Entering Passive Mode (...)" reply, which on Android (multiple
        // interfaces — WiFi, mobile data, VPN, hotspot AP — all up at once) can easily be the
        // wrong one. The control connection (login, PWD, LIST headers) works fine either way since
        // it's already an established socket, but every PASV data transfer (a real directory
        // listing's contents, upload, download) then dials an address the client can't actually
        // reach and just times out — which is exactly "service says active, but every real
        // operation times out". Passing the real LAN IP here pins the announced address to one the
        // client, on the same LAN, can actually reach.
        externalIpAddress: String? = null
    ): Boolean {
        if (isRunning) return true

        previousUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(ftpUncaughtExceptionHandler)

        return try {
            val serverFactory = FtpServerFactory().apply {
                connectionConfig = ConnectionConfigFactory().apply {
                    maxLogins = 100
                    maxAnonymousLogins = 100
                    maxLoginFailures = 100
                    loginFailureDelay = 0
                    maxThreads = MAX_WORKER_THREADS
                    isAnonymousLoginEnabled = true
                }.createConnectionConfig()

                addListener("default", buildListener(port, externalIpAddress))

                userManager = CustomFtpUserManager(
                    homeDirectory = Environment.getExternalStorageDirectory().absolutePath,
                    expectedPassword = password.trim()
                )

                // Caps how many STOR/RETR transfers actually run their file-copy loop at once,
                // independent of maxThreads above (which just bounds how many commands/connections
                // can be serviced in parallel at all). Without this, N clients' transfer jobs
                // compete for the phone's single flash storage I/O bus — more parallel writes/
                // reads than the storage can actually service concurrently doesn't move data
                // faster, just spreads the same bandwidth thinner across more in-flight transfers,
                // and ties up more worker threads doing it. Extra jobs beyond the cap simply queue
                // (blocked on the semaphore, not competing for storage bandwidth) until a slot
                // frees up, rather than racing ahead.
                ftplets = mapOf("transferLimiter" to TransferConcurrencyFtplet(MAX_CONCURRENT_TRANSFERS))
            }

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

    private fun buildListener(port: Int, externalIpAddress: String?) = ListenerFactory().apply {
        this.port = port
        if (!externalIpAddress.isNullOrBlank()) {
            dataConnectionConfiguration = DataConnectionConfigurationFactory().apply {
                // Pin PASV replies to the real LAN address instead of letting MINA guess from the
                // local socket — see start()'s externalIpAddress doc comment above.
                passiveExternalAddress = externalIpAddress
                // Leaving passivePorts at its "0" default (any free ephemeral port) is fine here:
                // on the same LAN there's no NAT/router port-forwarding step in the way, so
                // nothing needs those ports pre-opened externally, only the announced address
                // needs to be correct.
            }.createDataConnectionConfiguration()
        }
    }.createListener()

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
