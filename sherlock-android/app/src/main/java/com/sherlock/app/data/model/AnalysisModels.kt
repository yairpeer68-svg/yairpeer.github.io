package com.sherlock.app.data.model

data class OcrResult(
    val fullText: String,
    val detectedUsernames: List<String>,
    val detectedEmails: List<String>,
    val detectedPhones: List<String>,
    val detectedUrls: List<String>,
    val language: String = ""
)

data class ImageForensicsResult(
    val isLikelyEdited: Boolean,
    val editConfidence: Float,
    val detectedManipulations: List<String>,
    val metadataConsistency: Boolean,
    val qualityScore: Float
)

data class FakeProfileScore(
    val score: Int,
    val reasons: List<String>,
    val riskLevel: String
)

data class UsernameAnalysis(
    val username: String,
    val possibleFirstName: String?,
    val possibleLastName: String?,
    val possibleBirthYear: Int?,
    val possibleLocation: String?,
    val patterns: List<String>
)

data class ProfileAnalysis(
    val platform: String,
    val username: String,
    val estimatedActivity: String,
    val accountAge: String?,
    val contentType: String?,
    val engagementLevel: String?,
    val bioLinks: List<String>,
    val relatedUsernames: List<String>
)

data class PasteResult(
    val source: String,
    val title: String,
    val url: String,
    val snippet: String,
    val date: String
)

data class WaybackResult(
    val url: String,
    val timestamp: String,
    val archiveUrl: String
)

data class EmailPattern(
    val pattern: String,
    val email: String,
    val confidence: Float
)

data class IpGeoResult(
    val ip: String,
    val country: String,
    val city: String,
    val region: String,
    val isp: String,
    val lat: Double,
    val lon: Double,
    val timezone: String
)
