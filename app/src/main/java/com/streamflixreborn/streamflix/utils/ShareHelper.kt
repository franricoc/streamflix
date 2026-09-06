package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video

/**
 * Utility object for generating shareable links and triggering Android share intents.
 *
 * Link formats:
 *   App & Web Deep Links:
 *     https://streamflix.app/content?type=movie&id=xxx&provider=ProviderName
 *     streamflix://content?type=movie&id=xxx&provider=ProviderName
 *   Direct Stream Link:
 *     Shared from player with resolved streaming URL for instant web viewing without app.
 */
object ShareHelper {

    private const val SCHEME_CUSTOM = "streamflix"
    private const val HOST_CUSTOM = "content"

    private const val SCHEME_HTTPS = "https"
    private const val HOST_HTTPS = "streamflix.app"
    private const val PATH_HTTPS = "content"

    /**
     * Generate HTTPS link that WhatsApp/Telegram recognize as clickable.
     */
    fun generateHttpsLink(
        videoType: Video.Type,
        providerName: String?,
    ): Uri {
        val builder = Uri.Builder()
            .scheme(SCHEME_HTTPS)
            .authority(HOST_HTTPS)
            .appendPath(PATH_HTTPS)

        when (videoType) {
            is Video.Type.Movie -> {
                builder.appendQueryParameter("type", "movie")
                builder.appendQueryParameter("id", videoType.id)
                builder.appendQueryParameter("title", videoType.title)
                if (videoType.poster.isNotEmpty()) builder.appendQueryParameter("poster", videoType.poster)
                providerName?.let { builder.appendQueryParameter("provider", it) }
            }
            is Video.Type.Episode -> {
                builder.appendQueryParameter("type", "episode")
                builder.appendQueryParameter("id", videoType.id)
                builder.appendQueryParameter("season", videoType.season.number.toString())
                builder.appendQueryParameter("episode", videoType.number.toString())
                builder.appendQueryParameter("tvShowId", videoType.tvShow.id)
                builder.appendQueryParameter("tvShowTitle", videoType.tvShow.title)
                videoType.title?.let { builder.appendQueryParameter("episodeTitle", it) }
                videoType.poster?.let { builder.appendQueryParameter("poster", it) }
                providerName?.let { builder.appendQueryParameter("provider", it) }
            }
        }

        return builder.build()
    }

    /**
     * Generate custom scheme deep link URI.
     */
    fun generateCustomLink(
        videoType: Video.Type,
        providerName: String?,
    ): Uri {
        val builder = Uri.Builder()
            .scheme(SCHEME_CUSTOM)
            .authority(HOST_CUSTOM)

        when (videoType) {
            is Video.Type.Movie -> {
                builder.appendQueryParameter("type", "movie")
                builder.appendQueryParameter("id", videoType.id)
                builder.appendQueryParameter("title", videoType.title)
                if (videoType.poster.isNotEmpty()) builder.appendQueryParameter("poster", videoType.poster)
                providerName?.let { builder.appendQueryParameter("provider", it) }
            }
            is Video.Type.Episode -> {
                builder.appendQueryParameter("type", "episode")
                builder.appendQueryParameter("id", videoType.id)
                builder.appendQueryParameter("season", videoType.season.number.toString())
                builder.appendQueryParameter("episode", videoType.number.toString())
                builder.appendQueryParameter("tvShowId", videoType.tvShow.id)
                builder.appendQueryParameter("tvShowTitle", videoType.tvShow.title)
                videoType.title?.let { builder.appendQueryParameter("episodeTitle", it) }
                videoType.poster?.let { builder.appendQueryParameter("poster", it) }
                providerName?.let { builder.appendQueryParameter("provider", it) }
            }
        }

        return builder.build()
    }

