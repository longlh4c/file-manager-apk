package com.antigravity.filemanager.data.remote.ftp

import org.apache.ftpserver.ftplet.FtpRequest
import org.apache.ftpserver.ftplet.FtpSession
import org.apache.ftpserver.ftplet.FtpletResult
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TransferConcurrencyFtpletTest {

    /** A session that only supports the attribute calls the ftplet uses. */
    private fun fakeSession(): FtpSession {
        val attrs = ConcurrentHashMap<String, Any>()
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(FtpSession::class.java)) { _, method, args ->
            when (method.name) {
                "getAttribute" -> attrs[args[0] as String]
                "setAttribute" -> { attrs[args[0] as String] = args[1]; null }
                "removeAttribute" -> { attrs.remove(args[0] as String); null }
                "hashCode" -> System.identityHashCode(attrs)
                "equals" -> false
                else -> throw UnsupportedOperationException(method.name)
            }
        } as FtpSession
    }

    private val request = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(FtpRequest::class.java)) { _, _, _ -> null } as FtpRequest

    @Test
    fun slotIsReturnedWhenTheClientLeftWhileWaitingForIt() {
        val ftplet = TransferConcurrencyFtplet(1)
        val active = fakeSession()
        val waiting = fakeSession()
        assertEquals(FtpletResult.DEFAULT, ftplet.onUploadStart(active, request))

        val pool = Executors.newSingleThreadExecutor()
        val waitingResult = pool.submit<FtpletResult> { ftplet.onUploadStart(waiting, request) }
        Thread.sleep(200) // let it block on the full semaphore
        ftplet.onDisconnect(waiting) // client gives up while queued
        ftplet.onUploadEnd(active, request) // the slot frees up and goes to the dead session

        assertEquals(FtpletResult.DISCONNECT, waitingResult.get(5, TimeUnit.SECONDS))
        assertEquals("the dead session must not keep the slot", 1, ftplet.availableSlots())
        pool.shutdown()
    }

    @Test
    fun slotIsReturnedWhenAConnectedClientDisconnectsMidTransfer() {
        val ftplet = TransferConcurrencyFtplet(2)
        val session = fakeSession()
        ftplet.onDownloadStart(session, request)
        assertEquals(1, ftplet.availableSlots())
        ftplet.onDisconnect(session)
        assertEquals(2, ftplet.availableSlots())
        ftplet.onDownloadEnd(session, request) // a late end must not release twice
        assertEquals(2, ftplet.availableSlots())
    }
}
