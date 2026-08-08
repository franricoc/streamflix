package com.streamflixreborn.streamflix.providers.utils

import android.net.Uri
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern

object SuperFavoritesDeduplicator {

    private val STRIP_REGEX = Pattern.compile("(?i)\\b(latino|castellano|subtitulado|subbed|dubbed|hd|4k|1080p|720p)\\b|[\\[\\]()\\-_.:,']")

    fun normalizeTitle(title: String): String {
        val decomposed = Normalizer.normalize(title, Normalizer.Form.NFD)
        val withoutAccents = decomposed.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        val cleaned = STRIP_REGEX.matcher(withoutAccents).replaceAll(" ")
        return cleaned.trim().lowercase(Locale.ROOT).replace("\\s+".toRegex(), " ")
    }

    // ============================================
    // COMPOSITE ID ENCODING / DECODING
    // ============================================

    /**
     * Encodes a map of ProviderName -> OriginalItemId into a composite synthetic URI string.
     * Format: superfav://mediaType?ProviderA=ID1&ProviderB=ID2
     */
    fun encodeCompositeId(mediaType: String, sources: Map<String, String>): String {
        val builder = Uri.Builder()
            .scheme("superfav")
            .authority(mediaType)
        sources.forEach { (providerName, originalId) ->
            builder.appendQueryParameter(providerName, originalId)
        }
        return builder.build().toString()
    }

