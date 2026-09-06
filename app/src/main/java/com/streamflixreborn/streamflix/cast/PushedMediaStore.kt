package com.streamflixreborn.streamflix.cast

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * TV-side temporary store for media pushed from the phone.
 *
 * When a download is cast with [CastPayload.fullTransfer], the TV pulls the whole media once
 * from the phone's LocalMediaServer into this store, then plays it from local storage. From
 * that moment playback no longer depends on the phone (it can be turned off entirely).
 *
 * Two content shapes are supported:
 *  - Progressive single files: downloaded as one local file.
 *  - HLS: the master/variant playlists are downloaded recursively and rewritten so every
 *    segment, init section and encryption key points at a local file. This preserves the
 *    original structure (separate audio renditions, AES-128 keys) instead of trying to
 *    concatenate segments into a single file, which breaks on encrypted/multi-rendition
 *    content.
 *
 * Retention policy ("whichever comes first"):
 *  - Files older than [TTL_MS] (12 h) are deleted by a periodic sweep started at first use.
 *  - Everything in the store is deleted when the TV app actually finishes ([deleteAll] from
 *    MainTvActivity.onDestroy when `isFinishing`).
 */
object PushedMediaStore {

    private const val TAG = "PushedMediaStore"
    private const val DIR_NAME = "pushed_media"
    private const val PART_SUFFIX = ".part"

    /** How long a transferred file may sit unused before the sweep deletes it (12 h). */
    private val TTL_MS = TimeUnit.HOURS.toMillis(12)

    /** How often the TTL sweep runs while the app stays open (30 min). */
    private val SWEEP_INTERVAL_MS = TimeUnit.MINUTES.toMillis(30)

    /** Safety cap when slurping playlist bodies into memory. */
    private const val MAX_PLAYLIST_BYTES = 32 * 1024 * 1024

    private val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // Between-segment gaps on the phone's LocalMediaServer can be long; only stall
            // if no bytes arrive at all for this long.
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val storeDirHolder = arrayOfNulls<File>(1)
    private var sweepScheduled = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sweepRunnable = object : Runnable {
        override fun run() {
            val dir = storeDirHolder[0]
            if (dir != null) cleanupExpired(dir)
            mainHandler.postDelayed(this, SWEEP_INTERVAL_MS)
        }
    }

