package com.sherlock.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.sherlock.app.data.model.*

class AnalysisRepository(private val context: Context) {

    fun analyzeUsername(username: String): UsernameAnalysis {
        val patterns = mutableListOf<String>()
        var firstName: String? = null
        var lastName: String? = null
        var birthYear: Int? = null
        var location: String? = null

        val parts = username.replace(".", "_").replace("-", "_").split("_")
        if (parts.size >= 2) {
            firstName = parts[0].replaceFirstChar { it.uppercase() }
            lastName = parts[1].replaceFirstChar { it.uppercase() }
            patterns.add("מכיל שם פרטי ומשפחה אפשריים: $firstName $lastName")
        }

        val yearMatch = Regex("(19|20)\\d{2}").find(username)
        if (yearMatch != null) {
            birthYear = yearMatch.value.toIntOrNull()
            patterns.add("שנת לידה אפשרית: $birthYear")
        }
        val shortYear = Regex("(?<![0-9])(\\d{2})$").find(username)
        if (shortYear != null && birthYear == null) {
            val y = shortYear.groupValues[1].toIntOrNull()
            if (y != null) {
                birthYear = if (y > 50) 1900 + y else 2000 + y
                patterns.add("שנת לידה אפשרית: $birthYear (מ-${shortYear.value})")
            }
        }

        val cities = mapOf("nyc" to "New York", "la" to "Los Angeles", "tlv" to "Tel Aviv", "jlm" to "Jerusalem", "london" to "London", "paris" to "Paris", "berlin" to "Berlin", "tokyo" to "Tokyo")
        for ((code, city) in cities) {
            if (username.contains(code, ignoreCase = true)) {
                location = city
                patterns.add("מיקום אפשרי: $city")
                break
            }
        }

        if (username.matches(Regex("[a-z]+[0-9]+"))) patterns.add("תבנית: שם + מספרים")
        if (username.contains("_")) patterns.add("תבנית: מילים מופרדות בקו תחתון")
        if (username.contains(".")) patterns.add("תבנית: מילים מופרדות בנקודה")
        if (username == username.lowercase()) patterns.add("הכל באותיות קטנות")
        if (username.any { it.isUpperCase() }) patterns.add("מכיל אותיות גדולות")
        if (username.length < 6) patterns.add("שם קצר - ייתכן OG username")
        if (username.length > 15) patterns.add("שם ארוך - ייתכן שם מלא")

        val prefixes = mapOf("the" to "שימוש בתחילית 'the'", "real" to "שימוש בתחילית 'real'", "official" to "חשבון 'רשמי'", "its" to "שימוש בתחילית 'its'", "im" to "שימוש בתחילית 'im'", "not" to "שימוש בתחילית 'not'", "iam" to "שימוש בתחילית 'iam'")
        for ((prefix, desc) in prefixes) {
            if (username.startsWith(prefix, ignoreCase = true)) {
                patterns.add(desc)
                if (firstName == null) firstName = username.removePrefix(prefix).replaceFirstChar { it.uppercase() }
            }
        }

        return UsernameAnalysis(username, firstName, lastName, birthYear, location, patterns)
    }

    fun generateEmailPatterns(firstName: String, lastName: String, domain: String): List<EmailPattern> {
        val f = firstName.lowercase()
        val l = lastName.lowercase()
        val fi = f.first()
        val li = l.first()
        return listOf(
            EmailPattern("first.last", "$f.$l@$domain", 0.9f),
            EmailPattern("firstlast", "$f$l@$domain", 0.85f),
            EmailPattern("first_last", "${f}_${l}@$domain", 0.8f),
            EmailPattern("f.last", "$fi.$l@$domain", 0.75f),
            EmailPattern("flast", "$fi$l@$domain", 0.75f),
            EmailPattern("first.l", "$f.$li@$domain", 0.7f),
            EmailPattern("firstl", "$f$li@$domain", 0.7f),
            EmailPattern("last.first", "$l.$f@$domain", 0.65f),
            EmailPattern("first", "$f@$domain", 0.6f),
            EmailPattern("last", "$l@$domain", 0.5f),
            EmailPattern("fl", "$fi$li@$domain", 0.4f),
            EmailPattern("first-last", "$f-$l@$domain", 0.6f),
        )
    }

    fun calculateFakeProfileScore(
        hasProfilePic: Boolean,
        accountAge: String?,
        followersCount: Int?,
        followingCount: Int?,
        postCount: Int?,
        bioLength: Int?,
        hasLink: Boolean,
        isVerified: Boolean,
        username: String
    ): FakeProfileScore {
        var score = 50
        val reasons = mutableListOf<String>()

        if (!hasProfilePic) { score += 20; reasons.add("אין תמונת פרופיל") }
        if (isVerified) { score -= 30; reasons.add("חשבון מאומת") }
        if (followersCount != null && followersCount < 10) { score += 15; reasons.add("מעט עוקבים ($followersCount)") }
        if (followingCount != null && followingCount > 5000) { score += 10; reasons.add("עוקב אחרי הרבה ($followingCount)") }
        if (postCount != null && postCount == 0) { score += 20; reasons.add("אין פוסטים") }
        if (bioLength != null && bioLength == 0) { score += 10; reasons.add("ביו ריק") }
        if (bioLength != null && bioLength > 0) { score -= 5; reasons.add("יש ביו") }
        if (hasLink) { score -= 5; reasons.add("יש לינק בביו") }
        if (username.matches(Regex(".*\\d{5,}.*"))) { score += 15; reasons.add("הרבה מספרים בשם ($username)") }
        if (followersCount != null && followingCount != null && followingCount > 0) {
            val ratio = followersCount.toFloat() / followingCount
            if (ratio < 0.01f) { score += 10; reasons.add("יחס עוקבים/נעקבים חשוד") }
        }

        score = score.coerceIn(0, 100)
        val level = when {
            score >= 70 -> "סיכון גבוה - כנראה מזויף"
            score >= 40 -> "סיכון בינוני - חשוד"
            else -> "סיכון נמוך - נראה אמיתי"
        }

        return FakeProfileScore(score, reasons, level)
    }

