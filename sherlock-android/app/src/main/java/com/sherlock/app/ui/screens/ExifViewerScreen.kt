package com.sherlock.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sherlock.app.util.ExifHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExifViewerScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var exifData by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            imageUri = it
            exifData = ExifHelper.extractExifData(context, it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EXIF Viewer", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Text("הצגת מטא-דאטה מוסתרת בתמונות: מיקום צילום, מכשיר, תאריך ועוד", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = { launcher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text("בחר תמונה")
                }
            }

            imageUri?.let { uri ->
                item {
                    AsyncImage(
                        model = uri,
                        contentDescription = "תמונה",
                        modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            if (exifData.isNotEmpty()) {
                item {
                    Text("נמצאו ${exifData.size} שדות מטא-דאטה:", fontWeight = FontWeight.Medium)
                }

                items(exifData.entries.toList()) { (key, value) ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        onClick = {
                            if (value.startsWith("https://")) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
                            }
                        }
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            val icon = when {
                                key.contains("תאריך") -> Icons.Default.CalendarMonth
                                key.contains("מצלמה") || key.contains("דגם") -> Icons.Default.CameraAlt
                                key.contains("מיקום") || key.contains("רוחב") || key.contains("אורך") -> Icons.Default.LocationOn
                                key.contains("רוחב") && !key.contains("קו") -> Icons.Default.AspectRatio
                                else -> Icons.Default.Info
                            }
                            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(key, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (value.startsWith("https://")) {
                                Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            } else if (imageUri != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Text("לא נמצא מידע EXIF בתמונה.\nייתכן שהמטא-דאטה הוסר.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
