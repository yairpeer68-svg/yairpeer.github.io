package com.sherlock.app.data.local

import androidx.room.*
import com.sherlock.app.data.model.SavedLink
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedLinkDao {
    @Insert suspend fun insert(link: SavedLink): Long
    @Update suspend fun update(link: SavedLink)
    @Delete suspend fun delete(link: SavedLink)
    @Query("SELECT * FROM saved_links ORDER BY timestamp DESC")
    fun getAll(): Flow<List<SavedLink>>
}
