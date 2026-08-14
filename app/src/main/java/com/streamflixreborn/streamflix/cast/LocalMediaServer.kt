package com.streamflixreborn.streamflix.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import com.streamflixreborn.streamflix.offline.DownloadModule
import com.streamflixreborn.streamflix.offline.database.OfflineDatabase
import com.streamflixreborn.streamflix.offline.database.OfflineVideoEntity
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Turns the phone into a media server for content that was already downloaded on it.
 *
 * The TV never sees the provider URL: it plays `http://<phone>:8888/offline_stream/<id>`,
 * and every byte comes from the phone's download cache (offline, no re-download).
 *
 * Two content shapes are supported:
 *  - Progressive single files (mp4/mkv/ts): served with HTTP byte-range support.
 *  - HLS playlists: served with every segment/key reference rewritten to point back at this
 *    phone (`?seg=<original url>`), so the TV pulls all pieces from the download cache.
 */
@UnstableApi
class LocalMediaServer(
    context: Context,
    port: Int = 8888,
) : NanoHTTPD(port) {
    private val appContext: Context = context.applicationContext

    companion object {
        private const val TAG = "LocalMediaServer"

        @Volatile
        private var instance: LocalMediaServer? = null

        fun getInstance(context: Context): LocalMediaServer =
            instance ?: synchronized(this) {
                instance ?: LocalMediaServer(context).also { instance = it }
            }
    }

    fun startServer(): String? {
        if (!isAlive) {
            try {
                start(SOCKET_READ_TIMEOUT, false)
                Log.d(TAG, "LocalMediaServer started on port $listeningPort")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting LocalMediaServer", e)
                return null
            }
        }
        return getLocalBaseUrl()
    }

    fun stopServer() {
        if (isAlive) {
            stop()
            Log.d(TAG, "LocalMediaServer stopped")
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "Incoming HTTP request: $uri")

        val response =
            if (uri.startsWith(OFFLINE_STREAM_PATH)) {
                val rawToken = uri.removePrefix(OFFLINE_STREAM_PATH).substringBefore("?")
                val downloadId = decodeIdFromUrl(rawToken)
                if (downloadId == null) {
                    NanoHTTPD.newFixedLengthResponse(
                        Response.Status.BAD_REQUEST,
                        MIME_PLAINTEXT,
                        "Invalid download id",
                    )
                } else {
                    // session.parms values are already URL-decoded by NanoHTTPD; the seg token
                    // is base64 URL-safe so it survives untouched.
                    val segToken = session.parms[SEG_PARAM]
                    if (!segToken.isNullOrBlank()) {
                        serveSegContent(session, downloadId, segToken)
                    } else {
                        serveOfflineDownload(session, downloadId)
                    }
                }
            } else {
                NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Invalid Endpoint")
            }
        return response
    }

    private fun serveOfflineDownload(
        session: IHTTPSession,
        downloadId: String,
    ): Response {
        val entity = loadEntity(appContext, downloadId)
        val originalUrl = entity?.url ?: downloadId
        val mime = entity?.mimeType ?: "video/mp4"

        // 1) HLS content: serve the rewritten playlist so every segment/key request goes
        //    through this phone (which holds the download in its cache).
        val hlsResponse = if (entity != null && isHls(entity)) serveHlsPlaylist(entity) else null

        // 2) Single-file / progressive content: read from the phone's download cache keyed
        //    by the original URL (downloads are stored under their URI), falling back to a
        //    strict file scan if the cache lookup fails.
        return hlsResponse
            ?: serveFromCache(session, originalUrl, mime, appContext)
            ?: serveFromFiles(session, downloadId, appContext)
    }

    private fun serveHlsPlaylist(entity: OfflineVideoEntity): Response {
        val content = readFromCache(appContext, entity.url, MAX_PLAYLIST_BYTES)
        val serverBase = getLocalBaseUrl()
        if (content == null || serverBase == null) {
            val message = if (content == null) "Download not found in cache" else "No local address"
            return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, message)
        }

        val proxyPrefix = "$serverBase$OFFLINE_STREAM_PATH${encodeIdForUrl(entity.id)}?$SEG_PARAM="
        val rewritten = rewriteHlsPlaylist(content.toString(Charsets.UTF_8), entity.url, proxyPrefix)

        val res = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, HLS_MIME, rewritten)
        res.addHeader("Accept-Ranges", "bytes")
        return res
    }

    private fun serveSegContent(
        session: IHTTPSession,
        downloadId: String,
        segToken: String,
    ): Response {
        val serverBase = getLocalBaseUrl()
        val segUrl = decodeIdFromUrl(segToken)
        val response =
            when {
                serverBase == null ->
                    NanoHTTPD.newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR,
                        MIME_PLAINTEXT,
                        "No local address",
                    )
                segUrl == null ->
                    NanoHTTPD.newFixedLengthResponse(
                        Response.Status.BAD_REQUEST,
                        MIME_PLAINTEXT,
                        "Invalid segment token",
                    )
                else -> {
                    val proxyPrefix =
                        "$serverBase$OFFLINE_STREAM_PATH${encodeIdForUrl(downloadId)}?$SEG_PARAM="
                    serveNestedPlaylist(segUrl, proxyPrefix, appContext)
                        ?: serveFromCache(session, segUrl, guessSegMime(segUrl), appContext)
                        ?: NanoHTTPD.newFixedLengthResponse(
                            Response.Status.NOT_FOUND,
                            MIME_PLAINTEXT,
                            "Segment not found in cache",
                        )
                }
            }
        return response
    }

    private fun getLocalBaseUrl(): String? {
        val ip = resolveLocalIpv4(appContext) ?: return null
        return "http://$ip:$listeningPort"
    }
}

