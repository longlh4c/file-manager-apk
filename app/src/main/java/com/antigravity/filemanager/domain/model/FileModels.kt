package com.antigravity.filemanager.domain.model

import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

enum class FolderBadgeType {
    CAMERA,      // DCIM
    DOCUMENTS,   // Documents
    DOWNLOAD,    // Download
    MOVIES,      // Movies
    MUSIC,       // Music
    TRASH,       // Cloud provider's own Trash/Rubbish Bin (Google Drive, MEGA)
    STANDARD     // Other folders
}

enum class AppSourceBadge {
    MESSENGER,
    ZALO,
    DOWNLOAD,
    CAMERA,
    SCREENSHOT,
    INSTAGRAM,
    REDDIT,
    NINE_GAG,
    OFFICE_LENS,
    GENERIC_IMAGE,
    GENERIC_AUDIO,
    GENERIC_VIDEO,
    GENERIC_DOCUMENT,
    NONE
}

enum class CategoryType {
    MAIN_STORAGE,
    DOWNLOADS,
    STORAGE_ANALYSIS,
    IMAGES,
    AUDIO,
    VIDEOS,
    DOCUMENTS,
    NEW_FILES,
    CLOUD,
    ACCESS_FROM_NETWORK,
    RECYCLE_BIN
}

enum class CloudProvider {
    GOOGLE_DRIVE,
    DROPBOX,
    MEGA
}

enum class FileSortOption {
    BY_NAME_ASC,
    BY_NAME_DESC,
    BY_DATE_DESC,
    BY_DATE_ASC,
    BY_SIZE_DESC,
    BY_SIZE_ASC,
    BY_TYPE
}

data class FileItem(
    val id: String,
    val name: String,
    val path: String,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val isDirectory: Boolean = false,
    val mimeType: String = "*/*",
    val extension: String = "",
    val itemCount: Int = 0,
    // Direct-child breakdown of itemCount — currently only populated for MEGA folders (see
    // MegaApiClient.listFiles), where it's free to compute from the already-grouped children.
    val subfolderCount: Int = 0,
    val fileChildCount: Int = 0,
    val thumbnailUri: String? = null,
    val appSourceBadge: AppSourceBadge = AppSourceBadge.NONE,
    val folderBadgeType: FolderBadgeType = FolderBadgeType.STANDARD,
    val isHidden: Boolean = false,
    val isSelected: Boolean = false
) {
    val formattedSize: String
        get() = formatBytes(size)

    val formattedDate: String
        get() {
            if (lastModified <= 0L) return ""
            val cal = Calendar.getInstance().apply { timeInMillis = lastModified }
            val day = cal.get(Calendar.DAY_OF_MONTH)
            val month = cal.get(Calendar.MONTH) + 1
            val year = cal.get(Calendar.YEAR)
            return "$day thg $month, $year"
        }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            val format = DecimalFormat("#,##0.##")
            val index = digitGroups.coerceIn(0, units.size - 1)
            return "${format.format(bytes / Math.pow(1024.0, index.toDouble()))} ${units[index]}"
        }
    }
}

data class MediaFolder(
    val id: String,
    val name: String,
    val path: String,
    val itemCount: Int,
    val latestThumbnailUri: String? = null,
    val appSourceBadge: AppSourceBadge = AppSourceBadge.NONE,
    val totalSizeBytes: Long = 0L,
    val lastModified: Long = 0L
) {
    val formattedSize: String
        get() = FileItem.formatBytes(totalSizeBytes)
}

data class StorageVolumeInfo(
    val totalBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long
) {
    val usedPercentage: Float
        get() = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    val usedPercentageInt: Int
        get() = (usedPercentage * 100).toInt()

    val formattedUsed: String
        get() = FileItem.formatBytes(usedBytes)

    val formattedTotal: String
        get() = FileItem.formatBytes(totalBytes)

    val formattedFree: String
        get() = FileItem.formatBytes(freeBytes)
}

