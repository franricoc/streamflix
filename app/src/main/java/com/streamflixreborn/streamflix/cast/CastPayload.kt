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
    val videoType: Video.Type? = null,
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
