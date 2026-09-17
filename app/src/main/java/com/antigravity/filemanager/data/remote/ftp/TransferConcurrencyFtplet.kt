package com.antigravity.filemanager.data.remote.ftp

import org.apache.ftpserver.ftplet.DefaultFtplet
import org.apache.ftpserver.ftplet.FtpRequest
import org.apache.ftpserver.ftplet.FtpSession
import org.apache.ftpserver.ftplet.FtpletResult
import java.util.concurrent.Semaphore

/** Caps how many uploads/downloads run their actual file-copy loop at once — see the comment at
 * this Ftplet's registration in [EmbeddedFtpServer.start] for why. A transfer beyond the cap just
 * blocks in [onUploadStart]/[onDownloadStart] until a slot frees up (still consuming one of
 * maxThreads' worker threads while it waits — but that pool is sized generously precisely so
 * waiting jobs don't starve out unrelated commands on other connections).
 *
 * The permit-held flag lives on the FtpSession itself (its own getAttribute/setAttribute store,
 * not a map this class owns) so [onDisconnect] can always find and release it — a client that
 * vanishes mid-transfer (connection drop, app killed) may never reach onUploadEnd/onDownloadEnd,
 * and a permit that's never released would permanently shrink the pool of available slots. */
class TransferConcurrencyFtplet(maxConcurrentTransfers: Int) : DefaultFtplet() {
    private val semaphore = Semaphore(maxConcurrentTransfers, true) // fair: first-in-first-served
    private val permitHeldAttr = "owl_transfer_permit_held"

    private fun acquire(session: FtpSession): FtpletResult {
        semaphore.acquire()
        session.setAttribute(permitHeldAttr, true)
        return FtpletResult.DEFAULT
    }

    private fun release(session: FtpSession) {
        if (session.getAttribute(permitHeldAttr) == true) {
            session.removeAttribute(permitHeldAttr)
            semaphore.release()
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
