package com.antigravity.filemanager.domain.usecase

import android.content.ContextWrapper
import com.antigravity.filemanager.data.local.cache.FolderCacheManager
import com.antigravity.filemanager.data.local.observer.MediaChangeSignal
import com.antigravity.filemanager.data.service.TransferGuard
import com.antigravity.filemanager.domain.model.*
import com.antigravity.filemanager.domain.repository.ICloudRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BatchUploadProgressMonotonicityTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        tempDir = File(System.getProperty("java.io.tmpdir"), "batch_upload_test_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    private class FakeCloudRepo : ICloudRepository {
        val account = CloudAccount(
            id = "test_acc",
            provider = CloudProvider.DROPBOX,
            accountName = "Test Dropbox",
            email = "test@cloud.com"
        )

        override fun observeConnectedAccounts(): Flow<List<CloudAccount>> = emptyFlow()
        override suspend fun getConnectedAccounts(): List<CloudAccount> = listOf(account)
        override suspend fun addAccount(account: CloudAccount): Result<Unit> = Result.success(Unit)
        override suspend fun removeAccount(accountId: String): Result<Unit> = Result.success(Unit)
        override suspend fun updateAccountsOrder(accounts: List<CloudAccount>): Result<Unit> = Result.success(Unit)
        override suspend fun getCloudFiles(accountId: String, remotePath: String, forceFullRefresh: Boolean): Result<List<FileItem>> =
            Result.success(emptyList())

        override suspend fun downloadCloudFile(accountId: String, remotePath: String, localTargetDir: String, destFileName: String?, onProgress: ((Long, Long) -> Unit)?): Result<File> =
            Result.failure(UnsupportedOperationException())

        override suspend fun uploadCloudFile(accountId: String, localFilePath: String, remoteTargetDir: String, onProgress: ((Long, Long) -> Unit)?): Result<Unit> {
            val file = File(localFilePath)
            val size = file.length()
            val chunks = 4
            val chunkSize = size / chunks
            for (i in 1..chunks) {
                // Variable delay interleaves chunks across concurrent coroutines
                delay(if (size > 50_000) 15L else 5L)
                val sent = (chunkSize * i).coerceAtMost(size)
                onProgress?.invoke(sent, size)
            }
            return Result.success(Unit)
        }

        override suspend fun createFolder(accountId: String, folderName: String, parentPath: String): Result<FileItem> =
            Result.failure(UnsupportedOperationException())
        override suspend fun deleteCloudFile(accountId: String, remotePath: String, moveToTrash: Boolean): Result<Unit> =
            Result.success(Unit)
        override suspend fun deletePermanentlyBatchMega(accountId: String, remotePaths: List<String>): Map<String, Result<Unit>> =
            emptyMap()
        override suspend fun restoreCloudFile(accountId: String, remotePath: String): Result<Unit> =
            Result.failure(UnsupportedOperationException())
        override suspend fun renameCloudFile(accountId: String, remotePath: String, newName: String): Result<FileItem> =
            Result.failure(UnsupportedOperationException())
        override suspend fun moveCloudFileWithinAccount(accountId: String, sourcePath: String, targetDir: String): Result<Unit> =
            Result.failure(UnsupportedOperationException())
        override suspend fun getCloudQuota(accountId: String): Result<Pair<Long, Long>> =
            Result.success(Pair(0L, 100_000_000L))
        override suspend fun downloadCloudThumbnail(accountId: String, nodeId: String): Result<ByteArray> =
            Result.failure(UnsupportedOperationException())
        override suspend fun downloadCloudFilePartial(accountId: String, nodeId: String, localTargetFile: File, maxBytes: Long): Result<File> =
            Result.failure(UnsupportedOperationException())
        override suspend fun openCloudThumbnailDataSource(accountId: String, nodeId: String, forPlayback: Boolean): Result<android.media.MediaDataSource> =
            Result.failure(UnsupportedOperationException())
        override suspend fun getCloudStreamableLink(accountId: String, remotePath: String): Result<String> =
            Result.failure(UnsupportedOperationException())
        override suspend fun getCloudStreamSource(accountId: String, remotePath: String): Result<CloudStreamSource> =
            Result.failure(UnsupportedOperationException())
    }

    @Test
    fun batchUploadProgressIsStrictlyMonotonicAcrossConcurrentStreams() = runBlocking {
        val fakeRepo = FakeCloudRepo()
        val context = object : ContextWrapper(null) {
            override fun getCacheDir(): File = tempDir
            override fun getFilesDir(): File = tempDir
        }
        val cacheManager = FolderCacheManager(context, MediaChangeSignal(context))
        val transferGuard = TransferGuard(context)
        val useCase = CloudStorageUseCase(fakeRepo, transferGuard, cacheManager)

        // Create 6 files with varying sizes to guarantee interleaved uploads across Semaphore(3)
        val fileSizes = listOf(120_000, 20_000, 80_000, 15_000, 100_000, 30_000)
        val files = fileSizes.mapIndexed { idx, size ->
            File(tempDir, "file_$idx.bin").apply {
                writeBytes(ByteArray(size))
            }
        }
        val expectedTotalBytes = fileSizes.sum().toLong()

        data class ProgressSnapshot(val fileName: String, val index: Int, val totalFiles: Int, val bytesSent: Long, val totalBytes: Long)
        val snapshots = Collections.synchronizedList(mutableListOf<ProgressSnapshot>())

        val result = useCase.uploadFiles(
            accountId = "test_acc",
            localPaths = files.map { it.absolutePath },
            remoteDir = "/"
        ) { currentFile, currentIndex, totalFiles, bytesSent, totalBytes ->
            snapshots.add(ProgressSnapshot(currentFile, currentIndex, totalFiles, bytesSent, totalBytes))
        }

        assertTrue("Upload should succeed", result.isSuccess)
        assertEquals(files.size, result.getOrNull())
        assertTrue("Snapshots should be recorded", snapshots.isNotEmpty())

        var prevIndex = 1
        var prevBytes = 0L

        snapshots.forEachIndexed { i, s ->
            assertEquals("Total files should always equal batch size", files.size, s.totalFiles)
            assertEquals("Total bytes should always equal batch total", expectedTotalBytes, s.totalBytes)
            assertTrue("File index should never decrease! At #$i: ${s.index} was less than previous $prevIndex (snapshot=$s)", s.index >= prevIndex)
            assertTrue("Bytes sent should never decrease! At #$i: ${s.bytesSent} was less than previous $prevBytes (snapshot=$s)", s.bytesSent >= prevBytes)
            prevIndex = s.index
            prevBytes = s.bytesSent
        }

        // Final snapshot check
        val last = snapshots.last()
        assertEquals(files.size, last.index)
        assertEquals(expectedTotalBytes, last.bytesSent)
    }
}
