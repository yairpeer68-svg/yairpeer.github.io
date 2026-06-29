package com.sherlock.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "search_history")
data class SearchHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val query: String,
    val searchType: SearchType,
    val timestamp: Long = System.currentTimeMillis(),
    val totalFound: Int = 0,
    val totalChecked: Int = 0,
    val imageUri: String? = null
)

@Entity(tableName = "favorites")
data class Favorite(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val siteName: String,
    val url: String,
    val username: String,
    val category: SiteCategory,
    val tag: String = "",
    val notes: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "monitored_profiles")
data class MonitoredProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val siteName: String,
    val url: String,
    val username: String,
    val lastChecked: Long = 0,
    val lastStatus: Boolean = true,
    val profileImageHash: String = "",
    val bioHash: String = "",
    val isActive: Boolean = true
)

@Entity(tableName = "tags")
data class Tag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Long = 0xFF58A6FF
)
