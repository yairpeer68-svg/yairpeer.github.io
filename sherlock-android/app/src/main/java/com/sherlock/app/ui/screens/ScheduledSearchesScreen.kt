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
import com.sherlock.app.data.model.ScheduledSearch
import com.sherlock.app.data.model.SearchType
import com.sherlock.app.ui.theme.SherlockSuccess
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledSearchesScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val scheduledSearches by db.scheduledSearchDao().getAll().collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }

    if (showAddDialog) {
        AddScheduledSearchDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, query, type, interval ->
                scope.launch {
                    db.scheduledSearchDao().insert(
                        ScheduledSearch(name = name, query = query, searchType = type, intervalHours = interval)
                    )
                }
                showAddDialog = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("חיפושים מתוזמנים", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, "הוסף")
            }
        }
    ) { padding ->
        if (scheduledSearches.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Schedule, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(16.dp))
                    Text("אין חיפושים מתוזמנים", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("לחץ + ליצירת חיפוש חוזר אוטומטי", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        } else {
            val dateFormat = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text("${scheduledSearches.count { it.isActive }} חיפושים פעילים", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                items(scheduledSearches, key = { it.id }) { scheduled ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Schedule, null, Modifier.size(24.dp),
                                tint = if (scheduled.isActive) SherlockSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${scheduled.name} (${scheduled.searchType.hebrewName})", fontWeight = FontWeight.Medium)
                                Text("\"${scheduled.query}\" • כל ${scheduled.intervalHours} שעות", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (scheduled.lastRun > 0) {
                                    Text("ריצה אחרונה: ${dateFormat.format(Date(scheduled.lastRun))} • נמצאו ${scheduled.lastFound}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                                }
                            }
                            Switch(
                                checked = scheduled.isActive,
                                onCheckedChange = { active ->
                                    scope.launch { db.scheduledSearchDao().update(scheduled.copy(isActive = active)) }
                                },
                                modifier = Modifier.size(32.dp)
                            )
                            IconButton(onClick = { scope.launch { db.scheduledSearchDao().delete(scheduled) } }, Modifier.size(32.dp)) {
                                Icon(Icons.Default.Delete, "מחק", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddScheduledSearchDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, SearchType, Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var searchType by remember { mutableStateOf(SearchType.USERNAME) }
    var interval by remember { mutableStateOf(24) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("חיפוש מתוזמן חדש") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("שם (לדוגמה: מעקב אישי)") }, singleLine = true)
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("שאילתת חיפוש") }, singleLine = true)

                Text("סוג חיפוש", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(SearchType.USERNAME, SearchType.EMAIL, SearchType.PHONE).forEach { type ->
                        FilterChip(
                            selected = searchType == type,
                            onClick = { searchType = type },
                            label = { Text(type.hebrewName, fontSize = 12.sp) }
                        )
                    }
                }

                Text("תדירות: כל $interval שעות", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = interval.toFloat(),
                    onValueChange = { interval = it.toInt() },
                    valueRange = 1f..168f
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name, query, searchType, interval) }, enabled = name.isNotBlank() && query.isNotBlank()) { Text("שמור") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}
