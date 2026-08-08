package com.streamflixreborn.streamflix.providers

import android.content.Context
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.WebViewResolver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

@StreamflixProvider(
    name = "Tlnovelas",
    language = "es",
    movies = false,
    tvShows = true
)
object TlnovelasProvider : BaseProvider(){

    override val name = "Tlnovelas.net"
    override val baseUrl = "https://ww2.tlnovelas.net"
    override val language = "es"
    override val logo = "https://1.bp.blogspot.com/-VpuckQKjS0k/YPm5vzitbiI/AAAAAAAABng/__UPhA3tbisK3mLvcb_Om86gw7voLijeACLcBGAsYHQ/s273/images.jpeg" // Usa una imagen conocida para logo, o un default

    private var webViewResolver: WebViewResolver? = null
    private val providerMutex = Mutex()

    private fun getResolver(): WebViewResolver {
        return webViewResolver ?: WebViewResolver(StreamFlixApp.instance).also {
            webViewResolver = it
        }
    }

    fun init(context: Context) {
        webViewResolver = WebViewResolver(context)
    }

    private suspend fun getDocument(url: String): Document {
        try {
            val client = NetworkClient.default.newBuilder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(url).header("Referer", baseUrl).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string() ?: ""
                if (!html.contains("cf-browser-verification") && !html.contains("Just a moment...")) {
                    return Jsoup.parse(html).apply { setBaseUri(baseUrl) }
                }
            }
        } catch (_: Exception) {}

