package com.sherlock.app.data

data class SiteConfig(
    val name: String,
    val url: String,
    val errorType: String,
    val errorMsg: String,
    val category: String
)

data class SearchResult(
    val site: SiteConfig,
    val found: Boolean,
    val profileUrl: String,
    val error: String? = null
)
