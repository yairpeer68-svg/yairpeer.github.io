package com.sherlock.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.data.model.ProfileNote
import com.sherlock.app.data.model.ProjectStatus
import com.sherlock.app.data.model.ProjectTask
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(projectId: Long, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val project by db.projectDao().getByIdFlow(projectId).collectAsState(initial = null)
    val tasks by db.projectTaskDao().getForProject(projectId).collectAsState(initial = emptyList())
    val notes by db.noteDao().getForProject(projectId).collectAsState(initial = emptyList())
    var showStatusMenu by remember { mutableStateOf(false) }
    var newTaskText by remember { mutableStateOf("") }
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }

    val currentProject = project
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentProject?.name ?: "פרויקט") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } },
                actions = {
                    if (currentProject != null) {
                        Box {
                            IconButton(onClick = { showStatusMenu = true }) { Icon(Icons.Default.Flag, "סטטוס") }
                            DropdownMenu(expanded = showStatusMenu, onDismissRequest = { showStatusMenu = false }) {
                                ProjectStatus.entries.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text(s.hebrewName) },
                                        onClick = {
                                            scope.launch {
                                                db.projectDao().update(currentProject.copy(status = s, updatedAt = System.currentTimeMillis()))
                                            }
                                            showStatusMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (currentProject == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth()) {
                        Box(Modifier.width(6.dp).fillMaxHeight().background(Color(currentProject.color)))
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AssistChip(onClick = {}, label = { Text(currentProject.priority.hebrewName, fontSize = 11.sp) })
                                AssistChip(onClick = {}, label = { Text(currentProject.status.hebrewName, fontSize = 11.sp) })
                            }
                            if (currentProject.description.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(currentProject.description, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("עודכן: ${dateFormat.format(Date(currentProject.updatedAt))}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item {
                Text("רשימת משימות", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newTaskText,
                        onValueChange = { newTaskText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("הוסף משימה...") },
                        singleLine = true
                    )
                    IconButton(
                        onClick = {
                            if (newTaskText.isNotBlank()) {
                                scope.launch { db.projectTaskDao().insert(ProjectTask(projectId = projectId, text = newTaskText.trim())) }
                                newTaskText = ""
                            }
                        }
                    ) { Icon(Icons.Default.Add, "הוסף") }
                }
            }

            if (tasks.isEmpty()) {
                item { Text("אין משימות עדיין", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(tasks, key = { it.id }) { task ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = task.isDone,
                            onCheckedChange = { checked -> scope.launch { db.projectTaskDao().update(task.copy(isDone = checked)) } }
                        )
                        Text(
                            task.text,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                            textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                            color = if (task.isDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(onClick = { scope.launch { db.projectTaskDao().delete(task) } }) {
                            Icon(Icons.Default.Close, "מחק משימה", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            item {
                Text("הערות מקושרות", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
            }

            if (notes.isEmpty()) {
                item { Text("אין הערות מקושרות לפרויקט זה", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(notes, key = { it.id }) { note ->
                    LinkedNoteCard(note, dateFormat)
                }
            }
        }
    }
}

@Composable
private fun LinkedNoteCard(note: ProfileNote, dateFormat: SimpleDateFormat) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (note.profileUrl.isNotBlank()) {
                Text(note.profileUrl, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
            Text(note.note, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
            Text(dateFormat.format(Date(note.timestamp)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
