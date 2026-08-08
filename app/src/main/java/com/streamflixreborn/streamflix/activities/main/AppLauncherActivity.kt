package com.streamflixreborn.streamflix.activities.main

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.streamflixreborn.streamflix.activities.onboarding.OnboardingActivity
import com.streamflixreborn.streamflix.utils.DeviceUtils
import com.streamflixreborn.streamflix.utils.UserProfileManager

class AppLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        UserProfileManager.init(this)
        if (!UserProfileManager.hasCompletedOnboarding) {
            val intent = if (DeviceUtils.isTvDevice(this)) {
                Intent(this, com.streamflixreborn.streamflix.activities.onboarding.OnboardingTvActivity::class.java)
            } else {
                Intent(this, OnboardingActivity::class.java)
            }
            startActivity(intent)
            finish()
            return
        }

        DeviceUtils.launchMainActivity(this)
    }
}
