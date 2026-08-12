package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.streamflixreborn.streamflix.models.UserProfile
import com.streamflixreborn.streamflix.providers.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray

object UserProfileManager {
    private const val TAG = "UserProfileManager"
    private const val KEY_PROFILES = "profiles_json_array"
    private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
    private const val KEY_HAS_COMPLETED_ONBOARDING = "has_completed_onboarding"
    private const val KEY_PENDING_SPOTLIGHT = "pending_provider_spotlight"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // In-memory snapshot of the profiles DataStore. Keeps the synchronous API
    // while persisting through DataStore (async, Flow-based, with automatic
    // migration from the legacy "streamflix_user_profiles" SharedPreferences).
    private val snapshotFlow = MutableStateFlow<Preferences>(emptyPreferences())

    private var isLoaded = false

    val hasCompletedOnboarding: Boolean
        get() = UserPreferences.currentLanguage != null && hasCompletedOnboardingFlag

    private var hasCompletedOnboardingFlag: Boolean = false

    var isSpotlightPending: Boolean = false
        private set

    // In-memory flag to ensure profile selection is only asked once per app launch session
    var isSessionProfileSelected: Boolean = false

    fun init(context: Context) {
        ensureLoaded(context)
        hasCompletedOnboardingFlag = snapshotFlow.value[booleanPreferencesKey(KEY_HAS_COMPLETED_ONBOARDING)] ?: false
        isSpotlightPending = snapshotFlow.value[booleanPreferencesKey(KEY_PENDING_SPOTLIGHT)] ?: false
    }

    /**
     * Lazily initializes the profiles DataStore on first use (called from every
     * public method, so it also works when invoked before an explicit [init],
     * e.g. from UserPreferences.currentProvider).
     */
    private fun ensureLoaded(context: Context) {
        if (isLoaded) return
        synchronized(this) {
            if (isLoaded) return
            AppDataStores.init(context)
            snapshotFlow.value = runBlocking { AppDataStores.userProfilesDataStore.data.first() }
            scope.launch {
                AppDataStores.userProfilesDataStore.data.collect { snapshotFlow.value = it }
            }
            isLoaded = true
        }
    }

    /** Write-through: updates the snapshot synchronously and persists async. */
    private fun edit(context: Context, transform: MutablePreferences.() -> Unit) {
        ensureLoaded(context)
        snapshotFlow.update { it.toMutablePreferences().apply(transform) }
        scope.launch {
            AppDataStores.userProfilesDataStore.edit(transform)
        }
    }

    private fun getString(context: Context, key: String): String? {
        ensureLoaded(context)
        return snapshotFlow.value[stringPreferencesKey(key)]
    }

    private fun setString(context: Context, key: String, value: String?) = edit(context) {
        if (value == null) remove(stringPreferencesKey(key)) else set(stringPreferencesKey(key), value)
    }

    fun setOnboardingCompleted(context: Context, completed: Boolean) {
        hasCompletedOnboardingFlag = completed
        edit(context) {
            set(booleanPreferencesKey(KEY_HAS_COMPLETED_ONBOARDING), completed)
        }
    }

    fun setSpotlightPending(context: Context, pending: Boolean) {
        isSpotlightPending = pending
        edit(context) {
            set(booleanPreferencesKey(KEY_PENDING_SPOTLIGHT), pending)
        }
    }

    fun getProfiles(context: Context): List<UserProfile> {
        ensureLoaded(context)
        val jsonStr = snapshotFlow.value[stringPreferencesKey(KEY_PROFILES)] ?: return emptyList()
        val list = mutableListOf<UserProfile>()
        runCatching {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(UserProfile.fromJson(obj))
            }
        }.onFailure { Log.e(TAG, "Error parsing profiles JSON", it) }
        return list
    }

    fun getActiveProfileId(context: Context): String? {
        return getString(context, KEY_ACTIVE_PROFILE_ID)
    }

    fun getActiveProfile(context: Context): UserProfile? {
        val activeId = getActiveProfileId(context)
        val profiles = getProfiles(context)
        return profiles.find { it.id == activeId } ?: profiles.firstOrNull()
    }

    fun setActiveProfile(context: Context, profileId: String) {
        setString(context, KEY_ACTIVE_PROFILE_ID, profileId)
        val active = getProfiles(context).find { it.id == profileId }
        active?.let { profile ->
            UserPreferences.selectedTheme = profile.preferredTheme
            // Restore last selected provider for this specific profile
            val savedProviderName = profile.lastSelectedProvider
            val savedProvider = savedProviderName?.let { name -> Provider.providers.keys.find { it.name == name } }

            if (savedProvider != null) {
                UserPreferences.currentProvider = savedProvider
            } else {
                val defaultProvider = Provider.providers.keys.firstOrNull()
                if (defaultProvider != null) {
                    UserPreferences.currentProvider = defaultProvider
                    updateActiveProfileProvider(context, defaultProvider.name)
                }
            }
        }
        com.streamflixreborn.streamflix.database.AppDatabase.resetInstance()
        com.streamflixreborn.streamflix.offline.database.OfflineDatabase.resetInstance()
        ProviderChangeNotifier.notifyProviderChanged()
    }

    fun updateActiveProfileProvider(context: Context, providerName: String) {
        val active = getActiveProfile(context) ?: return
        if (active.lastSelectedProvider != providerName) {
            saveProfile(context, active.copy(lastSelectedProvider = providerName))
        }
    }

    fun saveProfile(context: Context, profile: UserProfile) {
        val profiles = getProfiles(context).toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            profiles[index] = profile
        } else {
            profiles.add(profile)
        }

        val jsonArray = JSONArray()
        profiles.forEach { jsonArray.put(it.toJson()) }
        setString(context, KEY_PROFILES, jsonArray.toString())

        if (getActiveProfile(context) == null) {
            setActiveProfile(context, profile.id)
        }
    }

    fun deleteProfile(context: Context, profileId: String) {
        val profiles = getProfiles(context).filterNot { it.id == profileId }
        val jsonArray = JSONArray()
        profiles.forEach { jsonArray.put(it.toJson()) }

        val activeProfileId = getActiveProfileId(context)
        edit(context) {
            set(stringPreferencesKey(KEY_PROFILES), jsonArray.toString())
            if (activeProfileId == profileId) {
                remove(stringPreferencesKey(KEY_ACTIVE_PROFILE_ID))
            }
        }
    }
}
