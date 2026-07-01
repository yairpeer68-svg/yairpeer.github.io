package com.sherlock.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.sherlock.app.data.model.ImageForensicsResult
import com.sherlock.app.data.repository.AnalysisRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageForensicsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var result by remember { mutableStateOf<ImageForensicsResult?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            imageUri = it
            isProcessing = true
            scope.launch {
                val repo = AnalysisRepository(context)
                result = withContext(Dispatchers.IO) { repo.analyzeImageForensics(context, it) }
                isProcessing = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ניתוח פורנזי") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(onClick = { launcher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.ImageSearch, null)
                Spacer(Modifier.width(8.dp))
                Text("בחר תמונה לניתוח")
            }

            imageUri?.let {
                AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp))
            }

            if (isProcessing) {
                CircularProgressIndicator()
                Text("מנתח תמונה...")
            }

            result?.let { r ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (r.isLikelyEdited) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            if (r.isLikelyEdited) "⚠ סביר שהתמונה נערכה" else "✓ התמונה נראית אותנטית",
                            fontWeight = FontWeight.Bold, fontSize = 18.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("רמת ביטחון: ${(r.editConfidence * 100).toInt()}%", fontSize = 14.sp)
                        LinearProgressIndicator(
                            progress = r.editConfidence,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        )
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("איכות תמונה", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = r.qualityScore / 100f,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        )
                        Text("${r.qualityScore.toInt()}/100", fontSize = 12.sp)
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("מטא-דאטה", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (r.metadataConsistency) "✓ מטא-דאטה קיים ועקבי" else "⚠ חסר מטא-דאטה",
                            fontSize = 14.sp
                        )
                    }
                }

                if (r.detectedManipulations.isNotEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("ממצאים", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            r.detectedManipulations.forEach { m ->
                                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(m, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (r.aiGeneratedLikelihood > 0.4f) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("חשד לתמונה שנוצרה ב-AI", fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = r.aiGeneratedLikelihood,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        )
                        Text("${(r.aiGeneratedLikelihood * 100).toInt()}% סבירות (הערכה ניסיונית בלבד)", fontSize = 12.sp)
                        if (r.aiGeneratedSignals.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            r.aiGeneratedSignals.forEach { s ->
                                Text("• $s", fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
                            }
                        } else {
                            Text("לא נמצאו סימנים לייצור באמצעות AI", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
