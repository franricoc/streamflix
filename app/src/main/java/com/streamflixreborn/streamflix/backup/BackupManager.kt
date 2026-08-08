package com.streamflixreborn.streamflix.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.streamflixreborn.streamflix.models.UserProfile
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.UserProfileManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

object BackupManager {
    private const val TAG = "BackupManager"

    fun exportBackup(context: Context, destinationUri: Uri): Boolean {
        return runCatching {
            val root = JSONObject()

            // 1. Export profiles
            val profilesArray = JSONArray()
            UserProfileManager.getProfiles(context).forEach { profilesArray.put(it.toJson()) }
            root.put("profiles", profilesArray)

            val activeProfile = UserProfileManager.getActiveProfile(context)
            activeProfile?.let { root.put("active_profile_id", it.id) }

            // 2. Export general settings
            val settings = JSONObject().apply {
                put("selectedTheme", UserPreferences.selectedTheme)
                put("currentLanguage", UserPreferences.currentLanguage ?: "")
                put("autoplay", UserPreferences.autoplay)
                put("playerGestures", UserPreferences.playerGestures)
                put("favoriteProviders", JSONArray(UserPreferences.favoriteProviders.toList()))
            }
            root.put("settings", settings)

            // Write to OutputStream
            context.contentResolver.openOutputStream(destinationUri)?.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { writer ->
                    writer.write(root.toString(2))
                }
            }
            true
        }.getOrElse {
            Log.e(TAG, "Error exporting backup", it)
            false
        }
    }

    fun importBackup(context: Context, sourceUri: Uri): Boolean {
        return runCatching {
            val content = StringBuilder()
            context.contentResolver.openInputStream(sourceUri)?.use { isStream ->
                BufferedReader(InputStreamReader(isStream, Charsets.UTF_8)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        content.append(line)
                        line = reader.readLine()
                    }
                }
            }

            val root = JSONObject(content.toString())

            // 1. Import profiles
            if (root.has("profiles")) {
                val profilesArray = root.getJSONArray("profiles")
                for (i in 0 until profilesArray.length()) {
                    val profileJson = profilesArray.getJSONObject(i)
                    val profile = UserProfile.fromJson(profileJson)
                    UserProfileManager.saveProfile(context, profile)
                }
            }

            if (root.has("active_profile_id")) {
                UserProfileManager.setActiveProfile(context, root.getString("active_profile_id"))
            }

            // 2. Import settings
            if (root.has("settings")) {
                val settings = root.getJSONObject("settings")
                if (settings.has("selectedTheme")) {
                    UserPreferences.selectedTheme = settings.getString("selectedTheme")
                }
                if (settings.has("autoplay")) {
                    UserPreferences.autoplay = settings.getBoolean("autoplay")
                }
                if (settings.has("playerGestures")) {
                    UserPreferences.playerGestures = settings.getBoolean("playerGestures")
                }
                if (settings.has("favoriteProviders")) {
                    val favoritesArr = settings.getJSONArray("favoriteProviders")
                    val favoritesSet = mutableSetOf<String>()
                    for (i in 0 until favoritesArr.length()) {
                        favoritesSet.add(favoritesArr.getString(i))
                    }
                    UserPreferences.favoriteProviders = favoritesSet
                }
            }
            true
        }.getOrElse {
            Log.e(TAG, "Error importing backup", it)
            false
        }
    }
}
