package com.streamflixreborn.streamflix.offline.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface OfflineDao {
    @Query("SELECT * FROM offline_videos")
    fun getAllFlow(): Flow<List<OfflineVideoEntity>>

    @Query("SELECT * FROM offline_videos WHERE id = :id")
    suspend fun getById(id: String): OfflineVideoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(video: OfflineVideoEntity)

    @Update
    suspend fun update(video: OfflineVideoEntity)

    @Query("DELETE FROM offline_videos WHERE id = :id")
    suspend fun deleteById(id: String)
}
