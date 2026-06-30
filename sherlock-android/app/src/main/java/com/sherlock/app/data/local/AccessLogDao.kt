package com.sherlock.app.data.local

import androidx.room.*
import com.sherlock.app.data.model.AccessLogEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface AccessLogDao {
    @Insert suspend fun insert(entry: AccessLogEntry): Long
    @Query("SELECT * FROM access_log ORDER BY timestamp DESC LIMIT 100") fun getRecent(): Flow<List<AccessLogEntry>>
    @Query("DELETE FROM access_log") suspend fun clearAll()
}
