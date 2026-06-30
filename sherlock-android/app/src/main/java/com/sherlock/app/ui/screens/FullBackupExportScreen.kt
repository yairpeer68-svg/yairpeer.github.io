package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
fun FullBackupExportScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val repository = remember { ExportRepository(context) }

    val history by db.searchHistoryDao().getAllHistory().collectAsState(initial = emptyList())
    val favorites by db.favoriteDao().getAllFavorites().collectAsState(initial = emptyList())
    val notes by db.noteDao().getAll().collectAsState(initial = emptyList())
    val projects by db.projectDao().getAll().collectAsState(initial = emptyList())
    val tasks by db.projectTaskDao().getAll().collectAsState(initial = emptyList())
    val identities by db.digitalIdentityDao().getAll().collectAsState(initial = emptyList())
    val links by db.identityLinkDao().getAll().collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("גיבוי מלא") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("גבה את כל הנתונים שלך - היסטוריה, מועדפים, הערות, פרויקטים וכרטיסי זהות - לקובץ ZIP אחד", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BackupRow("היסטוריית חיפושים", history.size)
                    BackupRow("מועדפים", favorites.size)
                    BackupRow("הערות", notes.size)
                    BackupRow("פרויקטים", projects.size)
                    BackupRow("משימות", tasks.size)
                    BackupRow("כרטיסי זהות", identities.size)
                    BackupRow("קישורי זהות", links.size)
                }
            }
            Button(
                onClick = {
                    val uri = repository.exportFullBackupZip(history, favorites, notes, projects, tasks, identities, links)
                    repository.shareFile(uri, "application/zip", "Sherlock Full Backup")
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Archive, null); Spacer(Modifier.width(8.dp)); Text("ייצוא גיבוי ZIP")
            }
        }
    }
}

@Composable
private fun BackupRow(label: String, count: Int) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp)
        Text(count.toString(), fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}
