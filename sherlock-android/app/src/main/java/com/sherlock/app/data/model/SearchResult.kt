package com.sherlock.app.data.model

data class SearchResult(
    val siteName: String,
    val url: String,
    val username: String,
    val exists: Boolean,
    val category: SiteCategory,
    val responseTimeMs: Long = 0
)

enum class SiteCategory(val displayName: String, val hebrewName: String) {
    SOCIAL_MEDIA("Social Media", "רשתות חברתיות"),
    PHOTO_VIDEO("Photo & Video", "תמונות ווידאו"),
    MUSIC("Music", "מוזיקה"),
    GAMING("Gaming", "גיימינג"),
    TECH("Tech", "טכנולוגיה"),
    FORUM("Forum", "פורומים"),
    DATING("Dating", "היכרויות"),
    BUSINESS("Business", "עסקים"),
    OTHER("Other", "אחר")
}

data class SiteConfig(
    val name: String,
    val urlTemplate: String,
    val category: SiteCategory,
    val errorType: ErrorType = ErrorType.STATUS_CODE,
    val errorIndicator: String = ""
)

enum class ErrorType {
    STATUS_CODE,
    MESSAGE_IN_PAGE,
    REDIRECT
}
