package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.models.UserProfile
import com.streamflixreborn.streamflix.providers.Provider
import org.json.JSONArray

object UserProfileManager {
    private const val TAG = "UserProfileManager"
    private const val PREFS_NAME = "streamflix_user_profiles"
    private const val KEY_PROFILES = "profiles_json_array"
    private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
    private const val KEY_HAS_COMPLETED_ONBOARDING = "has_completed_onboarding"
    private const val KEY_PENDING_SPOTLIGHT = "pending_provider_spotlight"

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val hasCompletedOnboarding: Boolean
        get() = UserPreferences.currentLanguage != null && hasCompletedOnboardingFlag

    private var hasCompletedOnboardingFlag: Boolean = false

    var isSpotlightPending: Boolean = false
        private set

    // In-memory flag to ensure profile selection is only asked once per app launch session
    var isSessionProfileSelected: Boolean = false

    fun init(context: Context) {
        val prefs = getPrefs(context)
        hasCompletedOnboardingFlag = prefs.getBoolean(KEY_HAS_COMPLETED_ONBOARDING, false)
        isSpotlightPending = prefs.getBoolean(KEY_PENDING_SPOTLIGHT, false)
    }

    fun setOnboardingCompleted(context: Context, completed: Boolean) {
        hasCompletedOnboardingFlag = completed
        getPrefs(context).edit().putBoolean(KEY_HAS_COMPLETED_ONBOARDING, completed).apply()
    }

    fun setSpotlightPending(context: Context, pending: Boolean) {
        isSpotlightPending = pending
        getPrefs(context).edit().putBoolean(KEY_PENDING_SPOTLIGHT, pending).apply()
    }

    fun getProfiles(context: Context): List<UserProfile> {
        val jsonStr = getPrefs(context).getString(KEY_PROFILES, null) ?: return emptyList()
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
        return getPrefs(context).getString(KEY_ACTIVE_PROFILE_ID, null)
    }

    fun getActiveProfile(context: Context): UserProfile? {
        val activeId = getActiveProfileId(context)
        val profiles = getProfiles(context)
        return profiles.find { it.id == activeId } ?: profiles.firstOrNull()
    }

    fun setActiveProfile(context: Context, profileId: String) {
        getPrefs(context).edit().putString(KEY_ACTIVE_PROFILE_ID, profileId).apply()
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
        getPrefs(context).edit().putString(KEY_PROFILES, jsonArray.toString()).apply()

        if (getActiveProfile(context) == null) {
            setActiveProfile(context, profile.id)
        }
    }

    fun deleteProfile(context: Context, profileId: String) {
        val profiles = getProfiles(context).filterNot { it.id == profileId }
        val jsonArray = JSONArray()
        profiles.forEach { jsonArray.put(it.toJson()) }

        val editor = getPrefs(context).edit()
        editor.putString(KEY_PROFILES, jsonArray.toString())
        if (getPrefs(context).getString(KEY_ACTIVE_PROFILE_ID, null) == profileId) {
            editor.remove(KEY_ACTIVE_PROFILE_ID)
        }
        editor.apply()
    }
}