    @Synchronized
    private fun storeDir(context: Context): File {
        val existing = storeDirHolder[0]
        if (existing != null) return existing
        val dir = File(context.applicationContext.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        storeDirHolder[0] = dir
        if (!sweepScheduled) {
            sweepScheduled = true
            // Drop anything that survived a force-kill before starting the periodic sweep.
            cleanupExpired(dir)
            mainHandler.postDelayed(sweepRunnable, SWEEP_INTERVAL_MS)
        }
        return dir
    }

    /**
     * Downloads everything referenced by [fromUrl] into the store and returns the local root:
     * the media file itself for progressive content, or the rewritten root playlist for HLS.
     */
    fun storeFrom(
        context: Context,
        fromUrl: String,
        key: String,
        mimeType: String?,
    ): File =
        if (isHlsSource(mimeType, fromUrl)) {
            Log.i(TAG, "Pulling full HLS transfer from $fromUrl")
            storeHls(context, fromUrl)
        } else {
            Log.i(TAG, "Pulling full progressive transfer from $fromUrl")
            storeProgressive(context, fromUrl, key, mimeType)
        }

    internal fun isHlsSource(
        mimeType: String?,
        url: String,
    ): Boolean = mimeType?.contains("mpegurl", ignoreCase = true) == true ||
        url.contains(".m3u8", ignoreCase = true)

    /** Safety margin (in bytes) left untouched in the TV's internal storage (400 MB). */
    private const val MIN_FREE_STORAGE_SAFETY_MARGIN = 400 * 1024 * 1024L

    /** Minimum free space on TV to even consider full transfer (1.2 GB). */
    private const val ABSOLUTE_MIN_USABLE_SPACE = 1200 * 1024 * 1024L

    /**
     * Checks whether the TV has enough free storage to safely receive and store [fileSizeBytes].
     * If the TV has low storage or if [fileSizeBytes] exceeds available storage minus safety margin,
     * returns false so the TV degrades to direct streaming instead of exhausting disk and crashing.
     */
    fun canStoreSafely(context: Context, fileSizeBytes: Long): Boolean {
        return try {
            val dir = storeDir(context)
            val freeBytes = dir.usableSpace
            if (freeBytes < ABSOLUTE_MIN_USABLE_SPACE) {
                Log.w(TAG, "TV has only ${freeBytes / (1024 * 1024)} MB free; skipping local transfer")
                return false
            }
            val required = if (fileSizeBytes > 0L) fileSizeBytes else 800 * 1024 * 1024L
            val enough = freeBytes > (required + MIN_FREE_STORAGE_SAFETY_MARGIN)
            if (!enough) {
                Log.w(TAG, "Not enough space for ${required / (1024 * 1024)} MB (free: ${freeBytes / (1024 * 1024)} MB)")
            }
            enough
        } catch (e: Exception) {
            Log.w(TAG, "Error checking usable storage space", e)
            false
        }
    }

    /**
     * Progressive path: downloads [fromUrl] completely under a stable name derived from [key].
     * Writes to a `.part` temp file first so an interrupted transfer never leaves a file that
     * looks playable.
     */
    private fun storeProgressive(
        context: Context,
        fromUrl: String,
        key: String,
        mimeType: String?,
    ): File {
        val dir = storeDir(context)
        val target = File(dir, fileNameFor(key, mimeType))
        val part = File(dir, target.name + PART_SUFFIX)

        fetch(fromUrl).use { input ->
            var written = 0L
            part.outputStream().use { output ->
                val buf = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    if (dir.usableSpace < MIN_FREE_STORAGE_SAFETY_MARGIN) {
                        part.delete()
                        throw IllegalStateException("TV storage reached critical limit during transfer")
                    }
                    output.write(buf, 0, read)
                    written += read
                }
            }
            Log.i(TAG, "Transfer complete: ${target.name} ($written bytes)")
        }

        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.delete()
            throw IllegalStateException("Could not finalize ${target.name}")
        }
        return target
    }

    /**
     * HLS path: recursively downloads playlists/segments/keys starting at [fromUrl] and
     * rewrites every reference to a local `file://` URI. Returns the local root playlist.
     */
    private fun storeHls(
        context: Context,
        fromUrl: String,
    ): File {
        val dir = storeDir(context)
        // Maps each source URL to the local file URI it was stored as; doubles as a
        // visited-set so shared renditions are transferred exactly once.
        val done = HashMap<String, String>()
        val rootUri = downloadNode(dir, fromUrl, done)
        val rootFile = File(rootUri.removePrefix("file://"))
        Log.i(TAG, "HLS transfer complete: ${done.size} parts stored")
        return rootFile
    }

    /**
     * Downloads one node of the HLS tree ([url]) — either a playlist or a binary part — and
     * returns its local `file://` URI. Playlists are rewritten with every reference replaced
     * by the local URI of the recursively-transferred child node.
     *
     * @throws UnsupportedOperationException for constructs we deliberately don't transfer
     *   (byte-range segments), letting the caller fall back to direct streaming.
     */
    private fun downloadNode(
        dir: File,
        url: String,
        done: MutableMap<String, String>,
    ): String {
        done[url]?.let { return it }

        val hash = hashFor(url)
        val part = File(dir, "$hash$PART_SUFFIX")

        fetch(url).use { input ->
            // Peek enough bytes to tell a playlist from a binary segment without losing them.
            val peek = ByteArray(SNIFF_BYTES)
            var peeked = 0
            while (peeked < peek.size) {
                val read = input.read(peek, peeked, peek.size - peeked)
                if (read <= 0) break
                peeked += read
            }
            val head = String(peek, 0, peeked, Charsets.US_ASCII)

            if (head.trimStart().startsWith("#EXTM3U")) {
                return storePlaylistNode(dir, url, input, head, peeked, part, done, hash)
            } else {
                return storeBinaryNode(input, peek, peeked, part, url, hash, done)
            }
        }
    }

    private fun storePlaylistNode(
        dir: File,
        url: String,
        rest: java.io.InputStream,
        head: String,
        peeked: Int,
        part: File,
        done: MutableMap<String, String>,
        hash: String,
    ): String {
        val text = head + String(readBounded(rest), Charsets.UTF_8)
        if (text.contains("#EXT-X-BYTERANGE") || text.contains("#EXT-X-I-FRAMES-ONLY")) {
            throw UnsupportedOperationException(
                "HLS byte-range playlists are not supported for full transfer: $url",
            )
        }

        val out = StringBuilder(text.length + 1024)
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#") -> {
                    // Rewrite URI="..." references inside tags (segments, keys, maps).
                    val rewritten =
                        TAG_URI_REGEX.replace(line) { m ->
                            val ref = m.groupValues[1]
                            if (ref.startsWith("data:")) {
                                m.value
                            } else {
                                val resolved = resolveUrl(url, ref) ?: ref
                                "URI=\"${downloadNode(dir, resolved, done)}\""
                            }
                        }
                    out.append(rewritten).append('\n')
                }

                trimmed.isEmpty() -> out.append('\n')

                else -> {
                    // A bare segment/playlist URI line.
                    val resolved = resolveUrl(url, trimmed) ?: trimmed
                    out.append(downloadNode(dir, resolved, done)).append('\n')
                }
            }
        }

        val target = File(dir, "$hash.m3u8")
        target.writeText(out.toString())
        part.delete()
        done[url] = Uri.fromFile(target).toString()
        return done[url]!!
    }

    private fun storeBinaryNode(
        rest: java.io.InputStream,
        peek: ByteArray,
        peeked: Int,
        part: File,
        url: String,
        hash: String,
        done: MutableMap<String, String>,
    ): String {
        part.outputStream().use { output ->
            output.write(peek, 0, peeked)
            rest.copyTo(output)
        }
        val ext =
            url
                .substringBefore('?')
                .substringAfterLast('.', "")
                .takeIf { Regex("[A-Za-z0-9]{1,5}").matches(it) }
                ?.let { ".$it" } ?: ".ts"
        val target = File(part.parentFile, "$hash$ext")
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.delete()
            throw IllegalStateException("Could not finalize ${target.name}")
        }
        done[url] = Uri.fromFile(target).toString()
        return done[url]!!
    }

    private fun fetch(url: String): java.io.InputStream {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IllegalStateException("HTTP ${response.code} pulling $url")
        }
        return response.body?.byteStream() ?: run {
            response.close()
            throw IllegalStateException("Empty body pulling $url")
        }
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(DEFAULT_BUFFER_SIZE * 8)
        var total = 0
        while (total < MAX_PLAYLIST_BYTES) {
            val read = input.read(buf, 0, minOf(buf.size, MAX_PLAYLIST_BYTES - total))
            if (read <= 0) break
            out.write(buf, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    internal fun hashFor(key: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)

    /** Deletes every stored transfer and stops the sweep. Called when the TV app finishes. */
    fun deleteAll(context: Context) {
        mainHandler.removeCallbacks(sweepRunnable)
        sweepScheduled = false
        val dir = storeDirHolder[0] ?: return
        val deleted = dir.listFiles()?.sumOf { if (it.delete()) 1 else 0 } ?: 0
        if (deleted > 0) Log.i(TAG, "Deleted $deleted pushed media files on exit")
    }

    private fun cleanupExpired(dir: File) {
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { file ->
            if (now - file.lastModified() > TTL_MS && file.delete()) {
                Log.d(TAG, "TTL expired, deleted ${file.name}")
            }
        }
    }

    /**
     * Stable, filesystem-safe file name: short hash of [key] plus an extension derived from
     * the mime type (download ids can be full URIs full of unsafe characters).
     */
    private fun fileNameFor(
        key: String,
        mimeType: String?,
    ): String {
        val ext =
            when {
                mimeType?.contains("matroska", ignoreCase = true) == true -> ".mkv"
                mimeType?.contains("mp2t", ignoreCase = true) == true -> ".ts"
                else -> ".mp4"
            }
        return "${hashFor(key)}$ext"
    }
}
