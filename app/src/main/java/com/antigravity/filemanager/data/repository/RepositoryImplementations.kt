package com.antigravity.filemanager.data.repository

import android.content.Context
import android.content.Intent
import android.os.Environment
import com.antigravity.filemanager.data.local.db.AppDatabase
import com.antigravity.filemanager.data.local.db.CloudEntity
import com.antigravity.filemanager.data.local.db.TrashEntity
import com.antigravity.filemanager.data.local.preferences.PreferenceManager
import com.antigravity.filemanager.data.local.storage.FileOperationsHelper
import com.antigravity.filemanager.data.local.storage.LocalFileScanner
import com.antigravity.filemanager.data.remote.cloud.CloudManager
import com.antigravity.filemanager.data.remote.ftp.FtpServerService
import com.antigravity.filemanager.domain.model.*
import com.antigravity.filemanager.domain.repository.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageRepositoryImpl @Inject constructor(
    private val scanner: LocalFileScanner,
    private val database: AppDatabase
) : IStorageRepository {

    override suspend fun getStorageVolumeInfo(): StorageVolumeInfo = withContext(Dispatchers.IO) {
        scanner.getStorageVolume()
    }

    override suspend fun getCategorySummaries(): List<CategorySummary> = withContext(Dispatchers.IO) {
        coroutineScope {
            val volume = scanner.getStorageVolume()
            val trashDeferred = async { database.trashDao().getTotalTrashSize() ?: 0L }
            val cloudDeferred = async { database.cloudDao().getAll().size }
            val imgDeferred = async { scanner.getMediaFolders(CategoryType.IMAGES) }
            val audioDeferred = async { scanner.getMediaFolders(CategoryType.AUDIO) }
            val videoDeferred = async { scanner.getMediaFolders(CategoryType.VIDEOS) }
            val docDeferred = async { scanner.getAllDocumentFiles() }
            val dlDeferred = async { scanner.getMediaFolders(CategoryType.DOWNLOADS) }

            val trashSize = trashDeferred.await()
            val cloudCount = cloudDeferred.await()
            val imgFolders = imgDeferred.await()
            val audioFolders = audioDeferred.await()
            val videoFolders = videoDeferred.await()
            val docFiles = docDeferred.await()
            val dlFolders = dlDeferred.await()

            val imgCount = imgFolders.sumOf { it.itemCount }
            val imgSize = imgFolders.sumOf { it.totalSizeBytes }
            val audioCount = audioFolders.sumOf { it.itemCount }
            val audioSize = audioFolders.sumOf { it.totalSizeBytes }
            val videoCount = videoFolders.sumOf { it.itemCount }
            val videoSize = videoFolders.sumOf { it.totalSizeBytes }
            val docCount = docFiles.size
            val docSize = docFiles.sumOf { it.size }
            val dlCount = dlFolders.sumOf { it.itemCount }
            val dlSize = dlFolders.sumOf { it.totalSizeBytes }

            listOf(
                CategorySummary(
                    type = CategoryType.MAIN_STORAGE,
                    title = "Main storage",
                    totalSizeBytes = volume.totalBytes,
                    itemCount = 0,
                    subtitle = "${volume.formattedUsed} / ${volume.formattedTotal}"
                ),
                CategorySummary(
                    type = CategoryType.DOWNLOADS,
                    title = "Downloads",
                    totalSizeBytes = dlSize,
                    itemCount = dlCount
                ),
                CategorySummary(
                    type = CategoryType.STORAGE_ANALYSIS,
                    title = "Storage Anal…",
                    totalSizeBytes = volume.usedBytes,
                    subtitle = "${volume.usedPercentageInt}% used"
                ),
                CategorySummary(
                    type = CategoryType.IMAGES,
                    title = "Images",
                    totalSizeBytes = imgSize,
                    itemCount = imgCount
                ),
                CategorySummary(
                    type = CategoryType.AUDIO,
                    title = "Audio",
                    totalSizeBytes = audioSize,
                    itemCount = audioCount
                ),
                CategorySummary(
                    type = CategoryType.VIDEOS,
                    title = "Videos",
                    totalSizeBytes = videoSize,
                    itemCount = videoCount
                ),
                CategorySummary(
                    type = CategoryType.DOCUMENTS,
                    title = "Documents",
                    totalSizeBytes = docSize,
                    itemCount = docCount
                ),
                CategorySummary(
                    type = CategoryType.CLOUD,
                    title = "Cloud",
                    itemCount = cloudCount
                ),
                CategorySummary(
                    type = CategoryType.ACCESS_FROM_NETWORK,
                    title = "FTP"
                ),
                CategorySummary(
                    type = CategoryType.RECYCLE_BIN,
                    title = "Recycle Bin",
                    totalSizeBytes = trashSize
                )
            )
        }
    }

    override fun observeCategorySummaries(): Flow<List<CategorySummary>> = flow {
        // Step 1: Immediate emission of baseline cards with fast volume info (<1ms)
        val volume = scanner.getStorageVolume()
        val baseline = listOf(
            CategorySummary(
                type = CategoryType.MAIN_STORAGE,
                title = "Main storage",
                totalSizeBytes = volume.totalBytes,
                subtitle = "${volume.formattedUsed} / ${volume.formattedTotal}"
            ),
            CategorySummary(type = CategoryType.DOWNLOADS, title = "Downloads"),
            CategorySummary(
                type = CategoryType.STORAGE_ANALYSIS,
                title = "Storage Anal…",
                totalSizeBytes = volume.usedBytes,
                subtitle = "${volume.usedPercentageInt}% used"
            ),
            CategorySummary(type = CategoryType.IMAGES, title = "Images"),
            CategorySummary(type = CategoryType.AUDIO, title = "Audio"),
            CategorySummary(type = CategoryType.VIDEOS, title = "Videos"),
            CategorySummary(type = CategoryType.DOCUMENTS, title = "Documents"),
            CategorySummary(type = CategoryType.CLOUD, title = "Cloud"),
            CategorySummary(type = CategoryType.ACCESS_FROM_NETWORK, title = "FTP"),
            CategorySummary(type = CategoryType.RECYCLE_BIN, title = "Recycle Bin")
        )
        emit(baseline)

        // Step 2: Compute full summaries in parallel and emit
        emit(getCategorySummaries())
    }
}

