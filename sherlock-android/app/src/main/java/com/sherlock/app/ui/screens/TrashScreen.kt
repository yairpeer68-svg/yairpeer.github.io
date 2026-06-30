package com.sherlock.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.data.model.Project
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val deletedProjects by db.projectDao().getDeleted().collectAsState(initial = emptyList())
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("סל מיחזור") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        if (deletedProjects.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.DeleteOutline, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Text("סל המיחזור ריק", fontSize = 16.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(deletedProjects, key = { it.id }) { project ->
                    TrashCard(
                        project = project,
                        dateFormat = dateFormat,
                        onRestore = { scope.launch { db.projectDao().update(project.copy(isDeleted = false, deletedAt = null)) } },
                        onDeletePermanently = { scope.launch { db.projectDao().delete(project) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrashCard(
    project: Project,
    dateFormat: SimpleDateFormat,
    onRestore: () -> Unit,
    onDeletePermanently: () -> Unit
) {
    var showConfirmDelete by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(Color(project.color)))
            Column(modifier = Modifier.padding(16.dp).weight(1f)) {
                Text(project.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (project.deletedAt != null) {
                    Text("נמחק: ${dateFormat.format(Date(project.deletedAt))}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRestore) {
                        Icon(Icons.Default.RestoreFromTrash, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("שחזר")
                    }
                    TextButton(onClick = { showConfirmDelete = true }) {
                        Icon(Icons.Default.DeleteForever, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(4.dp))
                        Text("מחק לצמיתות", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (showConfirmDelete) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            title = { Text("מחיקה לצמיתות") },
            text = { Text("פעולה זו תמחק את הפרויקט \"${project.name}\" לצמיתות ולא ניתן יהיה לשחזר אותו. להמשיך?") },
            confirmButton = {
                TextButton(onClick = { onDeletePermanently(); showConfirmDelete = false }) {
                    Text("מחק", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showConfirmDelete = false }) { Text("ביטול") } }
        )
    }
}
