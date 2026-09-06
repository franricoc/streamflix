package com.streamflixreborn.streamflix.providers

import android.net.Uri
import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.original
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * CineHax proxies TMDb metadata (its item ids ARE TMDb ids) through a WordPress theme, so
 * catalog/search/detail lean on [TmdbUtils] instead of scraping HTML for that data. The only
 * scraping needed is resolving playable servers: the /watch/ detail page links to an "UNLIMPLAY"
 * embed page whose HTML contains a server-rendered `const EMBEDS = {...}` JSON blob mapping
 * language -> server label -> real embed URL (remux.unlimplay.com is a first-party direct MP4
 * CDN; the rest are hosts already covered by the shared [Extractor] system).
 */
@StreamflixProvider(name = "CineHax", language = "es", movies = true, tvShows = true)
object CineHaxProvider : Provider {

    override val name = "CineHax"
    override val baseUrl = "https://cinehax.com"
    override val logo = "https://cinehax.com/wp-content/uploads/2026/06/cropped-favicon-192x192.jpg"
    override val language = "es"

    private const val TAG = "CineHaxProvider"
    private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
    private const val UNLIMPLAY_HOST = "unlimplay.com"
    private const val REMUX_HOST = "remux.unlimplay.com"

    private val LANGUAGE_ORDER = listOf("latino", "subtitulado", "castellano", "español", "espanol")
    private val LANGUAGE_LABELS = mapOf(
        "latino" to "Latino",
        "subtitulado" to "Subtitulado",
        "castellano" to "Castellano",
        "español" to "Castellano",
        "espanol" to "Castellano",
    )
    private val PRIORITY_SERVERS = listOf("remux")

    private val HOME_CATEGORY_LABELS = mapOf(
        "trending_all" to "Tendencias",
        "trending_movies" to "Películas en tendencia",
        "trending_series" to "Series en tendencia",
        "popular_movies" to "Películas populares",
        "popular_tv" to "Series populares",
        "top_rated_movies" to "Películas mejor valoradas",
        "top_rated_tv" to "Series mejor valoradas",
        "now_playing_movies" to "En cartelera",
        "upcoming_movies" to "Próximamente",
        "popular_korean_movies" to "Películas coreanas",
        "warner_bros_pictures" to "Warner Bros. Pictures",
        "dreamWorks_animation" to "DreamWorks Animation",
        "marvel" to "Marvel Studios",
        "blumhouse" to "Blumhouse",
        "netflix_series" to "Series de Netflix",
        "hbo" to "HBO",
        "hulu" to "Hulu",
        "amazon_prime" to "Amazon Prime",
        "action_movies" to "Películas de Acción",
        "comedy_movies" to "Películas de Comedia",
        "romance_movies" to "Películas de Romance",
        "horror_movies" to "Películas de Terror",
        "adventure_movies" to "Películas de Aventura",
        "fantasy_movies" to "Películas de Fantasía",
        "history_movies" to "Películas de Historia",
        "music_movies" to "Películas de Música",
        "mystery_movies" to "Películas de Misterio",
        "war_movies" to "Películas Bélicas",
        "western_movies" to "Películas Western",
        "sci_fi_fantasy_movie" to "Películas de Ciencia Ficción y Fantasía",
        "action_adventure_tv" to "Series de Acción y Aventura",
        "comedy_tv" to "Series de Comedia",
        "kids_tv" to "Series Infantiles",
        "sci_fi_fantasy_tv" to "Series de Ciencia Ficción y Fantasía",
        "soap_tv" to "Telenovelas",
        "war_politics_tv" to "Series de Guerra y Política",
    )

