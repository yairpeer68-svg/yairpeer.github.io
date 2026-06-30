package com.sherlock.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scheduled_searches")
data class ScheduledSearch(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val query: String,
    val searchType: SearchType,
    val intervalHours: Int = 24,
    val isActive: Boolean = true,
    val lastRun: Long = 0,
    val lastFound: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
