package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.providers.Provider.Companion.ProviderSupport

/**
 * Registry que auto-descubre providers anotados con @StreamflixProvider.
 * Usa reflexión de Java ligera (clazz.getAnnotation y field INSTANCE) 
 * sin necesidad de dependencias adicionales de kotlin-reflect.
 */
object ProviderRegistry {

    private const val TAG = "ProviderRegistry"
    
    /**
     * Mapa de todos los providers descubiertos automáticamente.
     * Se inicializa de forma lazy la primera vez que se accede.
     */
    val providers: Map<Provider, ProviderSupport> by lazy {
        discoverProviders()
    }

    /**
     * Descubre todos los providers anotados en el paquete de providers.
     */
    private fun discoverProviders(): Map<Provider, ProviderSupport> {
        val result = mutableMapOf<Provider, ProviderSupport>()
        
        try {
            val providerClassNames = getProviderClassNames()
            
            for (className in providerClassNames) {
                try {
                    val clazz = Class.forName("com.streamflixreborn.streamflix.providers.$className")
                    
                    val annotation = clazz.getAnnotation(StreamflixProvider::class.java)
                    if (annotation == null) {
                        Log.d(TAG, "Skipping $className: no @StreamflixProvider annotation")
                        continue
                    }
                    
                    val instanceField = try { clazz.getField("INSTANCE") } catch (e: Exception) { null }
                    val instance = instanceField?.get(null) as? Provider
                    
                    if (instance == null) {
                        Log.w(TAG, "Skipping $className: not a valid Provider object instance")
                        continue
                    }
                    
                    result[instance] = ProviderSupport(
                        movies = annotation.movies,
                        tvShows = annotation.tvShows
                    )
                    
                    Log.i(TAG, "Registered provider: ${annotation.name} (${annotation.language})")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading provider $className: ${e.message}")
                }
            }
            
            Log.i(TAG, "Total providers registered: ${result.size}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error discovering providers: ${e.message}", e)
        }
        
        return result
    }

    private fun getProviderClassNames(): List<String> {
        return listOf(
            "SflixProvider",
            "SerienStreamProvider",
            "StreamingCommunityProvider",
            "AnimeWorldProvider",
            "MkissaProvider",
            "AniWorldProvider",
            "RidomoviesProvider",
            "AnikotoProvider",
            "WiflixProvider",
            "MStreamProvider",
            "FrenchAnimeProvider",
            "FilmPalastProvider",
            "PoseidonHD2Provider",
            "CuevanaEuProvider",
            "LatanimeProvider",
            "DoramasflixProvider",
            "CineCalidadProvider",
            "SeriesFlixProvider",
            "FlixLatamProvider",
            "LaCartoonsProvider",
            "AnimefenixProvider",
            "AnimeFlvProvider",
            "AnimeAv1Provider",
            "AnimeOnlineNinjaProvider",
            "SoloLatinoProvider",
            "Cine24hProvider",
            "PelisplustoProvider",
            "PelisflixHdProvider",
            "CableVisionHDProvider",
            "Altadefinizione01Provider",
            "GuardaFlixProvider",
            "CB01Provider",
            "AnimeUnityProvider",
            "AnimeSaturnProvider",
            "FrenchStreamProvider",
            "GuardaSerieProvider",
            "EinschaltenProvider",
            "HDFilmeProvider",
            "MEGAKinoProvider",
            "FilmyOnlineCcProvider",
            "ZeriunProvider",
            "TvporinternetHDProvider",
            "FrembedProvider",
            "KidrazProvider",
            "FrenchMangaProvider",
            "IptvOrgProvider",
            "IptvSpainProvider",
            "TvLibrefutbolProvider",
            "PelotaLibreTvHdProvider",
            "PlutoTvMxProvider",
            "PlutoTvArProvider",
            "PlutoTvDeProvider",
            "PlutoTvEsProvider",
            "PlutoTvFrProvider",
            "PlutoTvItProvider",
            "PlutoTvUsProvider",
            "CineCityProvider",
            "VavooProvider",
            "AfterDarkProvider",
            "AnimeBumProvider",
            "AnyMovieProvider",
            "HiAnimeProvider",
            "LamovieProvider",
            "OtakufrProvider",
            "StreamingItaProvider",
            "SuperStreamProvider",
            "TlnovelasProvider",
            "TmdbProvider",
            "UnJourUnFilmProvider",
            "SuperFavoritesProvider"
        )
    }

    // ============================================
    // HELPERS
    // ============================================
    
    fun supportsMovies(provider: Provider): Boolean {
        val support = providers[provider] ?: ProviderSupport(movies = true, tvShows = true)
        return support.movies
    }

    fun supportsTvShows(provider: Provider): Boolean {
        val support = providers[provider] ?: ProviderSupport(movies = true, tvShows = true)
        return support.tvShows
    }

    fun findByName(name: String): Provider? {
        return providers.keys.find { it.name == name }
    }
}
