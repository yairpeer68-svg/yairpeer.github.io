package com.sherlock.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.data.model.ImageHash
import com.sherlock.app.util.ImageHashUtil
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageHashScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val library by db.imageHashDao().getAll().collectAsState(initial = emptyList())

    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var labelInput by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<Pair<ImageHash, Int>>>(emptyList()) }
    var queryImagePath by remember { mutableStateOf<String?>(null) }

    val addLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) pendingUri = uri
    }

    val checkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val hash = ImageHashUtil.computeAverageHash(context, uri)
            if (hash != null) {
                matches = library.map { it to ImageHashUtil.similarityPercent(hash, it.hash) }
                    .sortedByDescending { it.second }
                queryImagePath = uri.toString()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("התאמת תמונות מקומית") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "השוואת תמונות לפי טביעת אצבע ויזואלית (Average Hash) - הכל מתבצע באופן מקומי על המכשיר, ללא שרת חיצוני.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("בדוק תמונה מול הספרייה", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Button(onClick = { checkLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.ImageSearch, null)
                            Spacer(Modifier.width(8.dp))
                            Text("בחר תמונה לבדיקה")
                        }
                    }
                }
            }

            if (matches.isNotEmpty()) {
                item { Text("תוצאות התאמה (${matches.size}):", fontWeight = FontWeight.Medium) }
                items(matches) { (entry, similarity) ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = File(entry.imagePath), contentDescription = null,
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("התאמה: $similarity%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val color = when {
                                similarity >= 90 -> MaterialTheme.colorScheme.primary
                                similarity >= 70 -> Color(0xFFFF9100)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text("$similarity%", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = color)
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("ספריית תמונות ייחוס (${library.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    OutlinedButton(onClick = { addLauncher.launch("image/*") }) {
                        Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("הוסף")
                    }
                }
            }

            items(library) { entry ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = File(entry.imagePath), contentDescription = null,
                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(entry.label, modifier = Modifier.weight(1f), fontSize = 14.sp)
                        IconButton(onClick = { scope.launch { db.imageHashDao().delete(entry) } }) {
                            Icon(Icons.Default.Delete, "מחק", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    val uriToAdd = pendingUri
    if (uriToAdd != null) {
        AlertDialog(
            onDismissRequest = { pendingUri = null; labelInput = "" },
            title = { Text("תייג תמונה") },
            text = {
                OutlinedTextField(
                    value = labelInput, onValueChange = { labelInput = it },
                    label = { Text("תווית (לדוגמה: שם הפרופיל)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val hash = ImageHashUtil.computeAverageHash(context, uriToAdd)
                            val path = ImageHashUtil.saveImageToInternalStorage(context, uriToAdd, "img_${System.currentTimeMillis()}.jpg")
                            if (hash != null && path != null) {
                                db.imageHashDao().insert(ImageHash(label = labelInput.ifBlank { "ללא שם" }, hash = hash, imagePath = path))
                            }
                            pendingUri = null
                            labelInput = ""
                        }
                    },
                    enabled = labelInput.isNotBlank()
                ) { Text("שמור") }
            },
            dismissButton = { TextButton(onClick = { pendingUri = null; labelInput = "" }) { Text("ביטול") } }
        )
    }
}
