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
fun NotesExportScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val repository = remember { ExportRepository(context) }
    val notes by db.noteDao().getAll().collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ייצוא הערות חקירה") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("יצא את כל ההערות שתיעדת במהלך החקירות שלך לקובץ טקסט", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("${notes.size} הערות", fontSize = 14.sp)
                }
            }
            Button(
                onClick = {
                    val uri = repository.exportNotesToText(notes)
                    repository.shareFile(uri, "text/plain", "Sherlock Notes")
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = notes.isNotEmpty()
            ) {
                Icon(Icons.Default.StickyNote2, null); Spacer(Modifier.width(8.dp)); Text("ייצוא לקובץ טקסט")
            }
        }
    }
}
