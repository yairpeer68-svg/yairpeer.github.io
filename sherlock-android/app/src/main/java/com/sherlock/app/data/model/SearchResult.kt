package com.sherlock.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "search_results")
data class SearchResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val historyId: Long = 0,
    val siteName: String,
    val url: String,
    val username: String,
    val exists: Boolean,
    val category: SiteCategory,
    val responseTimeMs: Long = 0,
    val httpStatus: Int = 0
)

enum class SiteCategory(val displayName: String, val hebrewName: String, val icon: String) {
    SOCIAL_MEDIA("Social Media", "רשתות חברתיות", "people"),
    PHOTO_VIDEO("Photo & Video", "תמונות ווידאו", "camera"),
    MUSIC("Music", "מוזיקה", "music_note"),
    GAMING("Gaming", "גיימינג", "sports_esports"),
    TECH("Tech", "טכנולוגיה", "code"),
    FORUM("Forum", "פורומים", "forum"),
    DATING("Dating", "היכרויות", "favorite"),
    BUSINESS("Business", "עסקים", "business"),
    FINANCE("Finance", "פיננסים", "account_balance"),
    NEWS("News", "חדשות", "newspaper"),
    EDUCATION("Education", "חינוך", "school"),
    SHOPPING("Shopping", "קניות", "shopping_cart"),
    TRAVEL("Travel", "טיולים", "flight"),
    FITNESS("Fitness", "כושר", "fitness_center"),
    FOOD("Food", "אוכל", "restaurant"),
    CRYPTO("Crypto", "קריפטו", "currency_bitcoin"),
    ADULT("Adult", "מבוגרים", "18_up_rating"),
    OTHER("Other", "אחר", "public")
}

data class SiteConfig(
    val name: String,
    val urlTemplate: String,
    val category: SiteCategory,
    val errorType: ErrorType = ErrorType.STATUS_CODE,
    val errorIndicator: String = "",
    val headers: Map<String, String> = emptyMap()
)

enum class ErrorType {
    STATUS_CODE,
    MESSAGE_IN_PAGE,
    REDIRECT,
    JSON_FIELD
}

enum class SearchType(val hebrewName: String) {
    USERNAME("שם משתמש"),
    EMAIL("אימייל"),
    PHONE("מספר טלפון"),
    FULL_NAME("שם מלא"),
    DOMAIN("דומיין"),
    IP_ADDRESS("כתובת IP"),
    IMAGE("תמונה")
}
