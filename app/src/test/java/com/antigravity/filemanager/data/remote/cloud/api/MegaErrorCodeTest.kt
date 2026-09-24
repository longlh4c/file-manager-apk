package com.antigravity.filemanager.data.remote.cloud.api

import android.content.ContextWrapper
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MegaErrorCodeTest {

    private val client = MegaApiClient(ContextWrapper(null), OkHttpClient())

    @Test
    fun errorsAreFoundInBothReplyForms() {
        assertEquals(-9, client.megaErrorCode("-9"))
        assertEquals(-9, client.megaErrorCode("[-9]"))
        assertEquals(-11, client.megaErrorCode(" [-11] "))
    }

    @Test
    fun successRepliesAreNotErrors() {
        assertNull(client.megaErrorCode("0"))
        assertNull(client.megaErrorCode("[0]"))
        assertNull(client.megaErrorCode("[]"))
        assertNull(client.megaErrorCode("[{\"f\":[{\"h\":\"abc\"}]}]"))
    }
}
