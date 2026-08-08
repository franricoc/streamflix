package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.utils.SuperFavoritesDeduplicator
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

@StreamflixProvider(
    name = "Super Favoritos",
    language = "all",
    movies = true,
    tvShows = true
)
object SuperFavoritesProvider : BaseProvider() {

    override val name: String = "Super Favoritos"
    override val baseUrl: String = "https://superfavorites.local"
    override val logo: String = "ic_super_favorites"
    override val language: String = "all"

    private fun getFavoriteProviders(): List<Provider> {
        val favNames = UserPreferences.favoriteProviders
        if (favNames.isEmpty()) return emptyList()
        return Provider.providers.keys.filter {
            it.name != this.name && favNames.contains(it.name)
        }
    }

    override suspend fun getHome(): List<Category> = supervisorScope {
        val providers = getFavoriteProviders()
        if (providers.isEmpty()) {
            return@supervisorScope listOf(
                Category(
                    name = "Super Favoritos",
                    list = emptyList()
                )
            )
        }

        val deferreds = providers.map { provider ->
            async {
                try {
                    provider.getHome().onEach { cat ->
                        cat.list.forEach { item ->
                            if (item is Movie) item.providerName = provider.name
                            if (item is TvShow) item.providerName = provider.name
                        }
                    }
                } catch (e: Exception) {
                    emptyList<Category>()
                }
            }
        }

        val results = deferreds.awaitAll()
        val categoryMap = LinkedHashMap<String, MutableList<Show>>()

        for (categoryList in results) {
            for (category in categoryList) {
                val catName = category.name.trim()
                val targetList = categoryMap.getOrPut(catName) { mutableListOf() }
                for (item in category.list) {
                    if (item is Show) {
                        targetList.add(item)
                    }
                }
            }
        }

        val finalCategories = mutableListOf<Category>()
        for ((catName, rawShows) in categoryMap) {
            val movies = rawShows.filterIsInstance<Movie>()
            val tvShows = rawShows.filterIsInstance<TvShow>()

            val dedupMovies = SuperFavoritesDeduplicator.deduplicateMovies(movies)
            val dedupTvShows = SuperFavoritesDeduplicator.deduplicateTvShows(tvShows)

            val mergedShows = (dedupMovies + dedupTvShows)
            if (mergedShows.isNotEmpty()) {
                finalCategories.add(Category(catName, mergedShows))
            }
        }

        finalCategories
    }

