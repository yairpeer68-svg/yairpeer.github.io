package com.sherlock.app.data.repository

import com.sherlock.app.data.model.LinkHealthResult
import com.sherlock.app.data.model.PlatformFootprint
import com.sherlock.app.data.model.PlatformGuideTip
import com.sherlock.app.data.model.UsernameFormatRule
import com.sherlock.app.data.model.UsernameMatchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.max

class SocialRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun checkLinkHealth(url: String): LinkHealthResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).head().build()
            client.newCall(request).execute().use { response ->
                LinkHealthResult(url = url, isAlive = response.code in 200..399, statusCode = response.code)
            }
        } catch (e: Exception) {
            LinkHealthResult(url = url, isAlive = false, statusCode = null, errorMessage = e.message ?: "שגיאת רשת")
        }
    }

    fun matchUsernames(a: String, b: String): UsernameMatchResult {
        val normalizedA = a.trim().lowercase()
        val normalizedB = b.trim().lowercase()
        val distance = levenshtein(normalizedA, normalizedB)
        val maxLen = max(normalizedA.length, normalizedB.length).coerceAtLeast(1)
        val similarity = ((1f - distance.toFloat() / maxLen) * 100).toInt().coerceIn(0, 100)

        val notes = mutableListOf<String>()
        if (normalizedA == normalizedB) notes.add("שמות המשתמש זהים לחלוטין")
        if (normalizedA.replace(Regex("[._-]"), "") == normalizedB.replace(Regex("[._-]"), "")) {
            notes.add("השמות זהים למעט מפרידים (נקודות/קווים)")
        }
        if (Regex("\\d+$").containsMatchIn(normalizedA) && Regex("\\d+$").containsMatchIn(normalizedB)) {
            notes.add("שני השמות מסתיימים במספרים - ייתכן ומדובר בתבנית אישית חוזרת")
        }
        if (normalizedA.contains(normalizedB) || normalizedB.contains(normalizedA)) {
            notes.add("שם אחד מוכל בתוך השני")
        }

        val verdict = when {
            similarity >= 90 -> "סבירות גבוהה מאוד לאותו אדם"
            similarity >= 70 -> "סבירות גבוהה לאותו אדם"
            similarity >= 40 -> "סבירות בינונית - נדרשת בדיקה נוספת"
            else -> "סבירות נמוכה לאותו אדם"
        }

        return UsernameMatchResult(a, b, similarity, verdict, notes)
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        return dp[a.length][b.length]
    }

    fun extractContactsFromText(text: String): Map<String, List<String>> {
        val urlRegex = Regex("(https?://[\\w./?=&%#-]+)|(www\\.[\\w./?=&%#-]+)")
        val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        val phoneRegex = Regex("\\+?\\d[\\d \\-()]{7,}\\d")
        val handleRegex = Regex("@[A-Za-z0-9_.]{2,30}")

        return mapOf(
            "קישורים" to urlRegex.findAll(text).map { it.value }.distinct().toList(),
            "כתובות אימייל" to emailRegex.findAll(text).map { it.value }.distinct().toList(),
            "מספרי טלפון" to phoneRegex.findAll(text).map { it.value.trim() }.distinct().toList(),
            "שמות משתמש (@)" to handleRegex.findAll(text).map { it.value }.distinct().toList()
        )
    }

    val platformCategories: List<PlatformFootprint> = listOf(
        PlatformFootprint("חברתי", listOf("Facebook", "Instagram", "Twitter/X", "Snapchat", "TikTok")),
        PlatformFootprint("מקצועי", listOf("LinkedIn", "GitHub", "Behance", "Medium")),
        PlatformFootprint("יצירתי ומדיה", listOf("YouTube", "Pinterest", "DeviantArt", "Flickr", "SoundCloud", "Vimeo")),
        PlatformFootprint("גיימינג", listOf("Steam", "Twitch", "Discord")),
        PlatformFootprint("מסרים", listOf("Telegram", "WhatsApp", "Signal")),
        PlatformFootprint("אחר", listOf("Reddit", "Spotify"))
    )

    val usernameFormatRules: List<UsernameFormatRule> = listOf(
        UsernameFormatRule("Instagram", 1, 30, Regex("^[A-Za-z0-9._]+$"), "אותיות, ספרות, נקודות וקו תחתון בלבד"),
        UsernameFormatRule("Twitter/X", 4, 15, Regex("^[A-Za-z0-9_]+$"), "אותיות, ספרות וקו תחתון בלבד, ללא נקודות"),
        UsernameFormatRule("Facebook", 5, 50, Regex("^[A-Za-z0-9.]+$"), "אותיות, ספרות ונקודות בלבד"),
        UsernameFormatRule("TikTok", 2, 24, Regex("^[A-Za-z0-9._]+$"), "אותיות, ספרות, נקודות וקו תחתון בלבד"),
        UsernameFormatRule("GitHub", 1, 39, Regex("^[A-Za-z0-9-]+$"), "אותיות, ספרות ומקף בלבד, לא יכול להתחיל/להסתיים במקף"),
        UsernameFormatRule("Telegram", 5, 32, Regex("^[A-Za-z0-9_]+$"), "אותיות, ספרות וקו תחתון, חייב להתחיל באות"),
        UsernameFormatRule("Reddit", 3, 20, Regex("^[A-Za-z0-9_-]+$"), "אותיות, ספרות, מקף וקו תחתון בלבד"),
        UsernameFormatRule("YouTube", 3, 30, Regex("^[A-Za-z0-9._-]+$"), "אותיות, ספרות, נקודות, מקפים וקו תחתון")
    )

    fun validateUsername(rule: UsernameFormatRule, username: String): Pair<Boolean, List<String>> {
        val issues = mutableListOf<String>()
        if (username.length < rule.minLength) issues.add("קצר מדי (מינימום ${rule.minLength} תווים)")
        if (username.length > rule.maxLength) issues.add("ארוך מדי (מקסימום ${rule.maxLength} תווים)")
        if (!rule.allowedPattern.matches(username)) issues.add("מכיל תווים לא חוקיים לפלטפורמה זו")
        return issues.isEmpty() to issues
    }

    val platformGuides: List<PlatformGuideTip> = listOf(
        PlatformGuideTip("Instagram", listOf(
            "חיפוש לפי שם מלא בתיבת החיפוש לעיתים חושף פרופילים לא מקושרים",
            "בדיקת תגיות מיקום (Location Tags) בפוסטים ישנים עשויה לחשוף מיקומים קבועים",
            "תמונות פרופיל ייחודיות ניתנות לחיפוש הפוך באמצעות כלי חיפוש תמונה"
        )),
        PlatformGuideTip("Facebook", listOf(
            "חיפוש דרך 'אנשים' עם סינון לפי עיר/בית ספר/מקום עבודה",
            "תגובות ציבוריות בפוסטים של חברים משותפים עשויות לחשוף קשרים",
            "תמונות שמתויגות (Tagged Photos) לרוב נגישות גם אם הפרופיל פרטי"
        )),
        PlatformGuideTip("LinkedIn", listOf(
            "חיפוש לפי שם חברה + תפקיד מצמצם משמעותית את התוצאות",
            "רשימת 'אנשים שצפו גם ב...' עוזרת לאתר עמיתים לעבודה",
            "פרטי השכלה לרוב חופפים בין פלטפורמות שונות לאותו אדם"
        )),
        PlatformGuideTip("Twitter/X", listOf(
            "חיפוש מתקדם לפי תאריכים ומילות מפתח חושף פוסטים ישנים שנמחקו מהתצוגה הרגילה",
            "רשימת העוקבים/נעקבים ההדדית מצביעה על קשרים חברתיים",
            "תמונות רקע ופרופיל ישנות נשמרות בארכיון ולעיתים שונות מהנוכחיות"
        )),
        PlatformGuideTip("TikTok", listOf(
            "סאונד/מוזיקה בשימוש חוזר מקשר בין חשבונות שונים של אותו משתמש",
            "תגובות בין חשבונות מקושרים עשויות לחשוף קשרי משפחה/חברים",
            "ביו לעיתים מכיל קישור לרשת חברתית אחרת"
        ))
    )
}
