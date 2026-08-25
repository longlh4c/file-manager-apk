package com.antigravity.filemanager.data.repository

import com.antigravity.filemanager.data.local.db.FolderPreferenceDao
import com.antigravity.filemanager.data.local.db.FolderPreferenceEntity
import com.antigravity.filemanager.domain.model.FileSortOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

import com.antigravity.filemanager.data.local.preferences.PreferenceManager
import com.antigravity.filemanager.presentation.components.ViewMode
import kotlinx.coroutines.flow.first

@Singleton
class FolderPreferencesRepository @Inject constructor(
    private val folderPreferenceDao: FolderPreferenceDao,
    private val preferenceManager: PreferenceManager
) {

    suspend fun getSortOption(folderPath: String): FileSortOption = withContext(Dispatchers.IO) {
        val pref = folderPreferenceDao.getPreference(folderPath)
        if (pref != null) {
            try {
                FileSortOption.valueOf(pref.sortOption)
            } catch (e: Exception) {
                getDefaultSortOption()
            }
        } else {
            getDefaultSortOption()
        }
    }

    suspend fun getDefaultSortOption(): FileSortOption = withContext(Dispatchers.IO) {
        val name = preferenceManager.defaultSortOptionFlow.first()
        try {
            FileSortOption.valueOf(name)
        } catch (e: Exception) {
            FileSortOption.BY_NAME_ASC
        }
    }

    suspend fun saveSortOption(folderPath: String, sortOption: FileSortOption, applyToAll: Boolean = false) = withContext(Dispatchers.IO) {
        if (applyToAll) {
            preferenceManager.setDefaultSortOption(sortOption.name)
            folderPreferenceDao.updateAllSortOption(sortOption.name)
        }
        val current = folderPreferenceDao.getPreference(folderPath)
        val entity = FolderPreferenceEntity(
            folderPath = folderPath,
            sortOption = sortOption.name,
            showHidden = current?.showHidden ?: false,
            viewMode = current?.viewMode ?: "LIST"
        )
        folderPreferenceDao.savePreference(entity)
    }

    suspend fun getShowHidden(folderPath: String): Boolean = withContext(Dispatchers.IO) {
        val pref = folderPreferenceDao.getPreference(folderPath)
        if (pref != null) {
            pref.showHidden
        } else {
            preferenceManager.defaultShowHiddenFlow.first()
        }
    }

    suspend fun saveShowHidden(folderPath: String, showHidden: Boolean, applyToAll: Boolean = false) = withContext(Dispatchers.IO) {
        if (applyToAll) {
            preferenceManager.setDefaultShowHidden(showHidden)
            folderPreferenceDao.updateAllShowHidden(showHidden)
        }
        val current = folderPreferenceDao.getPreference(folderPath)
        val entity = FolderPreferenceEntity(
            folderPath = folderPath,
            sortOption = current?.sortOption ?: getDefaultSortOption().name,
            showHidden = showHidden,
            viewMode = current?.viewMode ?: "LIST"
        )
        folderPreferenceDao.savePreference(entity)
    }

    suspend fun getViewMode(folderPath: String): ViewMode = withContext(Dispatchers.IO) {
        val pref = folderPreferenceDao.getPreference(folderPath)
        if (pref != null && pref.viewMode.isNotBlank()) {
            try {
                ViewMode.valueOf(pref.viewMode)
            } catch (e: Exception) {
                getDefaultViewMode()
            }
        } else {
            getDefaultViewMode()
        }
    }

    suspend fun getDefaultViewMode(): ViewMode = withContext(Dispatchers.IO) {
        val name = preferenceManager.defaultViewModeFlow.first()
        try {
            ViewMode.valueOf(name)
        } catch (e: Exception) {
            ViewMode.LIST
        }
    }

    suspend fun saveViewMode(folderPath: String, viewMode: ViewMode, applyToAll: Boolean = false) = withContext(Dispatchers.IO) {
        if (applyToAll) {
            preferenceManager.setDefaultViewMode(viewMode.name)
            folderPreferenceDao.updateAllViewMode(viewMode.name)
        }
        val current = folderPreferenceDao.getPreference(folderPath)
        val entity = FolderPreferenceEntity(
            folderPath = folderPath,
            sortOption = current?.sortOption ?: getDefaultSortOption().name,
            showHidden = current?.showHidden ?: false,
            viewMode = viewMode.name
        )
        folderPreferenceDao.savePreference(entity)
    }
}