data class CategorySummary(
    val type: CategoryType,
    val title: String,
    val totalSizeBytes: Long = 0L,
    val itemCount: Int = 0,
    val subtitle: String = ""
) {
    val formattedDisplay: String
        get() = when (type) {
            CategoryType.MAIN_STORAGE -> subtitle
            CategoryType.STORAGE_ANALYSIS -> subtitle
            CategoryType.CLOUD -> if (itemCount > 0) "($itemCount)" else ""
            CategoryType.RECYCLE_BIN -> if (totalSizeBytes > 0L) FileItem.formatBytes(totalSizeBytes) else ""
            CategoryType.ACCESS_FROM_NETWORK -> ""
            else -> {
                if (totalSizeBytes > 0L || itemCount > 0) {
                    val sizeStr = FileItem.formatBytes(totalSizeBytes)
                    if (itemCount > 0) "$sizeStr ($itemCount)" else sizeStr
                } else ""
            }
        }
}

data class StorageCategoryBreakdown(
    val imagesBytes: Long,
    val audioBytes: Long,
    val videosBytes: Long,
    val documentsBytes: Long,
    val archivesBytes: Long,
    val othersBytes: Long
)

data class LargeFileItem(
    val id: String,
    val name: String,
    val path: String,
    val relativeDir: String,
    val sizeBytes: Long,
    val extension: String,
    val lastModified: Long = 0L
) {
    val formattedSize: String
        get() = FileItem.formatBytes(sizeBytes)

    val formattedDate: String
        get() {
            if (lastModified <= 0L) return ""
            val cal = Calendar.getInstance().apply { timeInMillis = lastModified }
            val day = cal.get(Calendar.DAY_OF_MONTH)
            val month = cal.get(Calendar.MONTH) + 1
            val year = cal.get(Calendar.YEAR)
            return "$day thg $month, $year"
        }
}

/** One file within a [DuplicateGroup] — all items in a group share identical content (same
 * size + hash). [isOriginal] flags the earliest-modified copy, kept as the suggested one to keep. */
data class DuplicateFileEntry(
    val id: String,
    val name: String,
    val path: String,
    val relativeDir: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val isOriginal: Boolean
) {
    val formattedSize: String
        get() = FileItem.formatBytes(sizeBytes)

    val formattedDate: String
        get() {
            if (lastModified <= 0L) return ""
            val cal = Calendar.getInstance().apply { timeInMillis = lastModified }
            val day = cal.get(Calendar.DAY_OF_MONTH)
            val month = cal.get(Calendar.MONTH) + 1
            val year = cal.get(Calendar.YEAR)
            return "$day thg $month, $year"
        }
}

/** A cluster of files with byte-identical content. [wastedBytes] is the size of every copy
 * except the original — i.e. what's reclaimed if all but [DuplicateFileEntry.isOriginal] are deleted. */
data class DuplicateGroup(
    val key: String,
    val items: List<DuplicateFileEntry>,
    val wastedBytes: Long
)

data class StorageAnalysisData(
    val volumeInfo: StorageVolumeInfo,
    val breakdown: StorageCategoryBreakdown,
    val largeFiles: List<LargeFileItem>,
    val largeFilesTotalBytes: Long,
    val recycleBinBytes: Long,
    val recycleBinSampleItem: FileItem? = null,
    val duplicateFileGroups: List<DuplicateGroup> = emptyList(),
    val duplicateFilesBytes: Long = 0L
)

