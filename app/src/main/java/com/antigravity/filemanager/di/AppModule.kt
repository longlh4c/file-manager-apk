package com.antigravity.filemanager.di

import android.content.Context
import androidx.room.Room
import com.antigravity.filemanager.data.local.db.AppDatabase
import com.antigravity.filemanager.data.local.db.CloudDao
import com.antigravity.filemanager.data.local.db.TrashDao
import com.antigravity.filemanager.data.repository.*
import com.antigravity.filemanager.domain.repository.*
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "file_manager.db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideTrashDao(database: AppDatabase): TrashDao = database.trashDao()

    @Provides
    fun provideCloudDao(database: AppDatabase): CloudDao = database.cloudDao()

    @Provides
    fun provideFolderPreferenceDao(database: AppDatabase): com.antigravity.filemanager.data.local.db.FolderPreferenceDao = database.folderPreferenceDao()

    @Provides
    fun provideBookmarkDao(database: AppDatabase): com.antigravity.filemanager.data.local.db.BookmarkDao = database.bookmarkDao()

    @Provides
    @Singleton
    fun provideOkHttpClient(): okhttp3.OkHttpClient {
        return okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}


@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindStorageRepository(impl: StorageRepositoryImpl): IStorageRepository

    @Binds
    @Singleton
    abstract fun bindStorageAnalysisRepository(impl: StorageAnalysisRepositoryImpl): IStorageAnalysisRepository

    @Binds
    @Singleton
    abstract fun bindFileRepository(impl: FileRepositoryImpl): IFileRepository

    @Binds
    @Singleton
    abstract fun bindRecycleBinRepository(impl: RecycleBinRepositoryImpl): IRecycleBinRepository

    @Binds
    @Singleton
    abstract fun bindFtpServerRepository(impl: FtpServerRepositoryImpl): IFtpServerRepository

    @Binds
    @Singleton
    abstract fun bindCloudRepository(impl: CloudRepositoryImpl): ICloudRepository
}
