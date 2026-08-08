package com.streamflixreborn.streamflix.models

import org.json.JSONObject
import java.util.UUID

enum class AvatarType {
    PRESET,
    CUSTOM_URI
}

data class UserProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val avatarType: AvatarType = AvatarType.PRESET,
    val avatarValue: String = "avatar_red", // preset color key or file path/URI
    val isKids: Boolean = false,
    val preferredTheme: String = "default",
    val lastSelectedProvider: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("avatarType", avatarType.name)
            put("avatarValue", avatarValue)
            put("isKids", isKids)
            put("preferredTheme", preferredTheme)
            put("lastSelectedProvider", lastSelectedProvider ?: "")
            put("createdAt", createdAt)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): UserProfile {
            return UserProfile(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", "Usuario"),
                avatarType = runCatching { AvatarType.valueOf(json.optString("avatarType", "PRESET")) }.getOrDefault(AvatarType.PRESET),
                avatarValue = json.optString("avatarValue", "avatar_red"),
                isKids = json.optBoolean("isKids", false),
                preferredTheme = json.optString("preferredTheme", "default"),
                lastSelectedProvider = json.optString("lastSelectedProvider", "").takeIf { it.isNotBlank() },
                createdAt = json.optLong("createdAt", System.currentTimeMillis())
            )
        }

        val PRESET_AVATARS = listOf(
            "avatar_red" to 0xFFFF4B4B.toInt(),     // Red / Carmesí
            "avatar_blue" to 0xFF3B82F6.toInt(),    // Blue / Azul
            "avatar_purple" to 0xFF8B5CF6.toInt(),  // Purple / Morado
            "avatar_gold" to 0xFFF59E0B.toInt(),    // Gold / Dorado
            "avatar_emerald" to 0xFF10B981.toInt()  // Emerald / Esmeralda
        )
    }
}