// ---------------------------------------------------------------------------
// File-level helpers: kept outside the class so the server stays small and the
// response-building logic stays independent of the NanoHTTPD instance.
// ---------------------------------------------------------------------------

private const val UNSIGNED_BYTE_MASK = 0xFF

private fun serveNestedPlaylist(
    segUrl: String,
    proxyPrefix: String,
    appContext: Context,
): Response? {
    val prefix = readFromCache(appContext, segUrl, SNIFF_BYTES)
    val full =
        if (prefix != null && prefix.toString(Charsets.UTF_8).trimStart().startsWith("#EXTM3U")) {
            readFromCache(appContext, segUrl, MAX_PLAYLIST_BYTES)
        } else {
            null
        }
    if (full == null) return null

    val rewritten = rewriteHlsPlaylist(full.toString(Charsets.UTF_8), segUrl, proxyPrefix)
    return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, HLS_MIME, rewritten)
}

private fun serveFromCache(
    session: NanoHTTPD.IHTTPSession,
    originalUrl: String,
    mime: String,
    appContext: Context,
): Response? {
    val cache = DownloadModule.getDownloadCache(appContext)
    val dataSpec = DataSpec(Uri.parse(originalUrl))
    var totalLength =
        try {
            val probe = createCacheDataSource(appContext)
            probe.open(dataSpec).also { probe.close() }
        } catch (e: Exception) {
            Log.w("LocalMediaServer", "Cache open failed for $originalUrl", e)
            -1L
        }
    if (totalLength <= 0) {
        val cachedBytes = cache.getCachedBytes(originalUrl, 0L, -1L)
        if (cachedBytes > 0) {
            totalLength = cachedBytes
        } else {
            return null
        }
    }

    val range = parseByteRange(session.headers["range"], totalLength)
    val stream = CacheInputStream(createCacheDataSource(appContext), dataSpec, range?.first ?: 0L)
    val res =
        if (range != null) {
            val contentLength = range.second - range.first + 1
            NanoHTTPD
                .newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT,
                    mime,
                    stream,
                    contentLength,
                ).apply {
                    addHeader("Content-Range", "bytes ${range.first}-${range.second}/$totalLength")
                    addHeader("Content-Length", contentLength.toString())
                }
        } else {
            NanoHTTPD.newFixedLengthResponse(Response.Status.OK, mime, stream, totalLength).apply {
                addHeader("Content-Length", totalLength.toString())
            }
        }
    res.addHeader("Accept-Ranges", "bytes")
    return res
}

private fun readFromCache(
    appContext: Context,
    originalUrl: String,
    maxBytes: Int,
): ByteArray? =
    try {
        val cacheDataSource = createCacheDataSource(appContext)
        cacheDataSource.open(DataSpec(Uri.parse(originalUrl)))

        val out = ByteArrayOutputStream()
        val buf = ByteArray(READ_BUFFER_SIZE)
        while (out.size() < maxBytes) {
            val read = cacheDataSource.read(buf, 0, minOf(buf.size, maxBytes - out.size()))
            if (read <= 0) break
            out.write(buf, 0, read)
        }
        cacheDataSource.close()
        out.toByteArray()
    } catch (e: Exception) {
        Log.e("LocalMediaServer", "Error reading $originalUrl from cache", e)
        null
    }

