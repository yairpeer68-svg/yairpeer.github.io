package com.sherlock.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.sherlock.app.data.model.FaceDetails
import com.sherlock.app.data.model.FaceSearchState
import com.sherlock.app.data.model.ImageSearchResult
import com.sherlock.app.ui.components.ScanningAnimation
import com.sherlock.app.ui.components.openUrl
import com.sherlock.app.ui.theme.SherlockSuccess
import com.sherlock.app.util.ExifHelper
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaceSearchScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(FaceSearchState()) }
    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }
    var showExif by remember { mutableStateOf(false) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { processImage(context, it) { newState -> state = newState } }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && cameraImageUri != null) {
            processImage(context, cameraImageUri!!) { newState -> state = newState }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("חיפוש לפי תמונה", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))

                Box(contentAlignment = Alignment.Center) {
                    if (state.selectedImageUri != null) {
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .clip(CircleShape)
                                .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        ) {
                            AsyncImage(
                                model = state.selectedImageUri,
                                contentDescription = "תמונה",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Face, null, Modifier.size(80.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        }
                    }

                    if (state.isAnalyzing) {
                        ScanningAnimation(modifier = Modifier.size(200.dp))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { galleryLauncher.launch("image/*") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text("גלריה")
                    }
                    Button(
                        onClick = {
                            val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                            cameraImageUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            cameraLauncher.launch(cameraImageUri!!)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(8.dp)); Text("מצלמה")
                    }
                }
            }

            if (state.isAnalyzing) {
                item {
                    CircularProgressIndicator()
                    Text("מזהה פנים...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            state.errorMessage?.let { error ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(12.dp))
                            Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            if (state.facesDetected > 0) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = SherlockSuccess)
                                Spacer(Modifier.width(12.dp))
                                Text("זוהו ${state.facesDetected} פנים!", fontWeight = FontWeight.Bold)
                            }

                            state.faceDetails.forEachIndexed { index, face ->
                                Spacer(Modifier.height(8.dp))
                                Text("פנים ${index + 1}:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                if (face.smilingProbability >= 0) {
                                    Text("  חיוך: ${"%.0f".format(face.smilingProbability * 100)}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (face.leftEyeOpenProbability >= 0) {
                                    Text("  עין שמאל פקוחה: ${"%.0f".format(face.leftEyeOpenProbability * 100)}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (face.rightEyeOpenProbability >= 0) {
                                    Text("  עין ימין פקוחה: ${"%.0f".format(face.rightEyeOpenProbability * 100)}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("  סיבוב ראש: Y=${"%.1f".format(face.headRotationY)}° Z=${"%.1f".format(face.headRotationZ)}°", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                item {
                    Text("בחר מנוע חיפוש:", fontWeight = FontWeight.Medium, modifier = Modifier.fillMaxWidth())
                }

                items(state.searchEngines) { engine ->
                    Card(
                        onClick = { openUrl(context, engine.searchUrl) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.TravelExplore, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(engine.engineName, fontWeight = FontWeight.Medium)
                                if (engine.description.isNotEmpty()) {
                                    Text(engine.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (engine.isPremium) {
                                AssistChip(onClick = {}, label = { Text("Premium", fontSize = 10.sp) }, leadingIcon = { Icon(Icons.Default.Star, null, Modifier.size(14.dp)) })
                            }
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            if (state.exifData.isNotEmpty()) {
                item {
                    OutlinedButton(
                        onClick = { showExif = !showExif },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Info, null); Spacer(Modifier.width(8.dp))
                        Text(if (showExif) "הסתר EXIF" else "הצג מידע EXIF (${state.exifData.size})")
                    }
                }

                if (showExif) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                state.exifData.forEach { (key, value) ->
                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                        Text("$key: ", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                        Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (state.selectedImageUri == null) {
                item {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "בחר תמונה מהגלריה או צלם תמונה חדשה.\nהאפליקציה תזהה פנים, תציג פרטים,\nותאפשר חיפוש ב-8 מנועי חיפוש.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 24.sp
                    )
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

private fun processImage(context: Context, uri: Uri, onState: (FaceSearchState) -> Unit) {
    onState(FaceSearchState(selectedImageUri = uri, isAnalyzing = true))

    val exifData = ExifHelper.extractExifData(context, uri)

    val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .setMinFaceSize(0.1f)
        .build()

    val detector = FaceDetection.getClient(options)

    try {
        val image = InputImage.fromFilePath(context, uri)
        detector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val details = faces.mapIndexed { index, face ->
                        FaceDetails(
                            index = index,
                            confidence = face.trackingId?.toFloat() ?: 0f,
                            smilingProbability = face.smilingProbability ?: -1f,
                            leftEyeOpenProbability = face.leftEyeOpenProbability ?: -1f,
                            rightEyeOpenProbability = face.rightEyeOpenProbability ?: -1f,
                            headRotationY = face.headEulerAngleY,
                            headRotationZ = face.headEulerAngleZ,
                            boundingBoxLeft = face.boundingBox.left,
                            boundingBoxTop = face.boundingBox.top,
                            boundingBoxWidth = face.boundingBox.width(),
                            boundingBoxHeight = face.boundingBox.height()
                        )
                    }
                    onState(FaceSearchState(
                        selectedImageUri = uri,
                        facesDetected = faces.size,
                        faceDetails = details,
                        searchEngines = buildSearchEngines(),
                        exifData = exifData
                    ))
                } else {
                    onState(FaceSearchState(
                        selectedImageUri = uri,
                        exifData = exifData,
                        errorMessage = "לא זוהו פנים בתמונה. נסה תמונה אחרת."
                    ))
                }
            }
            .addOnFailureListener {
                onState(FaceSearchState(selectedImageUri = uri, exifData = exifData, errorMessage = "שגיאה בזיהוי פנים: ${it.message}"))
            }
    } catch (e: Exception) {
        onState(FaceSearchState(selectedImageUri = uri, exifData = exifData, errorMessage = "שגיאה: ${e.message}"))
    }
}

private fun buildSearchEngines(): List<ImageSearchResult> = listOf(
    ImageSearchResult("Google Reverse Image Search", "https://images.google.com/", "חיפוש תמונות הפוך של גוגל"),
    ImageSearchResult("Yandex Images", "https://yandex.com/images/", "הכי טוב לזיהוי פנים"),
    ImageSearchResult("TinEye", "https://tineye.com/", "מנוע חיפוש תמונות מתמחה"),
    ImageSearchResult("Bing Visual Search", "https://www.bing.com/visualsearch", "חיפוש ויזואלי של מיקרוסופט"),
    ImageSearchResult("PimEyes", "https://pimeyes.com/", "זיהוי פנים מתקדם", isPremium = true),
    ImageSearchResult("FaceCheck.ID", "https://facecheck.id/", "חיפוש פנים ברשתות חברתיות", isPremium = true),
    ImageSearchResult("Social Catfish", "https://socialcatfish.com/", "חיפוש זהויות ופרופילים מזויפים"),
    ImageSearchResult("Search4faces", "https://search4faces.com/", "חיפוש בפרופילי VK ו-OK.ru"),
)
