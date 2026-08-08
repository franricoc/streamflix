package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

// ============================================
// EXTENSIONES PARA ELEMENT (Jsoup)
// ============================================

/**
 * Obtiene el primer atributo no vacío de la lista proporcionada, ignorando placeholders en base64.
 */
fun Element.firstAttr(vararg attrNames: String): String? {
    for (attr in attrNames) {
        val value = attr(attr).trim()
        if (value.isNotBlank() && !value.startsWith("data:image")) return value
    }
    return null
}

/**
 * Convierte un elemento HTML en un Show (Movie o TvShow).
 */
fun Element.toShow(
    baseUrl: String,
    moviePatterns: List<String> = listOf("/pelicula/", "/peliculas/", "/movie/", "/film/", "/films/", "/ver-pelicula/"),
    tvShowPatterns: List<String> = listOf("/serie/", "/series/", "/tv/", "/show/", "/anime/", "/doramas/", "/ver-serie/")
): Show? {
    val link = selectFirst("a")?.attr("href") ?: return null
    val title = selectFirst(".in_title, .title, .Title, h3, h2, h1")?.text()?.trim() ?: return null
    val poster = selectFirst("img")?.firstAttr("data-src", "src", "data-original", "data-lazy-src")
        ?: firstAttr("data-src", "src", "data-original", "data-lazy-src")

    val absolutePoster = poster?.toAbsoluteUrl(baseUrl)
    val seltText = selectFirst(".selt")?.text() ?: ""

    return when {
        moviePatterns.any { link.contains(it, ignoreCase = true) } || seltText.contains("Película", ignoreCase = true) ->
            Movie(id = link, title = title, poster = absolutePoster)
        tvShowPatterns.any { link.contains(it, ignoreCase = true) } || seltText.contains("Series", ignoreCase = true) ->
            TvShow(id = link, title = title, poster = absolutePoster)
        else -> Movie(id = link, title = title, poster = absolutePoster)
    }
}

fun Element.toMovie(baseUrl: String): Movie? {
    val show = toShow(baseUrl)
    return if (show is Movie) show else null
}

fun Element.toTvShow(baseUrl: String): TvShow? {
    val show = toShow(baseUrl)
    return if (show is TvShow) show else null
}

// ============================================
// EXTENSIONES PARA ELEMENTS
// ============================================

fun Elements.toShows(
    baseUrl: String,
    moviePatterns: List<String> = listOf("/pelicula/", "/peliculas/", "/movie/", "/film/", "/films/", "/ver-pelicula/"),
    tvShowPatterns: List<String> = listOf("/serie/", "/series/", "/tv/", "/show/", "/anime/", "/doramas/", "/ver-serie/")
): List<Show> {
    return mapNotNull { it.toShow(baseUrl, moviePatterns, tvShowPatterns) }
}

fun Elements.toMovies(baseUrl: String): List<Movie> {
    return mapNotNull { it.toMovie(baseUrl) }
}

fun Elements.toTvShows(baseUrl: String): List<TvShow> {
    return mapNotNull { it.toTvShow(baseUrl) }
}

// ============================================
// EXTENSIONES PARA DOCUMENT
// ============================================

fun Document.moviesFrom(selector: String, baseUrl: String): List<Movie> {
    return select(selector).toMovies(baseUrl)
}

fun Document.tvShowsFrom(selector: String, baseUrl: String): List<TvShow> {
    return select(selector).toTvShows(baseUrl)
}

fun Document.showsFrom(
    selector: String,
    baseUrl: String,
    moviePatterns: List<String> = listOf("/pelicula/", "/peliculas/", "/movie/", "/ver-pelicula/"),
    tvShowPatterns: List<String> = listOf("/serie/", "/series/", "/tv/", "/anime/", "/ver-serie/")
): List<Show> {
    return select(selector).toShows(baseUrl, moviePatterns, tvShowPatterns)
}

// ============================================
// EXTENSIONES PARA STRINGS (URLs)
// ============================================

fun String.toAbsoluteUrl(baseUrl: String): String {
    return when {
        startsWith("http://") || startsWith("https://") -> this
        startsWith("//") -> "https:$this"
        startsWith("/") -> "$baseUrl$this"
        else -> "$baseUrl/$this"
    }
}

fun String.extractId(): String {
    return this.trimEnd('/').substringAfterLast("/")
}

fun String.cleanId(): String {
    return this
        .removeSuffix("-temporada")
        .removeSuffix("-season")
        .substringBefore("-temporada")
        .substringBefore("-season")
}

// ============================================
// EXTENSIONES PARA CATEGORY
// ============================================

fun Category.Companion.featured(shows: List<Show>): Category {
    return Category(Category.FEATURED, shows)
}

// ============================================
// EXTENSIONES PARA VIDEO.SERVER
// ============================================

fun Element.toServer(): Video.Server? {
    val src = attr("src").ifBlank { attr("data-src") }
    if (src.isBlank()) return null
    val name = attr("title").ifBlank { attr("data-server-name").ifBlank { "Servidor" } }
    return Video.Server(id = src, name = name)
}

fun Elements.toServers(): List<Video.Server> {
    return mapNotNull { it.toServer() }
}

// ============================================
// EXTENSIONES PARA GENRE
// ============================================

fun Elements.toGenres(): List<Genre> {
    return mapNotNull { element ->
        val href = element.attr("href")
        val name = element.text()
        if (href.isNotBlank() && name.isNotBlank()) {
            Genre(id = href, name = name)
        } else null
    }
}

// ============================================
// EXTENSIONES PARA PEOPLE
// ============================================

fun Elements.toPeople(): List<People> {
    return mapNotNull { element ->
        val href = element.attr("href")
        val name = element.text()
        if (href.isNotBlank() && name.isNotBlank()) {
            People(id = href, name = name)
        } else null
    }
}
