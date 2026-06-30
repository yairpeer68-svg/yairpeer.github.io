package com.sherlock.app.data.model

data class BreachInfo(
    val name: String,
    val domain: String,
    val breachDate: String,
    val dataClasses: List<String>,
    val description: String
)

data class HashIdentification(
    val hash: String,
    val possibleTypes: List<String>,
    val knownPlaintext: String? = null
)

data class PasteResult(
    val id: String,
    val text: String,
    val date: String
) {
    val url: String get() = "https://pastebin.com/$id"
}

data class WaybackSnapshot(
    val timestamp: String,
    val url: String
)

data class WaybackAvailability(
    val available: Boolean,
    val url: String?,
    val timestamp: String?
)

data class ExposureReport(
    val query: String,
    val isEmail: Boolean,
    val hibpChecked: Boolean,
    val breaches: List<BreachInfo>,
    val pastes: List<PasteResult>
) {
    val totalExposures: Int get() = breaches.size + pastes.size
    val riskLevel: String get() = when {
        breaches.isNotEmpty() -> "גבוה"
        pastes.isNotEmpty() -> "בינוני"
        else -> "נמוך"
    }
}
