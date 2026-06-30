package com.sherlock.app.data.local

import androidx.room.*
import com.sherlock.app.data.model.ScheduledSearch
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledSearchDao {
    @Insert suspend fun insert(search: ScheduledSearch): Long
    @Update suspend fun update(search: ScheduledSearch)
    @Delete suspend fun delete(search: ScheduledSearch)
    @Query("SELECT * FROM scheduled_searches ORDER BY createdAt DESC")
    fun getAll(): Flow<List<ScheduledSearch>>
    @Query("SELECT * FROM scheduled_searches WHERE isActive = 1 AND (:now - lastRun) >= (intervalHours * 3600000)")
    suspend fun getDue(now: Long): List<ScheduledSearch>
}