    private val GENRES = listOf(
        "movie:action" to "Acción",
        "movie:adventure" to "Aventura",
        "movie:animation" to "Animación",
        "movie:comedy" to "Comedia",
        "movie:crime" to "Crimen",
        "movie:documentary" to "Documental",
        "movie:drama" to "Drama",
        "movie:family" to "Familia",
        "movie:fantasy" to "Fantasía",
        "movie:history" to "Historia",
        "movie:horror" to "Terror",
        "movie:music" to "Música",
        "movie:mystery" to "Misterio",
        "movie:romance" to "Romance",
        "movie:science-fiction" to "Ciencia ficción",
        "movie:tv-movie" to "Película de TV",
        "movie:thriller" to "Suspense",
        "movie:war" to "Bélica",
        "movie:western" to "Western",
        "tv:action-adventure" to "Acción y aventura",
        "tv:animation" to "Animación",
        "tv:comedy" to "Comedia",
        "tv:crime" to "Crimen",
        "tv:documentary" to "Documental",
        "tv:drama" to "Drama",
        "tv:family" to "Familia",
        "tv:kids" to "Infantil",
        "tv:mystery" to "Misterio",
        "tv:news" to "Noticias",
        "tv:reality" to "Reality",
        "tv:sci-fi-fantasy" to "Ciencia ficción y fantasía",
        "tv:soap" to "Telenovela",
        "tv:talk" to "Talk Show",
        "tv:war-politics" to "Guerra y política",
        "tv:western" to "Western",
    )

