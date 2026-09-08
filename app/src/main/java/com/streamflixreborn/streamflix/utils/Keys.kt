package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.BuildConfig

/**
 * Utility for accessing protected API keys and endpoints.
 * Prioritizes keys configured via BuildConfig / local.properties / CI,
 * falling back gracefully to native library if present, or returning empty strings.
 */
object Keys {
    private val isLoaded = try {
        System.loadLibrary("streamflix-keys")
        true
    } catch (_: Throwable) {
        false
    }

    fun getUprotApiBase(): String =
        BuildConfig.UPROT_API_BASE.ifBlank {
            if (isLoaded) runCatching { nativeGetUprotApiBase() }.getOrDefault("") else ""
        }

    fun getUprotSignKey(): String =
        BuildConfig.UPROT_SIGN_KEY.ifBlank {
            if (isLoaded) runCatching { nativeGetUprotSignKey() }.getOrDefault("") else ""
        }

    fun getUprotDirectApiBase(): String =
        BuildConfig.UPROT_DIRECT_API_BASE.ifBlank {
            if (isLoaded) runCatching { nativeGetUprotDirectApiBase() }.getOrDefault("") else ""
        }

    fun getUprotDirectKey(): String =
        BuildConfig.UPROT_DIRECT_KEY.ifBlank {
            if (isLoaded) runCatching { nativeGetUprotDirectKey() }.getOrDefault("") else ""
        }

    private external fun nativeGetUprotApiBase(): String
    private external fun nativeGetUprotSignKey(): String
    private external fun nativeGetUprotDirectApiBase(): String
    private external fun nativeGetUprotDirectKey(): String
}
