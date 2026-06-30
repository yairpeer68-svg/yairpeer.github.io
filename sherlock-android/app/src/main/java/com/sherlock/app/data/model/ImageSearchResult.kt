package com.sherlock.app.data.model

import android.net.Uri

data class ImageSearchResult(
    val engineName: String,
    val searchUrl: String,
    val description: String = "",
    val isPremium: Boolean = false
)

data class FaceSearchState(
    val selectedImageUri: Uri? = null,
    val isAnalyzing: Boolean = false,
    val facesDetected: Int = 0,
    val faceDetails: List<FaceDetails> = emptyList(),
    val searchEngines: List<ImageSearchResult> = emptyList(),
    val exifData: Map<String, String> = emptyMap(),
    val errorMessage: String? = null
)

data class FaceDetails(
    val index: Int,
    val confidence: Float,
    val smilingProbability: Float = -1f,
    val leftEyeOpenProbability: Float = -1f,
    val rightEyeOpenProbability: Float = -1f,
    val estimatedAge: String = "",
    val expressionLabel: String = "",
    val hasGlasses: Boolean = false,
    val headRotationY: Float = 0f,
    val headRotationZ: Float = 0f,
    val boundingBoxLeft: Int = 0,
    val boundingBoxTop: Int = 0,
    val boundingBoxWidth: Int = 0,
    val boundingBoxHeight: Int = 0
)

data class FaceCompareState(
    val image1Uri: Uri? = null,
    val image2Uri: Uri? = null,
    val isComparing: Boolean = false,
    val similarityScore: Float = -1f,
    val face1Details: FaceDetails? = null,
    val face2Details: FaceDetails? = null,
    val facesInImage1: Int = 0,
    val facesInImage2: Int = 0,
    val errorMessage: String? = null
)

data class UsernameSearchState(
    val query: String = "",
    val searchType: SearchType = SearchType.USERNAME,
    val isSearching: Boolean = false,
    val results: List<SearchResult> = emptyList(),
    val progress: Float = 0f,
    val totalSites: Int = 0,
    val checkedSites: Int = 0,
    val isIncognito: Boolean = false,
    val errorMessage: String? = null
)
