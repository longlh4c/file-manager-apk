package com.antigravity.filemanager.domain.usecase

import com.antigravity.filemanager.data.local.db.BookmarkDao
import com.antigravity.filemanager.data.local.db.BookmarkEntity
import com.antigravity.filemanager.domain.model.Bookmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookmarkUseCase @Inject constructor(
    private val bookmarkDao: BookmarkDao
) {
    fun observeBookmarks(): Flow<List<Bookmark>> =
        bookmarkDao.observeAll().map { entities ->
            entities.map { Bookmark(path = it.path, name = it.name, addedAt = it.addedAt) }
        }

    suspend fun addBookmark(path: String, name: String) = withContext(Dispatchers.IO) {
        bookmarkDao.insert(BookmarkEntity(path = path, name = name))
    }

    suspend fun removeBookmark(path: String) = withContext(Dispatchers.IO) {
        bookmarkDao.deleteByPath(path)
    }
}
