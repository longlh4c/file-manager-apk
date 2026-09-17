package com.antigravity.filemanager.data.remote.ftp

import org.apache.ftpserver.ftplet.DefaultFtplet
import org.apache.ftpserver.ftplet.FtpRequest
import org.apache.ftpserver.ftplet.FtpSession
import org.apache.ftpserver.ftplet.FtpletResult
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Caps how many uploads/downloads run their actual file-copy loop at once.
 * A transfer beyond the cap waits with a timeout in [onUploadStart]/[onDownloadStart] until a slot
 * frees up. If waiting times out, the transfer is rejected to prevent thread pool starvation.
 *
 * The permit-held flag lives on the FtpSession itself, protected by synchronization, so [onDisconnect]
 * or transfer end hooks can always reliably find and release it even under abnormal socket drops. */
class TransferConcurrencyFtplet(maxConcurrentTransfers: Int) : DefaultFtplet() {
    private val semaphore = Semaphore(maxConcurrentTransfers, true) // fair: first-in-first-served
    private val permitHeldAttr = "owl_transfer_permit_held"
    private val lock = Any()

    companion object {
        private const val PERMIT_ACQUIRE_TIMEOUT_SECONDS = 60L
    }

    private fun acquire(session: FtpSession): FtpletResult {
        val acquired = try {
            semaphore.tryAcquire(PERMIT_ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        return if (acquired) {
            synchronized(lock) {
                session.setAttribute(permitHeldAttr, true)
            }
            FtpletResult.DEFAULT
        } else {
            android.util.Log.w(
                "TransferLimiter",
                "Timed out waiting for transfer slot after ${PERMIT_ACQUIRE_TIMEOUT_SECONDS}s; disconnecting transfer to prevent thread pool starvation"
            )
            FtpletResult.DISCONNECT
        }
    }

    private fun release(session: FtpSession) {
        synchronized(lock) {
            if (session.getAttribute(permitHeldAttr) == true) {
                session.removeAttribute(permitHeldAttr)
                semaphore.release()
            }
        }
    }

    override fun onUploadStart(session: FtpSession, request: FtpRequest): FtpletResult = acquire(session)
    override fun onDownloadStart(session: FtpSession, request: FtpRequest): FtpletResult = acquire(session)

    override fun onUploadEnd(session: FtpSession, request: FtpRequest): FtpletResult {
        release(session)
        return FtpletResult.DEFAULT
    }

    override fun onDownloadEnd(session: FtpSession, request: FtpRequest): FtpletResult {
        release(session)
        return FtpletResult.DEFAULT
    }

    override fun onDisconnect(session: FtpSession): FtpletResult {
        release(session)
        return FtpletResult.DEFAULT
    }
}
