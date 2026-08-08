package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Request
import java.net.URLEncoder

/** Returns the element as JsonObject only if it is not null and not JsonNull. */
private fun JsonElement?.safeAsObject(): JsonObject? =
    if (this != null && !this.isJsonNull && this.isJsonObject) this.asJsonObject else null

@StreamflixProvider(
    name = "Lamovie",
    language = "es",
    movies = true,
    tvShows = true
)
object LamovieProvider : BaseProvider(){

    override val name = "LaMovie"
    override val baseUrl = "https://lamovie.org"
    override val logo = "https://lamovie.org/wp-content/uploads/2025/06/Captura-de-pantalla-2025-05-20-215429-1024x354.png"
    override val language = "es"

    private fun getJson(url: String): JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        val response = NetworkClient.default.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("HTTP error code: ${response.code}")
        val bodyStr = response.body?.string() ?: throw Exception("Empty body")
        return JsonParser.parseString(bodyStr).asJsonObject
    }

    private fun parsePost(post: JsonObject): Show? {
        val id = post.get("slug")?.asString ?: return null
        val title = post.get("title")?.asString ?: return null
        val type = post.get("type")?.asString ?: ""

        val posterPath = post.get("images").safeAsObject()?.get("poster")?.asString ?: ""
        val poster = if (posterPath.isNotEmpty()) {
            if (posterPath.startsWith("http")) posterPath else "https://lamovie.org/wp-content/uploads$posterPath"
        } else ""

        return if (type == "tvshows" || type == "animes") {
            TvShow(id = id, title = title, poster = poster)
        } else {
            Movie(id = id, title = title, poster = poster)
        }
    }

    private fun getListing(type: String, page: Int): List<Show> {
        val url = "https://lamovie.org/wp-api/v1/listing/$type?filter=%7B%7D&page=$page&orderBy=latest&order=DESC&postType=$type&postsPerPage=12"
        return try {
            val json = getJson(url)
            val posts = json.get("data").safeAsObject()?.getAsJsonArray("posts") ?: return emptyList()
            posts.mapNotNull { parsePost(it.asJsonObject) }
        } catch (e: Exception) {
            Log.e("LaMovie", "Error getting listing for $type", e)
            emptyList()
        }
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val categories = mutableListOf<Category>()
        try {
            val moviesDeferred = async { getListing("movies", 1) }
            val tvshowsDeferred = async { getListing("tvshows", 1) }
            val animesDeferred = async { getListing("animes", 1) }

            val movies = moviesDeferred.await()
            val tvshows = tvshowsDeferred.await()
            val animes = animesDeferred.await()

            if (movies.isNotEmpty()) categories.add(Category("Películas Recientes", movies))
            if (tvshows.isNotEmpty()) categories.add(Category("Series Recientes", tvshows))
            if (animes.isNotEmpty()) categories.add(Category("Animes Recientes", animes))
        } catch (e: Exception) {
            Log.e("LaMovie", "Error loading home", e)
        }
        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        return try {
            val url = "https://lamovie.org/wp-api/v1/search?filter=%7B%7D&postType=any&q=${URLEncoder.encode(query, "UTF-8")}&postsPerPage=26&page=$page"
            val json = getJson(url)
            val posts = json.get("data").safeAsObject()?.getAsJsonArray("posts") ?: return emptyList()
            posts.mapNotNull { parsePost(it.asJsonObject) }
        } catch (e: Exception) {
            Log.e("LaMovie", "Search error", e)
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return getListing("movies", page).filterIsInstance<Movie>()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return getListing("tvshows", page).filterIsInstance<TvShow>()
    }

    override suspend fun getMovie(id: String): Movie {
        val url = "https://lamovie.org/wp-api/v1/single/movies?slug=$id&postType=movies"
        val json = getJson(url)
        val data = json.get("data").safeAsObject() ?: throw Exception("Movie not found")

        val title = data.get("title")?.asString ?: ""
        val overview = data.get("overview")?.asString
        val rating = data.get("rating")?.asString?.toDoubleOrNull()
        val released = data.get("release_date")?.asString
        val runtimeStr = data.get("runtime")?.asString
        val runtime = runtimeStr?.toDoubleOrNull()?.toInt()

        val posterPath = data.get("images").safeAsObject()?.get("poster")?.asString ?: ""
        val poster = if (posterPath.isNotEmpty()) {
            if (posterPath.startsWith("http")) posterPath else "https://lamovie.org/wp-content/uploads$posterPath"
        } else ""

        val genresArray = data.getAsJsonArray("genres")
        val genres = genresArray?.mapNotNull { genreId ->
            Genre(id = genreId.asString, name = "Género")
        } ?: emptyList()

        val dbId = data.get("_id")?.asInt?.toString() ?: ""

        return Movie(
            id = if (dbId.isNotEmpty()) "$id|$dbId" else id,
            title = title,
            overview = overview,
            poster = poster,
            rating = rating,
            released = released,
            runtime = runtime,
            genres = genres
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        // Try tvshows first, fallback to animes
        var url = "https://lamovie.org/wp-api/v1/single/tvshows?slug=$id&postType=tvshows"
        var json = try {
            getJson(url)
        } catch (e: Exception) {
            null
        }

        if (json == null || json.get("error")?.asBoolean == true || json.getAsJsonObject("data") == null) {
            url = "https://lamovie.org/wp-api/v1/single/tvshows?slug=$id&postType=animes"
            json = getJson(url)
        }

        val data = json.get("data").safeAsObject() ?: throw Exception("TvShow not found")

        val title = data.get("title")?.asString ?: ""
        val overview = data.get("overview")?.asString
        val rating = data.get("rating")?.asString?.toDoubleOrNull()
        val released = data.get("release_date")?.asString

        val posterPath = data.get("images").safeAsObject()?.get("poster")?.asString ?: ""
        val poster = if (posterPath.isNotEmpty()) {
            if (posterPath.startsWith("http")) posterPath else "https://lamovie.org/wp-content/uploads$posterPath"
        } else ""

        val genresArray = data.getAsJsonArray("genres")
        val genres = genresArray?.mapNotNull { genreId ->
            Genre(id = genreId.asString, name = "Género")
        } ?: emptyList()

        val dbId = data.get("_id")?.asInt?.toString() ?: ""

        // Seasons extraction
        // From episodes check, the API provides "seasons" list of numbers in getEpisodesBySeason or single
        // Let's check if "latest_episode" contains season info or we can construct standard Seasons list.
        // Actually, the api response for single tvshow has: "latest_episode": {"season": 1, "episode": 6}
        // Let's extract max season number from latest_episode or default to 1 season
        // When the API returns "latest_episode": null, Gson exposes it as JsonNull
        // so we must check isJsonNull before trying to cast to JsonObject
        val latestEpisodeElement = data.get("latest_episode")
        val latestEpisode = if (latestEpisodeElement != null && !latestEpisodeElement.isJsonNull)
            latestEpisodeElement.asJsonObject else null
        val maxSeason = latestEpisode?.get("season")?.asInt ?: 1

        val seasons = (1..maxSeason).map { seasonNum ->
            Season(
                id = "$dbId-$seasonNum|$poster",
                number = seasonNum,
                title = "Temporada $seasonNum"
            )
        }

        return TvShow(
            id = id,
            title = title,
            overview = overview,
            poster = poster,
            rating = rating,
            released = released,
            genres = genres,
            seasons = seasons
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        return try {
            val parts = seasonId.split("|")
            val showPoster = parts.getOrNull(1) ?: ""
            val seasonParts = parts[0].split("-")
            val showId = seasonParts[0]
            val seasonNumber = seasonParts[1]

            val url = "https://lamovie.org/wp-api/v1/single/episodes/list?_id=$showId&season=$seasonNumber&page=1&postsPerPage=100"
            val json = getJson(url)
            val data = json.get("data").safeAsObject() ?: return emptyList()
            val posts = data.getAsJsonArray("posts") ?: return emptyList()

            posts.mapNotNull { item ->
                val epObj = item.asJsonObject
                val epId = epObj.get("_id")?.asInt?.toString() ?: return@mapNotNull null
                val epNumber = epObj.get("episode_number")?.asInt ?: 0
                val epTitle = epObj.get("title")?.asString ?: "Episodio $epNumber"
                val epReleased = epObj.get("date")?.asString

                val stillPath = epObj.get("still_path")?.asString ?: ""
                val poster = if (stillPath.isNotEmpty()) {
                    "https://image.tmdb.org/t/p/w500$stillPath"
                } else {
                    showPoster
                }

                Episode(
                    id = epId,
                    number = epNumber,
                    title = epTitle,
                    poster = poster,
                    released = epReleased
                )
            }.sortedBy { it.number }
        } catch (e: Exception) {
            Log.e("LaMovie", "Error getting episodes", e)
            emptyList()
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val postId = if (id.contains("|")) id.substringAfter("|") else id
        val url = "https://lamovie.org/wp-api/v1/player?postId=$postId&demo=0"
        return try {
            val json = getJson(url)
            val data = json.get("data").safeAsObject() ?: return emptyList()
            val embeds = data.getAsJsonArray("embeds") ?: return emptyList()

            embeds.mapNotNull { item ->
                val embedUrl = item.asJsonObject.get("url")?.asString ?: return@mapNotNull null
                val serverName = item.asJsonObject.get("server")?.asString ?: "Online"
                val lang = item.asJsonObject.get("lang")?.asString ?: "Latino"

                Video.Server(
                    id = embedUrl,
                    name = "$serverName ($lang)",
                    src = embedUrl
                )
            }
        } catch (e: Exception) {
            Log.e("LaMovie", "Error getting servers", e)
            emptyList()
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.src, server)
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        return Genre(id = id, name = "Género")
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = "Persona")
    }
}
