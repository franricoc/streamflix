package com.streamflixreborn.streamflix.activities.main

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import com.bumptech.glide.Glide
import com.tanasi.navigation.widget.setupWithNavController
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.ActivityMainTvBinding
import com.streamflixreborn.streamflix.databinding.ContentHeaderMenuMainTvBinding
import com.streamflixreborn.streamflix.fragments.player.PlayerTvFragment
import com.streamflixreborn.streamflix.ui.UpdateAppTvDialog
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.Cine24hProvider
import com.streamflixreborn.streamflix.providers.FilmyOnlineCcProvider
import com.streamflixreborn.streamflix.providers.GuardaSerieProvider
import com.streamflixreborn.streamflix.utils.AppLanguageManager
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.getCurrentFragment
import com.streamflixreborn.streamflix.providers.AnimeOnlineNinjaProvider
import kotlinx.coroutines.launch

import com.streamflixreborn.streamflix.ui.ProfileSelectorTvDialog
import com.streamflixreborn.streamflix.utils.UserProfileManager
import com.streamflixreborn.streamflix.models.UserProfile
import android.view.ViewGroup

class MainTvActivity : FragmentActivity() {

    private var _binding: ActivityMainTvBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModels<MainViewModel>()

    private lateinit var updateAppDialog: UpdateAppTvDialog

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!com.streamflixreborn.streamflix.utils.DeviceUtils.isTvDevice(this)) {
            startActivity(Intent(this, MainMobileActivity::class.java))
            finish()
            return
        }

        // Il setup delle preferenze è già avvenuto in StreamFlixApp
        setTheme(ThemeManager.tvThemeRes(UserPreferences.selectedTheme))
        
        super.onCreate(savedInstanceState)
        
        UserProfileManager.init(this)
        if (!UserProfileManager.hasCompletedOnboarding) {
            startActivity(Intent(this, com.streamflixreborn.streamflix.activities.onboarding.OnboardingTvActivity::class.java))
            finish()
            return
        }

        // Inizializza il provider con il context dell'attività per gestire eventuali bypass visibili
        AnimeOnlineNinjaProvider.init(this)
        Cine24hProvider.init(this)
        FilmyOnlineCcProvider.init(this)
        GuardaSerieProvider.init(this)
        
        _binding = ActivityMainTvBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyThemeNavigationChrome()

        binding.ivSplashOverlay.animate()
            .alpha(0f)
            .setDuration(800)
            .setStartDelay(400)
            .withEndAction {
                binding.ivSplashOverlay.visibility = View.GONE
            }

        startTvCastServer()

        val navHostFragment = this.supportFragmentManager
            .findFragmentById(binding.navMainFragment.id) as NavHostFragment
        val navController = navHostFragment.navController

        adjustLayoutDelta(null, null)

        if (!com.streamflixreborn.streamflix.utils.DeviceUtils.isTvDevice(this)) {
            finish()
            startActivity(Intent(this, MainMobileActivity::class.java))
            return
        }

        if (savedInstanceState == null) {
            UserPreferences.currentProvider?.let {
                navController.navigate(R.id.home)
            }
        }

        binding.navMain.setupWithNavController(navController)
        updateNavigationVisibility()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.navMainFragment.isFocusedByDefault = true
        }

        if (!UserProfileManager.isSessionProfileSelected) {
            val profiles = UserProfileManager.getProfiles(this)
            if (profiles.size > 1) {
                ProfileSelectorTvDialog(this) { selectedProfile ->
                    UserProfileManager.setActiveProfile(this, selectedProfile.id)
                    UserProfileManager.isSessionProfileSelected = true
                    showAppLaunchGreetingTv(selectedProfile)
                }.apply {
                    setCanceledOnTouchOutside(false)
                    show()
                }
            } else {
                UserProfileManager.isSessionProfileSelected = true
                UserProfileManager.getActiveProfile(this)?.let { showAppLaunchGreetingTv(it) }
            }
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.navMain.headerView?.apply {
                val header = ContentHeaderMenuMainTvBinding.bind(this)
                val activeProfile = UserProfileManager.getActiveProfile(this@MainTvActivity)

                Glide.with(context)
                    .load(UserPreferences.currentProvider?.logo?.takeIf { it.isNotEmpty() } ?: R.drawable.ic_provider_default_logo)
                    .error(R.drawable.ic_provider_default_logo)
                    .into(header.ivNavigationHeaderIcon)

                header.tvNavigationHeaderTitle.text = activeProfile?.name ?: UserPreferences.currentProvider?.name
                header.tvNavigationHeaderSubtitle.text = UserPreferences.currentProvider?.name ?: getString(R.string.main_menu_change_provider)
                val palette = ThemeManager.palette(UserPreferences.selectedTheme)
                header.tvNavigationHeaderTitle.setTextColor(palette.tvHeaderPrimary)
                header.tvNavigationHeaderSubtitle.setTextColor(palette.tvHeaderSecondary)
                setBackgroundColor(palette.tvNavBackground)

                setOnOpenListener {
                    header.tvNavigationHeaderTitle.visibility = View.VISIBLE
                    header.tvNavigationHeaderSubtitle.visibility = View.VISIBLE
                }
                setOnCloseListener {
                    header.tvNavigationHeaderTitle.visibility = View.GONE
                    header.tvNavigationHeaderSubtitle.visibility = View.GONE
                }

                setOnClickListener {
                    val options = arrayOf("👤 Cambiar de Perfil", "📺 Cambiar de Proveedor")
                    android.app.AlertDialog.Builder(this@MainTvActivity)
                        .setTitle("Cuenta y Proveedor")
                        .setItems(options) { _, which ->
                            when (which) {
                                0 -> {
                                    UserProfileManager.isSessionProfileSelected = false
                                    ProfileSelectorTvDialog(this@MainTvActivity) { selectedProfile ->
                                        UserProfileManager.setActiveProfile(this@MainTvActivity, selectedProfile.id)
                                        UserProfileManager.isSessionProfileSelected = true
                                        recreate()
                                    }.show()
                                }
                                1 -> navController.navigate(R.id.providers)
                            }
                        }
                        .show()
                }
            }

            when (destination.id) {
                R.id.search, R.id.home, R.id.movies, R.id.tv_shows, R.id.settings -> {
                    binding.navMain.visibility = View.VISIBLE
                    updateNavigationVisibility()
                }
                else -> binding.navMain.visibility = View.GONE
            }
        }

        lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    is MainViewModel.State.SuccessCheckingUpdate -> {
                        updateAppDialog = UpdateAppTvDialog(this@MainTvActivity, state.newReleases).also {
                            it.setOnUpdateClickListener { _ ->
                                if (!it.isLoading) viewModel.downloadUpdate(this@MainTvActivity, state.asset)
                            }
                            it.show()
                        }
                    }
                    MainViewModel.State.DownloadingUpdate -> if (::updateAppDialog.isInitialized) updateAppDialog.isLoading = true
                    is MainViewModel.State.SuccessDownloadingUpdate -> {
                        viewModel.installUpdate(this@MainTvActivity, state.apk)
                        if (::updateAppDialog.isInitialized) updateAppDialog.hide()
                    }
                    MainViewModel.State.InstallingUpdate -> if (::updateAppDialog.isInitialized) updateAppDialog.isLoading = true
                    is MainViewModel.State.FailedUpdate -> {
                        Toast.makeText(this@MainTvActivity, state.error.message ?: "Update failed", Toast.LENGTH_SHORT).show()
                    }
                    else -> {}
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (navController.currentDestination?.id) {
                    R.id.home -> if (binding.navMain.hasFocus()) finish() else binding.navMain.requestFocus()
                    R.id.settings, R.id.search, R.id.movies, R.id.tv_shows -> {
                        navigateToProviderHome(navController)
                        binding.navMain.requestFocus()
                    }
                    else -> {
                        val handled = (getCurrentFragment() as? PlayerTvFragment)?.onBackPressed() ?: false
                        if (!handled && !navController.navigateUp()) finish()
                    }
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkUpdate()
    }

    private fun applyThemeNavigationChrome() {
        val palette = ThemeManager.palette(UserPreferences.selectedTheme)
        window.statusBarColor = palette.systemBar
        window.navigationBarColor = palette.systemBar
        binding.navMain.setBackgroundColor(palette.tvNavBackground)
        binding.navMain.headerView?.let { headerView ->
            headerView.setBackgroundColor(palette.tvNavBackground)
            val header = ContentHeaderMenuMainTvBinding.bind(headerView)
            header.tvNavigationHeaderTitle.setTextColor(palette.tvHeaderPrimary)
            header.tvNavigationHeaderSubtitle.setTextColor(palette.tvHeaderSecondary)
        }
    }
    
    private fun updateNavigationVisibility() {
        UserPreferences.currentProvider?.let { provider ->
            binding.navMain.menu.findItem(R.id.movies)?.isVisible = Provider.supportsMovies(provider)
            val tvShowsItem = binding.navMain.menu.findItem(R.id.tv_shows)
            tvShowsItem?.isVisible = Provider.supportsTvShows(provider)
            tvShowsItem?.title = if (provider is IptvProvider)
                getString(R.string.main_menu_all_channels) else getString(R.string.main_menu_tv_shows)
        }
    }

    fun adjustLayoutDelta(deltaX: Int?, deltaY: Int?) {
        val uDeltaX = deltaX ?: UserPreferences.paddingX
        val uDeltaY = deltaY ?: UserPreferences.paddingY
        binding.root.setPadding(uDeltaX, uDeltaY, uDeltaX, uDeltaY)
    }

    var tvWebSocketServer: com.streamflixreborn.streamflix.cast.TvWebSocketServer? = null

    private var deviceDiscoveryManager: com.streamflixreborn.streamflix.cast.DeviceDiscoveryManager? = null

    private companion object {
        const val CAST_PORT_DEFAULT = 8080
        const val CAST_PORT_FALLBACK_1 = 8081
        const val CAST_PORT_FALLBACK_2 = 8082
        const val CAST_SERVER_START_TIMEOUT_MS = 5_000L

        // stop(code) closes connections and joins the server thread; the parameter is
        // the WebSocket close code (1000 = normal), not a timeout.
        const val CAST_SERVER_CLOSE_CODE = 1000
    }

    /** Stops a cast server off the main thread: stop() drains connections and joins
     *  the server thread, so calling it synchronously on onCreate/onDestroy (e.g.
     *  during an activity recreate) can freeze the UI.
     */
    private fun stopTvCastServerAsync(server: com.streamflixreborn.streamflix.cast.TvWebSocketServer) {
        Thread {
            try {
                server.stop(CAST_SERVER_CLOSE_CODE)
            } catch (ignored: Exception) {
            }
        }.apply {
            isDaemon = true
        }.start()
    }

    private fun startTvCastServer() {
        // The fixed 8080 port can be left in a transient state (e.g. activity recreate
        // after a provider/profile switch while a stale server still owns the socket).
        // Retry with fallback ports and only register the NSD service once the server
        // actually bound, using the real port it ended up on.
        val ports = listOf(CAST_PORT_DEFAULT, CAST_PORT_FALLBACK_1, CAST_PORT_FALLBACK_2, 0)
        for (port in ports) {
            val server =
                com.streamflixreborn.streamflix.cast.TvWebSocketServer(
                    port = port,
                    onPlayRequested = { payload ->
                        runOnUiThread { handleIncomingCastPayload(payload) }
                    },
                    onControlRequested = { action, pos ->
                        runOnUiThread {
                            (getCurrentFragment() as? PlayerTvFragment)?.handleRemoteControl(action, pos)
                        }
                    },
                )
            try {
                server.start()
                if (server.awaitStart(CAST_SERVER_START_TIMEOUT_MS)) {
                    val actualPort = server.address.port
                    tvWebSocketServer = server
                    deviceDiscoveryManager =
                        com.streamflixreborn.streamflix.cast.DeviceDiscoveryManager(this).also {
                            it.registerTvService(actualPort)
                        }
                    android.util.Log.i("MainTvActivity", "TV cast server started on port $actualPort")
                    return
                }
                val error = server.getStartError()
                android.util.Log.e(
                    "MainTvActivity",
                    "TV cast server failed to start on port $port: ${error?.message}",
                )
                stopTvCastServerAsync(server)
            } catch (e: Exception) {
                android.util.Log.e("MainTvActivity", "Error starting TV cast server on port $port", e)
                stopTvCastServerAsync(server)
            }
        }
        android.util.Log.e("MainTvActivity", "TV cast server could not bind any port")
    }

    private fun handleIncomingCastPayload(payload: com.streamflixreborn.streamflix.cast.CastPayload) {
        val navHostFragment = supportFragmentManager
            .findFragmentById(binding.navMainFragment.id) as? NavHostFragment ?: return
        val navController = navHostFragment.navController

        Toast.makeText(this, "📺 Reproduciendo desde móvil: ${payload.title}", Toast.LENGTH_SHORT).show()

        val currentFragment = getCurrentFragment()
        if (currentFragment is PlayerTvFragment) {
            currentFragment.onNewCastPayload(payload)
            return
        }

        val videoType = payload.videoType ?: com.streamflixreborn.streamflix.models.Video.Type.Movie(
            id = payload.mediaId ?: "cast_${System.currentTimeMillis()}",
            title = payload.title,
            releaseDate = payload.subtitle ?: "",
            poster = payload.posterUrl ?: "",
            imdbId = null
        )

        val mediaId = payload.mediaId ?: "cast_${System.currentTimeMillis()}"

        val bundle = Bundle().apply {
            putString("id", mediaId)
            putParcelable("videoType", videoType)
            putString("title", payload.title)
            putString("subtitle", payload.subtitle ?: "")
            putSerializable("cast_payload", payload)
            putBoolean("is_cast", true)
        }

        navController.navigate(
            R.id.player,
            bundle,
            navOptions {
                launchSingleTop = true
            }
        )
    }



    override fun onDestroy() {
        super.onDestroy()
        try {
            deviceDiscoveryManager?.unregisterTvService()
            // Draining open connections can block, so stop the server off the main
            // thread: a quick activity recreate (provider/profile switch) must not
            // stall while stale sockets close.
            tvWebSocketServer?.let { stopTvCastServerAsync(it) }
            tvWebSocketServer = null
        } catch (e: Exception) {
            android.util.Log.e("MainTvActivity", "Error stopping TV cast server", e)
        }
        _binding = null
    }

    private fun navigateToProviderHome(navController: androidx.navigation.NavController) {
        if (!navController.popBackStack(R.id.home, false)) {
            navController.navigate(
                R.id.home,
                null,
                navOptions {
                    launchSingleTop = true
                    popUpTo(R.id.providers) {
                        inclusive = true
                    }
                }
            )
        }
    }

    private fun showAppLaunchGreetingTv(profile: com.streamflixreborn.streamflix.models.UserProfile? = null) {
        val activeProfile = profile ?: UserProfileManager.getActiveProfile(this) ?: return
        val rootView = window.decorView as? ViewGroup ?: return

        val overlay = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            elevation = 999f
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(48, 48, 48, 48)

            val avatarCard = androidx.cardview.widget.CardView(context).apply {
                radius = 80f
                if (activeProfile.avatarType == com.streamflixreborn.streamflix.models.AvatarType.PRESET) {
                    val color = com.streamflixreborn.streamflix.models.UserProfile.PRESET_AVATARS.find { it.first == activeProfile.avatarValue }?.second ?: 0xFFE50914.toInt()
                    setCardBackgroundColor(color)
                } else {
                    setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            }
            val avatarParams = android.widget.LinearLayout.LayoutParams(160, 160)
            avatarCard.layoutParams = avatarParams

            val imgAvatar = android.widget.ImageView(context).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                if (activeProfile.avatarType == com.streamflixreborn.streamflix.models.AvatarType.CUSTOM_URI) {
                    setImageURI(android.net.Uri.fromFile(java.io.File(activeProfile.avatarValue)))
                }
            }
            avatarCard.addView(imgAvatar)
            addView(avatarCard)

            val tvHello = android.widget.TextView(context).apply {
                text = "¡Hola, ${activeProfile.name}!"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 34f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 24, 0, 0)
            }
            addView(tvHello)
        }

        overlay.addView(layout, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
        rootView.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        lifecycleScope.launch {
            kotlinx.coroutines.delay(1200)
            val fadeOut = android.animation.ObjectAnimator.ofFloat(overlay, "alpha", 1f, 0f).setDuration(350)
            fadeOut.start()
            kotlinx.coroutines.delay(350)
            rootView.removeView(overlay)
        }
    }
}


