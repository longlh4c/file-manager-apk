package com.antigravity.filemanager.data.remote.cloud.api

import com.antigravity.filemanager.domain.model.CloudAccount
import com.antigravity.filemanager.domain.model.CloudProvider
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TeraBoxApiClientTest {

    private lateinit var client: TeraBoxApiClient

    @Before
    fun setUp() {
        client = TeraBoxApiClient(OkHttpClient())
    }

    @Test
    fun testDomainPrefixAndBaseUrl() {
        assertEquals("https://www.terabox.com", client.getBaseUrl())
        client.setDomainPrefix("dm")
        assertEquals("https://dm.terabox.com", client.getBaseUrl())
        client.setDomainPrefix("jp")
        assertEquals("https://jp.terabox.com", client.getBaseUrl())
        client.setDomainPrefix(null)
        assertEquals("https://www.terabox.com", client.getBaseUrl())
    }

    @Test
    fun testExtractCleanNdus() {
        // Direct value
        assertEquals("test_token_123", client.extractCleanNdus("test_token_123"))

        // Single cookie format
        assertEquals("test_token_123", client.extractCleanNdus("ndus=test_token_123"))

        // Cookie header format with multiple cookies
        val multiCookie = "session_id=98765; ndus=test_token_123; lang=en; theme=dark"
        assertEquals("test_token_123", client.extractCleanNdus(multiCookie))

        // Empty / blank inputs
        assertEquals("", client.extractCleanNdus(""))
        assertEquals("", client.extractCleanNdus("   "))
    }

    @Test
    fun testQuotaJsonParsing() {
        val sampleJson = """
            {
                "errno": 0,
                "total": 1099511627776,
                "used": 53687091200,
                "free": 1045824536576,
                "request_id": 123456789
            }
        """.trimIndent()

        val root = JSONObject(sampleJson)
        assertEquals(0, root.getInt("errno"))
        val total = root.getLong("total")
        val used = root.getLong("used")

        assertEquals(1099511627776L, total) // 1 TB
        assertEquals(53687091200L, used)   // 50 GB
    }

    @Test
    fun testFileListJsonParsing() {
        val sampleListJson = """
            {
                "errno": 0,
                "list": [
                    {
                        "fs_id": 1001,
                        "server_filename": "Photos",
                        "path": "/Photos",
                        "size": 0,
                        "isdir": 1,
                        "server_mtime": 1700000000
                    },
                    {
                        "fs_id": 1002,
                        "server_filename": "Document.pdf",
                        "path": "/Document.pdf",
                        "size": 1048576,
                        "isdir": 0,
                        "server_mtime": 1700005000,
                        "dlink": "https://d.terabox.com/file/download/1002"
                    }
                ]
            }
        """.trimIndent()

        val root = JSONObject(sampleListJson)
        assertEquals(0, root.getInt("errno"))
        val arr = root.getJSONArray("list")
        assertEquals(2, arr.length())

        val folder = arr.getJSONObject(0)
        assertEquals("Photos", folder.getString("server_filename"))
        assertEquals(1, folder.getInt("isdir"))
        assertEquals(0L, folder.getLong("size"))

        val file = arr.getJSONObject(1)
        assertEquals("Document.pdf", file.getString("server_filename"))
        assertEquals(0, file.getInt("isdir"))
        assertEquals(1048576L, file.getLong("size"))
        assertEquals("https://d.terabox.com/file/download/1002", file.optString("dlink"))
    }

    @Test
    fun testCloudAccountTeraBoxProperties() {
        val account = CloudAccount(
            id = "tb_test_1",
            provider = CloudProvider.TERABOX,
            accountName = "My TeraBox",
            email = "user@terabox.com",
            displayOrder = 0,
            totalSpaceBytes = 1024L * 1024 * 1024 * 1024,
            usedSpaceBytes = 50L * 1024 * 1024 * 1024,
            accessToken = "ndus_sample_token",
            sessionHandle = "ndus_sample_token"
        )

        assertEquals(CloudProvider.TERABOX, account.provider)
        assertEquals(1024L * 1024 * 1024 * 1024, account.effectiveTotalBytes)
        assertEquals("My TeraBox", account.accountName)
        assertEquals("ndus_sample_token", account.accessToken)
    }

    @Test
    fun testThumbnailJsonParsing() {
        val sampleListJson = """
            {
                "errno": 0,
                "list": [
                    {
                        "fs_id": 1003,
                        "server_filename": "photo.jpg",
                        "path": "/photo.jpg",
                        "size": 204800,
                        "isdir": 0,
                        "server_mtime": 1700006000,
                        "thumbs": {
                            "url1": "https://data.terabox.com/thumb/1003_140x90.jpg",
                            "url2": "https://data.terabox.com/thumb/1003_360x270.jpg",
                            "url3": "https://data.terabox.com/thumb/1003_850x580.jpg",
                            "icon": "https://data.terabox.com/thumb/1003_icon.jpg"
                        }
                    }
                ]
            }
        """.trimIndent()

        val root = JSONObject(sampleListJson)
        val arr = root.getJSONArray("list")
        val item = arr.getJSONObject(0)
        val thumbsObj = item.optJSONObject("thumbs")
        val thumbUrl = thumbsObj?.optString("url3")?.takeIf { it.isNotBlank() }
            ?: thumbsObj?.optString("url2")?.takeIf { it.isNotBlank() }
            ?: thumbsObj?.optString("url1")?.takeIf { it.isNotBlank() }

        assertEquals("https://data.terabox.com/thumb/1003_850x580.jpg", thumbUrl)
    }

    @Test
    fun testStringifiedThumbnailJsonParsing() {
        val sampleListJson = """
            {
                "errno": 0,
                "list": [
                    {
                        "fs_id": 1004,
                        "server_filename": "photo2.jpg",
                        "path": "/photo2.jpg",
                        "size": 102400,
                        "isdir": 0,
                        "thumbs": "{\"url3\":\"https:\\/\\/data.terabox.com\\/thumb\\/1004_850x580.jpg\",\"url1\":\"https:\\/\\/data.terabox.com\\/thumb\\/1004_140x90.jpg\"}"
                    }
                ]
            }
        """.trimIndent()

        val root = JSONObject(sampleListJson)
        val arr = root.getJSONArray("list")
        val item = arr.getJSONObject(0)
        val thumbsObj = item.optJSONObject("thumbs") ?: (try { JSONObject(item.optString("thumbs")) } catch (_: Exception) { null })
        val rawThumb = thumbsObj?.optString("url3")?.takeIf { it.isNotBlank() }
        val thumbUrl = rawThumb?.replace("\\/", "/")?.replace("&amp;", "&")

        assertEquals("https://data.terabox.com/thumb/1004_850x580.jpg", thumbUrl)
    }

    @Test
    fun testUserInfoJsonParsingRootAndCookie() {
        val jsonStr = """
            {
                "errno": 0,
                "username": "my_terabox_name",
                "email": "user@example.com",
                "avatar_url": "https://data.terabox.com/avatar.jpg"
            }
        """.trimIndent()

        val json = JSONObject(jsonStr)
        val uname = json.optString("username").ifBlank { json.optString("uname") }
        val email = json.optString("email")

        assertEquals("my_terabox_name", uname)
        assertEquals("user@example.com", email)
    }
}
