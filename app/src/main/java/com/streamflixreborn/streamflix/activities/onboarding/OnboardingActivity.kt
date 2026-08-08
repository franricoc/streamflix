package com.streamflixreborn.streamflix.activities.onboarding

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.streamflixreborn.streamflix.activities.main.MainMobileActivity
import com.streamflixreborn.streamflix.databinding.ActivityOnboardingBinding
import com.streamflixreborn.streamflix.models.AvatarType
import com.streamflixreborn.streamflix.models.UserProfile
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.utils.AppLanguageManager
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.UserProfileManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    private val greetings = listOf("Hola", "Hello", "Bonjour", "Ciao", "Olá", "Hallo", "Привет")
    private var selectedLanguageCode: String = "es"

    private var selectedAvatarValue: String = "avatar_red"
    private var selectedAvatarType: AvatarType = AvatarType.PRESET
    private var customAvatarUri: Uri? = null

    private var selectedTheme: String = ThemeManager.DEFAULT
    private var selectedProvider: Provider? = null

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                saveImageToInternalStorage(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.mobileThemeRes(UserPreferences.selectedTheme))
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupStep1Greeting()
        setupStep2Profile()
        setupStep3Theme()
        setupStep4Provider()
    }

    // ==========================================
    // STEP 1: GREETINGS & LANGUAGE
    // ==========================================
    private fun setupStep1Greeting() {
        lifecycleScope.launch {
            for (greeting in greetings) {
                binding.tvGreetingText.text = greeting
                val fadeIn = ObjectAnimator.ofFloat(binding.tvGreetingText, "alpha", 0f, 1f).setDuration(400)
                val fadeOut = ObjectAnimator.ofFloat(binding.tvGreetingText, "alpha", 1f, 0f).setDuration(400)

                fadeIn.start()
                delay(700)
                fadeOut.start()
                delay(400)
            }

            // Show language selection
            binding.tvGreetingText.text = "Streamflix"
            ObjectAnimator.ofFloat(binding.tvGreetingText, "alpha", 0f, 1f).setDuration(500).start()

            ObjectAnimator.ofFloat(binding.tvSelectLangSubtitle, "alpha", 0f, 1f).setDuration(500).start()
            ObjectAnimator.ofFloat(binding.scrollLanguages, "alpha", 0f, 1f).setDuration(500).start()
            ObjectAnimator.ofFloat(binding.btnConfirmLanguage, "alpha", 0f, 1f).setDuration(500).start()

            populateLanguageChips()
        }

        binding.btnConfirmLanguage.setOnClickListener {
            AppLanguageManager.setSelectedLanguage(selectedLanguageCode)
            transitionStep(binding.stepGreetingContainer, binding.stepProfileContainer)
        }
    }

    private fun populateLanguageChips() {
        binding.layoutLanguageChips.removeAllViews()
        val languages = listOf(
            "Español" to "es",
            "English" to "en",
            "Français" to "fr",
            "Italiano" to "it",
            "Deutsch" to "de"
        )

        languages.forEach { (name, code) ->
            val button = Button(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
                text = name
                textSize = 16f
                isAllCaps = false
                setBackgroundColor(if (code == selectedLanguageCode) Color.parseColor("#E50914") else Color.parseColor("#2A2A2A"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    selectedLanguageCode = code
                    populateLanguageChips()
                }
            }
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(8, 0, 8, 0)
            }
            binding.layoutLanguageChips.addView(button, params)
        }
    }

    // ==========================================
    // STEP 2: PROFILE CREATION
    // ==========================================
    private fun setupStep2Profile() {
        populateAvatarPresets()

        binding.btnUploadGalleryPhoto.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
            }
            galleryLauncher.launch(intent)
        }

        binding.btnConfirmProfile.setOnClickListener {
            val name = binding.etProfileName.text?.toString()?.trim()
            if (name.isNullOrEmpty()) {
                Toast.makeText(this, "Ingresa un nombre para tu perfil", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            transitionStep(binding.stepProfileContainer, binding.stepThemeContainer)
        }
    }

    private fun populateAvatarPresets() {
        binding.layoutAvatarPresets.removeAllViews()
        UserProfile.PRESET_AVATARS.forEach { (presetKey, colorInt) ->
            val presetView = View(this).apply {
                val drawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorInt)
                    if (presetKey == selectedAvatarValue && selectedAvatarType == AvatarType.PRESET) {
                        setStroke(8, Color.WHITE)
                    }
                }
                background = drawable
                setOnClickListener {
                    selectedAvatarValue = presetKey
                    selectedAvatarType = AvatarType.PRESET
                    updateAvatarPreview()
                    populateAvatarPresets()
                }
            }
            val params = LinearLayout.LayoutParams(70, 70).apply {
                setMargins(14, 0, 14, 0)
            }
            binding.layoutAvatarPresets.addView(presetView, params)
        }
        updateAvatarPreview()
    }

    private fun updateAvatarPreview() {
        if (selectedAvatarType == AvatarType.PRESET) {
            val color = UserProfile.PRESET_AVATARS.find { it.first == selectedAvatarValue }?.second ?: 0xFFE50914.toInt()
            binding.avatarPreviewCard.setCardBackgroundColor(color)
            binding.imgAvatarPreview.setImageDrawable(null)
        } else {
            customAvatarUri?.let {
                binding.avatarPreviewCard.setCardBackgroundColor(Color.TRANSPARENT)
                binding.imgAvatarPreview.setImageURI(it)
                binding.imgAvatarPreview.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            }
        }
    }

    private fun saveImageToInternalStorage(uri: Uri) {
        runCatching {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val file = File(filesDir, "avatar_${System.currentTimeMillis()}.jpg")
            val outputStream = FileOutputStream(file)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()

            customAvatarUri = Uri.fromFile(file)
            selectedAvatarType = AvatarType.CUSTOM_URI
            selectedAvatarValue = file.absolutePath
            updateAvatarPreview()
            populateAvatarPresets()
        }.onFailure {
            Toast.makeText(this, "Error al guardar foto", Toast.LENGTH_SHORT).show()
        }
    }

    // ==========================================
    // STEP 3: THEME SELECTION
    // ==========================================
    private fun setupStep3Theme() {
        populateThemesGrid()

        binding.btnConfirmTheme.setOnClickListener {
            UserPreferences.selectedTheme = selectedTheme
            populateProvidersList() // Re-populate providers based on chosen language
            transitionStep(binding.stepThemeContainer, binding.stepProviderContainer)
        }
    }

    private fun populateThemesGrid() {
        binding.layoutThemesGrid.removeAllViews()
        val themes = listOf(
            "Predeterminado" to ThemeManager.DEFAULT,
            "Modo Oscuro OLED" to ThemeManager.NERO_AMOLED_OLED,
            "Carmesí Noir" to ThemeManager.CRIMSON_NOIR,
            "Esmeralda Luxe" to ThemeManager.EMERALD_LUXE
        )

        themes.forEach { (title, key) ->
            val card = MaterialCardView(this).apply {
                setCardBackgroundColor(Color.parseColor(if (key == selectedTheme) "#2D1B1E" else "#1F1F1F"))
                strokeColor = Color.parseColor(if (key == selectedTheme) "#E50914" else "#333333")
                strokeWidth = if (key == selectedTheme) 4 else 1
                radius = 24f
                setOnClickListener {
                    selectedTheme = key
                    populateThemesGrid()
                }

                val layout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(24, 24, 24, 24)
                    gravity = Gravity.CENTER_VERTICAL

                    val tv = TextView(context).apply {
                        text = title
                        setTextColor(Color.WHITE)
                        textSize = 16f
                    }
                    addView(tv)
                }
                addView(layout)
            }
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 8, 0, 8)
            }
            binding.layoutThemesGrid.addView(card, params)
        }
    }

    // ==========================================
    // STEP 4: PROVIDER SELECTION BY LANGUAGE & CONTENT TYPE
    // ==========================================
    private fun setupStep4Provider() {
        binding.btnConfirmProvider.setOnClickListener {
            val name = binding.etProfileName.text?.toString()?.trim() ?: "Usuario"

            val profile = UserProfile(
                name = name,
                avatarType = selectedAvatarType,
                avatarValue = selectedAvatarValue,
                isKids = false,
                preferredTheme = selectedTheme
            )

            UserProfileManager.saveProfile(this, profile)
            UserProfileManager.setActiveProfile(this, profile.id)

            if (selectedProvider != null) {
                UserPreferences.currentProvider = selectedProvider
            }

            UserProfileManager.setOnboardingCompleted(this, true)
            UserProfileManager.setSpotlightPending(this, true)

            com.streamflixreborn.streamflix.utils.DeviceUtils.launchMainActivity(this)
        }
    }

    private fun populateProvidersList() {
        binding.layoutProvidersList.removeAllViews()
        val allProviders = Provider.providers.keys.toList()

        // Filter providers matching selected language code (or fallback to all if empty)
        val filteredProviders = allProviders.filter { provider ->
            provider.language?.lowercase()?.contains(selectedLanguageCode.lowercase()) == true
        }.ifEmpty { allProviders }

        filteredProviders.forEach { provider ->
            val description = getProviderContentDescription(provider.name)

            val card = MaterialCardView(this).apply {
                setCardBackgroundColor(Color.parseColor(if (provider == selectedProvider) "#2D1B1E" else "#1F1F1F"))
                strokeColor = Color.parseColor(if (provider == selectedProvider) "#E50914" else "#333333")
                strokeWidth = if (provider == selectedProvider) 4 else 1
                radius = 24f
                setOnClickListener {
                    selectedProvider = provider
                    populateProvidersList()
                }

                val layout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 24, 24, 24)

                    val tvName = TextView(context).apply {
                        text = provider.name
                        setTextColor(Color.WHITE)
                        textSize = 18f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }
                    val tvDesc = TextView(context).apply {
                        text = description
                        setTextColor(Color.parseColor("#BBBBBB"))
                        textSize = 14f
                        setPadding(0, 8, 0, 0)
                    }

                    addView(tvName)
                    addView(tvDesc)
                }
                addView(layout)
            }

            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 8, 0, 8)
            }
            binding.layoutProvidersList.addView(card, params)
        }

        if (selectedProvider == null || selectedProvider !in filteredProviders) {
            selectedProvider = filteredProviders.firstOrNull()
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

    private fun transitionStep(fromView: View, toView: View) {
        val fadeOut = ObjectAnimator.ofFloat(fromView, "alpha", 1f, 0f).setDuration(300)
        fadeOut.start()

        lifecycleScope.launch {
            delay(300)
            fromView.visibility = View.GONE
            toView.alpha = 0f
            toView.visibility = View.VISIBLE
            ObjectAnimator.ofFloat(toView, "alpha", 0f, 1f).setDuration(400).start()
        }
    }
}
