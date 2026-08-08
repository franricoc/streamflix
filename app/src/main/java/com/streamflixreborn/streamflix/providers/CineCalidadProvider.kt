package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.ExtractorChain
import com.streamflixreborn.streamflix.models.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@StreamflixProvider(
    name = "CineCalidad",
    language = "es",
    movies = true,
    tvShows = true
)
object CineCalidadProvider : BaseProvider() {

    override val name = "CineCalidad"
    override val baseUrl = "https://www.cinecalidad.am"
    override val language = "es"
    override val logo = "https://www.cinecalidad.am/wp-content/themes/Cinecalidad/assets/img/logo.svg?v=1aaaaaa.0"

    override suspend fun getHome(): List<Category> = safeFetchList("getHome") {
        coroutineScope {
            val doc = baseService.getPage(baseUrl)
            val categories = mutableListOf<Category>()

            val latestShows = doc.showsFrom("article.item", baseUrl)
            if (latestShows.isNotEmpty()) {
                categories.add(Category("Últimos Estrenos", latestShows))
            }

            categories
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        return safeFetchList("search") {
            val url = if (page > 1) "$baseUrl/page/$page/?s=$query" else "$baseUrl/?s=$query"
            baseService.getPage(url).showsFrom("article.item", baseUrl)
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> = safeFetchList("getMovies") {
        val url = if (page > 1) "$baseUrl/page/$page/" else baseUrl
        baseService.getPage(url).showsFrom("article.item", baseUrl).filterIsInstance<Movie>()
    }

    override suspend fun getMovie(id: String): Movie = safeFetch("getMovie", Movie(id, "Error")) {
        val doc = baseService.getPage(id)
        val title = doc.selectFirst(".single_left h1, #single h1, h1, .entry-title")?.text()?.trim() ?: ""
        val poster = doc.selectFirst("div.single_left table img, #single table img, .poster img, .single-poster img, article img")?.firstAttr("data-src", "src")
            ?: doc.selectFirst("meta[property=og:image]")?.firstAttr("content")
        val overview = doc.selectFirst("td[style*=\"text-align:justify\"] p, .single_left td p, .entry-content p, .synopsis p")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }
        val rating = doc.selectFirst("td b, .rating b, .rating")?.text()?.toDoubleOrNull()
        val genres = doc.select("a[href*=/genero-de-la-pelicula/]").toGenres()
        val cast = doc.select("a[href*=/reparto/]").toPeople()

        Movie(
            id = id,
            title = title,
            poster = poster?.toAbsoluteUrl(baseUrl),
            overview = overview,
            rating = rating,
            genres = genres,
            cast = cast
        )
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = safeFetchList("getTvShows") {
        val url = if (page > 1) "$baseUrl/ver-serie/page/$page/" else "$baseUrl/ver-serie/"
        baseService.getPage(url).showsFrom("article.item", baseUrl).filterIsInstance<TvShow>()
    }

    override suspend fun getTvShow(id: String): TvShow = safeFetch("getTvShow", TvShow(id, "Error")) {
        val doc = baseService.getPage(id)
        val title = doc.selectFirst(".single_left h1, #single h1, h1, .entry-title")?.text()?.trim() ?: ""
        val poster = doc.selectFirst("div.single_left table img, #single table img, .poster img, .single-poster img, article img")?.firstAttr("data-src", "src")
            ?: doc.selectFirst("meta[property=og:image]")?.firstAttr("content")
        val overview = doc.selectFirst("td[style*=\"text-align:justify\"] p, .single_left td p, .entry-content p, .synopsis p")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }
        val rating = doc.selectFirst("td b, .rating b, .rating")?.text()?.toDoubleOrNull()
        val genres = doc.select("a[href*=/genero-de-la-pelicula/]").toGenres()

        val episodeElements = doc.select("ul.episodios li, #episodes li, .episodios li, .les-content a")
        val episodes = episodeElements.mapNotNull { ep ->
            val link = ep.selectFirst("a")?.attr("href") ?: ep.attr("href")
            if (link.isBlank()) return@mapNotNull null
            val epTitle = ep.selectFirst(".episodiotitle a, .ep-title, h3, a")?.text()?.trim() ?: "Episodio"
            val epNumText = ep.selectFirst(".numerando, .ep-num")?.text()
            val epNum = epNumText?.substringAfter("x")?.toIntOrNull() ?: 0
            val epPoster = ep.selectFirst("img")?.firstAttr("data-src", "src")
            Episode(
                id = link,
                number = epNum,
                title = epTitle,
                poster = epPoster?.toAbsoluteUrl(baseUrl)
            )
        }

        val seasons = if (episodes.isNotEmpty()) {
            listOf(Season(id = id, number = 1, title = "Episodios", episodes = episodes))
        } else emptyList()

        TvShow(
            id = id,
            title = title,
            poster = poster?.toAbsoluteUrl(baseUrl),
            overview = overview,
            rating = rating,
            genres = genres,
            seasons = seasons
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = safeFetchList("getEpisodes") {
        val tvShow = getTvShow(seasonId)
        tvShow.seasons.firstOrNull()?.episodes ?: emptyList()
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = safeFetchList("getServers") {
        val doc = baseService.getPage(id)
        doc.select("#playeroptionsul li[data-option]:not([class*=\"trailer\"]), .playeroptionsul li[data-option]:not([class*=\"trailer\"]), li.dooplay_player_option[data-option]:not([class*=\"trailer\"])").mapNotNull { elx ->
            val url = elx.attr("data-option")
            if (url.isBlank() || url.contains("youtube.com")) null
            else {
                val serverName = elx.ownText().ifBlank { elx.text().substringBefore("Recomendado").trim() }.ifBlank { "Servidor" }
                Video.Server(id = url, name = serverName)
            }
        }
    }

    override suspend fun getVideo(server: Video.Server): Video = ExtractorChain.extract(server.id, server)

    override suspend fun getGenre(id: String, page: Int) = Genre(id = id, name = "", shows = emptyList())
    override suspend fun getPeople(id: String, page: Int) = People(id = id, name = "", filmography = emptyList())
}