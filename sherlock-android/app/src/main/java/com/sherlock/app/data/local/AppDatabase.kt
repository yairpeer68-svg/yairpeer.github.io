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
        Tag::class,
        Project::class,
        ProfileNote::class,
        SearchTemplate::class,
        CustomSite::class,
        ImageHash::class,
        ProjectTask::class,
        DigitalIdentity::class,
        IdentityLink::class,
        ScheduledSearch::class
    ],
    version = 6,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun monitoredProfileDao(): MonitoredProfileDao
    abstract fun projectDao(): ProjectDao
    abstract fun noteDao(): NoteDao
    abstract fun templateDao(): TemplateDao
    abstract fun customSiteDao(): CustomSiteDao
    abstract fun imageHashDao(): ImageHashDao
    abstract fun projectTaskDao(): ProjectTaskDao
    abstract fun digitalIdentityDao(): DigitalIdentityDao
    abstract fun identityLinkDao(): IdentityLinkDao
    abstract fun scheduledSearchDao(): ScheduledSearchDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "sherlock_db")
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
