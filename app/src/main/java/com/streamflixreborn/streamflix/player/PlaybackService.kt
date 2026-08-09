package com.streamflixreborn.streamflix.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.streamflixreborn.streamflix.utils.UserPreferences

/**
 * Foreground media service that owns the app's ExoPlayer + MediaSession.
 *
 * Playback keeps running when the player UI is no longer visible (home button,
 * screen off, PiP) and is exposed to the lock screen, notification shade and
 * Android Auto. The player fragments reuse this service-owned player instead of
 * creating and releasing their own.
 */
class PlaybackService : MediaSessionService() {

    companion object {
        /**
         * Action used by the media notification's session activity: tapping the notification
         * returns to the active player instead of opening the launcher/home screen.
         */
        const val ACTION_OPEN_PLAYER = "com.streamflixreborn.streamflix.action.OPEN_PLAYER"

        /**
         * (Re)builds the service-owned player with the given configuration.
         * Called by the player fragments whenever the player must be (re)initialized.
         */
        fun reinitPlayer(
            context: Context,
            extraBuffering: Boolean,
            softwareDecoder: Boolean,
            dataSourceFactory: DataSource.Factory,
        ): ExoPlayer {
            val appContext = context.applicationContext
            appContext.startService(Intent(appContext, PlaybackService::class.java))
            return PlaybackSession.reinitPlayer(
                context = appContext,
                extraBuffering = extraBuffering,
                softwareDecoder = softwareDecoder,
                dataSourceFactory = dataSourceFactory,
            )
        }

        /** Stops playback and tears the service down. Used when the user exits the player. */
        fun stop(context: Context) {
            PlaybackSession.stop(context.applicationContext)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A plain service start (from [PlaybackService.reinitPlayer]) must attach the in-process
        // session to this service. Only then does Media3's MediaNotificationManager observe the
        // session and promote the service to foreground + show the media notification once
        // playback becomes active. (Without a MediaController ever connecting, nothing else does.)
        if (intent == null || intent.action == null) {
            val session = PlaybackSession.getOrCreateSession(this)
            if (!isSessionAdded(session)) {
                addSession(session)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        return PlaybackSession.getOrCreateSession(this)
    }

    override fun onDestroy() {
        PlaybackSession.release()
        super.onDestroy()
    }
}

/** Process-wide holder for the ExoPlayer + MediaSession owned by [PlaybackService]. */
object PlaybackSession {

    var player: ExoPlayer? = null
        private set

    /** Navigation args of the content currently (or last) playing, for notification taps. */
    var resumeData: ResumeData? = null

    /** Last displayed video + server, so a re-attached fragment keeps Cast/Download/servers working. */
    var lastVideo: com.streamflixreborn.streamflix.models.Video? = null
    var lastServer: com.streamflixreborn.streamflix.models.Video.Server? = null
    var lastServers: List<com.streamflixreborn.streamflix.models.Video.Server> = emptyList()

    data class ResumeData(
        val id: String,
        val title: String,
        val subtitle: String,
        val videoType: com.streamflixreborn.streamflix.models.Video.Type,
    )

    private var mediaSession: MediaSession? = null
    private var extraBuffering = false
    private var softwareDecoder = false
    private var dataSourceFactory: DataSource.Factory? = null

    fun getOrCreateSession(context: Context): MediaSession {
        mediaSession?.let { return it }
        val newPlayer = buildPlayer(context)
        val newSession = MediaSession.Builder(context, newPlayer)
            .setSessionActivity(sessionActivity(context))
            .build()
        player = newPlayer
        mediaSession = newSession
        return newSession
    }

    /**
     * PendingIntent that reopens [com.streamflixreborn.streamflix.activities.main.MainMobileActivity]
     * at the player screen (the activity reads [resumeData] to navigate there).
     */
    fun sessionActivity(context: Context): PendingIntent {
        val intent = Intent(context, com.streamflixreborn.streamflix.activities.main.MainMobileActivity::class.java).apply {
            action = PlaybackService.ACTION_OPEN_PLAYER
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun reinitPlayer(
        context: Context,
        extraBuffering: Boolean,
        softwareDecoder: Boolean,
        dataSourceFactory: DataSource.Factory,
    ): ExoPlayer {
        release()
        this.extraBuffering = extraBuffering
        this.softwareDecoder = softwareDecoder
        this.dataSourceFactory = dataSourceFactory
        return getOrCreateSession(context).player as ExoPlayer
    }

    fun stop(context: Context) {
        player?.let { p ->
            p.pause()
            p.clearMediaItems()
        }
        // Forget the last session so a stale notification tap can't reopen a stopped session.
        resumeData = null
        lastVideo = null
        lastServer = null
        lastServers = emptyList()
        context.stopService(Intent(context, PlaybackService::class.java))
    }

    fun release() {
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        dataSourceFactory = null
    }

    private fun buildPlayer(context: Context): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                if (extraBuffering) 300_000 else DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .build()

        val baseBuilder = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1 && !softwareDecoder) {
            ExoPlayer.Builder(context)
        } else {
            val renderersFactory = DefaultRenderersFactory(context).apply {
                setEnableDecoderFallback(true)
                if (softwareDecoder) {
                    setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                }
            }
            ExoPlayer.Builder(context, renderersFactory)
        }

        return baseBuilder
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    dataSourceFactory ?: DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory())
                )
            )
            .setLoadControl(loadControl)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )
                val lang = UserPreferences.currentProvider?.language?.substringBefore("-")
                if (lang == "es") {
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setPreferredAudioLanguage("spa")
                        .build()
                }
            }
    }
}