    /**
     * Generate HTTPS link from model objects.
     */
    fun generateHttpsLinkFromModel(
        content: Any,
        providerName: String?,
    ): Uri? {
        return when (content) {
            is TvShow -> {
                val builder = Uri.Builder()
                    .scheme(SCHEME_HTTPS)
                    .authority(HOST_HTTPS)
                    .appendPath(PATH_HTTPS)
                    .appendQueryParameter("type", "tvshow")
                    .appendQueryParameter("id", content.id)
                    .appendQueryParameter("tvShowTitle", content.title)
                content.poster?.let { builder.appendQueryParameter("poster", it) }
                content.banner?.let { builder.appendQueryParameter("banner", it) }
                providerName?.let { builder.appendQueryParameter("provider", it) }
                builder.build()
            }
            is Movie -> {
                val builder = Uri.Builder()
                    .scheme(SCHEME_HTTPS)
                    .authority(HOST_HTTPS)
                    .appendPath(PATH_HTTPS)
                    .appendQueryParameter("type", "movie")
                    .appendQueryParameter("id", content.id)
                    .appendQueryParameter("title", content.title)
                content.poster?.let { builder.appendQueryParameter("poster", it) }
                providerName?.let { builder.appendQueryParameter("provider", it) }
                builder.build()
            }
            is Episode -> {
                val builder = Uri.Builder()
                    .scheme(SCHEME_HTTPS)
                    .authority(HOST_HTTPS)
                    .appendPath(PATH_HTTPS)
                    .appendQueryParameter("type", "episode")
                    .appendQueryParameter("id", content.id)
                    .appendQueryParameter("season", (content.season?.number ?: 0).toString())
                    .appendQueryParameter("episode", content.number.toString())
                content.title?.let { builder.appendQueryParameter("episodeTitle", it) }
                content.poster?.let { builder.appendQueryParameter("poster", it) }
                content.tvShow?.let { tvShow ->
                    builder.appendQueryParameter("tvShowId", tvShow.id)
                    builder.appendQueryParameter("tvShowTitle", tvShow.title)
                }
                providerName?.let { builder.appendQueryParameter("provider", it) }
                builder.build()
            }
            else -> null
        }
    }

    /**
     * Share from poster/cards/details:
     * Generates a link that opens the destination app directly into the poster/details screen
     * with the correct provider selected, ready to hit play.
     */
    fun shareContent(
        context: Context,
        videoType: Video.Type,
        providerName: String? = UserPreferences.currentProvider?.name,
        title: String? = null,
    ) {
        val httpsLink = generateHttpsLink(videoType, providerName)
        val displayTitle = title ?: when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> {
                val tvShowTitle = videoType.tvShow.title
                val episodeInfo = "T${videoType.season.number}:E${videoType.number}"
                "$tvShowTitle - $episodeInfo"
            }
        }

        val shareText = buildString {
            append("🎬 *").append(displayTitle).append("*")
            providerName?.let { append(" (").append(it).append(")") }
            append("\n\n")
            append("📲 *Abrir en StreamFlix:*\n")
            append(httpsLink.toString())
        }

