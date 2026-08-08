package com.streamflixreborn.streamflix.providers

import android.util.Base64
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.ExtractorChain
import com.streamflixreborn.streamflix.models.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@StreamflixProvider(
    name = "AnimeFLV",
    language = "es",
    movies = false,
    tvShows = true
)
object AnimeFlvProvider : BaseProvider() {

    override val name = "AnimeFLV"
    override val baseUrl = "https://animeflv.or.at"
    override val language = "es"
    override val logo = "https://animeflv.or.at/wp-content/themes/animeflv/assets/img/animeflv.png"

    private fun decodeBase64IfNeeded(str: String): String {
        if (str.startsWith("http://") || str.startsWith("https://")) return str
        return try {
            val decodedBytes = Base64.decode(str, Base64.DEFAULT)
            val decodedStr = String(decodedBytes, Charsets.UTF_8)
            if (decodedStr.startsWith("http")) decodedStr else str
        } catch (e: Exception) {
            str
        }
    }

    override suspend fun getHome(): List<Category> = safeFetchList("getHome") {
        coroutineScope {
            val doc = baseService.getPage(baseUrl)
            val categories = mutableListOf<Category>()

            // 1. Últimos episodios en portada
            val episodes = doc.select(".List-Episodes .Episode, div.Episode").mapNotNull { element ->
                val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val title = element.selectFirst(".Title, h2, h3")?.text()?.trim() ?: return@mapNotNull null
                val poster = element.selectFirst("img")?.firstAttr("src", "data-src")?.toAbsoluteUrl(baseUrl)
                TvShow(
                    id = link,
                    title = title,
                    poster = poster
                )
            }

            if (episodes.isNotEmpty()) {
                categories.add(Category("Últimos Episodios", episodes))
            }

            // 2. Animes en emisión (Sidebar)
            val airingShows = doc.select("li.sidebar-cat-item a").mapNotNull { element ->
                val link = element.attr("href")
                val title = element.selectFirst(".sidebar-cat-name")?.text()?.trim()
                    ?: element.attr("title").trim()
                if (link.isNotBlank() && title.isNotBlank()) {
                    TvShow(
                        id = link,
                        title = title
                    )
                } else null
            }

            if (airingShows.isNotEmpty()) {
                categories.add(Category("Animes en Emisión", airingShows))
            }

            categories
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        return safeFetchList("search") {
            val url = if (page > 1) "$baseUrl/page/$page/?s=$query" else "$baseUrl/?s=$query"
            val doc = baseService.getPage(url)
            doc.select(".List-Episodes .Episode, div.Episode, article, .item").mapNotNull { element ->
                val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val title = element.selectFirst(".Title, h2, h3, .title")?.text()?.trim() ?: return@mapNotNull null
                val poster = element.selectFirst("img")?.firstAttr("src", "data-src")?.toAbsoluteUrl(baseUrl)
                TvShow(
                    id = link,
                    title = title,
                    poster = poster
                )
            }
        }
    }

    override suspend fun getMovies(page: Int) = emptyList<Movie>()
    override suspend fun getMovie(id: String) = Movie(id, "No soportado")

    override suspend fun getTvShows(page: Int): List<TvShow> = safeFetchList("getTvShows") {
        val url = if (page > 1) "$baseUrl/anime/page/$page/" else "$baseUrl/anime/"
        val doc = baseService.getPage(url)
        doc.select(".List-Episodes .Episode, div.Episode, article, .item, li.sidebar-cat-item").mapNotNull { element ->
            val link = element.selectFirst("a")?.attr("href") ?: element.attr("href")
            if (link.isBlank()) return@mapNotNull null
            val title = element.selectFirst(".Title, .sidebar-cat-name, h2, h3")?.text()?.trim()
                ?: element.attr("title").trim()
            if (title.isBlank()) return@mapNotNull null
            val poster = element.selectFirst("img")?.firstAttr("src", "data-src")?.toAbsoluteUrl(baseUrl)
            TvShow(
                id = link,
                title = title,
                poster = poster
            )
        }
    }

    override suspend fun getTvShow(id: String): TvShow = safeFetch("getTvShow", TvShow(id, "Error")) {
        val url = if (id.startsWith("http")) id else "$baseUrl/anime/$id"
        val doc = baseService.getPage(url)
        val title = doc.selectFirst("h1.anime-title, h1.Title, h1, .entry-title")?.text()?.trim() ?: ""
        val poster = doc.selectFirst(".poster-image")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.firstAttr("content")
            ?: doc.selectFirst(".Image img, div.thumb img, figure img")?.firstAttr("src", "data-src")
        val overview = doc.selectFirst(".anime-synopsis p")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.let { it.attr("content").ifBlank { it.text() } }
        val rating = doc.selectFirst(".rating-score")?.text()?.toDoubleOrNull()
        val genres = doc.select(".genre-tag").toGenres()

        // 1. Extraer episodios desde script JSON (class=animeflv-episodes-data)
        val episodes = mutableListOf<Episode>()
        val jsonScript = doc.selectFirst("script.animeflv-episodes-data")?.data()
        if (!jsonScript.isNullOrBlank()) {
            try {
                val jsonElement = Json.parseToJsonElement(jsonScript)
                val jsonArray = jsonElement.jsonArray
                for (item in jsonArray) {
                    val obj = item.jsonObject
                    val permalink = obj["permalink"]?.jsonPrimitive?.content ?: continue
                    val num = obj["number"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    episodes.add(
                        Episode(
                            id = permalink,
                            number = num,
                            title = "Episodio $num",
                            poster = poster?.toAbsoluteUrl(baseUrl)
                        )
                    )
                }
            } catch (e: Exception) { /* Fallback */ }
        }

        // Fallback: si no hay script JSON, buscar en el DOM
        if (episodes.isEmpty()) {
            val episodeElements = doc.select(".ListEpisodes li, .List-Episodes .Episode, ul.episodios li, div.Episode")
            episodeElements.mapNotNullTo(episodes) { ep ->
                val link = ep.selectFirst("a")?.attr("href") ?: return@mapNotNullTo null
                val epTitle = ep.selectFirst(".Title, h2, h3, a")?.text()?.trim() ?: "Episodio"
                val epNum = epTitle.substringAfterLast(" ").toIntOrNull() ?: 0
                val epPoster = ep.selectFirst("img")?.firstAttr("src", "data-src")
                Episode(
                    id = link,
                    number = epNum,
                    title = epTitle,
                    poster = epPoster?.toAbsoluteUrl(baseUrl)
                )
            }
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
        val show = getTvShow(seasonId)
        show.seasons.firstOrNull()?.episodes ?: emptyList()
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = safeFetchList("getServers") {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        val doc = baseService.getPage(url)

        val servers = mutableListOf<Video.Server>()

        // 1. Extraer botones en .server-frames o div.button-container
        doc.select(".server-frames .button-container, div.button-container").forEach { container ->
            val button = container.selectFirst("button[data-src], a[data-src], [data-src]") ?: return@forEach
            val rawDataSrc = button.attr("data-src")
            val realUrl = decodeBase64IfNeeded(rawDataSrc)

            if (realUrl.isNotBlank() && !realUrl.contains("youtube.com")) {
                val serverName = container.selectFirst(".tooltip-text")?.text()?.trim()
                    ?: button.text().trim().ifBlank { "Servidor" }
                servers.add(Video.Server(id = realUrl, name = serverName))
            }
        }

        // 2. Extraer iframeHolder data-default-src
        doc.select("#iframeHolder[data-default-src]").firstOrNull()?.let { holder ->
            val rawSrc = holder.attr("data-default-src")
            val realUrl = decodeBase64IfNeeded(rawSrc)
            if (realUrl.isNotBlank() && !realUrl.contains("youtube.com")) {
                servers.add(Video.Server(id = realUrl, name = "Opción Principal"))
            }
        }

        // 3. Extraer enlaces de descarga en .download-link table
        doc.select(".download-link table tbody tr").forEach { row ->
            val link = row.selectFirst("a[href]")?.attr("href") ?: return@forEach
            val serverName = row.selectFirst("td")?.text()?.trim() ?: "Descarga"
            if (link.isNotBlank()) {
                servers.add(Video.Server(id = link, name = "$serverName (Descarga)"))
            }
        }

        servers.distinctBy { it.id }
    }

    override suspend fun getVideo(server: Video.Server): Video = ExtractorChain.extract(server.id, server)

    override suspend fun getGenre(id: String, page: Int) = Genre(id = id, name = "", shows = emptyList())
    override suspend fun getPeople(id: String, page: Int) = People(id = id, name = "", filmography = emptyList())
}