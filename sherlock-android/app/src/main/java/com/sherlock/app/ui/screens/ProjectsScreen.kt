package com.sherlock.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.data.model.PROJECT_COLORS
import com.sherlock.app.data.model.Priority
import com.sherlock.app.data.model.Project
import com.sherlock.app.data.model.ProjectStatus
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private enum class ProjectSort(val label: String) {
    DATE("תאריך עדכון"), NAME("שם"), PRIORITY("עדיפות")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToProjectDetail: (Long) -> Unit = {},
    onNavigateToTrash: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val projects by db.projectDao().getAll().collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<ProjectStatus?>(null) }
    var sortBy by remember { mutableStateOf(ProjectSort.DATE) }
    var showSortMenu by remember { mutableStateOf(false) }

    val filtered = remember(projects, searchQuery, statusFilter, sortBy) {
        projects
            .filter { statusFilter == null || it.status == statusFilter }
            .filter { searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true) || it.description.contains(searchQuery, ignoreCase = true) }
            .sortedWith(
                compareByDescending<Project> { it.isPinned }.then(
                    when (sortBy) {
                        ProjectSort.DATE -> compareByDescending { it.updatedAt }
                        ProjectSort.NAME -> compareBy { it.name.lowercase() }
                        ProjectSort.PRIORITY -> compareBy { it.priority.ordinal }
                    }
                )
            )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("פרויקטי חקירה") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } },
                actions = {
                    Box {
                        IconButton(onClick = { showSortMenu = true }) { Icon(Icons.Default.Sort, "מיון") }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            ProjectSort.entries.forEach { s ->
                                DropdownMenuItem(text = { Text(s.label) }, onClick = { sortBy = s; showSortMenu = false })
                            }
                        }
                    }
                    IconButton(onClick = onNavigateToTrash) { Icon(Icons.Default.Delete, "סל מיחזור") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, "הוסף פרויקט")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("חיפוש פרויקטים...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true
            )

            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(selected = statusFilter == null, onClick = { statusFilter = null }, label = { Text("הכל") })
                }
                items(ProjectStatus.entries.toList()) { status ->
                    FilterChip(
                        selected = statusFilter == status,
                        onClick = { statusFilter = if (statusFilter == status) null else status },
                        label = { Text(status.hebrewName) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Text(if (projects.isEmpty()) "אין פרויקטים עדיין" else "לא נמצאו תוצאות", fontSize = 16.sp)
                        if (projects.isEmpty()) {
                            Text("לחץ + כדי ליצור פרויקט חדש", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(filtered, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            onClick = { onNavigateToProjectDetail(project.id) },
                            onTogglePin = { scope.launch { db.projectDao().update(project.copy(isPinned = !project.isPinned)) } },
                            onDelete = {
                                scope.launch {
                                    db.projectDao().update(project.copy(isDeleted = true, deletedAt = System.currentTimeMillis()))
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddProjectDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, desc, priority, color ->
                scope.launch {
                    val now = System.currentTimeMillis()
                    db.projectDao().insert(Project(name = name, description = desc, priority = priority, color = color, createdAt = now, updatedAt = now))
                }
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun ProjectCard(project: Project, onClick: () -> Unit, onTogglePin: () -> Unit, onDelete: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
    val priorityColor = when (project.priority) {
        Priority.URGENT -> MaterialTheme.colorScheme.error
        Priority.HIGH -> MaterialTheme.colorScheme.error
        Priority.NORMAL -> MaterialTheme.colorScheme.tertiary
        Priority.LOW -> MaterialTheme.colorScheme.primary
    }

    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(Color(project.color)))
            Column(modifier = Modifier.padding(16.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(
                        onClick = {},
                        label = { Text(project.priority.name, fontSize = 11.sp) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = priorityColor.copy(alpha = 0.15f))
                    )
                    Spacer(Modifier.width(6.dp))
                    AssistChip(
                        onClick = {},
                        label = { Text(project.status.hebrewName, fontSize = 11.sp) }
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onTogglePin, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.PushPin, "נעץ",
                            tint = if (project.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, "מחק", modifier = Modifier.size(18.dp))
                    }
                }
                Text(project.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (project.description.isNotBlank()) {
                    Text(project.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                Spacer(Modifier.height(4.dp))
                Text("עודכן: ${dateFormat.format(Date(project.updatedAt))}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AddProjectDialog(onDismiss: () -> Unit, onAdd: (String, String, Priority, Long) -> Unit) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(Priority.NORMAL) }
    var color by remember { mutableStateOf(PROJECT_COLORS.first()) }

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
                Text("צבע:", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PROJECT_COLORS.forEach { c ->
                        ColorSwatch(color = c, selected = color == c, onClick = { color = c })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name, desc, priority, color) }, enabled = name.isNotBlank()) { Text("צור") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}

@Composable
private fun ColorSwatch(color: Long, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(color)),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.fillMaxSize().semantics {
                contentDescription = if (selected) "צבע נבחר" else "בחר צבע"
            }
        ) {
            if (selected) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}
