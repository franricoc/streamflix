package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ExtractorChain es un dispatcher optimizado que encuentra el extractor correcto
 * para una URL dada usando indexación por HashMap (O(1) en lugar de O(n)).
 */
object ExtractorChain {

    private const val TAG = "ExtractorChain"

    // ============================================
    // ÍNDICE DE EXTRACTORES (HashMap para O(1))
    // ============================================
    
    /**
     * Mapa indexado por dominio limpio (sin protocolo ni www).
     * Ejemplo: "filemoon.sx" -> FilemoonExtractor()
     */
    private val domainIndex: Map<String, Extractor> by lazy {
        buildMap {
            getAllExtractors().forEach { extractor ->
                // Indexar dominio principal
                val mainDomain = cleanDomain(extractor.mainUrl)
                put(mainDomain, extractor)
                
                // Indexar alias
                extractor.aliasUrls.forEach { alias ->
                    put(cleanDomain(alias), extractor)
                }
                
                Log.d(TAG, "Indexed: ${extractor.name} -> $mainDomain (+${extractor.aliasUrls.size} aliases)")
            }
        }
    }

    /**
     * Lista de extractores con dominios rotativos (regex matching).
     */
    private val rotatingExtractors: List<Extractor> by lazy {
        getAllExtractors().filter { it.rotatingDomain.isNotEmpty() }
    }

    // ============================================
    // MÉTODO PRINCIPAL DE EXTRACCIÓN
    // ============================================
    
    /**
     * Extrae el video de una URL encontrando el extractor apropiado.
     * 
     * @param link URL del servidor de video
     * @param server Objeto Video.Server opcional (para fallback por nombre)
     * @return Video con la fuente de reproducción
     * @throws NoExtractorException si no se encuentra un extractor adecuado
     */
    suspend fun extract(link: String, server: Video.Server? = null): Video {
        var finalLink = link

        // PASO 0: Resolver bridges universales (StreamHG/Sync/Cuevana)
        finalLink = resolveUniversalBridge(finalLink)

        val cleanUrl = cleanDomain(finalLink)
        Log.d(TAG, "Looking for extractor: $finalLink (cleaned: $cleanUrl)")

        // PASO 1: Lookup directo en HashMap O(1)
        val directMatch = findDirectMatch(cleanUrl)
        if (directMatch != null) {
            Log.i(TAG, "[MATCH:Direct] ${directMatch.name} -> $finalLink")
            return directMatch.extract(finalLink, server)
        }

        // PASO 2: Match por regex de dominios rotativos
        val rotatingMatch = findRotatingMatch(cleanUrl)
        if (rotatingMatch != null) {
            Log.i(TAG, "[MATCH:Rotating] ${rotatingMatch.name} -> $finalLink")
            return rotatingMatch.extract(finalLink, server)
        }

        // PASO 3: Fallback por nombre del servidor
        val nameMatch = findByNameMatch(server?.name)
        if (nameMatch != null) {
            Log.i(TAG, "[MATCH:Name] ${nameMatch.name} -> $finalLink")
            return nameMatch.extract(finalLink, server)
        }

        // PASO 4: Intentar con la URL completa como prefix match
        val prefixMatch = findPrefixMatch(cleanUrl)
        if (prefixMatch != null) {
            Log.i(TAG, "[MATCH:Prefix] ${prefixMatch.name} -> $finalLink")
            return prefixMatch.extract(finalLink, server)
        }

        Log.e(TAG, "No extractor found for: $finalLink")
        throw NoExtractorException("No se encontró extractor para: $finalLink")
    }

    // ============================================
    // MÉTODOS DE BÚSQUEDA
    // ============================================
    
    private fun findDirectMatch(cleanUrl: String): Extractor? {
        domainIndex[cleanUrl]?.let { return it }
        
        val parts = cleanUrl.split(".")
        if (parts.size > 2) {
            val withoutSubdomain = parts.drop(1).joinToString(".")
            domainIndex[withoutSubdomain]?.let { return it }
        }
        
        return null
    }

    private fun findRotatingMatch(cleanUrl: String): Extractor? {
        return rotatingExtractors.firstOrNull { extractor ->
            extractor.rotatingDomain.any { regex ->
                regex.containsMatchIn(cleanUrl)
            }
        }
    }

    private fun findByNameMatch(serverName: String?): Extractor? {
        if (serverName.isNullOrBlank()) return null
        
        return getAllExtractors().firstOrNull { extractor ->
            serverName.lowercase().contains(extractor.name.lowercase())
        }
    }

    private fun findPrefixMatch(cleanUrl: String): Extractor? {
        return domainIndex.entries.firstOrNull { (domain, _) ->
            cleanUrl.startsWith(domain)
        }?.value
    }

    // ============================================
    // RESOLUCIÓN DE BRIDGES UNIVERSALES
    // ============================================
    
    private suspend fun resolveUniversalBridge(link: String): String {
        if (!link.contains("mysync.mov/stream/")) return link
        
        return try {
            val client = OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
            
            val responseBody = withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(link)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                client.newCall(request).execute().use { it.body?.string() } ?: ""
            }
            
            val redirectUrl = responseBody
                .substringAfter("window.location.replace(\"", "")
                .substringBefore("\"")
                .ifEmpty { 
                    responseBody.substringAfter("window.location.href = \"", "")
                        .substringBefore("\"") 
                }
                .ifEmpty { 
                    responseBody.substringAfter("src=\"", "")
                        .substringBefore("\"") 
                }
            
            if (redirectUrl.isNotEmpty() && redirectUrl.startsWith("http")) {
                Log.d(TAG, "Bridge resolved: $link -> $redirectUrl")
                redirectUrl
            } else {
                link
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bridge resolution error: ${e.message}")
            link
        }
    }

    // ============================================
    // UTILIDADES
    // ============================================
    
    private fun cleanDomain(url: String): String {
        return url.lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .substringBefore("/")
            .substringBefore("?")
    }

    private fun getAllExtractors(): List<Extractor> {
        return Extractor.getAllExtractorsList()
    }
}

class NoExtractorException(message: String) : Exception(message)
