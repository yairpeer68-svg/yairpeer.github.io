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
import com.sherlock.app.data.model.Project
import com.sherlock.app.data.model.ProfileNote
import com.sherlock.app.data.model.ProjectTask
import com.sherlock.app.data.repository.ExportRepository
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectReportScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val repository = remember { ExportRepository(context) }
    val projects by db.projectDao().getAll().collectAsState(initial = emptyList())
    var selected by remember { mutableStateOf<Project?>(null) }
    var expanded by remember { mutableStateOf(false) }

    val tasks by remember(selected) { selected?.let { db.projectTaskDao().getForProject(it.id) } ?: flowOf(emptyList<ProjectTask>()) }
        .collectAsState(initial = emptyList())
    val notes by remember(selected) { selected?.let { db.noteDao().getForProject(it.id) } ?: flowOf(emptyList<ProfileNote>()) }
        .collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("דוח פרויקט חקירה") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("בחר פרויקט לייצוא דוח חקירה מלא הכולל משימות והערות", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = selected?.name ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("פרויקט") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    projects.forEach { project ->
                        DropdownMenuItem(
                            text = { Text(project.name) },
                            onClick = { selected = project; expanded = false }
                        )
                    }
                }
            }

            selected?.let { project ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("${tasks.size} משימות • ${notes.size} הערות", fontSize = 14.sp)
                    }
                }
                Button(
                    onClick = {
                        val uri = repository.exportProjectReport(project, tasks, notes)
                        repository.shareFile(uri, "text/html", "Sherlock Project Report - ${project.name}")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Description, null); Spacer(Modifier.width(8.dp)); Text("ייצוא דוח HTML")
                }
            }
        }
    }
}
