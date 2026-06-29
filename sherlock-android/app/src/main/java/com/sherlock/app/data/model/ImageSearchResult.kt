package com.sherlock.app.data.model

import android.net.Uri

data class ImageSearchResult(
    val engineName: String,
    val searchUrl: String,
    val iconResName: String = ""
)

data class FaceSearchState(
    val selectedImageUri: Uri? = null,
    val isAnalyzing: Boolean = false,
    val faceDetected: Boolean = false,
    val searchEngines: List<ImageSearchResult> = emptyList(),
    val errorMessage: String? = null
)

data class UsernameSearchState(
    val username: String = "",
    val isSearching: Boolean = false,
    val results: List<SearchResult> = emptyList(),
    val progress: Float = 0f,
    val totalSites: Int = 0,
    val checkedSites: Int = 0,
    val errorMessage: String? = null
)
