package com.sherlock.app.data

data class SiteConfig(
    val name: String,
    val url: String,
    val errorType: String,            // "status_code" | "message" | "response_url"
    val category: String = "",
    val errorMsg: List<String>? = null,
    val errorUrl: String? = null,
    val regexCheck: String? = null,
    val urlProbe: String? = null
)

data class SearchResult(
    val site: SiteConfig,
    val found: Boolean,
    val profileUrl: String,
    val error: String? = null
)

/** A face match returned by the FaceCheck.ID API. */
data class FaceMatch(
    val url: String,
    val score: Int,       // 0-100 confidence
    val thumb: String     // data: URI base64 thumbnail
)

/** Outcome of a face search — either matches or a human-readable error. */
data class FaceSearchResult(
    val matches: List<FaceMatch> = emptyList(),
    val error: String? = null
)
