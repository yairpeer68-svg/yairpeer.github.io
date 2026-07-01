package com.sherlock.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.sherlock.app.data.model.ImageSearchResult
import com.sherlock.app.ui.components.openUrl
import com.sherlock.app.util.ExifHelper
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaceSearchScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }
    var exifData by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showExif by remember { mutableStateOf(false) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            selectedImageUri = it
            exifData = ExifHelper.extractExifData(context, it)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            cameraImageUri?.let {
                selectedImageUri = it
                exifData = ExifHelper.extractExifData(context, it)
            }
        }
    }

    val searchEngines = remember { buildReverseSearchEngines() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("חיפוש לפי תמונה", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה")
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

                if (selectedImageUri != null) {
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .clip(CircleShape)
                            .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    ) {
                        AsyncImage(
                            model = selectedImageUri,
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
                            Icons.Default.Face,
                            null,
                            Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
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
                        Icon(Icons.Default.PhotoLibrary, null)
                        Spacer(Modifier.width(8.dp))
                        Text("גלריה")
                    }
                    Button(
                        onClick = {
                            val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file
                            )
                            cameraImageUri = uri
                            cameraLauncher.launch(uri)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, null)
                        Spacer(Modifier.width(8.dp))
                        Text("מצלמה")
                    }
                }
            }

            if (selectedImageUri != null) {
                item {
                    Text(
                        "בחר מנוע חיפוש הפוך:",
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                items(searchEngines) { engine ->
                    Card(
                        onClick = { openUrl(context, engine.searchUrl) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.TravelExplore,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(engine.engineName, fontWeight = FontWeight.Medium)
                                if (engine.description.isNotEmpty()) {
                                    Text(
                                        engine.description,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (engine.isPremium) {
                                AssistChip(
                                    onClick = {},
                                    label = { Text("Premium", fontSize = 10.sp) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Star, null, Modifier.size(14.dp))
                                    }
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Icon(
                                Icons.Default.OpenInNew,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                if (exifData.isNotEmpty()) {
                    item {
                        OutlinedButton(
                            onClick = { showExif = !showExif },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Info, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (showExif) "הסתר EXIF" else "הצג מידע EXIF (${exifData.size})")
                        }
                    }

                    if (showExif) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    exifData.forEach { (key, value) ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 2.dp)
                                        ) {
                                            Text(
                                                "$key: ",
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 13.sp
                                            )
                                            Text(
                                                value,
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "בחר תמונה מהגלריה או צלם תמונה חדשה.\nאחר מכן בחר מנוע חיפוש הפוך\nכדי לזהות את האדם בתמונה.",
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

private fun buildReverseSearchEngines(): List<ImageSearchResult> = listOf(
    ImageSearchResult("Google Images", "https://images.google.com/", "חיפוש תמונות הפוך של גוגל"),
    ImageSearchResult("Yandex Images", "https://yandex.com/images/", "הכי טוב לזיהוי פנים"),
    ImageSearchResult("TinEye", "https://tineye.com/", "מנוע חיפוש תמונות מתמחה"),
    ImageSearchResult("Bing Visual Search", "https://www.bing.com/visualsearch", "חיפוש ויזואלי של מיקרוסופט"),
    ImageSearchResult("PimEyes", "https://pimeyes.com/", "זיהוי פנים מתקדם", isPremium = true),
    ImageSearchResult("FaceCheck.ID", "https://facecheck.id/", "חיפוש פנים ברשתות חברתיות", isPremium = true),
    ImageSearchResult("Social Catfish", "https://socialcatfish.com/", "חיפוש זהויות ופרופילים מזויפים"),
    ImageSearchResult("Search4faces", "https://search4faces.com/", "חיפוש בפרופילי VK ו-OK.ru"),
)
