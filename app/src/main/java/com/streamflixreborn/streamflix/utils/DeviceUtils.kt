package com.streamflixreborn.streamflix.utils

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.util.Log
import com.streamflixreborn.streamflix.activities.main.MainMobileActivity
import com.streamflixreborn.streamflix.activities.main.MainTvActivity

object DeviceUtils {

    private const val TAG = "TV_DETECT"

    fun isTvDevice(context: Context): Boolean {
        val preferredLayout = UserPreferences.appLayout
        Log.e(TAG, "preferredLayout = '$preferredLayout'")
        if (preferredLayout == "tv") return true
        if (preferredLayout == "mobile") return false

        // Automatic detection for TV devices
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val mode = uiModeManager?.currentModeType
        Log.e(TAG, "currentModeType = $mode, TELEVISION is ${Configuration.UI_MODE_TYPE_TELEVISION}")
        if (mode == Configuration.UI_MODE_TYPE_TELEVISION) return true

        val pm = context.packageManager
        val hasLeanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        Log.e(TAG, "hasLeanback = $hasLeanback")
        if (hasLeanback) return true
        if (pm.hasSystemFeature("android.hardware.type.television")) return true
        if (pm.hasSystemFeature("amazon.hardware.fire_tv")) return true

        val characteristics = getSystemProperty("ro.build.characteristics")
        Log.e(TAG, "characteristics = '$characteristics'")
        if (characteristics.contains("tv", ignoreCase = true) || characteristics.contains("leanback", ignoreCase = true)) return true

        // TV boxes typically lack a touchscreen
        val hasTouchscreen = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        Log.e(TAG, "hasTouchscreen = $hasTouchscreen")
        if (!hasTouchscreen) return true

        return false
    }

    private fun getSystemProperty(key: String): String {
        return try {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val getMethod = systemPropertiesClass.getMethod("get", String::class.java)
            getMethod.invoke(null, key) as? String ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun launchMainActivity(context: Context) {
        val intent = if (isTvDevice(context)) {
            Log.e(TAG, "Launching MainTvActivity")
            Intent(context, MainTvActivity::class.java)
        } else {
            Log.e(TAG, "Launching MainMobileActivity")
            Intent(context, MainMobileActivity::class.java)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
        if (context is Activity) {
            context.finish()
        }
    }
}