        val html = getResolver().get(url)
        return Jsoup.parse(html).apply { setBaseUri(baseUrl) }
    }

    override suspend fun getHome(): List<Category> = providerMutex.withLock {
        val categories = mutableListOf<Category>()
        try {
            // 1. Últimos Capítulos Publicados
            val doc = getDocument(baseUrl)
            val latestItems = doc.select(".vk-new-poster .vk-poster a").mapNotNull { el ->
                val href = el.attr("href")
                if (!href.contains("/ver/")) return@mapNotNull null
                val title = el.selectFirst(".vk-info p")?.text() ?: el.attr("title")
                val poster = getRealImageUrl(el.selectFirst("img"))
                val id = href.removePrefix("$baseUrl/ver/").removeSuffix("/")
                
                // Mapear capitulo a TvShow virtual para mostrar en el feed de novedades
                TvShow(id = "novela/" + id.substringBefore("-capitulo"), title = title, poster = poster)
            }
            if (latestItems.isNotEmpty()) {
                categories.add(Category(name = "Últimos Capítulos", list = latestItems))
            }

            // 2. Series Recientes
            val recentDoc = getDocument("$baseUrl/gratis/telenovelas/")
            val recentItems = recentDoc.select(".vk-poster a").mapNotNull { el ->
                val href = el.attr("href")
                if (!href.contains("/novela/")) return@mapNotNull null
                val title = el.selectFirst(".vk-info p")?.text() ?: el.selectFirst("img")?.attr("alt") ?: ""
                val poster = getRealImageUrl(el.selectFirst("img"))
                val id = href.removePrefix("$baseUrl/novela/").removeSuffix("/")
                TvShow(id = id, title = title, poster = poster)
            }
            if (recentItems.isNotEmpty()) {
                categories.add(Category(name = "Series Recientes", list = recentItems))
            }
        } catch (_: Exception) {}
        return@withLock categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        return try {
            val doc = getDocument("$baseUrl/buscar/?q=${URLEncoder.encode(query, "UTF-8")}")
            doc.select(".vk-poster a").mapNotNull { el ->
                val href = el.attr("href")
                val title = el.selectFirst(".vk-info p")?.text() ?: el.attr("title")
                val poster = getRealImageUrl(el.selectFirst("img"))
                if (href.contains("/novela/")) {
                    val id = href.removePrefix("$baseUrl/novela/").removeSuffix("/")
                    TvShow(id = id, title = title, poster = poster)
                } else null
            }.distinctBy { if (it is TvShow) it.id else "" }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            val url = if (page == 1) "$baseUrl/gratis/telenovelas/" else "$baseUrl/gratis/telenovelas/page/$page/"
            val doc = getDocument(url)
            doc.select(".vk-poster a").mapNotNull { el ->
                val href = el.attr("href")
                if (!href.contains("/novela/")) return@mapNotNull null
                val title = el.selectFirst(".vk-info p")?.text() ?: el.selectFirst("img")?.attr("alt") ?: ""
                val poster = getRealImageUrl(el.selectFirst("img"))
                val id = href.removePrefix("$baseUrl/novela/").removeSuffix("/")
                TvShow(id = id, title = title, poster = poster)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getMovie(id: String): Movie = throw Exception("Movies not supported by this provider")

    override suspend fun getTvShow(id: String): TvShow {
        val cleanId = id.removePrefix("novela/").removeSuffix("/")
        val doc = getDocument("$baseUrl/novela/$cleanId/")
        val title = doc.selectFirst("h1.card-title, h1")?.text() ?: cleanId.replace("-", " ")
        val poster = getRealImageUrl(doc.selectFirst(".vk-imagen img"))
        val overview = doc.selectFirst(".vk-description, p.text-justify")?.text()

        // Creamos una temporada virtual única para telenovelas
        val season = Season(id = "$cleanId/1", number = 1, title = "Temporada 1")
        return TvShow(
            id = cleanId,
            title = title,
            overview = overview,
            poster = poster,
            seasons = listOf(season)
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val showId = seasonId.substringBefore("/")
        val doc = getDocument("$baseUrl/novela/$showId/")
        val showPoster = getRealImageUrl(doc.selectFirst(".vk-imagen img"))

        return doc.select("a.list-link").mapNotNull { el ->
            val href = el.attr("href")
            val rawTitle = el.text().trim()
            val id = href.removePrefix("$baseUrl/ver/").removeSuffix("/")

            // Extracción del número del capítulo desde el título (ej. "Capítulo 129")
            val number = Regex("""(?i)cap[íi]tulo\s*(\d+)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            Episode(
                id = id,
                number = number,
                title = rawTitle,
                poster = showPoster
            )
        }.sortedBy { it.number }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val doc = getDocument("$baseUrl/ver/$id/")
        val script = doc.select("script").firstOrNull { it.data().contains("var e = [];") || it.data().contains("var e=[]") }?.data()
            ?: return emptyList()

        val regex = """e\[\d+\]\s*=\s*'([^']+)'""".toRegex()
        val rawLinks = regex.findAll(script).map { it.groupValues[1] }.toList()

        return rawLinks.mapNotNull { link ->
            val finalUrl = when {
                link.startsWith("http") -> link
                link.endsWith("|1") -> "https://hqq.to/e/${link.replace("|1", "")}"
                link.endsWith("|2") -> "https://dood.yt/e/${link.replace("|2", "")}"
                link.endsWith("|3") -> "https://player.ojearanime.com/e/${link.replace("|3", "")}"
                link.endsWith("|4") -> "https://player.vernovelastv.net/e/${link.replace("|4", "")}"
                else -> link
            }

            try {
                val host = finalUrl.substringAfter("://").substringBefore("/")
                val name = host.replace("www.", "").substringBefore(".")
                Video.Server(
                    id = finalUrl,
                    name = name.replaceFirstChar { it.uppercase() },
                    src = finalUrl
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.src, server)
    }

    override suspend fun getGenre(id: String, page: Int): Genre = throw Exception("Genres not implemented")
    override suspend fun getPeople(id: String, page: Int): People = throw Exception("People not implemented")

    private fun getRealImageUrl(img: org.jsoup.nodes.Element?): String {
        if (img == null) return ""
        val attrs = listOf("data-src", "data-lazy-src", "data-lazyloaded", "src")
        for (attr in attrs) {
            if (img.hasAttr(attr)) {
                val valStr = img.attr("abs:$attr")
                if (valStr.isNotEmpty() && !valStr.startsWith("data:image")) {
                    return valStr
                }
            }
        }
        val src = img.attr("abs:src")
        return if (src.startsWith("data:image")) "" else src
    }
}
