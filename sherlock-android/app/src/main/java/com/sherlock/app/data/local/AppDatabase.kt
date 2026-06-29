package com.sherlock.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.sherlock.app.data.model.*

@Database(
    entities = [
        SearchHistory::class,
        SearchResult::class,
        Favorite::class,
        MonitoredProfile::class,
        Tag::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun monitoredProfileDao(): MonitoredProfileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sherlock_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