        startShareIntent(context, shareText, displayTitle)
    }

    /**
     * Share from model (Movie, TvShow, Episode card/options).
     */
    fun shareFromModel(
        context: Context,
        content: Any,
        providerName: String? = UserPreferences.currentProvider?.name,
    ) {
        val httpsLink = generateHttpsLinkFromModel(content, providerName) ?: return
        val displayTitle = when (content) {
            is Movie -> content.title
            is Episode -> {
                val tvShowTitle = content.tvShow?.title ?: ""
                val episodeInfo = "T${content.season?.number ?: 0}:E${content.number}"
                "$tvShowTitle - $episodeInfo"
            }
            is TvShow -> content.title
            else -> return
        }

        val shareText = buildString {
            append("🎬 *").append(displayTitle).append("*")
            providerName?.let { append(" (").append(it).append(")") }
            append("\n\n")
            append("📲 *Abrir en StreamFlix:*\n")
            append(httpsLink.toString())
        }

        startShareIntent(context, shareText, displayTitle)
    }

    /**
     * Share from Player (Smart Hybrid Share):
     * - Provides the web player / embed link (if available) for guaranteed playback in any web browser.
     * - Provides the direct stream link (.m3u8 / .mp4) for direct playback or external players (VLC, MX Player).
     * - Includes helpful hints so the recipient always knows how to watch without errors.
     */
    fun shareResolvedStream(
        context: Context,
        title: String,
        subtitle: String? = null,
        resolvedStreamUrl: String,
        headers: Map<String, String>? = null,
        embedUrl: String? = null,
        serverName: String? = null,
    ) {
        val validEmbedUrl = embedUrl?.takeIf {
            (it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)) &&
            !it.contains(".m3u8", ignoreCase = true) &&
            !it.contains(".mp4", ignoreCase = true)
        }

        val referer = headers?.get("Referer") ?: headers?.get("referer")
        val fallbackWebUrl = validEmbedUrl ?: referer?.takeIf {
            (it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)) &&
            !it.contains(".m3u8", ignoreCase = true) &&
            !it.contains(".mp4", ignoreCase = true)
        }

        val shareText = buildString {
            append("🍿 *").append(title).append("*")
            if (!subtitle.isNullOrBlank()) {
                append(" - ").append(subtitle)
            }
            if (!serverName.isNullOrBlank()) {
                append(" (").append(serverName).append(")")
            }
            append("\n\n")

            if (fallbackWebUrl != null) {
                append("🌐 *Ver en el navegador:* (Se recomienda usar Brave para evitar publicidad)\n")
                append(fallbackWebUrl)
                append("\n\n")
                append("⚡ *Enlace directo de streaming:* (VLC / Navegador)\n")
                append(resolvedStreamUrl)
                append("\n\n")
                append("💡 *Tip:* Si el enlace directo muestra video roto en tu navegador, usa el enlace Web en Brave o ábrelo en VLC / MX Player.")
            } else {
                append("▶️ *Ver directo en el navegador:* (Se recomienda Brave)\n")
                append(resolvedStreamUrl)
                append("\n\n")
                append("💡 *Tip:* Si tu navegador no reproduce streams .m3u8, puedes copiar el enlace y abrirlo en VLC o MX Player.")
            }
        }

        startShareIntent(context, shareText, title)
    }

    private fun startShareIntent(context: Context, text: String, subject: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooser = Intent.createChooser(
            shareIntent,
            context.getString(com.streamflixreborn.streamflix.R.string.share_via)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        context.startActivity(chooser)
    }

    /**
     * Parse incoming deep link (custom streamflix:// or https://streamflix.app/content).
     */
    fun parseDeepLink(uri: Uri): ParsedDeepLink? {
        val isValidCustom = uri.scheme == SCHEME_CUSTOM && uri.host == HOST_CUSTOM
        val isValidHttps = (uri.scheme == "https" || uri.scheme == "http") &&
                uri.host == HOST_HTTPS &&
                uri.path?.contains(PATH_HTTPS) == true

        if (!isValidCustom && !isValidHttps) return null

        val type = uri.getQueryParameter("type") ?: return null
        val id = uri.getQueryParameter("id") ?: return null
        val providerName = uri.getQueryParameter("provider")

        return when (type) {
            "movie" -> {
                val title = uri.getQueryParameter("title") ?: ""
                val releaseDate = uri.getQueryParameter("releaseDate") ?: ""
                val poster = uri.getQueryParameter("poster") ?: ""
                val imdbId = uri.getQueryParameter("imdbId")
                ParsedDeepLink(
                    videoType = Video.Type.Movie(
                        id = id,
                        title = title,
                        releaseDate = releaseDate,
                        poster = poster,
                        imdbId = imdbId,
                    ),
                    providerName = providerName,
                )
            }
            "episode" -> {
                val seasonNumber = uri.getQueryParameter("season")?.toIntOrNull() ?: 0
                val episodeNumber = uri.getQueryParameter("episode")?.toIntOrNull() ?: 0
                val tvShowId = uri.getQueryParameter("tvShowId") ?: ""
                val tvShowTitle = uri.getQueryParameter("tvShowTitle") ?: ""
                val episodeTitle = uri.getQueryParameter("episodeTitle")
                val poster = uri.getQueryParameter("poster")

                ParsedDeepLink(
                    videoType = Video.Type.Episode(
                        id = id,
                        number = episodeNumber,
                        title = episodeTitle,
                        poster = poster,
                        overview = null,
                        tvShow = Video.Type.Episode.TvShow(
                            id = tvShowId,
                            title = tvShowTitle,
                            poster = null,
                            banner = null,
                            releaseDate = null,
                            imdbId = null,
                        ),
                        season = Video.Type.Episode.Season(
                            number = seasonNumber,
                            title = null,
                        ),
                    ),
                    providerName = providerName,
                )
            }
            "tvshow" -> {
                val tvShowTitle = uri.getQueryParameter("tvShowTitle") ?: ""
                val poster = uri.getQueryParameter("poster")
                val banner = uri.getQueryParameter("banner")
                ParsedDeepLink(
                    videoType = Video.Type.Episode(
                        id = id,
                        number = 0,
                        title = null,
                        poster = poster,
                        overview = null,
                        tvShow = Video.Type.Episode.TvShow(
                            id = id,
                            title = tvShowTitle,
                            poster = poster,
                            banner = banner,
                            releaseDate = null,
                            imdbId = null,
                        ),
                        season = Video.Type.Episode.Season(
                            number = 0,
                            title = null,
                        ),
                    ),
                    providerName = providerName,
                    isTvShow = true,
                )
            }
            else -> null
        }
    }

    data class ParsedDeepLink(
        val videoType: Video.Type,
        val providerName: String?,
        val isTvShow: Boolean = false,
    )
}