data class CloudAccount(
    val id: String,
    val provider: CloudProvider,
    val accountName: String,
    val email: String,
    val displayOrder: Int = 0,
    val connectedAt: Long = System.currentTimeMillis(),
    val totalSpaceBytes: Long? = null,
    val usedSpaceBytes: Long? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val sessionHandle: String? = null,
    val rootFolderId: String? = null
) {
    val effectiveTotalBytes: Long
        get() = totalSpaceBytes ?: when (provider) {
            CloudProvider.GOOGLE_DRIVE -> 15L * 1024 * 1024 * 1024
            CloudProvider.DROPBOX -> 2L * 1024 * 1024 * 1024
            CloudProvider.MEGA -> 50L * 1024 * 1024 * 1024
        }

    val effectiveUsedBytes: Long
        get() = usedSpaceBytes ?: 0L

    val freeSpaceBytes: Long
        get() = (effectiveTotalBytes - effectiveUsedBytes).coerceAtLeast(0L)

    val usedPercentage: Int
        get() = if (effectiveTotalBytes > 0) ((effectiveUsedBytes.toDouble() / effectiveTotalBytes.toDouble()) * 100).toInt() else 0

    // Compact form ("36.14/50 GB Used") sharing one unit for both numbers — picking the unit
    // independently per number (as plain formatBytes() does) could pair mismatched units
    // (e.g. "512 MB/50 GB") whenever used and total don't happen to fall in the same magnitude.
    val formattedQuotaText: String
        get() {
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = if (effectiveTotalBytes <= 0) 0 else
                (Math.log10(effectiveTotalBytes.toDouble()) / Math.log10(1024.0)).toInt()
            val index = digitGroups.coerceIn(0, units.size - 1)
            val divisor = Math.pow(1024.0, index.toDouble())
            val format = DecimalFormat("#,##0.##")
            val usedStr = format.format(effectiveUsedBytes / divisor)
            val totalStr = format.format(effectiveTotalBytes / divisor)
            return "$usedStr/$totalStr ${units[index]} Used"
        }

    val formattedFreeSpace: String
        get() = formattedQuotaText
}

data class TrashItem(
    val id: Long = 0L,
    val originalPath: String,
    val trashPath: String,
    val fileName: String,
    val fileSize: Long,
    val deletedTimestamp: Long,
    val isDirectory: Boolean
) {
    val formattedSize: String
        get() = FileItem.formatBytes(fileSize)
}

data class FtpServerState(
    val isRunning: Boolean = false,
    val ipAddress: String? = null,
    val port: Int = 1524,
    val password: String = "",
    val isRandomPassword: Boolean = false,
    val showHiddenFiles: Boolean = false
) {
    val accessUrl: String
        get() = if (ipAddress != null) "ftp://$ipAddress:$port" else ""
}

data class Bookmark(
    val path: String,
    val name: String,
    val addedAt: Long = System.currentTimeMillis()
)

data class OverwriteConflict(
    val name: String,
    val existingSize: Long,
    val newSize: Long,
    /** True when the conflicting item is a folder — its "size" is always 0 in this app's model
     * (folders don't track aggregate content size), so the UI shouldn't show a misleading
     * "0 B -> 0 B" size comparison for it the way it does for an actual file conflict. */
    val isDirectory: Boolean = false
)

/** A direct, playable/decodable URL for a cloud media file, plus any HTTP headers the request
 * needs (e.g. Google Drive requires a bearer token on every request; Dropbox's temporary link
 * is pre-signed and needs none). */
data class CloudStreamSource(
    val url: String,
    val headers: Map<String, String> = emptyMap()
)

data class CloudTransferProgress(
    val currentFileName: String = "",
    val currentIndex: Int = 1,
    val totalFiles: Int = 1,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val isIndeterminate: Boolean = false,
    val isUpload: Boolean = false,
    // Overrides the upload/download title, icon, and placeholder text below with a generic
    // operation — e.g. "Compressing" / "Extracting" — while reusing the same progress dialog.
    val operationLabel: String? = null
) {
    val progressFraction: Float
        get() = if (totalBytes > 0) (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    val formattedProgressText: String
        get() {
            if (totalBytes <= 0) {
                if (bytesTransferred > 0) return FileItem.formatBytes(bytesTransferred)
                return operationLabel?.let { "$it..." } ?: if (isUpload) "Uploading..." else "Downloading..."
            }
            val percent = (progressFraction * 100).toInt()
            return "${FileItem.formatBytes(bytesTransferred)} / ${FileItem.formatBytes(totalBytes)} ($percent%)"
        }
}
