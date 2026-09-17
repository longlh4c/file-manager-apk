package com.antigravity.filemanager.data.remote.ftp

import android.os.Environment
import org.apache.ftpserver.ConnectionConfigFactory
import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import javax.inject.Inject
import javax.inject.Singleton

// Number of STOR/RETR transfers allowed to actually run their file-copy loop at once.
// Allows up to 4 parallel active transfers matching standard multi-connection FTP clients (WinSCP / FileZilla).
private const val MAX_CONCURRENT_TRANSFERS = 4

// Thread pool size for MINA I/O worker threads.
// Note on CharsetEncoder: In vanilla apache-ftpserver 1.2.0, FtpResponseEncoder shared a single
// non-thread-safe CharsetEncoder across worker threads, leading to ICU native memory corruption
// (SIGSEGV/SIGABRT) under concurrent loads. We have resolved this by overriding FtpResponseEncoder
// with a ThreadLocal<CharsetEncoder> implementation.
// Setting MAX_WORKER_THREADS to 16 allows ample capacity to handle parallel control requests
// (directory listing, navigation, NOOP keep-alives) across multiple sessions/clients without latency,
// while storage I/O concurrency remains strictly bounded by MAX_CONCURRENT_TRANSFERS.
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

    // Kept as a second line of defense even with maxThreads=1 eliminating the encoder race itself
    // (see MAX_WORKER_THREADS) — this library still has other ways to throw from a worker thread
    // that aren't that specific bug, and this at least keeps THOSE from taking the whole app down.
    // It is NOT sufficient on its own against the encoder race specifically: logs from an actual
    // crash showed this handler correctly logging and swallowing that exception, and the app still
    // died seconds later from a separate native-level fault the race had already caused — no JVM
    // handler can undo native memory corruption after the fact. Only swallows exceptions whose
    // stack trace actually runs through org.apache.ftpserver or org.apache.mina (this library's
    // own packages), so it can only ever mask a bug in this embedded FTP server — never a real
    // crash elsewhere in the app, which still falls through to whatever handler was already
    // installed (Android's default process-kill included).
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
                //
                // MUST be a mutable map: DefaultFtpServer.stop() -> DefaultFtpServerContext.
                // dispose() calls .clear() on this exact map — Kotlin's mapOf() returns an
                // unmodifiable one, so every stop() threw UnsupportedOperationException here. That
                // alone wasn't fatal (stop() catches it), but it meant stop() never got past that
                // point to reset server/isRunning either — leaving the app reporting FTP as
                // running (notification and all) after a stop, while the very next start() call's
                // `if (isRunning) return true` guard skipped ever creating a new listener. From a
                // client's side that's indistinguishable from the app just refusing every
                // connection.
                ftplets = hashMapOf<String, org.apache.ftpserver.ftplet.Ftplet>("transferLimiter" to TransferConcurrencyFtplet(MAX_CONCURRENT_TRANSFERS))
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
        // server = null / isRunning = false must happen regardless of whether server.stop() itself
        // throws (see the ftplets map fix in start() for why it used to) — this used to sit inside
        // the try block after that call, so a thrown exception skipped resetting them entirely.
        // With isRunning stuck at true, the NEXT start() call's `if (isRunning) return true` guard
        // short-circuited without ever creating a new listener — the app kept reporting the FTP
        // server as running (notification and all) while nothing was actually listening on the
        // port at all, which is exactly what "server says on, client gets connection refused"
        // looks like from the outside.
        try {
            server?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            server = null
            isRunning = false
            Thread.setDefaultUncaughtExceptionHandler(previousUncaughtExceptionHandler)
        }
    }
}