    /**
     * Decodes a composite URI string back into Map<String, String> of ProviderName -> OriginalItemId.
     * Fallback for simple IDs: returns map of defaultProvider -> id.
     */
    fun decodeCompositeId(compositeId: String, defaultProviderName: String = ""): Map<String, String> {
        if (!compositeId.startsWith("superfav://")) {
            return if (defaultProviderName.isNotEmpty()) mapOf(defaultProviderName to compositeId) else emptyMap()
        }
        return try {
            val uri = Uri.parse(compositeId)
            val result = mutableMapOf<String, String>()
            for (paramName in uri.queryParameterNames) {
                val value = uri.getQueryParameter(paramName)
                if (!value.isNullOrEmpty()) {
                    result[paramName] = value
                }
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Encodes a season ID: superfav://season?number=1&pName1=origSeasonId1...
     */
    fun encodeSeasonId(seasonNumber: Int, sources: Map<String, String>): String {
        val builder = Uri.Builder()
            .scheme("superfav")
            .authority("season")
            .appendQueryParameter("number", seasonNumber.toString())
        sources.forEach { (providerName, originalId) ->
            builder.appendQueryParameter(providerName, originalId)
        }
        return builder.build().toString()
    }

    fun decodeSeasonId(seasonId: String): Pair<Int, Map<String, String>> {
        if (!seasonId.startsWith("superfav://season")) {
            return 1 to emptyMap()
        }
        return try {
            val uri = Uri.parse(seasonId)
            val number = uri.getQueryParameter("number")?.toIntOrNull() ?: 1
            val sources = mutableMapOf<String, String>()
            for (paramName in uri.queryParameterNames) {
                if (paramName != "number") {
                    val value = uri.getQueryParameter(paramName)
                    if (!value.isNullOrEmpty()) {
                        sources[paramName] = value
                    }
                }
            }
            number to sources
        } catch (e: Exception) {
            1 to emptyMap()
        }
    }

    /**
     * Encodes a server ID: superfav_server://ProviderName/originalServerId
     */
    fun encodeServerId(providerName: String, originalServerId: String): String {
        return "superfav_server://$providerName/$originalServerId"
    }

    fun decodeServerId(serverId: String): Pair<String, String> {
        if (!serverId.startsWith("superfav_server://")) {
            return "" to serverId
        }
        val remainder = serverId.removePrefix("superfav_server://")
        val slashIndex = remainder.indexOf('/')
        if (slashIndex == -1) return "" to remainder
        val providerName = remainder.substring(0, slashIndex)
        val origId = remainder.substring(slashIndex + 1)
        return providerName to origId
    }

    // ============================================
    // MOVIE DEDUPLICATION
    // ============================================

    fun deduplicateMovies(rawMovies: List<Movie>): List<Movie> {
        if (rawMovies.isEmpty()) return emptyList()

        val grouped = LinkedHashMap<String, MutableList<Movie>>()

        for (movie in rawMovies) {
            val key = when {
                !movie.imdbId.isNullOrEmpty() -> "imdb:${movie.imdbId}"
                else -> {
                    val normTitle = normalizeTitle(movie.title)
                    val year = movie.released?.get(Calendar.YEAR) ?: ""
                    "title:${normTitle}_$year"
                }
            }
            grouped.getOrPut(key) { mutableListOf() }.add(movie)
        }

        val result = mutableListOf<Movie>()

        for ((_, movies) in grouped) {
            if (movies.size == 1) {
                val m = movies.first()
                val provider = m.providerName ?: ""
                val sources = mapOf(provider to m.id)
                val compositeId = encodeCompositeId("movie", sources)
                result.add(
                    m.copy(
                        id = compositeId
                    ).apply { providerName = "Super Favoritos" }
                )
            } else {
                val sources = mutableMapOf<String, String>()
                movies.forEach { m ->
                    val pName = m.providerName ?: ""
                    if (pName.isNotEmpty() && !sources.containsKey(pName)) {
                        sources[pName] = m.id
                    }
                }
                val primary = movies.maxByOrNull {
                    (if (!it.poster.isNullOrEmpty()) 2 else 0) +
                    (if (!it.banner.isNullOrEmpty()) 2 else 0) +
                    (if (!it.overview.isNullOrEmpty()) 1 else 0)
                } ?: movies.first()

                val compositeId = encodeCompositeId("movie", sources)
                result.add(
                    primary.copy(
                        id = compositeId
                    ).apply { providerName = "Super Favoritos" }
                )
            }
        }

        return result
    }

    // ============================================
    // TV SHOW DEDUPLICATION
    // ============================================

    fun deduplicateTvShows(rawTvShows: List<TvShow>): List<TvShow> {
        if (rawTvShows.isEmpty()) return emptyList()

        val grouped = LinkedHashMap<String, MutableList<TvShow>>()

        for (tvShow in rawTvShows) {
            val key = when {
                !tvShow.imdbId.isNullOrEmpty() -> "imdb:${tvShow.imdbId}"
                else -> {
                    val normTitle = normalizeTitle(tvShow.title)
                    val year = tvShow.released?.get(Calendar.YEAR) ?: ""
                    "title:${normTitle}_$year"
                }
            }
            grouped.getOrPut(key) { mutableListOf() }.add(tvShow)
        }

        val result = mutableListOf<TvShow>()

        for ((_, tvShows) in grouped) {
            if (tvShows.size == 1) {
                val tv = tvShows.first()
                val provider = tv.providerName ?: ""
                val sources = mapOf(provider to tv.id)
                val compositeId = encodeCompositeId("tvshow", sources)
                result.add(
                    tv.copy(
                        id = compositeId
                    ).apply { providerName = "Super Favoritos" }
                )
            } else {
                val sources = mutableMapOf<String, String>()
                tvShows.forEach { tv ->
                    val pName = tv.providerName ?: ""
                    if (pName.isNotEmpty() && !sources.containsKey(pName)) {
                        sources[pName] = tv.id
                    }
                }
                val primary = tvShows.maxByOrNull {
                    (if (!it.poster.isNullOrEmpty()) 2 else 0) +
                    (if (!it.banner.isNullOrEmpty()) 2 else 0) +
                    (if (!it.overview.isNullOrEmpty()) 1 else 0) +
                    it.seasons.size
                } ?: tvShows.first()

                val compositeId = encodeCompositeId("tvshow", sources)
                result.add(
                    primary.copy(
                        id = compositeId
                    ).apply { providerName = "Super Favoritos" }
                )
            }
        }

        return result
    }
}
