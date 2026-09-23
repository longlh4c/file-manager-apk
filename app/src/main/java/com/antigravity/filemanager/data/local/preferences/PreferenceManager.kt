package com.antigravity.filemanager.data.local.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// A corrupt settings file (e.g. power loss mid-write) used to make every read throw, crashing the
// app on each launch; start over from defaults instead.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "file_manager_prefs",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { emptyPreferences() }
)

@Singleton
class PreferenceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // A read error (not corruption, handled above) falls back to defaults rather than crashing
    // whichever screen collects these flows.
    private val prefsData: Flow<Preferences> = context.dataStore.data.catch { e ->
        if (e is java.io.IOException) emit(emptyPreferences()) else throw e
    }

    private object Keys {
        val FTP_PORT = intPreferencesKey("ftp_port")
        val HTTP_PORT = intPreferencesKey("http_port")
        val FTP_PASSWORD = stringPreferencesKey("ftp_password")
        val FTP_RANDOM_PASSWORD = booleanPreferencesKey("ftp_random_password")
        val FTP_WAS_RUNNING = booleanPreferencesKey("ftp_was_running")
        val IS_GRID_VIEW = booleanPreferencesKey("is_grid_view")
        val DEFAULT_SORT_OPTION = stringPreferencesKey("default_sort_option")
        val DEFAULT_VIEW_MODE = stringPreferencesKey("default_view_mode")
        val DEFAULT_SHOW_HIDDEN = booleanPreferencesKey("default_show_hidden")
        val LAST_VIEWED_IMAGE_PATH = stringPreferencesKey("last_viewed_image_path")
        val LAST_VIEWED_IMAGE_PARENT = stringPreferencesKey("last_viewed_image_parent")
        val LAST_VIEWED_IMAGE_SORT = stringPreferencesKey("last_viewed_image_sort")
        val LAST_VIEWED_IMAGE_CLOUD_ID = stringPreferencesKey("last_viewed_image_cloud_id")
        val LAST_VIEWED_IMAGE_NAME = stringPreferencesKey("last_viewed_image_name")
        val IS_VIEWING_IMAGE = booleanPreferencesKey("is_viewing_image")
    }

    val ftpPortFlow: Flow<Int> = prefsData.map { it[Keys.FTP_PORT] ?: 1524 }
    val httpPortFlow: Flow<Int> = prefsData.map { it[Keys.HTTP_PORT] ?: 8080 }
    val ftpPasswordFlow: Flow<String> = prefsData.map { it[Keys.FTP_PASSWORD] ?: "" }
    val ftpRandomPasswordFlow: Flow<Boolean> = prefsData.map { it[Keys.FTP_RANDOM_PASSWORD] ?: false }
    // Whether the FTP/HTTP server was left running (as opposed to explicitly stopped by the user) —
    // used to auto-restart it if the OS/OEM battery manager kills the app process outright while
    // it's on, since a plain Android low-memory kill doesn't otherwise bring the FTP listener back
    // on its own. See FtpServerService.onStartCommand's null-intent (system restart) branch.
    val ftpWasRunningFlow: Flow<Boolean> = prefsData.map { it[Keys.FTP_WAS_RUNNING] ?: false }
    val isGridViewFlow: Flow<Boolean> = prefsData.map { it[Keys.IS_GRID_VIEW] ?: true }
    val defaultSortOptionFlow: Flow<String> = prefsData.map { it[Keys.DEFAULT_SORT_OPTION] ?: "BY_NAME_ASC" }
    val defaultViewModeFlow: Flow<String> = prefsData.map { it[Keys.DEFAULT_VIEW_MODE] ?: "LIST" }
    val defaultShowHiddenFlow: Flow<Boolean> = prefsData.map { it[Keys.DEFAULT_SHOW_HIDDEN] ?: false }

    suspend fun saveFtpConfig(port: Int, password: String, isRandom: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.FTP_PORT] = port
            prefs[Keys.FTP_PASSWORD] = password
            prefs[Keys.FTP_RANDOM_PASSWORD] = isRandom
        }
    }

    suspend fun saveNetworkConfig(ftpPort: Int, httpPort: Int, password: String, isRandom: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.FTP_PORT] = ftpPort
            prefs[Keys.HTTP_PORT] = httpPort
            prefs[Keys.FTP_PASSWORD] = password
            prefs[Keys.FTP_RANDOM_PASSWORD] = isRandom
        }
    }

    suspend fun setFtpWasRunning(running: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.FTP_WAS_RUNNING] = running }
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

    val lastViewedImageFlow: Flow<LastViewedImageState?> = prefsData.map { prefs ->
        if (prefs[Keys.IS_VIEWING_IMAGE] == true) {
            val path = prefs[Keys.LAST_VIEWED_IMAGE_PATH]
            if (!path.isNullOrEmpty()) {
                LastViewedImageState(
                    path = path,
                    parentPath = prefs[Keys.LAST_VIEWED_IMAGE_PARENT] ?: "",
                    sortOption = prefs[Keys.LAST_VIEWED_IMAGE_SORT] ?: "BY_NAME_ASC",
                    cloudAccountId = prefs[Keys.LAST_VIEWED_IMAGE_CLOUD_ID]?.takeIf { it.isNotEmpty() },
                    fileName = prefs[Keys.LAST_VIEWED_IMAGE_NAME] ?: ""
                )
            } else null
        } else null
    }

    suspend fun saveLastViewedImage(
        path: String,
        parentPath: String,
        sortOption: String,
        cloudAccountId: String?,
        fileName: String
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_VIEWED_IMAGE_PATH] = path
            prefs[Keys.LAST_VIEWED_IMAGE_PARENT] = parentPath
            prefs[Keys.LAST_VIEWED_IMAGE_SORT] = sortOption
            prefs[Keys.LAST_VIEWED_IMAGE_CLOUD_ID] = cloudAccountId ?: ""
            prefs[Keys.LAST_VIEWED_IMAGE_NAME] = fileName
            prefs[Keys.IS_VIEWING_IMAGE] = true
        }
    }

    suspend fun clearLastViewedImage() {
        context.dataStore.edit { prefs ->
            prefs[Keys.IS_VIEWING_IMAGE] = false
            prefs.remove(Keys.LAST_VIEWED_IMAGE_PATH)
        }
    }
}

data class LastViewedImageState(
    val path: String,
    val parentPath: String,
    val sortOption: String,
    val cloudAccountId: String?,
    val fileName: String
)
