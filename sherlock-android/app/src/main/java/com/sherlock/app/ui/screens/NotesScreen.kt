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
import com.sherlock.app.data.model.Project
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
    val projects by db.projectDao().getAll().collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val filtered = remember(notes, searchQuery) {
        notes.filter {
            searchQuery.isBlank() || it.note.contains(searchQuery, ignoreCase = true) || it.profileUrl.contains(searchQuery, ignoreCase = true)
        }
    }
    val projectsById = remember(projects) { projects.associateBy { it.id } }

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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("חיפוש הערות...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true
            )

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.StickyNote2, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Text(if (notes.isEmpty()) "אין הערות עדיין" else "לא נמצאו תוצאות", fontSize = 16.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(filtered, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            projectName = note.projectId?.let { projectsById[it]?.name },
                            onDelete = { scope.launch { db.noteDao().delete(note) } }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddNoteDialog(
            projects = projects,
            onDismiss = { showAddDialog = false },
            onAdd = { profileUrl, noteText, projectId ->
                scope.launch {
                    db.noteDao().insert(ProfileNote(profileUrl = profileUrl, siteName = "", username = "", note = noteText, projectId = projectId, timestamp = System.currentTimeMillis()))
                }
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun NoteCard(note: ProfileNote, projectName: String?, onDelete: () -> Unit) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dateFormat.format(Date(note.timestamp)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (projectName != null) {
                    Spacer(Modifier.width(8.dp))
                    AssistChip(
                        onClick = {},
                        label = { Text(projectName, fontSize = 10.sp) },
                        modifier = Modifier.height(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AddNoteDialog(
    projects: List<Project>,
    onDismiss: () -> Unit,
    onAdd: (String, String, Long?) -> Unit
) {
    var profileUrl by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }
    var selectedProject by remember { mutableStateOf<Project?>(null) }
    var showProjectMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("הערה חדשה") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = profileUrl, onValueChange = { profileUrl = it }, label = { Text("כתובת פרופיל") }, singleLine = true)
                OutlinedTextField(value = noteText, onValueChange = { noteText = it }, label = { Text("תוכן ההערה") }, maxLines = 5, minLines = 3)
                if (projects.isNotEmpty()) {
                    Text("שייך לפרויקט (אופציונלי):", fontWeight = FontWeight.SemiBold)
                    Box {
                        OutlinedButton(onClick = { showProjectMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(selectedProject?.name ?: "ללא פרויקט")
                        }
                        DropdownMenu(expanded = showProjectMenu, onDismissRequest = { showProjectMenu = false }) {
                            DropdownMenuItem(text = { Text("ללא פרויקט") }, onClick = { selectedProject = null; showProjectMenu = false })
                            projects.forEach { p ->
                                DropdownMenuItem(text = { Text(p.name) }, onClick = { selectedProject = p; showProjectMenu = false })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(profileUrl, noteText, selectedProject?.id) }, enabled = noteText.isNotBlank()) { Text("שמור") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}
