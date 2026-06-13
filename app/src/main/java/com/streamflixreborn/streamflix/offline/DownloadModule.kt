package com.streamflixreborn.streamflix.offline

import android.content.Context
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.streamflixreborn.streamflix.offline.database.OfflineDatabase
import com.streamflixreborn.streamflix.offline.database.OfflineVideoEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@UnstableApi
object DownloadModule {

    private const val DOWNLOAD_DIR = "offline_videos"
    
    @Volatile
    private var databaseProvider: StandaloneDatabaseProvider? = null
    
    @Volatile
    private var downloadCache: Cache? = null
    
    @Volatile
    private var downloadManager: DownloadManager? = null

    @Synchronized
    fun getDatabaseProvider(context: Context): StandaloneDatabaseProvider {
        return databaseProvider ?: StandaloneDatabaseProvider(context.applicationContext).also {
            databaseProvider = it
        }
    }

    @Synchronized
    fun getDownloadCache(context: Context): Cache {
        return downloadCache ?: run {
            val cacheFolder = File(context.applicationContext.filesDir, DOWNLOAD_DIR)
            if (!cacheFolder.exists()) {
                cacheFolder.mkdirs()
            }
            val dbProvider = getDatabaseProvider(context)
            val cache = SimpleCache(cacheFolder, NoOpCacheEvictor(), dbProvider)
            downloadCache = cache
            cache
        }
    }

    @Synchronized
    fun getDownloadManager(context: Context): DownloadManager {
        return downloadManager ?: run {
            val appCtx = context.applicationContext
            val dbProvider = getDatabaseProvider(appCtx)
            val cache = getDownloadCache(appCtx)
            
            val downloadDispatcher = okhttp3.Dispatcher().apply {
                maxRequests = 12
                maxRequestsPerHost = 4
            }
            val okHttpClient = com.streamflixreborn.streamflix.utils.NetworkClient.default.newBuilder()
                .dispatcher(downloadDispatcher)
                .build()
            val httpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient)

            val dm = DownloadManager(
                appCtx,
                dbProvider,
                cache,
                httpDataSourceFactory,
                java.util.concurrent.Executors.newFixedThreadPool(4)
            ).apply {
                maxParallelDownloads = 2
            }

            dm.addListener(object : DownloadManager.Listener {
                override fun onDownloadChanged(
                    downloadManager: DownloadManager,
                    download: Download,
                    finalException: Exception?
                ) {
                    val dbState = when (download.state) {
                        Download.STATE_QUEUED -> 0 // Pending
                        Download.STATE_DOWNLOADING -> 1 // Downloading
                        Download.STATE_STOPPED -> 2 // Paused
                        Download.STATE_COMPLETED -> 3 // Completed
                        Download.STATE_FAILED -> 4 // Failed
                        else -> 0
                    }
                    
                    CoroutineScope(Dispatchers.IO).launch {
                        val dao = OfflineDatabase.getInstance(appCtx).offlineDao()
                        val existing = dao.getById(download.request.id)
                        if (existing != null) {
                            dao.update(existing.copy(
                                state = dbState,
                                progress = download.percentDownloaded,
                                downloadedBytes = download.bytesDownloaded,
                                totalBytes = download.contentLength
                            ))
                        }
                    }
                }

                override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                    CoroutineScope(Dispatchers.IO).launch {
                        OfflineDatabase.getInstance(appCtx).offlineDao().deleteById(download.request.id)
                    }
                }
            })

            downloadManager = dm
            dm
        }
    }

    fun getCacheDataSourceFactory(context: Context): DataSource.Factory {
        val appCtx = context.applicationContext
        val cache = getDownloadCache(appCtx)
        val okHttpClient = com.streamflixreborn.streamflix.utils.NetworkClient.default
        val upstreamFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient)

        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(null) // Read-only from cache for offline playback
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun startDownload(
        context: Context,
        id: String,
        url: String,
        title: String,
        posterUrl: String?,
        season: Int?,
        episode: Int?,
        mimeType: String?
    ) {
        val appCtx = context.applicationContext
        val resolvedMime = mimeType ?: when {
            url.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
            url.contains(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> MimeTypes.VIDEO_MP4
        }

        val entity = OfflineVideoEntity(
            id = id,
            url = url,
            title = title,
            seasonNumber = season,
            episodeNumber = episode,
            posterUrl = posterUrl,
            state = 1, // downloading
            progress = 0f,
            downloadedBytes = 0L,
            totalBytes = 0L,
            mimeType = resolvedMime
        )

        CoroutineScope(Dispatchers.IO).launch {
            OfflineDatabase.getInstance(appCtx).offlineDao().insert(entity)
            
            val request = DownloadRequest.Builder(id, Uri.parse(url))
                .setMimeType(resolvedMime)
                .build()

            DownloadService.sendAddDownload(
                appCtx,
                StreamflixDownloadService::class.java,
                request,
                /* foreground = */ true
            )
        }
    }

    fun pauseDownload(context: Context, id: String) {
        DownloadService.sendSetStopReason(
            context.applicationContext,
            StreamflixDownloadService::class.java,
            id,
            /* stopReason = */ 1,
            /* foreground = */ false
        )
    }

    fun resumeDownload(context: Context, id: String) {
        DownloadService.sendSetStopReason(
            context.applicationContext,
            StreamflixDownloadService::class.java,
            id,
            /* stopReason = */ Download.STOP_REASON_NONE,
            /* foreground = */ false
        )
    }

    fun removeDownload(context: Context, id: String) {
        DownloadService.sendRemoveDownload(
            context.applicationContext,
            StreamflixDownloadService::class.java,
            id,
            /* foreground = */ false
        )
    }
}
