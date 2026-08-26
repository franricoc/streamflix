package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CineHaxProviderTest {

    @Test
    fun `explore page parses movie items with new ver URL`() {
        val html = """
            <div style="opacity:1; transform:none;">
                <a class="group cursor-pointer relative block h-full" href="/ver/?tipo=pelicula&#038;id=969681">
                    <div class="relative overflow-hidden rounded-xl">
                        <div class="aspect-[2/3] relative">
                            <img alt="Spider-Man: Un nuevo día" src="https://image.tmdb.org/t/p/w500/fyaOQR7bgacK0BQNgEBDeIHdKhE.png" />
                        </div>
                    </div>
                    <div class="mt-3 px-1">
                        <h3 class="text-white font-medium text-sm md:text-base truncate">Spider-Man: Un nuevo día</h3>
                        <p class="text-gray-400 text-xs mt-0.5">2026</p>
                    </div>
                </a>
            </div>
        """.trimIndent()

        val items = Jsoup.parse(html).select("a[href*=/ver/], a[href*=/watch/]").mapNotNull { a ->
            val href = a.attr("href")
            val id = Regex("""id=(\d+)""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = a.selectFirst("h3")?.text()?.takeIf { it.isNotBlank() }
                ?: a.selectFirst("img")?.attr("alt").orEmpty()
            val poster = a.selectFirst("img")?.attr("src")
            val isTv = href.contains("tipo=serie") || href.contains("season=")
            if (isTv) {
                TvShow(id = id, title = title, poster = poster, banner = poster)
            } else {
                Movie(id = id, title = title, poster = poster, banner = poster)
            }
        }

        assertEquals(1, items.size)
        val movie = items[0] as Movie
        assertEquals("969681", movie.id)
        assertEquals("Spider-Man: Un nuevo día", movie.title)
        assertEquals("https://image.tmdb.org/t/p/w500/fyaOQR7bgacK0BQNgEBDeIHdKhE.png", movie.poster)
    }

    @Test
    fun `explore page parses tv items with new ver URL`() {
        val html = """
            <div style="opacity:1; transform:none;">
                <a class="group cursor-pointer relative block h-full" href="/ver/?tipo=serie&#038;id=108978&#038;season=1&#038;episode=1">
                    <div class="relative overflow-hidden rounded-xl">
                        <div class="aspect-[2/3] relative">
                            <img alt="Reacher" src="https://image.tmdb.org/t/p/w500/6iuVqg86hJPwTWAO1TDWKLxjgn4.jpg" />
                        </div>
                    </div>
                    <div class="mt-3 px-1">
                        <h3 class="text-white font-medium text-sm md:text-base truncate">Reacher</h3>
                        <p class="text-gray-400 text-xs mt-0.5">2022</p>
                    </div>
                </a>
            </div>
        """.trimIndent()

        val items = Jsoup.parse(html).select("a[href*=/ver/], a[href*=/watch/]").mapNotNull { a ->
            val href = a.attr("href")
            val id = Regex("""id=(\d+)""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = a.selectFirst("h3")?.text()?.takeIf { it.isNotBlank() }
                ?: a.selectFirst("img")?.attr("alt").orEmpty()
            val poster = a.selectFirst("img")?.attr("src")
            val isTv = href.contains("tipo=serie") || href.contains("season=")
            if (isTv) {
                TvShow(id = id, title = title, poster = poster, banner = poster)
            } else {
                Movie(id = id, title = title, poster = poster, banner = poster)
            }
        }

        assertEquals(1, items.size)
        assertTrue(items[0] is TvShow)
        val tvShow = items[0] as TvShow
        assertEquals("108978", tvShow.id)
        assertEquals("Reacher", tvShow.title)
    }

    @Test
    fun `episodes parser filters out recommendations with different ids`() {
        val tvId = "108978"
        val seasonNumber = 1
        val html = """
            <div>
                <!-- Real Episode 1 -->
                <a href="/ver/?tipo=serie&amp;id=108978&amp;season=1&amp;episode=1">
                    <img src="https://image.tmdb.org/t/p/w300/ep1.jpg" alt="Bienvenidos a Margrave"/>
                    <h4>Bienvenidos a Margrave</h4>
                </a>
                <!-- Real Episode 2 -->
                <a href="/ver/?tipo=serie&amp;id=108978&amp;season=1&amp;episode=2">
                    <img src="https://image.tmdb.org/t/p/w300/ep2.jpg" alt="Primer baile"/>
                    <h4>Primer baile</h4>
                </a>
                <!-- Recommendation item matching season=1&episode=1 but different id -->
                <a href="/ver/?tipo=serie&amp;id=222766&amp;season=1&amp;episode=1">
                    <img src="https://image.tmdb.org/t/p/w300/rec.jpg" alt="Other Show"/>
                    <h4>Other Show</h4>
                </a>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val episodes = doc.select("a[href*=\"id=$tvId\"][href*=\"season=$seasonNumber\"][href*=\"episode=\"]:has(img)")
            .mapNotNull { a ->
                val href = a.attr("href")
                val number = Regex("""episode=(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                val title = a.selectFirst("h4")?.text()?.takeIf { it.isNotBlank() }
                    ?: a.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                    ?: "Episodio $number"
                com.streamflixreborn.streamflix.models.Episode(
                    id = "$tvId|$seasonNumber|$number",
                    number = number,
                    title = title,
                    poster = a.selectFirst("img")?.attr("src"),
                )
            }
            .distinctBy { it.number }
            .sortedBy { it.number }

        assertEquals(2, episodes.size)
        assertEquals("108978|1|1", episodes[0].id)
        assertEquals(1, episodes[0].number)
        assertEquals("Bienvenidos a Margrave", episodes[0].title)
        assertEquals("https://image.tmdb.org/t/p/w300/ep1.jpg", episodes[0].poster)

        assertEquals("108978|1|2", episodes[1].id)
        assertEquals(2, episodes[1].number)
        assertEquals("Primer baile", episodes[1].title)
    }
}
