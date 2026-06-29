package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.data.model.ProfileNote
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val notes by db.noteDao().getAll().collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("הערות חקירה") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } },
                actions = {
                    if (notes.isNotEmpty()) {
                        IconButton(onClick = { scope.launch { db.noteDao().clearAll() } }) {
                            Icon(Icons.Default.DeleteSweep, "נקה הכל")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.NoteAdd, "הוסף הערה")
            }
        }
    ) { padding ->
        if (notes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.StickyNote2, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Text("אין הערות עדיין", fontSize = 16.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(notes) { note ->
                    NoteCard(note) { scope.launch { db.noteDao().delete(note) } }
                }
            }
        }
    }

    if (showAddDialog) {
        AddNoteDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { profileUrl, noteText ->
                scope.launch {
                    db.noteDao().insert(ProfileNote(profileUrl = profileUrl, siteName = "", username = "", note = noteText, timestamp = System.currentTimeMillis()))
                }
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun NoteCard(note: ProfileNote, onDelete: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Link, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text(note.profileUrl, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f), maxLines = 1)
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "מחק", modifier = Modifier.size(18.dp)) }
            }
            Text(note.note, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
            Text(dateFormat.format(Date(note.timestamp)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AddNoteDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var profileUrl by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("הערה חדשה") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = profileUrl, onValueChange = { profileUrl = it }, label = { Text("כתובת פרופיל") }, singleLine = true)
                OutlinedTextField(value = noteText, onValueChange = { noteText = it }, label = { Text("תוכן ההערה") }, maxLines = 5, minLines = 3)
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(profileUrl, noteText) }, enabled = noteText.isNotBlank()) { Text("שמור") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}
