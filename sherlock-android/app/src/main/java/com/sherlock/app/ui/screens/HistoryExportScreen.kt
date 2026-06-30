package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.data.repository.ExportRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryExportScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val repository = remember { ExportRepository(context) }
    val history by db.searchHistoryDao().getAllHistory().collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ייצוא היסטוריה") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("יצא את היסטוריית החיפושים שלך לקובץ לשיתוף או גיבוי", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("${history.size} רשומות בהיסטוריה", fontSize = 14.sp)
                }
            }
            Button(
                onClick = {
                    val uri = repository.exportHistoryToCsv(history)
                    repository.shareFile(uri, "text/csv", "Sherlock History CSV")
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = history.isNotEmpty()
            ) {
                Icon(Icons.Default.TableChart, null); Spacer(Modifier.width(8.dp)); Text("ייצוא ל-CSV")
            }
            Button(
                onClick = {
                    val uri = repository.exportHistoryToHtml(history)
                    repository.shareFile(uri, "text/html", "Sherlock History Report")
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = history.isNotEmpty()
            ) {
                Icon(Icons.Default.Description, null); Spacer(Modifier.width(8.dp)); Text("ייצוא לדוח HTML")
            }
        }
    }
}
