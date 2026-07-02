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
