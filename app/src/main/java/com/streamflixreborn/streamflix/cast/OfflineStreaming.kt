package com.streamflixreborn.streamflix.cast

import android.net.ConnectivityManager
import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.offline.database.OfflineVideoEntity
import java.net.URL

/**
 * Pure helpers used by [LocalMediaServer] to identify and rewrite HLS playlists and to parse
 * HTTP byte ranges. Kept out of the server class so the serving logic stays readable.
 */

internal const val OFFLINE_STREAM_PATH = "/offline_stream/"
internal const val SEG_PARAM = "seg"
internal const val HLS_MIME = "application/x-mpegURL"
internal const val SNIFF_BYTES = 16 * 1024
internal const val MAX_PLAYLIST_BYTES = 4 * 1024 * 1024
internal const val HLS_PLAYLIST_PADDING = 256
internal const val READ_BUFFER_SIZE = 16 * 1024
internal val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "ts", "m4v", "webm", "mov", "m2ts")
internal val SEG_MIME_SUFFIXES =
    listOf(
        "m3u8" to HLS_MIME,
        ".m2ts" to "video/mp2t",
        ".mts" to "video/mp2t",
        ".ts" to "video/mp2t",
        ".aac" to "audio/mpeg",
        ".m4a" to "audio/mp4",
        ".mp3" to "audio/mpeg",
        ".vtt" to "text/vtt",
        ".mpd" to "application/dash+xml",
        ".key" to "application/octet-stream",
        ".bin" to "application/octet-stream",
        ".mp4" to "video/mp4",
        ".m4v" to "video/mp4",
    )
internal val TAG_URI_REGEX = Regex("URI=\"([^\"]*)\"")

/**
 * Encodes an arbitrary download id / segment URL for safe use inside a URL path or query.
 *
 * Download ids can be full URIs themselves (e.g. `superfav://episode?SoloLatino=https%3A...`),
 * full of characters (`?`, `=`, `&`, `%`, `/`) that would be mangled by URL parsers and by
 * NanoHTTPD's single percent-decoding of the request URI. Base64 URL-safe output only contains
 * `[A-Za-z0-9_-]`, so it survives every hop untouched.
 */
internal fun encodeIdForUrl(id: String): String =
    Base64.encodeToString(id.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

internal fun decodeIdFromUrl(token: String): String? =
    try {
        String(
            Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
            Charsets.UTF_8,
        )
    } catch (e: Exception) {
        Log.w("LocalMediaServer", "Invalid base64 token: $token", e)
        null
    }

// ---------------------------------------------------------------------------
// Local IP resolution for the phone-as-server feature.
//
// Must prefer the address of the network that actually has connectivity (usually
// WiFi): phones with mobile data active enumerate rmnet interfaces before wlan0,
// and advertising a carrier-NAT address would make the TV unable to reach the
// phone's LocalMediaServer.
// ---------------------------------------------------------------------------

private const val IP_PREFERENCE_LAN = 0
private const val IP_PREFERENCE_OTHER = 1
private const val IP_PREFERENCE_CELLULAR = 2
private const val IP_PREFERENCE_VPN = 3

internal fun resolveLocalIpv4(appContext: android.content.Context): String? =
    resolveActiveNetworkIp(appContext) ?: resolvePreferredInterfaceIp()

private fun resolveActiveNetworkIp(appContext: android.content.Context): String? =
    try {
        val cm =
            appContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager
        val linkAddresses = cm?.getLinkProperties(cm?.activeNetwork)?.linkAddresses
        val usable = linkAddresses?.firstOrNull { ip -> isUsableIpv4(ip.address) }
        usable?.address?.hostAddress
    } catch (e: Exception) {
        Log.w("LocalMediaServer", "Error resolving active network address", e)
        null
    }

private val isUsableIpv4: (java.net.InetAddress) -> Boolean = { ip ->
    !ip.isLoopbackAddress &&
        ip is java.net.Inet4Address &&
        !ip.hostAddress.isNullOrEmpty() &&
        !ip.hostAddress.startsWith("127.")
}

private val usableAddressesOf: (java.net.NetworkInterface) -> List<Pair<String, java.net.InetAddress>> =
    { iface ->
        iface
            .inetAddresses
            .toList()
            .filter { isUsableIpv4(it) }
            .map { iface.name to it }
    }

private fun resolvePreferredInterfaceIp(): String? =
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
        val candidate =
            interfaces
                .toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { iface -> usableAddressesOf(iface) }
                .minByOrNull { (name, _) -> interfacePreference(name) }
        candidate?.second?.hostAddress
    } catch (e: Exception) {
        Log.w("LocalMediaServer", "Error getting network interfaces IP", e)
        null
    }

private val interfacePreference: (String) -> Int = { name ->
    when {
        name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("en") -> IP_PREFERENCE_LAN
        name.startsWith("tun") || name.startsWith("ppp") -> IP_PREFERENCE_VPN
        name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("radio") -> IP_PREFERENCE_CELLULAR
        else -> IP_PREFERENCE_OTHER
    }
}

internal fun isHls(entity: OfflineVideoEntity): Boolean {
    val mime = entity.mimeType.orEmpty().lowercase()
    val url = entity.url.orEmpty().lowercase()
    return mime.contains("mpegurl") || url.contains(".m3u8")
}

internal fun guessSegMime(url: String): String {
    val lower = url.lowercase()
    for ((suffix, mime) in SEG_MIME_SUFFIXES) {
        if (lower.contains(suffix)) return mime
    }
    return "video/mp4"
}

/**
 * Rewrites every segment/key/rendition reference of an HLS playlist so it points back at this
 * phone: `?seg=<original absolute url>`. Relative references are resolved against [baseUrl].
 */
internal fun rewriteHlsPlaylist(
    playlist: String,
    baseUrl: String,
    proxyPrefix: String,
): String {
    val out = StringBuilder(playlist.length + HLS_PLAYLIST_PADDING)
    for (line in playlist.lineSequence()) {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("#") -> {
                // Rewrite URI="..." references inside tags (segments, keys, maps, renditions).
                val rewritten =
                    TAG_URI_REGEX.replace(line) { m ->
                        val ref = m.groupValues[1]
                        if (ref.startsWith("data:")) {
                            m.value
                        } else {
                            "URI=\"${proxyPrefix}${encodeIdForUrl(resolveUrl(baseUrl, ref) ?: ref)}\""
                        }
                    }
                out.append(rewritten).append('\n')
            }

            trimmed.isEmpty() -> out.append('\n')

            else -> {
                // A bare segment/playlist URI line (absolute or relative).
                val resolved = resolveUrl(baseUrl, trimmed)
                if (resolved != null) {
                    out.append(proxyPrefix).append(encodeIdForUrl(resolved)).append('\n')
                } else {
                    out.append(line).append('\n')
                }
            }
        }
    }
    return out.toString()
}

internal fun resolveUrl(
    base: String,
    ref: String,
): String? {
    if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
    return try {
        URL(URL(base), ref).toString()
    } catch (e: Exception) {
        Log.w("LocalMediaServer", "Could not resolve $ref against $base", e)
        null
    }
}

internal fun parseByteRange(
    rangeHeader: String?,
    fileLength: Long,
): Pair<Long, Long>? {
    if (rangeHeader == null || fileLength <= 0 || !rangeHeader.startsWith("bytes=")) {
        return null
    }
    val parts = rangeHeader.substring("bytes=".length).split("-")
    val start = parts.getOrNull(0)?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0L
    val end = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: (fileLength - 1)
    val valid = start <= end && start < fileLength
    return if (valid) start to minOf(end, fileLength - 1) else null
}
