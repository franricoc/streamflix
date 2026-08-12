package com.streamflixreborn.streamflix.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.streamflixreborn.streamflix.BuildConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// File names match the legacy SharedPreferences files so that
// SharedPreferencesMigration can move existing user data over on first read.
private const val APP_PREFERENCES_FILE = "${BuildConfig.APPLICATION_ID}.preferences"
private const val USER_PROFILES_FILE = "streamflix_user_profiles"
private const val ANIWORLD_UPDATE_FILE = "AniWorldUpdateTvShowsPrefs"

/**
 * Central holder for the app's [DataStore] instances.
 *
 * The DataStore file names intentionally match the legacy SharedPreferences file
 * names so [SharedPreferencesMigration] copies existing user settings (video
 * quality, theme, profiles, ...) into DataStore on the first read. A corrupted
 * DataStore file is discarded instead of crashing the app.
 */
object AppDataStores {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialized = false

    private lateinit var appPreferences: DataStore<Preferences>
    private lateinit var userProfiles: DataStore<Preferences>
    private lateinit var aniWorldUpdate: DataStore<Preferences>

    /**
     * Creates the stores. Called from [UserPreferences.setup], which runs in
     * `StreamFlixApp.attachBaseContext` before the language wrap, so the raw
     * base context is used (applicationContext is not available at that point).
     */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val dataStoreDir = File(context.filesDir, "datastore")
            appPreferences = createStore(context, dataStoreDir, APP_PREFERENCES_FILE)
            userProfiles = createStore(context, dataStoreDir, USER_PROFILES_FILE)
            aniWorldUpdate = createStore(context, dataStoreDir, ANIWORLD_UPDATE_FILE)
            initialized = true
        }
    }

    private fun createStore(context: Context, dir: File, name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(dir, "$name.preferences_pb") },
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            migrations = listOf(SharedPreferencesMigration(context, name)),
        )

    val appPreferencesDataStore: DataStore<Preferences>
        get() = appPreferences

    val userProfilesDataStore: DataStore<Preferences>
        get() = userProfiles

    val aniWorldUpdateDataStore: DataStore<Preferences>
        get() = aniWorldUpdate
}
