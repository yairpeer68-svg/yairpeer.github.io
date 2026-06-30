package com.sherlock.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_links")
data class SavedLink(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String = "",
    val note: String = "",
    val isRead: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
