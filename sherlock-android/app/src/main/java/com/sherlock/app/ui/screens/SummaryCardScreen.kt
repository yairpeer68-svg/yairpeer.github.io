package com.sherlock.app.ui.screens

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sherlock.app.data.repository.ExportRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryCardScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { ExportRepository(context) }
    var title by remember { mutableStateOf("") }
    val bulletPoints = remember { mutableStateListOf("") }
    var generatedUri by remember { mutableStateOf<android.net.Uri?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("יוצר כרטיס סיכום") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("צור תמונת סיכום מעוצבת לשיתוף עם עיקרי הממצאים שלך", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("כותרת") },
                placeholder = { Text("דוח חקירה - John Doe") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Text("נקודות עיקריות", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 14.sp)
            bulletPoints.forEachIndexed { index, point ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = point,
                        onValueChange = { bulletPoints[index] = it },
                        modifier = Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("נמצאו 12 פרופילים פעילים") }
                    )
                    if (bulletPoints.size > 1) {
                        IconButton(onClick = { bulletPoints.removeAt(index) }) {
                            Icon(Icons.Default.Close, "הסר")
                        }
                    }
                }
            }
            OutlinedButton(onClick = { bulletPoints.add("") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("הוסף נקודה")
            }

            Button(
                onClick = {
                    val points = bulletPoints.filter { it.isNotBlank() }
                    val uri = repository.generateSummaryCardImage(title.ifBlank { "Sherlock Report" }, points)
                    generatedUri = uri
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = title.isNotBlank() && bulletPoints.any { it.isNotBlank() }
            ) {
                Icon(Icons.Default.Image, null); Spacer(Modifier.width(8.dp)); Text("צור תמונה")
            }

            generatedUri?.let { uri ->
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { repository.shareFile(uri, "image/png", "Sherlock Summary Card") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("שתף תמונה")
                }
            }
        }
    }
}
