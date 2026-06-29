package com.sherlock.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val color: Long = 0xFF58A6FF,
    val priority: Priority = Priority.NORMAL,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class Priority(val hebrewName: String, val color: Long) {
    URGENT("דחוף", 0xFFFF1744),
    HIGH("חשוב", 0xFFFF9100),
    NORMAL("רגיל", 0xFF58A6FF),
    LOW("נמוך", 0xFF8B949E)
}

@Entity(tableName = "profile_notes")
data class ProfileNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileUrl: String,
    val siteName: String,
    val username: String,
    val note: String,
    val projectId: Long? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "search_templates")
data class SearchTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val searchType: SearchType,
    val query: String,
    val includeVariations: Boolean = false,
    val selectedCategories: String = "",
    val parallelThreads: Int = 10,
    val timeout: Int = 10
)

@Entity(tableName = "custom_sites")
data class CustomSite(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val urlTemplate: String,
    val category: SiteCategory = SiteCategory.OTHER,
    val errorType: ErrorType = ErrorType.STATUS_CODE,
    val errorIndicator: String = ""
)
