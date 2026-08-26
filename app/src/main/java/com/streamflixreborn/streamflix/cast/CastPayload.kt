package com.streamflixreborn.streamflix.cast

import com.streamflixreborn.streamflix.models.Video
import java.io.Serializable

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
    @Transient val videoType: Video.Type? = null,
    val mediaId: String? = null
) : Serializable {

    data class SubtitleInfo(
        val label: String,
        val url: String,
        val default: Boolean = false
    ) : Serializable

    data class DiscoveredDevice(
        val name: String,
        val ipAddress: String,
        val port: Int
    )
}
