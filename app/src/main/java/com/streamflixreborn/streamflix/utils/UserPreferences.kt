package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.fragments.player.settings.PlayerSettingsView
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.Provider.Companion.providers
import com.streamflixreborn.streamflix.providers.TmdbProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray

object UserPreferences {

    private const val TAG = "UserPrefsDebug"

    // Default DoH Provider URL (Cloudflare)
    private const val DEFAULT_DOH_PROVIDER_URL = "https://cloudflare-dns.com/dns-query"
    const val DOH_DISABLED_VALUE = "" // Value to represent DoH being disabled
    private const val DEFAULT_SERIENSTREAM_DOMAIN = "s.to"
    private const val DEFAULT_MOFLIX_DOMAIN = "moflix-stream.xyz"
    private const val DEFAULT_STREAMINGCOMMUNITY_DOMAIN = "streamingunity.cc"
    private const val DEFAULT_CUEVANA_DOMAIN = "cuevana.gs"
    private const val DEFAULT_POSEIDON_DOMAIN = "www.poseidonhd2.co"

    const val PROVIDER_URL = "URL"
    const val PROVIDER_LOGO = "LOGO"
    const val PROVIDER_PORTAL_URL = "PORTAL_URL"
    const val PROVIDER_AUTOUPDATE = "AUTOUPDATE_URL"
    const val PROVIDER_NEW_INTERFACE = "NEW_INTERFACE"
    const val PROVIDER_PREFERRED_SERVER = "PREFERRED_SERVER"

    lateinit var providerCache: JSONObject

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // In-memory snapshot of the DataStore. Reads keep the exact same synchronous
    // API as before while writes go through DataStore (async, Flow-based) and
    // update the snapshot immediately (write-through) for read-after-write
    // consistency. MutableStateFlow makes snapshot updates atomic across threads.
    // Note: with two rapid edits in flight the DataStore collector can briefly
    // deliver an intermediate state; the snapshot self-corrects on the next
    // emission, so the window is transient and theoretical in practice.
    private val snapshotFlow = MutableStateFlow<Preferences>(emptyPreferences())

    private var isSetup = false

