package com.antigravity.filemanager.data.local.db

import androidx.room.*
import com.antigravity.filemanager.domain.model.CloudAccount
import com.antigravity.filemanager.domain.model.CloudProvider
import com.antigravity.filemanager.domain.model.TrashItem
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "trash_items")
data class TrashEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val originalPath: String,
    val trashPath: String,
    val fileName: String,
    val fileSize: Long,
    val deletedTimestamp: Long,
    val isDirectory: Boolean
) {
    fun toDomain(): TrashItem = TrashItem(
        id = id,
        originalPath = originalPath,
        trashPath = trashPath,
        fileName = fileName,
        fileSize = fileSize,
        deletedTimestamp = deletedTimestamp,
        isDirectory = isDirectory
    )

    companion object {
        fun fromDomain(item: TrashItem): TrashEntity = TrashEntity(
            id = item.id,
            originalPath = item.originalPath,
            trashPath = item.trashPath,
            fileName = item.fileName,
            fileSize = item.fileSize,
            deletedTimestamp = item.deletedTimestamp,
            isDirectory = item.isDirectory
        )
    }
}

@Entity(tableName = "cloud_accounts")
data class CloudEntity(
    @PrimaryKey
    val id: String,
    val provider: String,
    val accountName: String,
    val email: String,
    val displayOrder: Int = 0,
    val connectedAt: Long,
    val totalSpaceBytes: Long?,
    val usedSpaceBytes: Long?,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val sessionHandle: String? = null,
    val rootFolderId: String? = null
) {
    fun toDomain(): CloudAccount = CloudAccount(
        id = id,
        provider = try { CloudProvider.valueOf(provider) } catch (e: Exception) { CloudProvider.GOOGLE_DRIVE },
        accountName = accountName,
        email = email,
        displayOrder = displayOrder,
        connectedAt = connectedAt,
        totalSpaceBytes = totalSpaceBytes,
        usedSpaceBytes = usedSpaceBytes,
        accessToken = accessToken,
        refreshToken = refreshToken,
        sessionHandle = sessionHandle,
        rootFolderId = rootFolderId
    )

    companion object {
        fun fromDomain(account: CloudAccount): CloudEntity = CloudEntity(
            id = account.id,
            provider = account.provider.name,
            accountName = account.accountName,
            email = account.email,
            displayOrder = account.displayOrder,
            connectedAt = account.connectedAt,
            totalSpaceBytes = account.totalSpaceBytes,
            usedSpaceBytes = account.usedSpaceBytes,
            accessToken = account.accessToken,
            refreshToken = account.refreshToken,
            sessionHandle = account.sessionHandle,
            rootFolderId = account.rootFolderId
        )
    }
}

@Dao
interface TrashDao {
    @Query("SELECT * FROM trash_items ORDER BY deletedTimestamp DESC")
    fun observeAll(): Flow<List<TrashEntity>>

    @Query("SELECT * FROM trash_items ORDER BY deletedTimestamp DESC")
    suspend fun getAll(): List<TrashEntity>

    @Query("SELECT * FROM trash_items WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<TrashEntity>

    @Query("SELECT SUM(fileSize) FROM trash_items")
    suspend fun getTotalTrashSize(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: TrashEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<TrashEntity>): List<Long>

    @Query("DELETE FROM trash_items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    @Query("DELETE FROM trash_items")
    suspend fun clearAll()
}

@Dao
interface CloudDao {
    @Query("SELECT * FROM cloud_accounts ORDER BY displayOrder ASC, connectedAt DESC")
    fun observeAll(): Flow<List<CloudEntity>>

    @Query("SELECT * FROM cloud_accounts ORDER BY displayOrder ASC, connectedAt DESC")
    suspend fun getAll(): List<CloudEntity>

    @Query("SELECT * FROM cloud_accounts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CloudEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: CloudEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(accounts: List<CloudEntity>)

    @Query("DELETE FROM cloud_accounts WHERE id = :id")
    suspend fun deleteById(id: String): Int
}

@Entity(tableName = "folder_preferences")
data class FolderPreferenceEntity(
    @PrimaryKey
    val folderPath: String,
    val sortOption: String = "BY_NAME_ASC",
    val showHidden: Boolean = false,
    val viewMode: String = "LIST"
)

@Dao
interface FolderPreferenceDao {
    @Query("SELECT * FROM folder_preferences WHERE folderPath = :path LIMIT 1")
    suspend fun getPreference(path: String): FolderPreferenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePreference(entity: FolderPreferenceEntity)

    @Query("UPDATE folder_preferences SET sortOption = :sortOption")
    suspend fun updateAllSortOption(sortOption: String)

    @Query("UPDATE folder_preferences SET showHidden = :showHidden")
    suspend fun updateAllShowHidden(showHidden: Boolean)

    @Query("UPDATE folder_preferences SET viewMode = :viewMode")
    suspend fun updateAllViewMode(viewMode: String)

    @Query("DELETE FROM folder_preferences")
    suspend fun clearAll()
}

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey
    val path: String,
    val name: String,
    val addedAt: Long = System.currentTimeMillis()
)

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE path = :path")
    suspend fun deleteByPath(path: String)
}

@Database(
    entities = [TrashEntity::class, CloudEntity::class, FolderPreferenceEntity::class, BookmarkEntity::class],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trashDao(): TrashDao
    abstract fun cloudDao(): CloudDao
    abstract fun folderPreferenceDao(): FolderPreferenceDao
    abstract fun bookmarkDao(): BookmarkDao
}

