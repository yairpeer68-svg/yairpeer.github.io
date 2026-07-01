package com.sherlock.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.data.model.SavedLink
import com.sherlock.app.ui.components.openUrl
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedLinksScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val links by db.savedLinkDao().getAll().collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ארכיון קישורים", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) { Icon(Icons.Default.Add, "הוסף קישור") }
        }
    ) { padding ->
        if (links.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Bookmarks, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("אין קישורים שמורים", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("שמרי מקורות לקריאה מאוחר יותר", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(links, key = { it.id }) { link ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                scope.launch { db.savedLinkDao().update(link.copy(isRead = !link.isRead)) }
                            }) {
                                Icon(
                                    if (link.isRead) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    "נקרא",
                                    tint = if (link.isRead) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(
                                Modifier.weight(1f).clickable { openUrl(context, link.url) }
                            ) {
                                Text(
                                    link.title.ifBlank { link.url },
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                    textDecoration = if (link.isRead) TextDecoration.LineThrough else null,
                                    maxLines = 1
                                )
                                Text(link.url, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                if (link.note.isNotBlank()) {
                                    Text(link.note, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                                }
                            }
                            IconButton(onClick = { scope.launch { db.savedLinkDao().delete(link) } }) {
                                Icon(Icons.Default.Delete, "מחק", Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddSavedLinkDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { url, title, note ->
                scope.launch { db.savedLinkDao().insert(SavedLink(url = url, title = title, note = note)) }
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun AddSavedLinkDialog(onDismiss: () -> Unit, onAdd: (String, String, String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("שמור קישור") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("כתובת URL") }, singleLine = true)
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("כותרת (אופציונלי)") }, singleLine = true)
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("הערה (אופציונלי)") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(url, title, note) }, enabled = url.isNotBlank()) { Text("שמור") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}