    fun analyzeImageForensics(context: Context, uri: Uri): ImageForensicsResult {
        val manipulations = mutableListOf<String>()
        var editConfidence = 0f
        var qualityScore = 100f
        var bitmapWidth = 0
        var bitmapHeight = 0

        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap != null) {
                bitmapWidth = bitmap.width
                bitmapHeight = bitmap.height

                if (bitmap.width % 16 != 0 || bitmap.height % 16 != 0) {
                    manipulations.add("גודל תמונה לא סטנדרטי - ייתכן חיתוך")
                    editConfidence += 0.1f
                }

                val pixels = IntArray(minOf(100 * 100, bitmap.width * bitmap.height))
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, minOf(100, bitmap.width), minOf(100, bitmap.height))

                var uniformCount = 0
                for (i in 1 until pixels.size) {
                    if (pixels[i] == pixels[i - 1]) uniformCount++
                }
                val uniformRatio = uniformCount.toFloat() / pixels.size
                if (uniformRatio > 0.5f) {
                    manipulations.add("אזורים אחידים מדי - ייתכן ריטוש")
                    editConfidence += 0.2f
                }

                qualityScore = when {
                    bitmap.width >= 2000 -> 90f
                    bitmap.width >= 1000 -> 70f
                    bitmap.width >= 500 -> 50f
                    else -> 30f
                }
                if (qualityScore < 50) {
                    manipulations.add("רזולוציה נמוכה (${bitmap.width}x${bitmap.height})")
                }

                bitmap.recycle()
            }
        } catch (_: Exception) {
            manipulations.add("לא ניתן לנתח את התמונה")
        }

        val exifData = com.sherlock.app.util.ExifHelper.extractExifData(context, uri)
        val metadataConsistency = exifData.isNotEmpty()
        if (!metadataConsistency) {
            manipulations.add("חסר מטא-דאטה - ייתכן שהתמונה עברה עיבוד")
            editConfidence += 0.15f
        }
        val softwareTag = exifData["תוכנה"] ?: ""
        if (softwareTag.isNotEmpty()) {
            if (softwareTag.contains("Photoshop", true) || softwareTag.contains("GIMP", true) || softwareTag.contains("Lightroom", true)) {
                manipulations.add("נערכה בתוכנת עריכה: $softwareTag")
                editConfidence += 0.3f
            }
        }

        val (aiLikelihood, aiSignals) = detectAiGenerationSignals(bitmapWidth, bitmapHeight, exifData, softwareTag)

        return ImageForensicsResult(
            isLikelyEdited = editConfidence > 0.3f,
            editConfidence = editConfidence.coerceAtMost(1f),
            detectedManipulations = manipulations,
            metadataConsistency = metadataConsistency,
            qualityScore = qualityScore,
            aiGeneratedLikelihood = aiLikelihood,
            aiGeneratedSignals = aiSignals
        )
    }

    private fun detectAiGenerationSignals(
        width: Int,
        height: Int,
        exifData: Map<String, String>,
        softwareTag: String
    ): Pair<Float, List<String>> {
        val signals = mutableListOf<String>()
        var score = 0f

        val aiToolKeywords = listOf(
            "Stable Diffusion", "Midjourney", "DALL-E", "DALL·E", "DALLE",
            "NightCafe", "Leonardo.Ai", "Adobe Firefly", "RunwayML", "DreamStudio", "ComfyUI"
        )
        val matchedTool = aiToolKeywords.firstOrNull { softwareTag.contains(it, ignoreCase = true) }
        if (matchedTool != null) {
            signals.add("תגית תוכנה מצביעה על כלי ייצור תמונה: $matchedTool")
            score += 0.6f
        }

        if (width > 0 && height > 0) {
            val isPowerOfTwo = { n: Int -> n >= 256 && (n and (n - 1)) == 0 }
            val commonGenSizes = setOf(512, 576, 640, 768, 896, 1024, 1152, 1280, 1536, 2048)
            if ((isPowerOfTwo(width) && isPowerOfTwo(height)) || (width in commonGenSizes && height in commonGenSizes)) {
                signals.add("רזולוציה אופיינית לכלי ייצור תמונה (${width}x${height})")
                score += 0.2f
            }
        }

        val hasCameraInfo = exifData.containsKey("יצרן מצלמה") || exifData.containsKey("דגם מצלמה")
        val hasShootingInfo = exifData.containsKey("ISO") || exifData.containsKey("צמצם") || exifData.containsKey("חשיפה") || exifData.containsKey("אורך מוקד")
        if (!hasCameraInfo && !hasShootingInfo && exifData.isEmpty()) {
            signals.add("היעדר מוחלט של מטא-דאטת מצלמה (ייצור/צילום מסך)")
            score += 0.1f
        }

        return score.coerceAtMost(1f) to signals
    }
}
