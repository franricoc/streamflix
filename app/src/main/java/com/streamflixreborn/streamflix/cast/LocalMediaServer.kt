package com.streamflixreborn.streamflix.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import com.streamflixreborn.streamflix.offline.DownloadModule
import com.streamflixreborn.streamflix.offline.database.OfflineDatabase
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface

@UnstableApi
class LocalMediaServer(
    context: Context,
    port: Int = 8888
) : NanoHTTPD(port) {

    private val appContext: Context = context.applicationContext

    companion object {
        private const val TAG = "LocalMediaServer"

        @Volatile
        private var instance: LocalMediaServer? = null

        fun getInstance(context: Context): LocalMediaServer {
            return instance ?: synchronized(this) {
                instance ?: LocalMediaServer(context).also { instance = it }
            }
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
        val ip = getLocalIpAddress(appContext) ?: return null
        return "http://$ip:$listeningPort"
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

        if (uri.startsWith("/offline_stream/")) {
            val downloadId = uri.substringAfter("/offline_stream/")
            return serveOfflineDownload(session, downloadId)
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Invalid Endpoint")
    }

    private fun serveOfflineDownload(session: IHTTPSession, downloadId: String): Response {
        val cacheFolder = File(appContext.filesDir, "offline_videos")
        val singleFile = findFileForDownload(cacheFolder, downloadId)

        if (singleFile != null && singleFile.exists()) {
            return serveFileWithRanges(session, singleFile)
        }

        // Try reading from Room Database and SimpleCache
        val dbEntity = try {
            kotlinx.coroutines.runBlocking {
                OfflineDatabase.getInstance(appContext).offlineDao().getById(downloadId)
            }
        } catch (e: Exception) {
            null
        }


        val cache = DownloadModule.getDownloadCache(appContext)
        val originalUrl = dbEntity?.url ?: downloadId

        val upstreamFactory = DefaultHttpDataSource.Factory()
        val cacheDataSource = CacheDataSource(
            cache,
            upstreamFactory.createDataSource(),
            CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
        )

        val dataSpec = DataSpec(Uri.parse(originalUrl))

        try {
            val totalLength = try { cacheDataSource.open(dataSpec) } catch (e: Exception) { -1L }
            val fileLength = if (totalLength > 0) totalLength else (dbEntity?.totalBytes ?: -1L)

            val headers = session.headers
            val rangeHeader = headers["range"]
            val mime = dbEntity?.mimeType ?: "video/mp4"

            if (fileLength > 0 && rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                var start = 0L
                var end = fileLength - 1
                val rangeStr = rangeHeader.substring("bytes=".length)
                val parts = rangeStr.split("-")

                if (parts[0].isNotEmpty()) start = parts[0].toLong()
                if (parts.size > 1 && parts[1].isNotEmpty()) end = parts[1].toLong()
                if (end >= fileLength) end = fileLength - 1

                val contentLength = end - start + 1
                val stream = CacheInputStream(cacheDataSource, dataSpec, start)

                val res = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, stream, contentLength)
                res.addHeader("Content-Range", "bytes $start-$end/$fileLength")
                res.addHeader("Accept-Ranges", "bytes")
                res.addHeader("Content-Length", contentLength.toString())
                return res
            } else {
                val stream = CacheInputStream(cacheDataSource, dataSpec, 0L)
                val res = if (fileLength > 0) {
                    newFixedLengthResponse(Response.Status.OK, mime, stream, fileLength).apply {
                        addHeader("Content-Length", fileLength.toString())
                    }
                } else {
                    newChunkedResponse(Response.Status.OK, mime, stream)
                }
                res.addHeader("Accept-Ranges", "bytes")
                return res
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error serving offline download stream", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Server Error: ${e.message}")
        }
    }

    private fun findFileForDownload(dir: File, downloadId: String): File? {
        if (!dir.exists() || !dir.isDirectory) return null
        val files = dir.listFiles() ?: return null

        for (file in files) {
            if (file.name.contains(downloadId, ignoreCase = true)) {
                if (file.isFile) return file
                if (file.isDirectory) {
                    val subFiles = file.listFiles()
                    val videoFile = subFiles?.firstOrNull {
                        it.isFile && (it.extension == "mp4" || it.extension == "mkv" || it.extension == "ts" || !it.name.endsWith(".exo"))
                    }
                    if (videoFile != null) return videoFile
                }
            }
        }

        return files.filter { it.isFile }.maxByOrNull { it.length() }
    }

    private fun serveFileWithRanges(session: IHTTPSession, file: File): Response {
        val mime = getMimeTypeFromFile(file)
        val fileLength = file.length()
        val headers = session.headers
        val rangeHeader = headers["range"]

        try {
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                var start: Long = 0
                var end: Long = fileLength - 1
                val rangeStr = rangeHeader.substring("bytes=".length)
                val parts = rangeStr.split("-")

                if (parts[0].isNotEmpty()) start = parts[0].toLong()
                if (parts.size > 1 && parts[1].isNotEmpty()) end = parts[1].toLong()
                if (end >= fileLength) end = fileLength - 1

                val contentLength = end - start + 1
                val fis = FileInputStream(file)
                fis.skip(start)

                val res = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, fis, contentLength)
                res.addHeader("Content-Range", "bytes $start-$end/$fileLength")
                res.addHeader("Accept-Ranges", "bytes")
                res.addHeader("Content-Length", contentLength.toString())
                return res
            } else {
                val fis = FileInputStream(file)
                val res = newFixedLengthResponse(Response.Status.OK, mime, fis, fileLength)
                res.addHeader("Accept-Ranges", "bytes")
                res.addHeader("Content-Length", fileLength.toString())
                return res
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error serving file with ranges", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Server Error: ${e.message}")
        }
    }

    private fun getMimeTypeFromFile(file: File): String {
        return when (file.extension.lowercase()) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "m3u8" -> "application/x-mpegURL"
            "ts" -> "video/mp2t"
            else -> "video/mp4"
        }
    }

    private fun getLocalIpAddress(context: Context): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (networkInterface in interfaces.toList()) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val addresses = networkInterface.inetAddresses
                for (inetAddress in addresses.toList()) {
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        val ip = inetAddress.hostAddress
                        if (!ip.isNullOrEmpty() && !ip.startsWith("127.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting network interfaces IP", e)
        }
        return null
    }

    private class CacheInputStream(
        private val dataSource: CacheDataSource,
        private val dataSpec: DataSpec,
        private val startPosition: Long
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
            return if (read <= 0) -1 else buffer[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            ensureOpened()
            val read = dataSource.read(b, off, len)
            return if (read <= 0) -1 else read
        }

        override fun close() {
            if (isOpened) {
                try { dataSource.close() } catch (ignored: Exception) {}
                isOpened = false
            }
        }
    }
}