    override suspend fun getMovies(page: Int): List<Movie> = supervisorScope {
        val providers = getFavoriteProviders().filter { ProviderRegistry.supportsMovies(it) }
        if (providers.isEmpty()) return@supervisorScope emptyList()

        val deferreds = providers.map { provider ->
            async {
                try {
                    provider.getMovies(page).onEach { it.providerName = provider.name }
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }

        val allMovies = deferreds.awaitAll().flatten()
        SuperFavoritesDeduplicator.deduplicateMovies(allMovies)
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = supervisorScope {
        val providers = getFavoriteProviders().filter { ProviderRegistry.supportsTvShows(it) }
        if (providers.isEmpty()) return@supervisorScope emptyList()

        val deferreds = providers.map { provider ->
            async {
                try {
                    provider.getTvShows(page).onEach { it.providerName = provider.name }
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }

        val allTvShows = deferreds.awaitAll().flatten()
        SuperFavoritesDeduplicator.deduplicateTvShows(allTvShows)
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> = supervisorScope {
        val providers = getFavoriteProviders()
        if (providers.isEmpty()) return@supervisorScope emptyList()

        val deferreds = providers.map { provider ->
            async {
                try {
                    provider.search(query, page).onEach { item ->
                        if (item is Movie) item.providerName = provider.name
                        if (item is TvShow) item.providerName = provider.name
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }

        val allItems = deferreds.awaitAll().flatten()
        val movies = allItems.filterIsInstance<Movie>()
        val tvShows = allItems.filterIsInstance<TvShow>()
        val others = allItems.filter { it !is Movie && it !is TvShow }

        val dedupMovies = SuperFavoritesDeduplicator.deduplicateMovies(movies)
        val dedupTvShows = SuperFavoritesDeduplicator.deduplicateTvShows(tvShows)

        dedupMovies + dedupTvShows + others
    }

    override suspend fun getMovie(id: String): Movie = supervisorScope {
        val sources = SuperFavoritesDeduplicator.decodeCompositeId(id)
        if (sources.isEmpty()) {
            return@supervisorScope Movie(id = id, title = "No disponible", providerName = name)
        }

        val deferreds = sources.map { (providerName, origId) ->
            async {
                val provider = ProviderRegistry.findByName(providerName) ?: return@async null
                try {
                    provider.getMovie(origId).also { it.providerName = providerName }
                } catch (e: Exception) {
                    null
                }
            }
        }

        val movies = deferreds.awaitAll().filterNotNull()
        if (movies.isEmpty()) {
            return@supervisorScope Movie(id = id, title = "Sin información").apply { providerName = name }
        }

        val primary = movies.maxByOrNull {
            (if (!it.poster.isNullOrEmpty()) 2 else 0) +
            (if (!it.banner.isNullOrEmpty()) 2 else 0) +
            (if (!it.overview.isNullOrEmpty()) 1 else 0)
        } ?: movies.first()

        val mergedGenres = movies.flatMap { it.genres }.distinctBy { it.name }
        val mergedCast = movies.flatMap { it.cast }.distinctBy { it.name }
        val mergedRecommendations = movies.flatMap { it.recommendations }.distinctBy {
            (it as? Movie)?.title ?: (it as? TvShow)?.title ?: ""
        }

        primary.copy(
            id = id,
            genres = mergedGenres,
            cast = mergedCast,
            recommendations = mergedRecommendations
        ).apply { providerName = name }
    }

    override suspend fun getTvShow(id: String): TvShow = supervisorScope {
        val sources = SuperFavoritesDeduplicator.decodeCompositeId(id)
        if (sources.isEmpty()) {
            return@supervisorScope TvShow(id = id, title = "No disponible").apply { providerName = name }
        }

        val deferreds = sources.map { (providerName, origId) ->
            async {
                val provider = ProviderRegistry.findByName(providerName) ?: return@async null
                try {
                    provider.getTvShow(origId).also { it.providerName = providerName }
                } catch (e: Exception) {
                    null
                }
            }
        }

        val tvShows = deferreds.awaitAll().filterNotNull()
        if (tvShows.isEmpty()) {
            return@supervisorScope TvShow(id = id, title = "Sin información").apply { providerName = name }
        }

        val primary = tvShows.maxByOrNull {
            (if (!it.poster.isNullOrEmpty()) 2 else 0) +
            (if (!it.banner.isNullOrEmpty()) 2 else 0) +
            (if (!it.overview.isNullOrEmpty()) 1 else 0) +
            it.seasons.size
        } ?: tvShows.first()

        val seasonMap = LinkedHashMap<Int, MutableMap<String, String>>()
        for (tv in tvShows) {
            val pName = tv.providerName ?: continue
            for (season in tv.seasons) {
                val seasonNum = season.number
                val sourcesForSeason = seasonMap.getOrPut(seasonNum) { mutableMapOf() }
                if (!sourcesForSeason.containsKey(pName)) {
                    sourcesForSeason[pName] = season.id
                }
            }
        }

        val compositeSeasons = seasonMap.entries.sortedBy { it.key }.map { (seasonNum, seasonSources) ->
            val compositeSeasonId = SuperFavoritesDeduplicator.encodeSeasonId(seasonNum, seasonSources)
            Season(
                id = compositeSeasonId,
                number = seasonNum,
                title = "Temporada $seasonNum"
            )
        }

        val mergedGenres = tvShows.flatMap { it.genres }.distinctBy { it.name }
        val mergedCast = tvShows.flatMap { it.cast }.distinctBy { it.name }
        val mergedRecommendations = tvShows.flatMap { it.recommendations }.distinctBy {
            (it as? Movie)?.title ?: (it as? TvShow)?.title ?: ""
        }

        primary.copy(
            id = id,
            seasons = compositeSeasons,
            genres = mergedGenres,
            cast = mergedCast,
            recommendations = mergedRecommendations
        ).apply { providerName = name }
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = supervisorScope {
        val (seasonNumber, sources) = SuperFavoritesDeduplicator.decodeSeasonId(seasonId)
        if (sources.isEmpty()) return@supervisorScope emptyList()

        val deferreds = sources.map { (providerName, origSeasonId) ->
            async {
                val provider = ProviderRegistry.findByName(providerName) ?: return@async emptyList()
                try {
                    provider.getEpisodesBySeason(origSeasonId).onEach { it.id = "$providerName::${it.id}" }
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }

        val allEpisodes = deferreds.awaitAll().flatten()
        val episodeMap = LinkedHashMap<Int, MutableList<Pair<String, Episode>>>()

        for (ep in allEpisodes) {
            val parts = ep.id.split("::", limit = 2)
            if (parts.size == 2) {
                val pName = parts[0]
                val origEpId = parts[1]
                val list = episodeMap.getOrPut(ep.number) { mutableListOf() }
                list.add(pName to ep.copy(id = origEpId))
            }
        }

        val resultEpisodes = mutableListOf<Episode>()
        for ((epNum, epList) in episodeMap.entries.sortedBy { it.key }) {
            val primaryPair = epList.maxByOrNull { (_, ep) ->
                (if (!ep.poster.isNullOrEmpty()) 2 else 0) +
                (if (!ep.overview.isNullOrEmpty()) 1 else 0)
            } ?: epList.first()

            val primaryEp = primaryPair.second
            val sourcesForEp = mutableMapOf<String, String>()
            epList.forEach { (pName, ep) ->
                if (!sourcesForEp.containsKey(pName)) {
                    sourcesForEp[pName] = ep.id
                }
            }

            val compositeEpId = SuperFavoritesDeduplicator.encodeCompositeId("episode", sourcesForEp)
            resultEpisodes.add(
                primaryEp.copy(
                    id = compositeEpId
                )
            )
        }

        resultEpisodes
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = supervisorScope {
        val targetId = when (videoType) {
            is Video.Type.Movie -> videoType.id.ifEmpty { id }
            is Video.Type.Episode -> videoType.id.ifEmpty { id }
        }

        val sources = SuperFavoritesDeduplicator.decodeCompositeId(targetId)
        if (sources.isEmpty()) return@supervisorScope emptyList()

        val deferreds = sources.map { (providerName, origId) ->
            async {
                val provider = ProviderRegistry.findByName(providerName) ?: return@async emptyList()
                try {
                    val subVideoType = when (videoType) {
                        is Video.Type.Movie -> videoType.copy(id = origId)
                        is Video.Type.Episode -> videoType.copy(id = origId)
                    }
                    val servers = provider.getServers(origId, subVideoType)
                    servers.map { server ->
                        val encodedId = SuperFavoritesDeduplicator.encodeServerId(providerName, server.id)
                        server.copy(
                            id = encodedId,
                            name = "[$providerName] ${server.name}"
                        )
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }

        deferreds.awaitAll().flatten()
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val (providerName, origServerId) = SuperFavoritesDeduplicator.decodeServerId(server.id)
        if (providerName.isEmpty()) {
            throw IllegalArgumentException("Provider no válido para servidor: ${server.name}")
        }

        val provider = ProviderRegistry.findByName(providerName)
            ?: throw IllegalStateException("Proveedor $providerName no encontrado")

        val targetServer = server.copy(
            id = origServerId,
            name = server.name.substringAfter("] ").ifEmpty { server.name }
        )

        return provider.getVideo(targetServer)
    }

    override suspend fun getGenre(id: String, page: Int): Genre = supervisorScope {
        val providers = getFavoriteProviders()
        if (providers.isEmpty()) return@supervisorScope Genre(id = id, name = id, shows = emptyList())

        val deferreds = providers.map { provider ->
            async {
                try {
                    provider.getGenre(id, page).shows.onEach { item ->
                        if (item is Movie) item.providerName = provider.name
                        if (item is TvShow) item.providerName = provider.name
                    }
                } catch (e: Exception) {
                    emptyList<Show>()
                }
            }
        }

        val allShows = deferreds.awaitAll().flatten()
        val movies = allShows.filterIsInstance<Movie>()
        val tvShows = allShows.filterIsInstance<TvShow>()

        val dedupMovies = SuperFavoritesDeduplicator.deduplicateMovies(movies)
        val dedupTvShows = SuperFavoritesDeduplicator.deduplicateTvShows(tvShows)

        Genre(
            id = id,
            name = id.replace("-", " ").replaceFirstChar { it.uppercase() },
            shows = dedupMovies + dedupTvShows
        )
    }

    override suspend fun getPeople(id: String, page: Int): People {
        throw UnsupportedOperationException("$name no soporta getPeople")
    }
}
