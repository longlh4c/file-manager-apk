package com.antigravity.filemanager.domain.repository

import com.antigravity.filemanager.domain.model.*
import kotlinx.coroutines.flow.Flow
import java.io.File

interface IStorageRepository {
    suspend fun getStorageVolumeInfo(): StorageVolumeInfo
    suspend fun getCategorySummaries(): List<CategorySummary>
    fun observeCategorySummaries(): Flow<List<CategorySummary>>
}

interface IStorageAnalysisRepository {
    suspend fun getStorageAnalysisData(): StorageAnalysisData
    suspend fun getAllLargeFiles(): List<LargeFileItem>
}

interface IFileRepository {
    suspend fun getFilesInDirectory(
        directoryPath: String,
        sortOption: FileSortOption = FileSortOption.BY_NAME_ASC,
        showHidden: Boolean = false
    ): List<FileItem>

    suspend fun getMediaFolders(
        categoryType: CategoryType,
        sortOption: FileSortOption = FileSortOption.BY_NAME_ASC
    ): List<MediaFolder>

    suspend fun getMediaFilesInFolder(
        folderPath: String,
        categoryType: CategoryType,
        sortOption: FileSortOption = FileSortOption.BY_DATE_DESC
    ): List<FileItem>

    suspend fun getAllDocuments(
        sortOption: FileSortOption = FileSortOption.BY_DATE_DESC
    ): List<FileItem>

    suspend fun searchFiles(
        query: String,
        rootPath: String? = null,
        categoryType: CategoryType? = null
    ): List<FileItem>

    // Per-file conflict resolution: a name in overwriteNames replaces the existing item, a name in
    // skipNames is left out of the operation entirely, and any other conflicting name defaults to
    // "keep both" (renamed with a numbered suffix) — matching the Overwrite/Skip/Keep Both choices
    // in OverwriteConflictDialog. Names not in either set and with no conflict are unaffected.
    suspend fun copyFiles(sourcePaths: List<String>, targetDirectory: String, overwriteNames: Set<String> = emptySet(), skipNames: Set<String> = emptySet()): Result<Unit>
    suspend fun moveFiles(sourcePaths: List<String>, targetDirectory: String, overwriteNames: Set<String> = emptySet(), skipNames: Set<String> = emptySet()): Result<Unit>
    suspend fun findCopyConflicts(sourcePaths: List<String>, targetDirectory: String): List<com.antigravity.filemanager.domain.model.OverwriteConflict>
    suspend fun renameFile(filePath: String, newName: String): Result<FileItem>
    suspend fun createDirectory(parentPath: String, directoryName: String): Result<FileItem>
    suspend fun zipFiles(
        sourcePaths: List<String>,
        targetZipPath: String,
        onProgress: ((currentFile: String, currentIndex: Int, totalFiles: Int) -> Unit)? = null
    ): Result<FileItem>
    suspend fun extractZip(zipFilePath: String, targetDirectory: String): Result<Unit>
    suspend fun getFileDetails(filePath: String): FileItem?
}

interface IRecycleBinRepository {
    fun observeTrashItems(): Flow<List<TrashItem>>
    suspend fun getTrashItems(): List<TrashItem>
    suspend fun moveToTrash(filePaths: List<String>): Result<Int>
    suspend fun restoreFromTrash(trashIds: List<Long>): Result<Int>
    suspend fun deletePermanently(trashIds: List<Long>): Result<Int>
    suspend fun emptyTrash(): Result<Unit>
    suspend fun getTrashTotalSize(): Long
}

interface ICloudRepository {
    fun observeConnectedAccounts(): Flow<List<CloudAccount>>
    suspend fun getConnectedAccounts(): List<CloudAccount>
    suspend fun addAccount(account: CloudAccount): Result<Unit>
    suspend fun removeAccount(accountId: String): Result<Unit>
    suspend fun updateAccountsOrder(accounts: List<CloudAccount>): Result<Unit>
    suspend fun getCloudFiles(accountId: String, remotePath: String, forceFullRefresh: Boolean = false): Result<List<FileItem>>
    suspend fun downloadCloudFile(
        accountId: String,
        remotePath: String,
        localTargetDir: String,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File>
    suspend fun uploadCloudFile(
        accountId: String,
        localFilePath: String,
        remoteTargetDir: String,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<Unit>
    suspend fun createFolder(accountId: String, folderName: String, parentPath: String): Result<FileItem>
    suspend fun deleteCloudFile(accountId: String, remotePath: String, moveToTrash: Boolean = true): Result<Unit>
    /** Permanently deletes many MEGA items in as few HTTP round trips as possible instead of one
     * request per item — see CloudManager.deletePermanentlyBatchMega. MEGA only; other providers
     * return an empty map so callers know to fall back to their own per-item loop. */
    suspend fun deletePermanentlyBatchMega(accountId: String, remotePaths: List<String>): Map<String, Result<Unit>>
    suspend fun restoreCloudFile(accountId: String, remotePath: String): Result<Unit>
    suspend fun renameCloudFile(accountId: String, remotePath: String, newName: String): Result<FileItem>
    /** Relocates an item to a different folder within the SAME cloud account, entirely
     * server-side (no download+reupload round trip). See CloudManager.moveItemWithinAccount. */
    suspend fun moveCloudFileWithinAccount(accountId: String, sourcePath: String, targetDir: String): Result<Unit>
    suspend fun getCloudQuota(accountId: String): Result<Pair<Long, Long>>
    suspend fun downloadCloudThumbnail(accountId: String, nodeId: String): Result<ByteArray>
    suspend fun downloadCloudFilePartial(accountId: String, nodeId: String, localTargetFile: java.io.File, maxBytes: Long): Result<java.io.File>
    suspend fun openCloudThumbnailDataSource(accountId: String, nodeId: String): Result<android.media.MediaDataSource>
    suspend fun getCloudStreamableLink(accountId: String, remotePath: String): Result<String>
    suspend fun getCloudStreamSource(accountId: String, remotePath: String): Result<com.antigravity.filemanager.domain.model.CloudStreamSource>
}


interface IFtpServerRepository {
    fun observeFtpServerState(): Flow<FtpServerState>
    suspend fun getFtpServerState(): FtpServerState
    suspend fun startFtpServer(port: Int, password: String, isRandomPassword: Boolean, showHidden: Boolean): Result<Unit>
    suspend fun stopFtpServer(): Result<Unit>
    suspend fun updateConfig(port: Int, password: String, isRandomPassword: Boolean, showHidden: Boolean)
}
