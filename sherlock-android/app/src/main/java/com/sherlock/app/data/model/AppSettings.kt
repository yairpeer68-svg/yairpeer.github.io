package com.sherlock.app.data.model

enum class AppTheme(val displayName: String, val hebrewName: String) {
    DARK_BLUE("Dark Blue", "כחול כהה"),
    HACKER_GREEN("Hacker Green", "ירוק האקר"),
    OCEAN_BLUE("Ocean Blue", "אוקיינוס"),
    SUNSET("Sunset", "שקיעה"),
    PURPLE_NIGHT("Purple Night", "סגול לילה"),
    LIGHT("Light", "בהיר"),
    AMOLED("AMOLED Black", "שחור AMOLED"),
    MATERIAL_YOU("Material You", "דינמי")
}

data class AppSettings(
    val theme: AppTheme = AppTheme.DARK_BLUE,
    val language: AppLanguage = AppLanguage.HEBREW,
    val biometricLock: Boolean = false,
    val incognitoMode: Boolean = false,
    val screenshotProtection: Boolean = false,
    val hapticFeedback: Boolean = true,
    val parallelSearchThreads: Int = 10,
    val searchTimeout: Int = 10,
    val showAdultSites: Boolean = false,
    val autoVariations: Boolean = true,
    val fakeAppIcon: FakeAppIcon = FakeAppIcon.SHERLOCK,
    val notificationsEnabled: Boolean = true,
    val monitorInterval: Int = 60
)

enum class AppLanguage(val displayName: String, val code: String) {
    HEBREW("עברית", "iw"),
    ENGLISH("English", "en"),
    ARABIC("العربية", "ar"),
    RUSSIAN("Русский", "ru")
}

enum class FontScale(val scale: Float, val hebrewName: String) {
    SMALL(0.85f, "קטן"),
    MEDIUM(1.0f, "בינוני"),
    LARGE(1.2f, "גדול")
}

enum class FakeAppIcon(val displayName: String) {
    SHERLOCK("Sherlock (ברירת מחדל)"),
    CALCULATOR("מחשבון"),
    CLOCK("שעון"),
    WEATHER("מזג אוויר"),
    NOTES("פתקים")
}
