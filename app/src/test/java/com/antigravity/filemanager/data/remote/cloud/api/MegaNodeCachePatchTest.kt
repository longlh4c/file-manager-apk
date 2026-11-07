package com.antigravity.filemanager.data.remote.cloud.api

import android.content.ContextWrapper
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MegaNodeCachePatchTest {

    private val client = MegaApiClient(ContextWrapper(null), OkHttpClient())

    @Test
    fun patchNodeCacheAfterDeleteRemovesTargetNodeWithoutWipingCache() {
        val accountId = "test_acc_123"
        val nodeTreeCacheField = MegaApiClient::class.java.getDeclaredField("nodeTreeCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cacheMap = nodeTreeCacheField.get(client) as MutableMap<String, Any>

        // Create an initial NodeTreeCache object with 2 nodes
        val nodeTreeCacheClass = Class.forName("com.antigravity.filemanager.data.remote.cloud.api.MegaApiClient\$NodeTreeCache")
        val megaNodeClass = Class.forName("com.antigravity.filemanager.data.remote.cloud.api.MegaApiClient\$MegaNode")
        val megaNodeCtor = megaNodeClass.getConstructor(
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            String::class.java
        )

        val node1 = megaNodeCtor.newInstance("node_1", "root", 0, 100L, 1000L, "file1.txt", "key1", "")
        val node2 = megaNodeCtor.newInstance("node_2", "root", 0, 200L, 2000L, "file2.txt", "key2", "")

        val nodeTreeCtor = nodeTreeCacheClass.getConstructor(List::class.java, String::class.java, Long::class.javaPrimitiveType)
        val initialCache = nodeTreeCtor.newInstance(listOf(node1, node2), "root", System.currentTimeMillis())
        cacheMap[accountId] = initialCache

        // Now patch delete on node_1
        client.patchNodeCacheAfterDelete(accountId, "node_1")

        // Assert that cache still exists (NOT wiped!)
        assertTrue(cacheMap.containsKey(accountId))
        val updatedCache = cacheMap[accountId]!!
        val allNodesField = nodeTreeCacheClass.getDeclaredField("allNodes").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val remainingNodes = allNodesField.get(updatedCache) as List<Any>

        assertEquals(1, remainingNodes.size)
        val handleField = megaNodeClass.getDeclaredField("handle").apply { isAccessible = true }
        assertEquals("node_2", handleField.get(remainingNodes[0]))
    }
}
