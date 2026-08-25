package com.antigravity.filemanager.data.local.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "file_manager_prefs")

@Singleton
class PreferenceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val FTP_PORT = intPreferencesKey("ftp_port")
        val FTP_PASSWORD = stringPreferencesKey("ftp_password")
        val FTP_RANDOM_PASSWORD = booleanPreferencesKey("ftp_random_password")
        val FTP_SHOW_HIDDEN = booleanPreferencesKey("ftp_show_hidden")
        val IS_GRID_VIEW = booleanPreferencesKey("is_grid_view")
        val DEFAULT_SORT_OPTION = stringPreferencesKey("default_sort_option")
        val DEFAULT_VIEW_MODE = stringPreferencesKey("default_view_mode")
        val DEFAULT_SHOW_HIDDEN = booleanPreferencesKey("default_show_hidden")
    }

    val ftpPortFlow: Flow<Int> = context.dataStore.data.map { it[Keys.FTP_PORT] ?: 1524 }
    val ftpPasswordFlow: Flow<String> = context.dataStore.data.map { it[Keys.FTP_PASSWORD] ?: "" }
    val ftpRandomPasswordFlow: Flow<Boolean> = context.dataStore.data.map { it[Keys.FTP_RANDOM_PASSWORD] ?: false }
    val ftpShowHiddenFlow: Flow<Boolean> = context.dataStore.data.map { it[Keys.FTP_SHOW_HIDDEN] ?: false }
    val isGridViewFlow: Flow<Boolean> = context.dataStore.data.map { it[Keys.IS_GRID_VIEW] ?: true }
    val defaultSortOptionFlow: Flow<String> = context.dataStore.data.map { it[Keys.DEFAULT_SORT_OPTION] ?: "BY_NAME_ASC" }
    val defaultViewModeFlow: Flow<String> = context.dataStore.data.map { it[Keys.DEFAULT_VIEW_MODE] ?: "LIST" }
    val defaultShowHiddenFlow: Flow<Boolean> = context.dataStore.data.map { it[Keys.DEFAULT_SHOW_HIDDEN] ?: false }

    suspend fun saveFtpConfig(port: Int, password: String, isRandom: Boolean, showHidden: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.FTP_PORT] = port
            prefs[Keys.FTP_PASSWORD] = password
            prefs[Keys.FTP_RANDOM_PASSWORD] = isRandom
            prefs[Keys.FTP_SHOW_HIDDEN] = showHidden
        }
    }

    suspend fun setGridView(isGrid: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.IS_GRID_VIEW] = isGrid
        }
    }

    suspend fun setDefaultSortOption(sort: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEFAULT_SORT_OPTION] = sort
        }
    }

    suspend fun setDefaultViewMode(viewMode: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEFAULT_VIEW_MODE] = viewMode
        }
    }

    suspend fun setDefaultShowHidden(show: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEFAULT_SHOW_HIDDEN] = show
        }
    }
}