@Singleton
class StorageAnalysisRepositoryImpl @Inject constructor(
    private val scanner: LocalFileScanner
) : IStorageAnalysisRepository {

    private val mutex = Mutex()
    private var inFlight: kotlinx.coroutines.Deferred<StorageAnalysisData>? = null

    // Single-flight: if a screen asks for both the full breakdown and the large-files list
    // around the same time, the second caller joins the first's in-progress scan instead of
    // starting its own full-device walk. Unlike a time-based cache, this never returns a stale
    // result once the scan has finished — the next call always triggers a fresh one.
    private suspend fun scanWithCache(): StorageAnalysisData = coroutineScope {
        val joined = mutex.withLock { inFlight }
        if (joined != null) return@coroutineScope joined.await()

        val deferred = async { scanner.scanStorageAnalysis() }
        mutex.withLock { inFlight = deferred }
        try {
            deferred.await()
        } finally {
            mutex.withLock { if (inFlight === deferred) inFlight = null }
        }
    }

    override suspend fun getStorageAnalysisData(): StorageAnalysisData = scanWithCache()

    override suspend fun getAllLargeFiles(): List<LargeFileItem> = scanWithCache().largeFiles
}

@Singleton
class FileRepositoryImpl @Inject constructor(
    private val scanner: LocalFileScanner,
    private val operationsHelper: FileOperationsHelper
) : IFileRepository {

    override suspend fun getFilesInDirectory(
        directoryPath: String,
        sortOption: FileSortOption,
        showHidden: Boolean
    ): List<FileItem> = scanner.listFilesInDir(directoryPath, sortOption, showHidden)

    override suspend fun getMediaFolders(categoryType: CategoryType, sortOption: FileSortOption): List<MediaFolder> =
        scanner.getMediaFolders(categoryType, sortOption)

    override suspend fun getMediaFilesInFolder(
        folderPath: String,
        categoryType: CategoryType,
        sortOption: FileSortOption
    ): List<FileItem> = scanner.listFilesInDir(folderPath, sortOption, showHidden = false)

    override suspend fun getAllDocuments(sortOption: FileSortOption): List<FileItem> =
        scanner.getAllDocumentFiles(sortOption)

    override suspend fun searchFiles(query: String, rootPath: String?, categoryType: CategoryType?): List<FileItem> = withContext(Dispatchers.IO) {
        val root = if (rootPath != null) File(rootPath) else Environment.getExternalStorageDirectory()
        val results = mutableListOf<FileItem>()

        // Bounded so a broad query on a huge device can't turn into an unlimited-depth,
        // unlimited-result full-storage walk — stop once we have enough matches to show.
        val maxResults = 500
        val maxDepth = 12

        fun searchRecursive(dir: File, depth: Int) {
            if (results.size >= maxResults || depth > maxDepth) return
            val list = dir.listFiles() ?: return
            for (f in list) {
                if (results.size >= maxResults) return
                if (f.name.contains(query, ignoreCase = true)) {
                    val isDir = f.isDirectory
                    results.add(
                        FileItem(
                            id = f.absolutePath,
                            name = f.name,
                            path = f.absolutePath,
                            size = if (isDir) 0L else f.length(),
                            lastModified = f.lastModified(),
                            isDirectory = isDir,
                            extension = if (isDir) "" else f.extension
                        )
                    )
                }
                if (f.isDirectory && !f.name.startsWith(".")) {
                    searchRecursive(f, depth + 1)
                }
            }
        }

        searchRecursive(root, 0)
        results
    }

    override suspend fun copyFiles(sourcePaths: List<String>, targetDirectory: String, overwriteNames: Set<String>, skipNames: Set<String>): Result<Unit> =
        operationsHelper.copy(sourcePaths, targetDirectory, overwriteNames, skipNames)

    override suspend fun moveFiles(sourcePaths: List<String>, targetDirectory: String, overwriteNames: Set<String>, skipNames: Set<String>): Result<Unit> =
        operationsHelper.move(sourcePaths, targetDirectory, overwriteNames, skipNames)

    override suspend fun findCopyConflicts(sourcePaths: List<String>, targetDirectory: String): List<com.antigravity.filemanager.domain.model.OverwriteConflict> =
        operationsHelper.findConflicts(sourcePaths, targetDirectory)

    override suspend fun renameFile(filePath: String, newName: String): Result<FileItem> =
        operationsHelper.rename(filePath, newName)

    override suspend fun createDirectory(parentPath: String, directoryName: String): Result<FileItem> =
        operationsHelper.createDirectory(parentPath, directoryName)

    override suspend fun zipFiles(
        sourcePaths: List<String>,
        targetZipPath: String,
        onProgress: ((currentFile: String, currentIndex: Int, totalFiles: Int) -> Unit)?
    ): Result<FileItem> =
        operationsHelper.zipFiles(sourcePaths, targetZipPath, onProgress)

    override suspend fun extractZip(zipFilePath: String, targetDirectory: String): Result<Unit> =
        operationsHelper.extractZip(zipFilePath, targetDirectory)

    override suspend fun getFileDetails(filePath: String): FileItem? = withContext(Dispatchers.IO) {
        val f = File(filePath)
        if (!f.exists()) return@withContext null
        val isDir = f.isDirectory
        FileItem(
            id = f.absolutePath,
            name = f.name,
            path = f.absolutePath,
            size = if (isDir) 0L else f.length(),
            lastModified = f.lastModified(),
            isDirectory = isDir,
            extension = if (isDir) "" else f.extension
        )
    }
}

