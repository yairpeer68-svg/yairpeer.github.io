package com.sherlock.app.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.sherlock.app.data.model.FaceCompareState
import com.sherlock.app.data.model.FaceDetails
import com.sherlock.app.ui.theme.SherlockError
import com.sherlock.app.ui.theme.SherlockSuccess
import com.sherlock.app.util.FaceHeuristics
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaceCompareScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(FaceCompareState()) }

    val launcher1 = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { state = state.copy(image1Uri = it, similarityScore = -1f) }
    }
    val launcher2 = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { state = state.copy(image2Uri = it, similarityScore = -1f) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("השוואת פנים", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ImageSelector("תמונה 1", state.image1Uri) { launcher1.launch("image/*") }
                Icon(Icons.Default.CompareArrows, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                ImageSelector("תמונה 2", state.image2Uri) { launcher2.launch("image/*") }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    if (state.image1Uri != null && state.image2Uri != null) {
                        state = state.copy(isComparing = true, errorMessage = null)
                        scope.launch {
                            try {
                                val r = compareFaces(context, state.image1Uri!!, state.image2Uri!!)
                                state = state.copy(
                                    isComparing = false, similarityScore = r.score, face1Details = r.face1,
                                    face2Details = r.face2, facesInImage1 = r.count1, facesInImage2 = r.count2,
                                    errorMessage = r.error
                                )
                            } catch (e: Exception) {
                                state = state.copy(isComparing = false, errorMessage = "שגיאה: ${e.message}")
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = state.image1Uri != null && state.image2Uri != null && !state.isComparing
            ) {
                if (state.isComparing) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("השווה פנים")
            }

            Spacer(Modifier.height(24.dp))

            if (state.facesInImage1 > 1 || state.facesInImage2 > 1) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Groups, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "זוהו ${state.facesInImage1} פנים בתמונה 1 ו-${state.facesInImage2} פנים בתמונה 2 - מוצגת ההתאמה הטובה ביותר מבין כל הזוגות",
                            fontSize = 12.sp
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (state.similarityScore >= 0) {
                val scorePercent = (state.similarityScore * 100).toInt()
                val color = when {
                    scorePercent >= 70 -> SherlockSuccess
                    scorePercent >= 40 -> Color(0xFFFFB74D)
                    else -> SherlockError
                }
                val label = when {
                    scorePercent >= 70 -> "התאמה גבוהה - כנראה אותו אדם"
                    scorePercent >= 40 -> "התאמה חלקית - ייתכן אותו אדם"
                    else -> "התאמה נמוכה - כנראה אנשים שונים"
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("$scorePercent%", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = color)
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = state.similarityScore,
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = color
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(label, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
                    }
                }

                Spacer(Modifier.height(16.dp))

                state.face1Details?.let { face ->
                    Text("פרטי תמונה 1:", fontWeight = FontWeight.Medium)
                    Text("חיוך: ${"%.0f".format((face.smilingProbability.coerceAtLeast(0f)) * 100)}% · הבעה: ${face.expressionLabel}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.face2Details?.let { face ->
                    Spacer(Modifier.height(8.dp))
                    Text("פרטי תמונה 2:", fontWeight = FontWeight.Medium)
                    Text("חיוך: ${"%.0f".format((face.smilingProbability.coerceAtLeast(0f)) * 100)}% · הבעה: ${face.expressionLabel}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            state.errorMessage?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(12.dp))
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageSelector(label: String, uri: Uri?, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Card(onClick = onClick, shape = CircleShape, modifier = Modifier.size(120.dp)) {
            if (uri != null) {
                AsyncImage(model = uri, contentDescription = label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.AddAPhoto, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun faceSimilarity(face1: Face, face2: Face): Float {
    val bb1 = face1.boundingBox
    val bb2 = face2.boundingBox
    val ratio1 = bb1.width().toFloat() / bb1.height()
    val ratio2 = bb2.width().toFloat() / bb2.height()
    val ratioSim = 1f - abs(ratio1 - ratio2).coerceAtMost(1f)

    val angleSim = 1f - (abs(face1.headEulerAngleY - face2.headEulerAngleY) / 90f).coerceAtMost(1f)
    val zAngleSim = 1f - (abs(face1.headEulerAngleZ - face2.headEulerAngleZ) / 90f).coerceAtMost(1f)

    return (ratioSim * 0.4f + angleSim * 0.3f + zAngleSim * 0.3f).coerceIn(0f, 1f)
}

private fun toFaceDetails(face: Face): FaceDetails = FaceDetails(
    index = 0,
    confidence = 0f,
    smilingProbability = face.smilingProbability ?: -1f,
    leftEyeOpenProbability = face.leftEyeOpenProbability ?: -1f,
    rightEyeOpenProbability = face.rightEyeOpenProbability ?: -1f,
    estimatedAge = FaceHeuristics.estimateAgeRange(face),
    expressionLabel = FaceHeuristics.estimateExpression(face)
)

private data class FaceCompareResult(
    val score: Float, val face1: FaceDetails?, val face2: FaceDetails?,
    val error: String?, val count1: Int, val count2: Int
)

private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

private suspend fun compareFaces(context: Context, uri1: Uri, uri2: Uri): FaceCompareResult {
    val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .build()
    val detector = FaceDetection.getClient(options)
    return try {
        val image1 = withContext(Dispatchers.IO) { InputImage.fromFilePath(context, uri1) }
        val faces1 = detector.process(image1).await()
        if (faces1.isEmpty()) return FaceCompareResult(-1f, null, null, "לא זוהו פנים בתמונה 1", 0, 0)

        val image2 = withContext(Dispatchers.IO) { InputImage.fromFilePath(context, uri2) }
        val faces2 = detector.process(image2).await()
        if (faces2.isEmpty()) return FaceCompareResult(-1f, toFaceDetails(faces1[0]), null, "לא זוהו פנים בתמונה 2", faces1.size, 0)

        var bestScore = -1f
        var bestFace1 = faces1[0]
        var bestFace2 = faces2[0]
        for (f1 in faces1) {
            for (f2 in faces2) {
                val sim = faceSimilarity(f1, f2)
                if (sim > bestScore) { bestScore = sim; bestFace1 = f1; bestFace2 = f2 }
            }
        }
        FaceCompareResult(bestScore, toFaceDetails(bestFace1), toFaceDetails(bestFace2), null, faces1.size, faces2.size)
    } finally {
        detector.close()
    }
}
