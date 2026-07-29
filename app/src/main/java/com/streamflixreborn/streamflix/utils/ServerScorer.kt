package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.models.Video

object ServerScorer {

    data class ServerMetadata(
        val providerName: String,
        val audioLanguage: String, // e.g. "LAT", "CAST", "SUB", "EN", "IT"
        val quality: String, // e.g. "1080p", "720p", "HD"
        val hostName: String,
        val originalServer: Video.Server
    )

    fun parseMetadata(server: Video.Server, defaultProviderName: String = "", defaultLanguage: String = "es"): ServerMetadata {
        val rawName = server.name.uppercase()

        val audioLanguage = when {
            rawName.contains("LATINO") || rawName.contains("LAT") || rawName.contains("MEX") -> "LAT"
            rawName.contains("CASTELLANO") || rawName.contains("CAST") || rawName.contains("ESP") || rawName.contains("ESPAÑA") -> "CAST"
            rawName.contains("SUB") || rawName.contains("VOSE") || rawName.contains("JAP") || rawName.contains("ENG") || rawName.contains("INGLES") -> "SUB"
            rawName.contains("ITA") || rawName.contains("ITALIANO") -> "IT"
            rawName.contains("FRE") || rawName.contains("FRENCH") -> "FR"
            rawName.contains("GER") || rawName.contains("GERMAN") -> "DE"
            defaultLanguage == "es" -> "LAT"
            else -> defaultLanguage.uppercase()
        }

        val quality = when {
            rawName.contains("4K") || rawName.contains("2160P") -> "4K"
            rawName.contains("1080P") || rawName.contains("FULLHD") || rawName.contains("FHD") -> "1080p"
            rawName.contains("720P") || rawName.contains("HD") -> "720p"
            rawName.contains("480P") || rawName.contains("SD") -> "480p"
            rawName.contains("CAM") || rawName.contains("TS") -> "CAM"
            else -> "HD"
        }

        val cleanHostName = server.name
            .replace(Regex("(?i)\\b(latino|castellano|subtitulado|sub|lat|cast|1080p|720p|480p|hd|4k)\\b"), "")
            .trim(' ', '-', '[', ']', '(', ')', '|')
            .ifEmpty { "Servidor Directo" }

        return ServerMetadata(
            providerName = defaultProviderName,
            audioLanguage = audioLanguage,
            quality = quality,
            hostName = cleanHostName,
            originalServer = server
        )
    }

    fun getAudioScore(audioLang: String, userLanguage: String): Int {
        val priorityList = when (userLanguage.lowercase()) {
            "es" -> listOf("LAT", "CAST", "SUB")
            "it" -> listOf("IT", "SUB", "EN")
            "fr" -> listOf("FR", "SUB", "EN")
            "de" -> listOf("DE", "SUB", "EN")
            else -> listOf("EN", "SUB", "LAT")
        }

        val index = priorityList.indexOf(audioLang.uppercase())
        return if (index != -1) index else 99
    }

    fun getQualityScore(quality: String): Int {
        return when (quality.uppercase()) {
            "4K" -> 1
            "1080P" -> 2
            "720P", "HD" -> 3
            "480P", "SD" -> 4
            "CAM" -> 5
            else -> 3
        }
    }

    fun sortServers(servers: List<Pair<Video.Server, String>>, userLanguage: String): List<Pair<Video.Server, ServerMetadata>> {
        val parsedList = servers.map { (server, providerName) ->
            val meta = parseMetadata(server, providerName, userLanguage)
            Pair(server, meta)
        }

        return parsedList.sortedWith(
            compareBy<Pair<Video.Server, ServerMetadata>> { (_, meta) ->
                getAudioScore(meta.audioLanguage, userLanguage)
            }.thenBy { (_, meta) ->
                getQualityScore(meta.quality)
            }
        )
    }
}
