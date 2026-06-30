package com.sherlock.app.ui.screens

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.model.ExposureReport
import com.sherlock.app.data.repository.OsintToolsRepository
import com.sherlock.app.util.SettingsManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExposureReportScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { OsintToolsRepository() }
    val settings = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()
    val hibpApiKey by settings.hibpApiKey.collectAsState(initial = "")

    var query by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<ExposureReport?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun riskColor(level: String) = when (level) {
        "גבוה" -> androidx.compose.ui.graphics.Color(0xFFD32F2F)
        "בינוני" -> androidx.compose.ui.graphics.Color(0xFFF57C00)
        else -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("דוח חשיפה מאוחד") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "הזן אימייל או שם משתמש לקבלת דוח חשיפה מאוחד - בדיקת דליפות מידע וחיפוש באתרי Paste",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.trim() },
                label = { Text("אימייל או שם משתמש") },
                leadingIcon = { Icon(Icons.Default.Person, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    isLoading = true; error = null; report = null
                    val isEmail = query.contains("@")
                    scope.launch {
                        try {
                            report = repository.buildExposureReport(query, isEmail, hibpApiKey)
                        } catch (e: Exception) {
                            error = e.message
                        } finally { isLoading = false }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = query.isNotBlank() && !isLoading
            ) {
                if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                else { Icon(Icons.Default.Assessment, null); Spacer(Modifier.width(8.dp)); Text("הפק דוח") }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }

            report?.let { r ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Security, null, tint = riskColor(r.riskLevel))
                            Spacer(Modifier.width(8.dp))
                            Text("רמת סיכון: ${r.riskLevel}", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = riskColor(r.riskLevel))
                        }
                        Text("סה\"כ חשיפות שנמצאו: ${r.totalExposures}", fontSize = 14.sp)
                        Text("דליפות מידע: ${r.breaches.size}" + if (!r.hibpChecked) " (לא נבדק - נדרש מפתח API)" else "", fontSize = 13.sp)
                        Text("ממצאים באתרי Paste: ${r.pastes.size}", fontSize = 13.sp)
                    }
                }

                if (r.breaches.isNotEmpty()) {
                    Text("דליפות מידע (${r.breaches.size}):", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        r.breaches.forEach { breach ->
                            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(breach.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                    if (breach.breachDate.isNotBlank()) Text(breach.breachDate, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                if (r.pastes.isNotEmpty()) {
                    Text("ממצאים ב-Paste (${r.pastes.size}):", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(r.pastes) { paste ->
                            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(paste.url, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    if (paste.date.isNotBlank()) Text(paste.date, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
