package com.streamflixreborn.streamflix.offline.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.streamflixreborn.streamflix.utils.UserProfileManager

@Database(entities = [OfflineVideoEntity::class], version = 1, exportSchema = false)
abstract class OfflineDatabase : RoomDatabase() {
    abstract fun offlineDao(): OfflineDao

    companion object {
        @Volatile
        private var INSTANCE: OfflineDatabase? = null
        @Volatile
        private var currentProfileId: String? = null

        fun getInstance(context: Context): OfflineDatabase {
            val activeProfileId = UserProfileManager.getActiveProfile(context)?.id ?: "default_profile"
            val sanitized = activeProfileId.lowercase().replace("[^a-z0-9]".toRegex(), "_")
            val dbKey = sanitized

            return INSTANCE?.takeIf { currentProfileId == dbKey } ?: synchronized(this) {
                INSTANCE?.takeIf { currentProfileId == dbKey } ?: run {
                    INSTANCE?.close()
                    val instance = Room.databaseBuilder(
                        context.applicationContext,
                        OfflineDatabase::class.java,
                        "offline_${sanitized}.db"
                    ).build()
                    INSTANCE = instance
                    currentProfileId = dbKey
                    instance
                }
            }
        }

        fun resetInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
                currentProfileId = null
            }
        }
    }
}
