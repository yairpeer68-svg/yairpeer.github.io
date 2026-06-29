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
import com.sherlock.app.data.model.FaceSearchState
import com.sherlock.app.data.model.ImageSearchResult
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaceSearchScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(FaceSearchState()) }
    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            state = state.copy(selectedImageUri = it, isAnalyzing = true, errorMessage = null)
            detectFace(context, it) { hasFace ->
                state = if (hasFace) {
                    state.copy(
                        isAnalyzing = false,
                        faceDetected = true,
                        searchEngines = buildSearchEngines()
                    )
                } else {
                    state.copy(
                        isAnalyzing = false,
                        faceDetected = false,
                        errorMessage = "לא זוהו פנים בתמונה. נסה תמונה אחרת."
                    )
                }
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraImageUri != null) {
            state = state.copy(selectedImageUri = cameraImageUri, isAnalyzing = true, errorMessage = null)
            detectFace(context, cameraImageUri!!) { hasFace ->
                state = if (hasFace) {
                    state.copy(
                        isAnalyzing = false,
                        faceDetected = true,
                        searchEngines = buildSearchEngines()
                    )
                } else {
                    state.copy(
                        isAnalyzing = false,
                        faceDetected = false,
                        errorMessage = "לא זוהו פנים בתמונה. נסה תמונה אחרת."
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("חיפוש לפי תמונה", fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
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

                if (state.selectedImageUri != null) {
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .clip(CircleShape)
                            .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    ) {
                        AsyncImage(
                            model = state.selectedImageUri,
                            contentDescription = "תמונה נבחרת",
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
                        Icon(
                            imageVector = Icons.Default.Face,
                            contentDescription = null,
                            modifier = Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { galleryLauncher.launch("image/*") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("גלריה")
                    }

                    Button(
                        onClick = {
                            val file = File(context.cacheDir, "sherlock_capture_${System.currentTimeMillis()}.jpg")
                            cameraImageUri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file
                            )
                            cameraLauncher.launch(cameraImageUri!!)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("מצלמה")
                    }
                }
            }

            if (state.isAnalyzing) {
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "מזהה פנים...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            state.errorMessage?.let { error ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            if (state.faceDetected) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "פנים זוהו בהצלחה! בחר מנוע חיפוש:",
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                items(state.searchEngines) { engine ->
                    SearchEngineCard(engine = engine, context = context)
                }
            }

            if (state.selectedImageUri == null) {
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "בחר תמונה מהגלריה או צלם תמונה חדשה.\nהאפליקציה תזהה פנים ותאפשר לך\nלחפש אותן במנועי חיפוש תמונות.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 24.sp
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun SearchEngineCard(engine: ImageSearchResult, context: Context) {
    Card(
        onClick = {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(engine.searchUrl))
            context.startActivity(intent)
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.TravelExplore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = engine.engineName,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Icon(
                imageVector = Icons.Default.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun detectFace(context: Context, imageUri: Uri, onResult: (Boolean) -> Unit) {
    val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setMinFaceSize(0.1f)
        .build()

    val detector = FaceDetection.getClient(options)

    try {
        val image = InputImage.fromFilePath(context, imageUri)
        detector.process(image)
            .addOnSuccessListener { faces ->
                onResult(faces.isNotEmpty())
            }
            .addOnFailureListener {
                onResult(false)
            }
    } catch (_: Exception) {
        onResult(false)
    }
}

private fun buildSearchEngines(): List<ImageSearchResult> {
    return listOf(
        ImageSearchResult(
            engineName = "Google Reverse Image Search",
            searchUrl = "https://images.google.com/"
        ),
        ImageSearchResult(
            engineName = "Yandex Images (הכי טוב לפנים)",
            searchUrl = "https://yandex.com/images/"
        ),
        ImageSearchResult(
            engineName = "TinEye",
            searchUrl = "https://tineye.com/"
        ),
        ImageSearchResult(
            engineName = "Bing Visual Search",
            searchUrl = "https://www.bing.com/visualsearch"
        ),
        ImageSearchResult(
            engineName = "PimEyes (זיהוי פנים מתקדם)",
            searchUrl = "https://pimeyes.com/"
        ),
        ImageSearchResult(
            engineName = "FaceCheck.ID",
            searchUrl = "https://facecheck.id/"
        ),
        ImageSearchResult(
            engineName = "Social Catfish",
            searchUrl = "https://socialcatfish.com/"
        ),
        ImageSearchResult(
            engineName = "Search4faces",
            searchUrl = "https://search4faces.com/"
        )
    )
}