private fun createCacheDataSource(appContext: Context): CacheDataSource {
    val cache = DownloadModule.getDownloadCache(appContext)
    return CacheDataSource(
        cache,
        null,
        0,
    )
}

private fun loadEntity(
    appContext: Context,
    downloadId: String,
): OfflineVideoEntity? =
    try {
        runBlocking {
            val dao = OfflineDatabase.getInstance(appContext).offlineDao()
            dao.getById(downloadId)
                ?: dao.getAllFlow().firstOrNull()?.find { it.url == downloadId }
        }
    } catch (e: Exception) {
        Log.e("LocalMediaServer", "Error loading download entity $downloadId", e)
        null
    }

private fun serveFromFiles(
    session: NanoHTTPD.IHTTPSession,
    downloadId: String,
    appContext: Context,
): Response {
    val cacheFolder = File(appContext.filesDir, "offline_videos")
    val videoFile = findVideoFileForDownload(cacheFolder, downloadId)
    if (videoFile != null) {
        return serveFileWithRanges(session, videoFile)
    }
    return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Download not found")
}

private fun findVideoFileForDownload(
    dir: File,
    downloadId: String,
): File? {
    val files = dir.takeIf { it.exists() && it.isDirectory }?.listFiles() ?: return null
    // SimpleCache files (content_0, content_1...) cannot be mapped back to a download id by
    // name. Only use them when there is exactly one plausible candidate, otherwise a wrong
    // file could be served for the wrong download.
    val directVideos =
        files.filter {
            it.isFile &&
                !it.name.endsWith(".exo") &&
                !it.name.endsWith(".uid") &&
                (it.extension.lowercase() in VIDEO_EXTENSIONS || it.name.startsWith("content_"))
        }
    return if (directVideos.size == 1) {
        directVideos.first()
    } else {
        // Exact match by download id in the file name (e.g. manually placed files).
        files
            .filter {
                it.isFile &&
                    it.name.contains(downloadId, ignoreCase = true) &&
                    it.extension.lowercase() in VIDEO_EXTENSIONS
            }.maxByOrNull { it.length() }
    }
}

private fun serveFileWithRanges(
    session: NanoHTTPD.IHTTPSession,
    file: File,
): Response {
    val mime = getMimeTypeFromFile(file)
    val fileLength = file.length()
    val range = parseByteRange(session.headers["range"], fileLength)
    return try {
        if (range != null) {
            val contentLength = range.second - range.first + 1
            val fis = FileInputStream(file).apply { skip(range.first) }
            NanoHTTPD.newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, fis, contentLength).apply {
                addHeader("Content-Range", "bytes ${range.first}-${range.second}/$fileLength")
                addHeader("Content-Length", contentLength.toString())
            }
        } else {
            NanoHTTPD.newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(file), fileLength).apply {
                addHeader("Content-Length", fileLength.toString())
            }
        }.also { it.addHeader("Accept-Ranges", "bytes") }
    } catch (e: Exception) {
        Log.e("LocalMediaServer", "Error serving file with ranges", e)
        NanoHTTPD.newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR,
            NanoHTTPD.MIME_PLAINTEXT,
            "Server Error: ${e.message}",
        )
    }
}

private fun getMimeTypeFromFile(file: File): String =
    when (file.extension.lowercase()) {
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "m3u8" -> HLS_MIME
        "ts" -> "video/mp2t"
        else -> "video/mp4"
    }

private class CacheInputStream(
    private val dataSource: CacheDataSource,
    private val dataSpec: DataSpec,
    private val startPosition: Long,
) : InputStream() {
    private var isOpened = false

    private fun ensureOpened() {
        if (!isOpened) {
            val spec = dataSpec.buildUpon().setPosition(startPosition).build()
            dataSource.open(spec)
            isOpened = true
        }
    }

    override fun read(): Int {
        ensureOpened()
        val buffer = ByteArray(1)
        val read = dataSource.read(buffer, 0, 1)
        return if (read <= 0) -1 else buffer[0].toInt() and UNSIGNED_BYTE_MASK
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        ensureOpened()
        val read = dataSource.read(b, off, len)
        return if (read <= 0) -1 else read
    }

    override fun close() {
        if (isOpened) {
            try {
                dataSource.close()
            } catch (ignored: Exception) {
            }
            isOpened = false
        }
    }
}