@Singleton
class RecycleBinRepositoryImpl @Inject constructor(
    private val database: AppDatabase
) : IRecycleBinRepository {

    private val trashRoot = File(Environment.getExternalStorageDirectory(), ".filemanager_trash")

    init {
        if (!trashRoot.exists()) trashRoot.mkdirs()
    }

    override fun observeTrashItems(): Flow<List<TrashItem>> =
        database.trashDao().observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getTrashItems(): List<TrashItem> = withContext(Dispatchers.IO) {
        database.trashDao().getAll().map { it.toDomain() }
    }

    override suspend fun moveToTrash(filePaths: List<String>): Result<Int> = withContext(Dispatchers.IO) {
        try {
            var count = 0
            for (path in filePaths) {
                val source = File(path)
                if (!source.exists()) continue

                val trashName = "${System.currentTimeMillis()}_${source.name}"
                val trashFile = File(trashRoot, trashName)
                val size = if (source.isDirectory) 0L else source.length()
                val isDir = source.isDirectory

                if (source.renameTo(trashFile)) {
                    database.trashDao().insert(
                        TrashEntity(
                            originalPath = source.absolutePath,
                            trashPath = trashFile.absolutePath,
                            fileName = source.name,
                            fileSize = size,
                            deletedTimestamp = System.currentTimeMillis(),
                            isDirectory = isDir
                        )
                    )
                    count++
                }
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun restoreFromTrash(trashIds: List<Long>): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val entities = database.trashDao().getByIds(trashIds)
            var restored = 0
            for (entity in entities) {
                val trashFile = File(entity.trashPath)
                val originalFile = File(entity.originalPath)
                originalFile.parentFile?.mkdirs()

                if (trashFile.exists() && trashFile.renameTo(originalFile)) {
                    database.trashDao().deleteByIds(listOf(entity.id))
                    restored++
                }
            }
            Result.success(restored)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deletePermanently(trashIds: List<Long>): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val entities = database.trashDao().getByIds(trashIds)
            var deleted = 0
            for (entity in entities) {
                val trashFile = File(entity.trashPath)
                if (trashFile.exists()) {
                    if (trashFile.isDirectory) trashFile.deleteRecursively() else trashFile.delete()
                }
                database.trashDao().deleteByIds(listOf(entity.id))
                deleted++
            }
            Result.success(deleted)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun emptyTrash(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val all = database.trashDao().getAll()
            for (item in all) {
                val f = File(item.trashPath)
                if (f.exists()) {
                    if (f.isDirectory) f.deleteRecursively() else f.delete()
                }
            }
            database.trashDao().clearAll()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getTrashTotalSize(): Long = withContext(Dispatchers.IO) {
        database.trashDao().getTotalTrashSize() ?: 0L
    }
}

@Singleton
class FtpServerRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefManager: PreferenceManager
) : IFtpServerRepository {

    override fun observeFtpServerState(): Flow<FtpServerState> = FtpServerService.ftpState

    override suspend fun getFtpServerState(): FtpServerState = FtpServerService.ftpState.value

    override suspend fun startFtpServer(
        port: Int,
        password: String,
        isRandomPassword: Boolean,
        showHidden: Boolean
    ): Result<Unit> {
        val intent = Intent(context, FtpServerService::class.java).apply {
            action = FtpServerService.ACTION_START
            putExtra(FtpServerService.EXTRA_PORT, port)
            putExtra(FtpServerService.EXTRA_PASSWORD, password)
            putExtra(FtpServerService.EXTRA_RANDOM_PASS, isRandomPassword)
            putExtra(FtpServerService.EXTRA_SHOW_HIDDEN, showHidden)
        }
        context.startService(intent)
        return Result.success(Unit)
    }

    override suspend fun stopFtpServer(): Result<Unit> {
        val intent = Intent(context, FtpServerService::class.java).apply {
            action = FtpServerService.ACTION_STOP
        }
        context.startService(intent)
        return Result.success(Unit)
    }

    override suspend fun updateConfig(
        port: Int,
        password: String,
        isRandomPassword: Boolean,
        showHidden: Boolean
    ) {
        prefManager.saveFtpConfig(port, password, isRandomPassword, showHidden)
    }
}

@Singleton
class CloudRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val cloudManager: CloudManager
) : ICloudRepository {

    override fun observeConnectedAccounts(): Flow<List<CloudAccount>> =
        database.cloudDao().observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getConnectedAccounts(): List<CloudAccount> = withContext(Dispatchers.IO) {
        database.cloudDao().getAll().map { it.toDomain() }
    }

    override suspend fun addAccount(account: CloudAccount): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val count = database.cloudDao().getAll().size
            val rawSession = account.sessionHandle ?: ""
            // Offload large JSON payload to disk to prevent SQLiteBlobTooBigException (SQLite CursorWindow limit)
            val lightAccount = if (rawSession.length > 500 || rawSession.contains("\"folders\":") || rawSession.contains("\"files\":")) {
                cloudManager.saveSessionPayload(account.id, rawSession)
                val lightSession = if (rawSession.contains("\"sid\":")) {
                    try {
                        org.json.JSONObject(rawSession).optString("sid", "session_active")
                    } catch (e: Exception) { "session_active" }
                } else "session_active"
                account.copy(displayOrder = count, sessionHandle = lightSession)
            } else {
                account.copy(displayOrder = count)
            }
            database.cloudDao().insert(CloudEntity.fromDomain(lightAccount))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun removeAccount(accountId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            cloudManager.deleteSessionPayload(accountId)
            database.cloudDao().deleteById(accountId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateAccountsOrder(accounts: List<CloudAccount>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val entities = accounts.mapIndexed { index, acc ->
                CloudEntity.fromDomain(acc.copy(displayOrder = index))
            }
            database.cloudDao().insertAll(entities)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getCloudFiles(accountId: String, remotePath: String, forceFullRefresh: Boolean): Result<List<FileItem>> =
        withContext(Dispatchers.IO) {
            val account = database.cloudDao().getById(accountId)?.toDomain()
                ?: return@withContext Result.failure(Exception("Account not found"))
            cloudManager.listCloudFiles(account, remotePath, forceFullRefresh)
        }

    override suspend fun downloadCloudFile(
        accountId: String,
        remotePath: String,
        localTargetDir: String,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)?
    ): Result<File> = withContext(Dispatchers.IO) {
        val account = database.cloudDao().getById(accountId)?.toDomain()
            ?: return@withContext Result.failure(Exception("Account not found"))
        cloudManager.downloadFile(account, remotePath, localTargetDir, onProgress)
    }

    override suspend fun uploadCloudFile(
        accountId: String,
        localFilePath: String,
        remoteTargetDir: String,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val account = database.cloudDao().getById(accountId)?.toDomain()
            ?: return@withContext Result.failure(Exception("Account not found"))
        cloudManager.uploadFile(account, localFilePath, remoteTargetDir, onProgress)
    }

    override suspend fun createFolder(
        accountId: String,
        folderName: String,
        parentPath: String
    ): Result<FileItem> = withContext(Dispatchers.IO) {
        val account = database.cloudDao().getById(accountId)?.toDomain()
            ?: return@withContext Result.failure(Exception("Account not found"))
        cloudManager.createFolder(account, folderName, parentPath)
    }

    override suspend fun deleteCloudFile(
        accountId: String,
        remotePath: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val account = database.cloudDao().getById(accountId)?.toDomain()
            ?: return@withContext Result.failure(Exception("Account not found"))
        cloudManager.deleteItem(account, remotePath)
    }

    override suspend fun getCloudQuota(accountId: String): Result<Pair<Long, Long>> = withContext(Dispatchers.IO) {
        val account = database.cloudDao().getById(accountId)?.toDomain()
            ?: return@withContext Result.failure(Exception("Account not found"))
        cloudManager.getAccountQuota(account)
    }

    override suspend fun renameCloudFile(
        accountId: String,
        remotePath: String,
        newName: String
    ): Result<FileItem> = withContext(Dispatchers.IO) {
        val account = database.cloudDao().getById(accountId)?.toDomain()
            ?: return@withContext Result.failure(Exception("Account not found"))
        cloudManager.renameItem(account, remotePath, newName)
    }

    override suspend fun downloadCloudThumbnail(accountId: String, nodeId: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        val account = database.cloudDao().getById(accountId)?.toDomain()
            ?: return@withContext Result.failure(Exception("Account not found"))
        cloudManager.downloadThumbnail(account, nodeId)
    }

    override suspend fun getCloudStreamableLink(accountId: String, remotePath: String): Result<String> = withContext(Dispatchers.IO) {
        val account = database.cloudDao().getById(accountId)?.toDomain()
            ?: return@withContext Result.failure(Exception("Account not found"))
        cloudManager.getStreamableLink(account, remotePath)
    }
}

