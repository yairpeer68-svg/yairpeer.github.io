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
import com.sherlock.app.data.model.Priority
import com.sherlock.app.data.model.Project
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val projects by db.projectDao().getAll().collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("פרויקטי חקירה") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, "הוסף פרויקט")
            }
        }
    ) { padding ->
        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Text("אין פרויקטים עדיין", fontSize = 16.sp)
                    Text("לחץ + כדי ליצור פרויקט חדש", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(projects) { project ->
                    ProjectCard(project) {
                        scope.launch { db.projectDao().delete(project) }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddProjectDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, desc, priority ->
                scope.launch {
                    val now = System.currentTimeMillis()
                    db.projectDao().insert(Project(name = name, description = desc, priority = priority, createdAt = now, updatedAt = now))
                }
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun ProjectCard(project: Project, onDelete: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
    val priorityColor = when (project.priority) {
        Priority.URGENT -> MaterialTheme.colorScheme.error
        Priority.HIGH -> MaterialTheme.colorScheme.error
        Priority.NORMAL -> MaterialTheme.colorScheme.tertiary
        Priority.LOW -> MaterialTheme.colorScheme.primary
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = {},
                    label = { Text(project.priority.name, fontSize = 11.sp) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = priorityColor.copy(alpha = 0.15f))
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "מחק", modifier = Modifier.size(20.dp)) }
            }
            Text(project.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (project.description.isNotBlank()) {
                Text(project.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Spacer(Modifier.height(4.dp))
            Text("נוצר: ${dateFormat.format(Date(project.createdAt))}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AddProjectDialog(onDismiss: () -> Unit, onAdd: (String, String, Priority) -> Unit) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(Priority.NORMAL) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("פרויקט חדש") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("שם הפרויקט") }, singleLine = true)
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("תיאור") }, maxLines = 3)
                Text("עדיפות:", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Priority.entries.forEach { p ->
                        FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(p.name) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name, desc, priority) }, enabled = name.isNotBlank()) { Text("צור") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}