    private inline fun debugLog(message: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message())
        }
    }

    fun setup(context: Context) {
        if (isSetup) return
        AppDataStores.init(context)
        // Synchronously load the initial state (this also runs the
        // SharedPreferences -> DataStore migration) so every consumer reads the
        // persisted values right away, exactly like the old synchronous API.
        snapshotFlow.value = runBlocking { AppDataStores.appPreferencesDataStore.data.first() }
        scope.launch {
            AppDataStores.appPreferencesDataStore.data.collect { snapshotFlow.value = it }
        }
        isSetup = true

        val jsonString = Key.PROVIDER_CACHE.getString() ?: "{}"
        providerCache = runCatching { JSONObject(jsonString) }.getOrDefault(JSONObject())
        debugLog { "preferences initialized: ${snapshotFlow.value.asMap().size} keys" }
    }

    /** Write-through: applies to the snapshot synchronously and persists async. */
    private fun edit(transform: MutablePreferences.() -> Unit) {
        snapshotFlow.update { it.toMutablePreferences().apply(transform) }
        scope.launch {
            AppDataStores.appPreferencesDataStore.edit(transform)
        }
    }

    private fun getStringSet(keyName: String): Set<String>? = snapshotFlow.value[stringSetPreferencesKey(keyName)]

    private fun setStringSet(keyName: String, value: Set<String>) = edit {
        set(stringSetPreferencesKey(keyName), value)
    }

    var currentProvider: Provider?
        get() {
            val providerName = Key.CURRENT_PROVIDER.getString()
            if (providerName?.startsWith("TMDb (") == true && providerName.endsWith(")")) {
                val lang = providerName.substringAfter("TMDb (").substringBefore(")")
                return TmdbProvider(lang)
            }
            return Provider.providers.keys.find { it.name == providerName }
        }
        set(value) {
            AppDatabase.resetInstance()

            Key.CURRENT_PROVIDER.setString(value?.name)
            value?.name?.let { providerName ->
                runCatching {
                    UserProfileManager.updateActiveProfileProvider(StreamFlixApp.instance, providerName)
                }
            }
            UserProfileManager.isSessionProfileSelected = true
            runCatching {
                ArtworkRepairScheduler.schedule(StreamFlixApp.instance, value)
            }
            ProviderChangeNotifier.notifyProviderChanged()
        }

    fun getProviderCache(provider: Provider, key: String): String {
        return providerCache
            .optJSONObject(provider.name)
            ?.optString(key)
            .orEmpty()
    }

    fun setProviderCache(provider: Provider?, key: String, value: String) {
        val providerName = provider?.name ?: currentProvider?.name ?: return
        val innerJson = providerCache.optJSONObject(providerName)
            ?: JSONObject().also { providerCache.put(providerName, it) }
        innerJson.put(key, value)
        Key.PROVIDER_CACHE.setString(providerCache.toString())
    }

    fun clearProviderCache(providerName: String) {
        if (providerCache.has(providerName)) {
            debugLog { "CACHE: removing stored data for $providerName" }
            providerCache.remove(providerName)
            Key.PROVIDER_CACHE.setString(providerCache.toString())
        }
    }

    var appLayout: String
        get() = Key.APP_LAYOUT.getString() ?: "auto"
        set(value) {
            Key.APP_LAYOUT.setString(value)
        }

    var currentLanguage: String?
        get() = Key.CURRENT_LANGUAGE.getString()
        set(value) = Key.CURRENT_LANGUAGE.setString(value)

    var providerLanguage: String?
        get() = Key.PROVIDER_LANGUAGE.getString() ?: currentLanguage
        set(value) = Key.PROVIDER_LANGUAGE.setString(value)

    var captionTextSize: Float
        get() = Key.CAPTION_TEXT_SIZE.getFloat()
            ?: PlayerSettingsView.Settings.Subtitle.Style.TextSize.DEFAULT.value
        set(value) {
            Key.CAPTION_TEXT_SIZE.setFloat(value)
        }

    var autoplay: Boolean
        get() = Key.AUTOPLAY.getBoolean() ?: true
        set(value) {
            Key.AUTOPLAY.setBoolean(value)
        }

    var keepScreenOnWhenPaused: Boolean
        get() = Key.KEEP_SCREEN_ON_WHEN_PAUSED.getBoolean() ?: false
        set(value) {
            Key.KEEP_SCREEN_ON_WHEN_PAUSED.setBoolean(value)
        }

    var playerGestures: Boolean
        get() = Key.PLAYER_GESTURES.getBoolean() ?: true
        set(value) {
            Key.PLAYER_GESTURES.setBoolean(value)
        }

    var immersiveMode: Boolean
        get() = Key.IMMERSIVE_MODE.getBoolean() ?: false // Default changed to false
        set(value) {
            Key.IMMERSIVE_MODE.setBoolean(value)
        }

    var forceExtraBuffering: Boolean
        get() = Key.FORCE_EXTRA_BUFFERING.getBoolean() ?: false
        set(value) {
            Key.FORCE_EXTRA_BUFFERING.setBoolean(value)
        }

    var autoplayBuffer: Long
        get() = Key.AUTOPLAY_BUFFER.getLong() ?: 3L
        set(value) {
            Key.AUTOPLAY_BUFFER.setLong(value)
        }

    var serverAutoSubtitlesDisabled: Boolean
        get() = Key.SERVER_AUTO_SUBTITLES_DISABLED.getBoolean() ?: true
        set(value) {
            Key.SERVER_AUTO_SUBTITLES_DISABLED.setBoolean(value)
        }

    var selectedTheme: String
        get() = Key.SELECTED_THEME.getString() ?: "default"
        set(value) = Key.SELECTED_THEME.setString(value)

    var tmdbApiKey: String
        get() = Key.TMDB_API_KEY.getString() ?: ""
        set(value) {
            Key.TMDB_API_KEY.setString(value)
            TMDb3.rebuildService()
        }
    var enableTmdb: Boolean
        get() = Key.ENABLE_TMDB.getBoolean() ?: true
        set(value) {
            Key.ENABLE_TMDB.setBoolean(value)
            TMDb3.rebuildService()
            if (value) {
                runCatching {
                    ArtworkRepairScheduler.schedule(StreamFlixApp.instance, currentProvider)
                }
            }
        }

    var parentalControlPin: String
        get() = Key.PARENTAL_CONTROL_PIN.getString() ?: ""
        set(value) {
            Key.PARENTAL_CONTROL_PIN.setString(value.trim())
        }

    var parentalControlAdminPin: String
        get() = Key.PARENTAL_CONTROL_ADMIN_PIN.getString() ?: ""
        set(value) {
            Key.PARENTAL_CONTROL_ADMIN_PIN.setString(value.trim())
        }

    var parentalControlMaxAge: Int?
        get() = Key.PARENTAL_CONTROL_MAX_AGE.getInt()
        set(value) {
            Key.PARENTAL_CONTROL_MAX_AGE.setInt(value)
        }

    var parentalControlFailedAttempts: Int
        get() = Key.PARENTAL_CONTROL_FAILED_ATTEMPTS.getInt() ?: 0
        set(value) {
            Key.PARENTAL_CONTROL_FAILED_ATTEMPTS.setInt(value)
        }

    var parentalControlLockedUntilMillis: Long
        get() = Key.PARENTAL_CONTROL_LOCKED_UNTIL.getLong() ?: 0L
        set(value) {
            Key.PARENTAL_CONTROL_LOCKED_UNTIL.setLong(value)
        }

    var parentalControlHardLocked: Boolean
        get() = Key.PARENTAL_CONTROL_HARD_LOCKED.getBoolean() ?: false
        set(value) {
            Key.PARENTAL_CONTROL_HARD_LOCKED.setBoolean(value)
        }

    val isParentalControlActive: Boolean
        get() = enableTmdb && parentalControlPin.isNotBlank() && parentalControlMaxAge != null

    val isParentalControlTemporarilyLocked: Boolean
        get() = parentalControlLockedUntilMillis > System.currentTimeMillis()

    val parentalControlLockRemainingMillis: Long
        get() = (parentalControlLockedUntilMillis - System.currentTimeMillis()).coerceAtLeast(0L)

    fun registerParentalPinSuccess() {
        parentalControlFailedAttempts = 0
        parentalControlLockedUntilMillis = 0L
        parentalControlHardLocked = false
    }

    fun registerParentalPinFailure(nowMillis: Long = System.currentTimeMillis()) {
        val attempts = parentalControlFailedAttempts + 1
        parentalControlFailedAttempts = attempts

        when {
            attempts >= 7 && parentalControlAdminPin.isNotBlank() -> {
                parentalControlHardLocked = true
                parentalControlLockedUntilMillis = 0L
            }
            attempts >= 7 -> {
                parentalControlLockedUntilMillis = nowMillis + 24L * 60L * 60L * 1000L
            }
            attempts >= 5 -> {
                parentalControlLockedUntilMillis = nowMillis + 30L * 60L * 1000L
            }
            attempts >= 3 -> {
                parentalControlLockedUntilMillis = nowMillis + 5L * 60L * 1000L
            }
        }
    }

    var updateCheckEnabled: Boolean
        get() = Key.UPDATE_CHECK_ENABLED.getBoolean() ?: true
        set(value) {
            Key.UPDATE_CHECK_ENABLED.setBoolean(value)
        }

    fun unlockParentalControls() {
        parentalControlFailedAttempts = 0
        parentalControlLockedUntilMillis = 0L
        parentalControlHardLocked = false
    }

    var subdlApiKey: String
        get() = Key.SUBDL_API_KEY.getString() ?: ""
        set(value) {
            Key.SUBDL_API_KEY.setString(value)
        }

    var bypassWsAdvertisedHost: String
        get() = Key.BYPASS_WS_ADVERTISED_HOST.getString() ?: ""
        set(value) {
            Key.BYPASS_WS_ADVERTISED_HOST.setString(value.trim())
        }

    enum class PlayerResize(
        val stringRes: Int,
        val resizeMode: Int,
    ) {
        Fit(R.string.player_aspect_ratio_fit, AspectRatioFrameLayout.RESIZE_MODE_FIT),
        Fill(R.string.player_aspect_ratio_fill, AspectRatioFrameLayout.RESIZE_MODE_FILL),
        Zoom(R.string.player_aspect_ratio_zoom, AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
        Stretch43(R.string.player_aspect_ratio_zoom_4_3, AspectRatioFrameLayout.RESIZE_MODE_FIT),
        StretchVertical(R.string.player_aspect_ratio_stretch_vertical, AspectRatioFrameLayout.RESIZE_MODE_FIT),
        SuperZoom(R.string.player_aspect_ratio_super_zoom, AspectRatioFrameLayout.RESIZE_MODE_FIT);
    }

    var playerResize: PlayerResize
        get() = PlayerResize.entries.find { it.resizeMode == Key.PLAYER_RESIZE.getInt() && it.name == Key.PLAYER_RESIZE_NAME.getString() }
            ?: PlayerResize.entries.find { it.resizeMode == Key.PLAYER_RESIZE.getInt() }
            ?: PlayerResize.Fit
        set(value) {
            Key.PLAYER_RESIZE.setInt(value.resizeMode)
            Key.PLAYER_RESIZE_NAME.setString(value.name)
        }

    var captionStyle: CaptionStyleCompat
        get() = CaptionStyleCompat(
            Key.CAPTION_STYLE_FONT_COLOR.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.foregroundColor,
            Key.CAPTION_STYLE_BACKGROUND_COLOR.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.backgroundColor,
            Key.CAPTION_STYLE_WINDOW_COLOR.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.windowColor,
            Key.CAPTION_STYLE_EDGE_TYPE.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.edgeType,
            Key.CAPTION_STYLE_EDGE_COLOR.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.edgeColor,
            PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.typeface
        )
        set(value) {
            Key.CAPTION_STYLE_FONT_COLOR.setInt(value.foregroundColor)
            Key.CAPTION_STYLE_BACKGROUND_COLOR.setInt(value.backgroundColor)
            Key.CAPTION_STYLE_WINDOW_COLOR.setInt(value.windowColor)
            Key.CAPTION_STYLE_EDGE_TYPE.setInt(value.edgeType)
            Key.CAPTION_STYLE_EDGE_COLOR.setInt(value.edgeColor)
        }

    var captionMargin: Int
        get() = Key.CAPTION_STYLE_MARGIN.getInt() ?: 24
        set(value) {
            Key.CAPTION_STYLE_MARGIN.setInt(value)
        }

    var qualityHeight: Int?
        get() = Key.QUALITY_HEIGHT.getInt()
        set(value) {
            Key.QUALITY_HEIGHT.setInt(value)
        }

    var subtitleName: String?
        get() = Key.SUBTITLE_NAME.getString()
        set(value) = Key.SUBTITLE_NAME.setString(value)
    var streamingcommunityDomain: String
        get() {
            if (!isSetup) {
                Log.e(TAG, "streamingcommunityDomain GET: preferences not initialized")
                return DEFAULT_STREAMINGCOMMUNITY_DOMAIN
            }
            val storedValue = Key.STREAMINGCOMMUNITY_DOMAIN.getString()
            return if (storedValue.isNullOrEmpty()) {
                DEFAULT_STREAMINGCOMMUNITY_DOMAIN
            } else {
                storedValue
            }
        }
        set(value) {
            if (!isSetup) {
                Log.e(TAG, "streamingcommunityDomain SET: preferences not initialized")
                return
            }
            val oldDomain = Key.STREAMINGCOMMUNITY_DOMAIN.getString()

            if (value != oldDomain && !value.isNullOrEmpty() && !oldDomain.isNullOrEmpty()) {
                clearProviderCache("StreamingCommunity")
            }

            Key.STREAMINGCOMMUNITY_DOMAIN.setString(value?.takeUnless { it.isNullOrEmpty() })
        }

    var serienstreamDomain: String
        get() {
            if (!isSetup) return DEFAULT_SERIENSTREAM_DOMAIN
            val storedValue = Key.SERIENSTREAM_DOMAIN.getString()
            return if (storedValue.isNullOrEmpty()) DEFAULT_SERIENSTREAM_DOMAIN else storedValue
        }
        set(value) {
            if (!isSetup) return
            val oldDomain = Key.SERIENSTREAM_DOMAIN.getString()

            if (value != oldDomain && !value.isNullOrEmpty() && !oldDomain.isNullOrEmpty()) {
                clearProviderCache("SerienStream")
            }

            Key.SERIENSTREAM_DOMAIN.setString(value?.takeUnless { it.isNullOrEmpty() })
        }

    var cuevanaDomain: String
        get() {
            if (!isSetup) return DEFAULT_CUEVANA_DOMAIN
            val storedValue = Key.CUEVANA_DOMAIN.getString()
            return if (storedValue.isNullOrEmpty()) DEFAULT_CUEVANA_DOMAIN else storedValue
        }
        set(value) {
            if (!isSetup) return

            val oldDomain = Key.CUEVANA_DOMAIN.getString()
            if (value != oldDomain && !value.isNullOrEmpty() && !oldDomain.isNullOrEmpty()) {
                clearProviderCache("Cuevana 3")
            }

            Key.CUEVANA_DOMAIN.setString(value?.takeUnless { it.isNullOrEmpty() })
        }

    var poseidonDomain: String
        get() {
            if (!isSetup) return DEFAULT_POSEIDON_DOMAIN
            val storedValue = Key.POSEIDON_DOMAIN.getString()
            return if (storedValue.isNullOrEmpty()) DEFAULT_POSEIDON_DOMAIN else storedValue
        }
        set(value) {
            if (!isSetup) return

            val oldDomain = Key.POSEIDON_DOMAIN.getString()
            if (value != oldDomain && !value.isNullOrEmpty() && !oldDomain.isNullOrEmpty()) {
                clearProviderCache("Poseidonhd2")
            }

            Key.POSEIDON_DOMAIN.setString(value?.takeUnless { it.isNullOrEmpty() })
        }

    var moflixDomain: String
        get() {
            if (!isSetup) return DEFAULT_MOFLIX_DOMAIN
            val storedValue = Key.MOFLIX_DOMAIN.getString()
            return if (storedValue.isNullOrEmpty()) DEFAULT_MOFLIX_DOMAIN else storedValue
        }
        set(value) {
            if (!isSetup) return

            Key.MOFLIX_DOMAIN.setString(value?.takeUnless { it.isNullOrEmpty() })
        }

    var dohProviderUrl: String
        get() = Key.DOH_PROVIDER_URL.getString() ?: DEFAULT_DOH_PROVIDER_URL
        set(value) {
            Key.DOH_PROVIDER_URL.setString(value)
            DnsResolver.setDnsUrl(value)
        }

    var paddingX: Int
        get() = Key.SCREEN_PADDING_X.getInt() ?: 0
        set(value) = Key.SCREEN_PADDING_X.setInt(value)

    var paddingY: Int
        get() = Key.SCREEN_PADDING_Y.getInt() ?: 0
        set(value) = Key.SCREEN_PADDING_Y.setInt(value)

    var favoriteProviders: Set<String>
        get() {
            val activeId = UserProfileManager.getActiveProfileId(StreamFlixApp.instance)
            val key = if (activeId.isNullOrEmpty()) Key.FAVORITE_PROVIDERS.name else "${Key.FAVORITE_PROVIDERS.name}_$activeId"
            return getStringSet(key) ?: emptySet()
        }
        set(value) {
            val activeId = UserProfileManager.getActiveProfileId(StreamFlixApp.instance)
            val key = if (activeId.isNullOrEmpty()) Key.FAVORITE_PROVIDERS.name else "${Key.FAVORITE_PROVIDERS.name}_$activeId"
            setStringSet(key, value)
        }

    /** Key dinámico (por provider/sección) sobre DataStore, con la misma semántica de Key. */
    private fun getPrefString(keyName: String): String? =
        snapshotFlow.value[stringPreferencesKey(keyName)]

    private fun setPrefString(keyName: String, value: String?) = edit {
        if (value == null) remove(stringPreferencesKey(keyName)) else set(stringPreferencesKey(keyName), value)
    }

    fun getFavoriteCategoryOrder(providerName: String): List<String> {
        val key = "FAVORITE_CATEGORY_ORDER_$providerName"
        val saved = getPrefString(key)
            ?.split(',')
            ?.filter { it == "movies" || it == "tv_shows" }
            .orEmpty()
        return (saved + listOf("movies", "tv_shows")).distinct()
    }

    fun setFavoriteCategoryOrder(providerName: String, order: List<String>) {
        val normalized = (order.filter { it == "movies" || it == "tv_shows" } +
            listOf("movies", "tv_shows")).distinct()
        setPrefString("FAVORITE_CATEGORY_ORDER_$providerName", normalized.joinToString(","))
    }

    fun getFavoriteItemOrder(providerName: String, section: String): List<String> {
        val raw = getPrefString("FAVORITE_ITEM_ORDER_${providerName}_$section") ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            List(json.length()) { index -> json.getString(index) }
        }.getOrDefault(emptyList())
    }

    fun setFavoriteItemOrder(providerName: String, section: String, order: List<String>) {
        setPrefString("FAVORITE_ITEM_ORDER_${providerName}_$section", JSONArray(order).toString())
    }

    fun getFavoriteSortMode(providerName: String): String =
        getPrefString("FAVORITE_SORT_MODE_$providerName") ?: "manual"

    fun setFavoriteSortMode(providerName: String, mode: String) {
        setPrefString("FAVORITE_SORT_MODE_$providerName", mode)
    }

    private enum class Key {
        APP_LAYOUT,
        CURRENT_LANGUAGE,
        CURRENT_PROVIDER,
        PLAYER_RESIZE,
        PLAYER_RESIZE_NAME,
        CAPTION_TEXT_SIZE,
        CAPTION_STYLE_FONT_COLOR,
        CAPTION_STYLE_BACKGROUND_COLOR,
        CAPTION_STYLE_WINDOW_COLOR,
        CAPTION_STYLE_EDGE_TYPE,
        CAPTION_STYLE_EDGE_COLOR,
        CAPTION_STYLE_MARGIN,
        SCREEN_PADDING_X,
        SCREEN_PADDING_Y,
        QUALITY_HEIGHT,
        SUBTITLE_NAME,
        SERIENSTREAM_DOMAIN,
        MOFLIX_DOMAIN,
        STREAMINGCOMMUNITY_DOMAIN,
        CUEVANA_DOMAIN,
        POSEIDON_DOMAIN,
        DOH_PROVIDER_URL, // Removed STREAMINGCOMMUNITY_DNS_OVER_HTTPS, added DOH_PROVIDER_URL
        AUTOPLAY,
        PROVIDER_CACHE,
        KEEP_SCREEN_ON_WHEN_PAUSED,
        PLAYER_GESTURES,
        IMMERSIVE_MODE,
        TMDB_API_KEY,
        SUBDL_API_KEY,
        FORCE_EXTRA_BUFFERING,
        AUTOPLAY_BUFFER,
        SERVER_AUTO_SUBTITLES_DISABLED,
        ENABLE_TMDB,
        PARENTAL_CONTROL_PIN,
        PARENTAL_CONTROL_ADMIN_PIN,
        PARENTAL_CONTROL_MAX_AGE,
        PARENTAL_CONTROL_FAILED_ATTEMPTS,
        PARENTAL_CONTROL_LOCKED_UNTIL,
        PARENTAL_CONTROL_HARD_LOCKED,
        SELECTED_THEME,
        BYPASS_WS_ADVERTISED_HOST,
        UPDATE_CHECK_ENABLED,
        PROVIDER_LANGUAGE,
        FAVORITE_PROVIDERS;

        fun getBoolean(): Boolean? = snapshotFlow.value[booleanPreferencesKey(name)]

        fun getFloat(): Float? = snapshotFlow.value[floatPreferencesKey(name)]

        fun getInt(): Int? = snapshotFlow.value[intPreferencesKey(name)]

        fun getLong(): Long? = snapshotFlow.value[longPreferencesKey(name)]

        fun getString(): String? = snapshotFlow.value[stringPreferencesKey(name)]

        fun setBoolean(value: Boolean?) = edit {
            if (value == null) remove(booleanPreferencesKey(name)) else set(booleanPreferencesKey(name), value)
        }

        fun setFloat(value: Float?) = edit {
            if (value == null) remove(floatPreferencesKey(name)) else set(floatPreferencesKey(name), value)
        }

        fun setInt(value: Int?) = edit {
            if (value == null) remove(intPreferencesKey(name)) else set(intPreferencesKey(name), value)
        }

        fun setLong(value: Long?) = edit {
            if (value == null) remove(longPreferencesKey(name)) else set(longPreferencesKey(name), value)
        }

        fun setString(value: String?) = edit {
            if (value == null) remove(stringPreferencesKey(name)) else set(stringPreferencesKey(name), value)
        }
    }
}
