package com.streamflixreborn.streamflix.cast

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.AnimeOnlineNinjaProvider
import com.streamflixreborn.streamflix.providers.Cine24hProvider
import com.streamflixreborn.streamflix.providers.FilmyOnlineCcProvider
import com.streamflixreborn.streamflix.providers.GuardaSerieProvider
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object BackgroundLinkResolver {

    private const val TAG = "BackgroundLinkResolver"

    suspend fun resolveStreamPayload(
        context: Context,
        videoType: Video.Type,
        id: String
    ): Result<CastPayload> = withContext(Dispatchers.IO) {
        try {
            val provider = UserPreferences.currentProvider
                ?: return@withContext Result.failure(Exception("No provider selected"))

            try {
                AnimeOnlineNinjaProvider.init(context)
                Cine24hProvider.init(context)
                FilmyOnlineCcProvider.init(context)
                GuardaSerieProvider.init(context)
            } catch (ignored: Exception) {}

            Log.d(TAG, "Resolving servers for ID: $id using provider: ${provider.name}")
            val servers = provider.getServers(id, videoType)
            if (servers.isEmpty()) {
                return@withContext Result.failure(Exception("No servers found for this item"))
            }

            var videoResult: Video? = null
            var selectedServerName = ""

            for (server in servers.take(5)) {
                try {
                    Log.d(TAG, "Trying server: ${server.name}")
                    val v = kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                        provider.getVideo(server)
                    }
                    if (v != null && v.source.isNotEmpty()) {
                        videoResult = v
                        selectedServerName = server.name
                        break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Server ${server.name} failed to resolve", e)
                }
            }


            val video = videoResult ?: return@withContext Result.failure(Exception("Could not resolve stream URL from any server"))

            val title = when (videoType) {
                is Video.Type.Movie -> videoType.title
                is Video.Type.Episode -> "${videoType.tvShow.title} - S${videoType.season.number}E${videoType.number}"
            }

            val subtitleText = when (videoType) {
                is Video.Type.Movie -> videoType.releaseDate
                is Video.Type.Episode -> videoType.title ?: "Episode ${videoType.number}"
            }

            val posterUrl = when (videoType) {
                is Video.Type.Movie -> videoType.poster
                is Video.Type.Episode -> videoType.poster ?: videoType.tvShow.poster
            }

            val subtitlesList = video.subtitles.map {
                CastPayload.SubtitleInfo(
                    label = it.label,
                    url = it.file,
                    default = it.default
                )
            }

            val payload = CastPayload(
                action = "PLAY",
                title = title,
                subtitle = subtitleText,
                posterUrl = posterUrl,
                streamUrl = video.source,
                headers = video.headers,
                mimeType = video.type,
                maintainToken = video.maintainToken,
                tokenQuery = if (video.maintainToken) com.streamflixreborn.streamflix.extractors.TokenManager.latestQuery else null,
                subtitles = subtitlesList,
                startPositionMs = 0L,
                isOfflineDownload = false
            )

            Log.d(TAG, "Successfully resolved payload for $title from server $selectedServerName")
            Result.success(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving stream payload", e)
            Result.failure(e)
        }
    }
}
