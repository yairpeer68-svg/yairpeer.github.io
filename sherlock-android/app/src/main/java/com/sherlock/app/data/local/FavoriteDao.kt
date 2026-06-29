package com.sherlock.app.data.local

import androidx.room.*
import com.sherlock.app.data.model.Favorite
import com.sherlock.app.data.model.MonitoredProfile
import com.sherlock.app.data.model.Tag
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    @Insert
    suspend fun insertFavorite(favorite: Favorite): Long

    @Delete
    suspend fun deleteFavorite(favorite: Favorite)

    @Update
    suspend fun updateFavorite(favorite: Favorite)

    @Query("SELECT * FROM favorites ORDER BY timestamp DESC")
    fun getAllFavorites(): Flow<List<Favorite>>

    @Query("SELECT * FROM favorites WHERE tag = :tag ORDER BY timestamp DESC")
    fun getFavoritesByTag(tag: String): Flow<List<Favorite>>

    @Query("SELECT * FROM favorites WHERE url = :url LIMIT 1")
    suspend fun getFavoriteByUrl(url: String): Favorite?

    @Query("SELECT DISTINCT tag FROM favorites WHERE tag != '' ORDER BY tag")
    fun getAllTags(): Flow<List<String>>

    @Query("DELETE FROM favorites")
    suspend fun clearAllFavorites()

    @Insert
    suspend fun insertTag(tag: Tag): Long

    @Query("SELECT * FROM tags ORDER BY name")
    fun getAllTagEntities(): Flow<List<Tag>>

    @Delete
    suspend fun deleteTag(tag: Tag)
}

@Dao
interface MonitoredProfileDao {

    @Insert
    suspend fun insertProfile(profile: MonitoredProfile): Long

    @Delete
    suspend fun deleteProfile(profile: MonitoredProfile)

    @Update
    suspend fun updateProfile(profile: MonitoredProfile)

    @Query("SELECT * FROM monitored_profiles WHERE isActive = 1 ORDER BY lastChecked ASC")
    fun getActiveProfiles(): Flow<List<MonitoredProfile>>

    @Query("SELECT * FROM monitored_profiles ORDER BY lastChecked DESC")
    fun getAllProfiles(): Flow<List<MonitoredProfile>>

    @Query("SELECT * FROM monitored_profiles WHERE isActive = 1 AND lastChecked < :before")
    suspend fun getProfilesDueForCheck(before: Long): List<MonitoredProfile>

    @Query("SELECT COUNT(*) FROM monitored_profiles WHERE isActive = 1")
    suspend fun getActiveCount(): Int
}
