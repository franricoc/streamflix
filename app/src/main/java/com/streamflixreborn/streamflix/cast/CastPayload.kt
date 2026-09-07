package com.streamflixreborn.streamflix.cast

import androidx.annotation.Keep
import com.streamflixreborn.streamflix.models.Video
import java.io.Serializable

@Keep
data class CastPayload(
    val action: String = "PLAY",
    val title: String,
    val subtitle: String? = null,
    val posterUrl: String? = null,
    val streamUrl: String = "",
    val headers: Map<String, String>? = null,
    val mimeType: String? = null,
    val maintainToken: Boolean = false,
    val tokenQuery: String? = null,
    val subtitles: List<SubtitleInfo> = emptyList(),
    val startPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isOfflineDownload: Boolean = false,

    /**
     * When true (offline downloads only) the TV pulls the whole file from the phone once,
     * stores it locally and plays from its own storage, so playback no longer depends on
     * the phone staying awake. Only used for progressive content; HLS keeps direct streaming.
     */
    val fullTransfer: Boolean = false,
    val mediaId: String? = null,
    val contentType: String? = null, // "movie", "episode"
    val providerName: String? = null,
    val episodeId: String? = null,
    val episodeNumber: Int? = null,
    val seasonNumber: Int? = null,
    val tvShowId: String? = null,
    val tvShowTitle: String? = null,
    val tvShowPoster: String? = null,
    val tvShowBanner: String? = null,
    val episodeTitle: String? = null,
    val releaseDate: String? = null,
    val imdbId: String? = null,
    val fileSizeBytes: Long = 0L,
) : Serializable {

    fun toVideoType(): Video.Type {
        if (contentType == "episode" || episodeNumber != null || !tvShowId.isNullOrEmpty()) {
            return Video.Type.Episode(
                id = episodeId ?: mediaId ?: "cast_${System.currentTimeMillis()}",
                number = episodeNumber ?: 0,
                title = episodeTitle ?: subtitle,
                poster = posterUrl,
                overview = null,
                tvShow = Video.Type.Episode.TvShow(
                    id = tvShowId ?: "",
                    title = tvShowTitle ?: title,
                    poster = tvShowPoster ?: posterUrl,
                    banner = tvShowBanner,
                    releaseDate = releaseDate,
                    imdbId = imdbId,
                ),
                season = Video.Type.Episode.Season(
                    number = seasonNumber ?: 1,
                    title = null,
                ),
            )
        }
        return Video.Type.Movie(
            id = mediaId ?: "cast_${System.currentTimeMillis()}",
            title = title,
            releaseDate = releaseDate ?: subtitle ?: "",
            poster = posterUrl ?: "",
            imdbId = imdbId,
        )
    }

    @Keep
    data class SubtitleInfo(
        val label: String,
        val url: String,
        val default: Boolean = false
    ) : Serializable

    @Keep
    data class DiscoveredDevice(
        val name: String,
        val ipAddress: String,
        val port: Int
    )
}
