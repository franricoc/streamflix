package com.streamflixreborn.streamflix.activities.onboarding

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.cardview.widget.CardView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.models.UserProfile
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.utils.AppLanguageManager
import com.streamflixreborn.streamflix.utils.DeviceUtils
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.UserProfileManager

class OnboardingTvActivity : FragmentActivity() {

    private var selectedLanguageCode: String = "es"
    private var selectedProvider: Provider? = null

    private lateinit var step1Container: View
    private lateinit var step2Container: View
    private lateinit var step3Container: View
    private lateinit var etProfileName: EditText
    private lateinit var btnConfirmName: Button
    private lateinit var layoutLanguages: LinearLayout
    private lateinit var layoutProviders: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.tvThemeRes(UserPreferences.selectedTheme))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding_tv)

        step1Container = findViewById(R.id.step1ContainerTv)
        step2Container = findViewById(R.id.step2ContainerTv)
        step3Container = findViewById(R.id.step3ContainerTv)
        etProfileName = findViewById(R.id.etTvProfileName)
        btnConfirmName = findViewById(R.id.btnTvConfirmProfileName)
        layoutLanguages = findViewById(R.id.layoutTvLanguages)
        layoutProviders = findViewById(R.id.layoutTvProviders)

        setupStep1()
    }

    private fun setupStep1() {
        etProfileName.requestFocus()

        fun proceedToStep2() {
            val name = etProfileName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Ingresa tu nombre de usuario", Toast.LENGTH_SHORT).show()
                return
            }

            val profile = UserProfile(name = name)
            UserProfileManager.saveProfile(this, profile)
            UserProfileManager.setActiveProfile(this, profile.id)

            step1Container.visibility = View.GONE
            step2Container.visibility = View.VISIBLE
            setupStep2()
        }

        btnConfirmName.setOnClickListener { proceedToStep2() }

        etProfileName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_NULL) {
                proceedToStep2()
                true
            } else {
                false
            }
        }
    }

    private fun setupStep2() {
        layoutLanguages.removeAllViews()
        val languages = listOf(
            "Español" to "es",
            "English" to "en",
            "Français" to "fr",
            "Italiano" to "it",
            "Deutsch" to "de"
        )

        languages.forEachIndexed { index, (name, code) ->
            val button = Button(this).apply {
                text = name
                textSize = 18f
                isAllCaps = false
                setBackgroundColor(Color.parseColor("#2A2A2A"))
                setTextColor(Color.WHITE)
                isFocusable = true
                isFocusableInTouchMode = true

                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        setBackgroundColor(Color.parseColor("#E50914"))
                        scaleX = 1.1f
                        scaleY = 1.1f
                    } else {
                        setBackgroundColor(Color.parseColor("#2A2A2A"))
                        scaleX = 1.0f
                        scaleY = 1.0f
                    }
                }

                setOnClickListener {
                    selectedLanguageCode = code
                    AppLanguageManager.setSelectedLanguage(code)

                    step2Container.visibility = View.GONE
                    step3Container.visibility = View.VISIBLE
                    setupStep3()
                }
            }

            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(12, 0, 12, 0)
            }
            layoutLanguages.addView(button, params)

            if (index == 0) {
                button.post { button.requestFocus() }
            }
        }
    }

    private fun setupStep3() {
        layoutProviders.removeAllViews()
        val allProviders = Provider.providers.keys.toList()

        val filteredProviders = allProviders.filter { provider ->
            provider.language?.lowercase()?.contains(selectedLanguageCode.lowercase()) == true
        }.ifEmpty { allProviders }

        filteredProviders.forEachIndexed { index, provider ->
            val card = CardView(this).apply {
                setCardBackgroundColor(Color.parseColor("#1F1F1F"))
                radius = 16f
                isFocusable = true
                isFocusableInTouchMode = true

                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        setCardBackgroundColor(Color.parseColor("#3D1B1E"))
                        cardElevation = 12f
                        scaleX = 1.03f
                        scaleY = 1.03f
                    } else {
                        setCardBackgroundColor(Color.parseColor("#1F1F1F"))
                        cardElevation = 4f
                        scaleX = 1.0f
                        scaleY = 1.0f
                    }
                }

                val layout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 20, 24, 20)

                    val tvName = TextView(context).apply {
                        text = provider.name
                        setTextColor(Color.WHITE)
                        textSize = 18f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }
                    val tvDesc = TextView(context).apply {
                        text = getProviderContentDescription(provider.name)
                        setTextColor(Color.parseColor("#BBBBBB"))
                        textSize = 14f
                        setPadding(0, 4, 0, 0)
                    }

                    addView(tvName)
                    addView(tvDesc)
                }
                addView(layout)

                setOnClickListener {
                    UserPreferences.currentProvider = provider
                    UserProfileManager.setOnboardingCompleted(this@OnboardingTvActivity, true)
                    UserProfileManager.setSpotlightPending(this@OnboardingTvActivity, true)
                    DeviceUtils.launchMainActivity(this@OnboardingTvActivity)
                }
            }

            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 10, 0, 10)
            }
            layoutProviders.addView(card, params)

            if (index == 0) {
                card.post { card.requestFocus() }
            }
        }
    }

    private fun getProviderContentDescription(providerName: String): String {
        return when {
            providerName.contains("Anime", ignoreCase = true) || providerName.contains("AniWorld", ignoreCase = true) ->
                "Anime, OVAs y Películas de Animación Japonesa"
            providerName.contains("Cuevana", ignoreCase = true) || providerName.contains("Cine", ignoreCase = true) || providerName.contains("Poseidon", ignoreCase = true) || providerName.contains("Flix", ignoreCase = true) ->
                "Películas de Estreno en HD, Series y Cine Internacional"
            providerName.contains("Doramas", ignoreCase = true) ->
                "Doramas, K-Dramas y Series Asiáticas Subtituladas"
            providerName.contains("Cable", ignoreCase = true) || providerName.contains("IPTV", ignoreCase = true) || providerName.contains("Tv", ignoreCase = true) ->
                "Televisión en Vivo, Canales de Cable y Deportes"
            providerName.contains("Serie", ignoreCase = true) || providerName.contains("Streaming", ignoreCase = true) ->
                "Series de Televisión, Temporadas y Episodios Completos"
            providerName.contains("TMDb", ignoreCase = true) ->
                "Buscador Universal, Fichas de Cine e Información"
            else -> "Catálogo Multimedia de Películas y Series"
        }
    }
}
