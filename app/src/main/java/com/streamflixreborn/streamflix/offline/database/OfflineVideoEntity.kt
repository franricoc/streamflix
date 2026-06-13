package com.streamflixreborn.streamflix.offline.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "offline_videos")
data class OfflineVideoEntity(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val posterUrl: String?,
    val state: Int, // 0=Pending, 1=Downloading, 2=Paused, 3=Completed, 4=Failed
    val progress: Float,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val mimeType: String?
)
