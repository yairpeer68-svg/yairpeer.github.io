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
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.data.model.SearchHistory
import com.sherlock.app.ui.components.ResponsiveContent
import com.sherlock.app.ui.theme.SherlockSuccess
import com.sherlock.app.util.SettingsManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val settings = remember { SettingsManager(context) }
    val compactDensity by settings.compactDensity.collectAsState(initial = false)
    val history by db.searchHistoryDao().getAllHistory().collectAsState(initial = emptyList())
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("מחיקת היסטוריה") },
            text = { Text("למחוק את כל היסטוריית החיפושים?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { db.searchHistoryDao().clearAllHistory() }
                    showClearDialog = false
                }) { Text("מחק", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("ביטול") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("היסטוריית חיפושים", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                actions = {
                    if (history.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Default.DeleteSweep, "נקה הכל", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.History, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(16.dp))
                    Text("אין היסטוריית חיפושים", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            ResponsiveContent(modifier = Modifier.padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (compactDensity) 4.dp else 8.dp)
            ) {
                items(history, key = { it.id }) { item ->
                    HistoryItem(item, compactDensity) {
                        scope.launch {
                            db.searchHistoryDao().deleteResultsForHistory(item.id)
                            db.searchHistoryDao().deleteHistory(item)
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun HistoryItem(item: SearchHistory, compactDensity: Boolean = false, onDelete: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(if (compactDensity) 8.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon = when (item.searchType.name) {
                "EMAIL" -> Icons.Default.Email
                "PHONE" -> Icons.Default.Phone
                "IMAGE" -> Icons.Default.Image
                else -> Icons.Default.Person
            }
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.query, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${item.searchType.hebrewName} • ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.Default.CheckCircle, null, Modifier.size(12.dp), tint = SherlockSuccess)
                    Text(" ${item.totalFound}/${item.totalChecked}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(dateFormat.format(Date(item.timestamp)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, "מחק", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
            }
        }
    }
}
