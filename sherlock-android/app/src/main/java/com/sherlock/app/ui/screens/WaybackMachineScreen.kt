package com.sherlock.app.ui.screens

import android.content.Intent
import android.net.Uri
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
import com.sherlock.app.data.model.WaybackAvailability
import com.sherlock.app.data.model.WaybackSnapshot
import com.sherlock.app.data.repository.OsintToolsRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaybackMachineScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { OsintToolsRepository() }
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var availability by remember { mutableStateOf<WaybackAvailability?>(null) }
    var history by remember { mutableStateOf<List<WaybackSnapshot>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun formatTimestamp(ts: String): String {
        return if (ts.length >= 8) "${ts.substring(6, 8)}/${ts.substring(4, 6)}/${ts.substring(0, 4)}" else ts
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ארכיון Wayback Machine") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "בדוק האם כתובת אינטרנט נשמרה בארכיון Wayback Machine, וצפה בהיסטוריית גרסאות",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it.trim() },
                label = { Text("כתובת אתר") },
                placeholder = { Text("example.com") },
                leadingIcon = { Icon(Icons.Default.Language, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    isLoading = true; error = null; availability = null; history = emptyList()
                    scope.launch {
                        try {
                            availability = repository.checkWaybackAvailability(url)
                            history = repository.getWaybackHistory(url)
                        } catch (e: Exception) {
                            error = e.message
                        } finally { isLoading = false }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = url.isNotBlank() && !isLoading
            ) {
                if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                else { Icon(Icons.Default.History, null); Spacer(Modifier.width(8.dp)); Text("בדוק ארכיון") }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }

            availability?.let { a ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (a.available) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (a.available) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            null,
                            tint = if (a.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.width(8.dp))
                        if (a.available) {
                            Column {
                                Text("נמצאה גרסה ארכיונית", fontWeight = FontWeight.Medium)
                                a.timestamp?.let { Text("מתאריך ${formatTimestamp(it)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        } else {
                            Text("לא נמצאה גרסה בארכיון", color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            if (history.isNotEmpty()) {
                Text("היסטוריית גרסאות (${history.size}):", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(history) { snapshot ->
                        Card(
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(snapshot.url))) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(formatTimestamp(snapshot.timestamp), fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
