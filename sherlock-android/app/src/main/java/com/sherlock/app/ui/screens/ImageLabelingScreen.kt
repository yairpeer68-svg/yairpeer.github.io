package com.sherlock.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private data class LabelItem(val text: String, val confidence: Float)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageLabelingScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var labels by remember { mutableStateOf<List<LabelItem>>(emptyList()) }
    var isProcessing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            imageUri = it
            isProcessing = true
            error = null
            searched = false
            scope.launch {
                try {
                    val image = InputImage.fromFilePath(context, it)
                    val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
                    val results = labeler.process(image).await()
                    labels = results.map { LabelItem(it.text, it.confidence) }.sortedByDescending { l -> l.confidence }
                    searched = true
                } catch (e: Exception) {
                    error = "שגיאה בתיוג תמונה: ${e.message}"
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("זיהוי לוגואים ותוויות") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "תיוג תמונה כללי (ON-DEVICE, ML Kit Image Labeling) - מזהה אובייקטים, מותגים נפוצים וסמלים גנריים. אינו מאגר לוגואים ייעודי.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(onClick = { launcher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.ImageSearch, null)
                Spacer(Modifier.width(8.dp))
                Text("בחר תמונה")
            }

            imageUri?.let {
                AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp))
            }

            if (isProcessing) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (searched) {
                if (labels.isEmpty()) {
                    Text("לא זוהו תוויות בתמונה", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("תוויות שזוהו (${labels.size}):", fontWeight = FontWeight.Bold)
                    labels.forEach { item ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Label, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(item.text, modifier = Modifier.weight(1f), fontSize = 14.sp)
                                Text("${(item.confidence * 100).toInt()}%", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