    // region HTTP

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
                .header("Referer", "$baseUrl/")
                .build()
            chain.proceed(request)
        }
        .build()

    private fun get(url: String): String {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} for $url")
            return response.body?.string().orEmpty()
        }
    }

    @Volatile
    private var cachedNonce: String? = null
    @Volatile
    private var nonceTimestamp: Long = 0L

    private fun getNonce(): String {
        val now = System.currentTimeMillis()
        val existing = cachedNonce
        if (existing != null && (now - nonceTimestamp) < 15 * 60 * 1000L) {
            return existing
        }
        return synchronized(this) {
            val current = cachedNonce
            if (current != null && (now - nonceTimestamp) < 15 * 60 * 1000L) {
                return@synchronized current
            }
            try {
                val homeHtml = get("$baseUrl/")
                val nonce = Regex("""["']nonce["']\s*:\s*["']([a-f0-9]+)["']""")
                    .find(homeHtml)?.groupValues?.get(1)
                    ?: "8094ec4022"
                cachedNonce = nonce
                nonceTimestamp = System.currentTimeMillis()
                nonce
            } catch (e: Exception) {
                Log.w(TAG, "Failed to scrape nonce: ${e.message}")
                cachedNonce ?: "8094ec4022"
            }
        }
    }

    // endregion

    // region Catalog (TMDb-shaped JSON, no HTML scraping)

    private fun JSONObject.toShow(): Show? {
        val id = optInt("id", -1).takeIf { it != -1 } ?: return null
        val isTv = optString("media_type") == "tv" || (!has("title") && has("name"))
        val poster = optString("poster_path").takeIf { it.isNotEmpty() }?.let { "$TMDB_IMAGE_BASE$it" }
        val backdrop = optString("backdrop_path").takeIf { it.isNotEmpty() }?.let { "$TMDB_IMAGE_BASE$it" }
        val overview = optString("overview").takeIf { it.isNotEmpty() }

        return if (isTv) {
            TvShow(
                id = id.toString(),
                title = optString("name").ifEmpty { optString("title") },
                overview = overview,
                poster = poster,
                banner = backdrop,
            )
        } else {
            Movie(
                id = id.toString(),
                title = optString("title").ifEmpty { optString("name") },
                overview = overview,
                poster = poster,
                banner = backdrop,
            )
        }
    }

    private fun humanizeKey(key: String) = key.replace('_', ' ').replaceFirstChar { it.uppercase() }

    override suspend fun getHome(): List<Category> {
        val json = JSONObject(get("$baseUrl/wp-json/cinehax/v1/home-data"))
        val categories = mutableListOf<Category>()
        json.keys().forEach { key ->
            val items = json.optJSONArray(key) ?: return@forEach
            val shows = (0 until items.length()).mapNotNull { items.optJSONObject(it)?.toShow() }
            if (shows.isNotEmpty()) {
                categories.add(Category(name = HOME_CATEGORY_LABELS[key] ?: humanizeKey(key), list = shows))
            }
        }
        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            return GENRES.map { (id, genreName) -> Genre(id = id, name = genreName) }
        }
        if (page > 1) return emptyList()

        val nonce = getNonce()
        val url = "$baseUrl/wp-admin/admin-ajax.php?action=tmdb_live_search&query=${URLEncoder.encode(query, "UTF-8")}&nonce=$nonce"
        val json = JSONObject(get(url))
        if (!json.optBoolean("success")) return emptyList()
        val results = json.optJSONArray("data") ?: return emptyList()
        return (0 until results.length()).mapNotNull { results.optJSONObject(it)?.toShow() }
    }

    private fun fetchExplorePage(type: String, page: Int, genre: String = "", sort: String = "popular"): List<Show> {
        val nonce = getNonce()
        val url = "$baseUrl/wp-admin/admin-ajax.php?action=load_explore_data" +
                "&page=$page&type=$type&genre=$genre&network=&language=&sort=$sort&q=&nonce=$nonce"
        val json = JSONObject(get(url))
        if (!json.optBoolean("success")) return emptyList()
        val html = json.optJSONObject("data")?.optString("html").orEmpty()
        if (html.isBlank()) return emptyList()

        val seen = mutableSetOf<String>()
        return Jsoup.parse(html).select("a[href]").mapNotNull { a ->
            val href = a.attr("href")
            val id = Regex("""id=(\d+)""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            val title = a.selectFirst("h3")?.text()?.takeIf { it.isNotBlank() }
                ?: a.selectFirst("h2")?.text()?.takeIf { it.isNotBlank() }
                ?: a.selectFirst("h4")?.text()?.takeIf { it.isNotBlank() }
                ?: a.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: "CineHax $id"
            val poster = a.selectFirst("img")?.attr("src")
            val isTv = type == "tv" || href.contains("tipo=serie") || href.contains("type=tv") || href.contains("season=")
            if (isTv) {
                TvShow(id = id, title = title, poster = poster, banner = poster)
            } else {
                Movie(id = id, title = title, poster = poster, banner = poster)
            }
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> =
        fetchExplorePage("movie", page).filterIsInstance<Movie>()

    override suspend fun getTvShows(page: Int): List<TvShow> =
        fetchExplorePage("tv", page).filterIsInstance<TvShow>()

    override suspend fun getGenre(id: String, page: Int): Genre {
        val type = id.substringBefore(":")
        val slug = id.substringAfter(":")
        val shows = fetchExplorePage(type, page, genre = slug)
        return Genre(id = id, name = GENRES.toMap()[id] ?: slug, shows = shows)
    }

    // endregion

    // region Detail
    //
    // cinehax.com used to server-render a schema.org JSON-LD block with the title/overview/
    // rating/genres, but that block disappeared from detail pages (verified against multiple
    // ids with cache-busting - not a stale-cache fluke). The same data is still on the page in
    // other forms though: the title/backdrop are query params on the "data-url" embed link (the
    // same one getServers() already reads), the overview sits right after an <h3>Descripción</h3>,
    // and rating/genres/release-year are plain DOM text. [parseWatchPageFromDom] reads those
    // directly. [parseWatchPage] still tries the JSON-LD first in case they bring it back.

    private data class WatchPageMeta(
        val title: String,
        val overview: String?,
        val poster: String?,
        val backdrop: String?,
        val rating: Double?,
        val released: String?,
        val genres: List<Genre>,
        val trailer: String?,
    )

    private fun parseWatchPage(html: String): WatchPageMeta {
        return parseWatchPageFromJsonLd(html)?.takeIf { it.title.isNotBlank() }
            ?: parseWatchPageFromDom(html)
    }

    private fun parseWatchPageFromJsonLd(html: String): WatchPageMeta? {
        val json = Regex("""<script type="application/ld\+json">\s*(\{.*?\})\s*</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null

        val genres = json.optJSONArray("genre")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotEmpty) }
        }.orEmpty().map { Genre(id = it.lowercase(), name = it) }

        return WatchPageMeta(
            title = json.optString("name"),
            overview = json.optString("description").takeIf { it.isNotEmpty() },
            poster = extractPoster(html),
            backdrop = json.optString("image").takeIf { it.isNotEmpty() },
            rating = json.optJSONObject("aggregateRating")?.optDouble("ratingValue")?.takeIf { !it.isNaN() },
            released = json.optString("datePublished").takeIf { it.isNotEmpty() }
                ?: json.optString("startDate").takeIf { it.isNotEmpty() },
            genres = genres,
            trailer = extractTrailer(html),
        )
    }

    private fun parseWatchPageFromDom(html: String): WatchPageMeta {
        val overview = Regex("""Descripción</h3>\s*<p[^>]*>([^<]*)</p>""")
            .find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

        val rating = Regex("""font-bold text-white flex items-center gap-0\.5">([\d.]+)</div>""")
            .find(html)?.groupValues?.get(1)?.toDoubleOrNull()

        val released = Regex("""\bde (\d{4})</span>""").find(html)?.groupValues?.get(1)

        val genres = Regex("""px-2 py-1 bg-white/5 border border-gray-700 rounded-full text-white text-xs">\s*([^<]+?)\s*</div>""")
            .findAll(html)
            .map { org.jsoup.parser.Parser.unescapeEntities(it.groupValues[1].trim(), false) }
            .filter { it.isNotEmpty() }
            .map { Genre(id = it.lowercase(), name = it) }
            .toList()

        val poster = extractPoster(html)
        val trailer = extractTrailer(html)
        val title = Jsoup.parse(html).selectFirst("h1")?.text()?.takeIf { it.isNotBlank() } ?: "CineHax"

        return WatchPageMeta(
            title = title,
            overview = overview,
            poster = poster,
            backdrop = poster,
            rating = rating,
            released = released,
            genres = genres,
            trailer = trailer,
        )
    }

    private fun extractPoster(html: String) =
        Regex("""https://image\.tmdb\.org/t/p/w500/[^"]+""").find(html)?.value

    private fun extractTrailer(html: String): String? {
        val embedUrl = Regex("""id="iframe-trailer" src="([^"]+)"""").find(html)?.groupValues?.get(1)
        return embedUrl?.substringAfterLast("/")?.let { "https://www.youtube.com/watch?v=$it" }
    }

    override suspend fun getMovie(id: String): Movie {
        val tmdbMovie = try {
            val movieId = id.toIntOrNull()
            if (movieId != null) {
                TMDb3.Movies.details(
                    movieId = movieId,
                    appendToResponse = listOf(
                        TMDb3.Params.AppendToResponse.Movie.CREDITS,
                        TMDb3.Params.AppendToResponse.Movie.RECOMMENDATIONS,
                        TMDb3.Params.AppendToResponse.Movie.VIDEOS,
                        TMDb3.Params.AppendToResponse.Movie.EXTERNAL_IDS,
                    ),
                    language = "es-ES"
                )
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "TMDb3 details failed for movie $id: ${e.message}")
            null
        }

        if (tmdbMovie != null) {
            return Movie(
                id = tmdbMovie.id.toString(),
                title = tmdbMovie.title,
                overview = tmdbMovie.overview,
                released = tmdbMovie.releaseDate,
                runtime = tmdbMovie.runtime,
                trailer = tmdbMovie.videos?.results
                    ?.sortedBy { it.publishedAt ?: "" }
                    ?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = tmdbMovie.voteAverage.toDouble(),
                poster = tmdbMovie.posterPath?.original ?: tmdbMovie.posterPath?.w500,
                banner = tmdbMovie.backdropPath?.original,
                imdbId = tmdbMovie.externalIds?.imdbId,
                genres = tmdbMovie.genres.map { Genre(it.id.toString(), it.name) },
                cast = tmdbMovie.credits?.cast?.map { People(it.id.toString(), it.name, it.profilePath?.w500) } ?: emptyList(),
                directors = tmdbMovie.credits?.crew?.filter { it.job == "Director" }?.map { People(it.id.toString(), it.name, it.profilePath?.w500) } ?: emptyList(),
                recommendations = tmdbMovie.recommendations?.results?.mapNotNull { (it as? TMDb3.Movie)?.let { m ->
                    Movie(id = m.id.toString(), title = m.title, poster = m.posterPath?.w500, banner = m.backdropPath?.w500)
                } } ?: emptyList(),
            )
        }

        val meta = parseWatchPage(get("$baseUrl/ver/?tipo=pelicula&id=$id"))
        return Movie(
            id = id,
            title = meta.title,
            overview = meta.overview,
            released = meta.released,
            rating = meta.rating,
            poster = meta.poster,
            banner = meta.backdrop,
            trailer = meta.trailer,
            genres = meta.genres,
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val tmdbTv = try {
            val tvId = id.toIntOrNull()
            if (tvId != null) {
                TMDb3.TvSeries.details(
                    seriesId = tvId,
                    appendToResponse = listOf(
                        TMDb3.Params.AppendToResponse.Tv.CREDITS,
                        TMDb3.Params.AppendToResponse.Tv.RECOMMENDATIONS,
                        TMDb3.Params.AppendToResponse.Tv.VIDEOS,
                        TMDb3.Params.AppendToResponse.Tv.EXTERNAL_IDS,
                    ),
                    language = "es-ES"
                )
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "TMDb3 details failed for tv $id: ${e.message}")
            null
        }

        if (tmdbTv != null) {
            val seasons = tmdbTv.seasons?.filter { (it.seasonNumber ?: 0) > 0 }?.map { s ->
                Season(
                    id = "$id-${s.seasonNumber}",
                    number = s.seasonNumber ?: 1,
                    title = s.name ?: "Temporada ${s.seasonNumber}",
                    poster = s.posterPath?.w500,
                )
            } ?: listOf(Season(id = "$id-1", number = 1, title = "Temporada 1"))

            return TvShow(
                id = tmdbTv.id.toString(),
                title = tmdbTv.name,
                overview = tmdbTv.overview,
                released = tmdbTv.firstAirDate,
                rating = tmdbTv.voteAverage.toDouble(),
                poster = tmdbTv.posterPath?.original ?: tmdbTv.posterPath?.w500,
                banner = tmdbTv.backdropPath?.original,
                trailer = tmdbTv.videos?.results
                    ?.sortedBy { it.publishedAt ?: "" }
                    ?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                genres = tmdbTv.genres.map { Genre(it.id.toString(), it.name) },
                seasons = seasons,
                cast = tmdbTv.credits?.cast?.map { People(it.id.toString(), it.name, it.profilePath?.w500) } ?: emptyList(),
            )
        }

        val html = get("$baseUrl/ver/?tipo=serie&id=$id&season=1&episode=1")
        val meta = parseWatchPage(html)
        val seasonNumbers = Regex("""season=(\d+)""").findAll(html)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .distinct()
            .sorted()
            .toList()
            .ifEmpty { listOf(1) }

        return TvShow(
            id = id,
            title = meta.title,
            overview = meta.overview,
            released = meta.released,
            rating = meta.rating,
            poster = meta.poster,
            banner = meta.backdrop,
            trailer = meta.trailer,
            genres = meta.genres,
            seasons = seasonNumbers.map { number -> Season(id = "$id-$number", number = number, title = "Temporada $number") },
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val tvId = seasonId.substringBeforeLast("-")
        val seasonNumber = seasonId.substringAfterLast("-").toIntOrNull() ?: 1

        val tmdbEpisodes = try {
            val seriesIdInt = tvId.toIntOrNull()
            if (seriesIdInt != null) {
                TMDb3.TvSeasons.details(
                    seriesId = seriesIdInt,
                    seasonNumber = seasonNumber,
                    language = "es-ES"
                ).episodes?.map { ep ->
                    Episode(
                        id = "$tvId|$seasonNumber|${ep.episodeNumber}",
                        number = ep.episodeNumber ?: 1,
                        title = ep.name ?: "Episodio ${ep.episodeNumber}",
                        overview = ep.overview,
                        poster = ep.stillPath?.w500,
                        released = ep.airDate,
                    )
                }
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "TMDb3 season details failed for $seasonId: ${e.message}")
            null
        }

        if (!tmdbEpisodes.isNullOrEmpty()) {
            return tmdbEpisodes
        }

        val html = try { get("$baseUrl/ver/?tipo=serie&id=$tvId&season=$seasonNumber&episode=1") } catch (e: Exception) { "" }
        val doc = Jsoup.parse(html)
        return doc.select("a[href*=\"id=$tvId\"][href*=\"season=$seasonNumber\"][href*=\"episode=\"]:has(img)")
            .mapNotNull { a ->
                val href = a.attr("href")
                val number = Regex("""episode=(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                val title = a.selectFirst("h4")?.text()?.takeIf { it.isNotBlank() }
                    ?: a.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                    ?: "Episodio $number"
                Episode(
                    id = "$tvId|$seasonNumber|$number",
                    number = number,
                    title = title,
                    poster = a.selectFirst("img")?.attr("src"),
                )
            }
            .distinctBy { it.number }
            .sortedBy { it.number }
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val person = try {
            val personId = id.toIntOrNull()
            if (personId != null) {
                TMDb3.People.details(
                    personId = personId,
                    appendToResponse = if (page > 1) null else listOf(TMDb3.Params.AppendToResponse.Person.COMBINED_CREDITS),
                    language = "es-ES"
                )
            } else null
        } catch (e: Exception) {
            null
        }

        return People(
            id = id,
            name = person?.name ?: "Persona $id",
            image = person?.profilePath?.w500,
        )
    }

    // endregion

    // region Playback

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val watchUrl = when (videoType) {
            is Video.Type.Movie -> "$baseUrl/ver/?tipo=pelicula&id=$id"
            is Video.Type.Episode -> {
                val parts = id.split("|")
                val tvId = parts.getOrNull(0) ?: return emptyList()
                val season = parts.getOrNull(1) ?: "1"
                val episode = parts.getOrNull(2) ?: "1"
                "$baseUrl/ver/?tipo=serie&id=$tvId&season=$season&episode=$episode"
            }
            else -> return emptyList()
        }

        val html = try { get(watchUrl) } catch (e: Exception) { "" }
        val doc = Jsoup.parse(html)
        val embedPageUrls = doc.select("[data-url*=$UNLIMPLAY_HOST], [data-src*=$UNLIMPLAY_HOST], iframe[src*=$UNLIMPLAY_HOST]")
            .flatMap { listOf(it.attr("data-url"), it.attr("data-src"), it.attr("src")) }
            .filter { it.contains(UNLIMPLAY_HOST) }
            .map { if (it.startsWith("//")) "https:$it" else it }
            .distinct()
            .toMutableList()

        if (embedPageUrls.isEmpty()) {
            val fallbackUrl = when (videoType) {
                is Video.Type.Movie -> "https://$UNLIMPLAY_HOST/f/embed/movie/$id"
                is Video.Type.Episode -> {
                    val parts = id.split("|")
                    val tvId = parts.getOrNull(0) ?: id
                    val season = parts.getOrNull(1) ?: "1"
                    val episode = parts.getOrNull(2) ?: "1"
                    "https://$UNLIMPLAY_HOST/f/embed/serie/$tvId/$season/$episode"
                }
                else -> null
            }
            if (fallbackUrl != null) embedPageUrls.add(fallbackUrl)
        }

        val servers = mutableListOf<Video.Server>()
        for (embedPageUrl in embedPageUrls) {
            try {
                servers.addAll(resolveUnlimplayServers(embedPageUrl))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve embed page $embedPageUrl: ${e.message}")
            }
        }
        return servers
    }

    private fun resolveUnlimplayServers(embedPageUrl: String): List<Video.Server> {
        val embedHtml = get(embedPageUrl)
        val embedsJson = Regex("""(?:const\s+EMBEDS\s*=\s*|finalizePlayer\s*\(\s*)(\{.*?\})\s*(?:;|\))""", RegexOption.DOT_MATCHES_ALL)
            .find(embedHtml)?.groupValues?.get(1)
            ?: return emptyList()
        val embeds = JSONObject(embedsJson)

        val languages = embeds.keys().asSequence().toList()
        val orderedLanguages = LANGUAGE_ORDER.filter { it in languages } + (languages - LANGUAGE_ORDER.toSet())

        return orderedLanguages.flatMap { lang ->
            val langServers = embeds.optJSONObject(lang) ?: return@flatMap emptyList()
            val labels = langServers.keys().asSequence().toList()
            val orderedLabels = PRIORITY_SERVERS.filter { it in labels } + (labels - PRIORITY_SERVERS.toSet())

            orderedLabels.mapNotNull { serverLabel ->
                val url = langServers.optString(serverLabel).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val langLabel = LANGUAGE_LABELS[lang] ?: lang.replaceFirstChar { it.uppercase() }
                Video.Server(
                    id = url,
                    name = "${serverLabel.replaceFirstChar { it.uppercase() }} · $langLabel",
                    src = url,
                )
            }
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        if (server.src.contains(REMUX_HOST) || server.src.endsWith(".mp4")) {
            return Video(source = server.src, type = MimeTypes.VIDEO_MP4)
        }
        if (server.src.contains(".m3u8")) {
            return Video(
                source = server.src,
                type = MimeTypes.APPLICATION_M3U8,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    "Referer" to "https://$UNLIMPLAY_HOST/",
                    "Origin" to "https://$UNLIMPLAY_HOST"
                )
            )
        }
        return Extractor.extract(server.src, server)
    }

    // endregion
}

