package com.sherlock.app.ui.screens

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.data.model.DigitalIdentity
import com.sherlock.app.data.model.IdentityLink
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DigitalIdentityScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val identities by db.digitalIdentityDao().getAll().collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("כרטיסי זהות דיגיטלית") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.PersonAdd, "כרטיס חדש")
            }
        }
    ) { padding ->
        if (identities.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Badge, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Text("אין כרטיסי זהות עדיין", fontSize = 16.sp)
                    Text("רכז את כל הפרופילים שנמצאו עבור אדם אחד תחת כרטיס אחד", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(identities, key = { it.id }) { identity ->
                    IdentityCard(
                        identity = identity,
                        isExpanded = expandedId == identity.id,
                        onToggleExpand = { expandedId = if (expandedId == identity.id) null else identity.id },
                        onDelete = { scope.launch { db.digitalIdentityDao().delete(identity) } }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddIdentityDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, notes ->
                scope.launch { db.digitalIdentityDao().insert(DigitalIdentity(name = name, notes = notes)) }
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun IdentityCard(
    identity: DigitalIdentity,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val links by db.identityLinkDao().getForIdentity(identity.id).collectAsState(initial = emptyList())
    var showAddLinkDialog by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth(), onClick = onToggleExpand) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Badge, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(identity.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("${links.size} פרופילים מקושרים", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "מחק", modifier = Modifier.size(18.dp)) }
                Icon(if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }

            if (isExpanded) {
                Spacer(Modifier.height(8.dp))
                if (identity.notes.isNotBlank()) {
                    Text(identity.notes, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                links.forEach { link ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Link, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(link.platform, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text(link.url, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        IconButton(onClick = { scope.launch { db.identityLinkDao().delete(link) } }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, "מחק קישור", modifier = Modifier.size(14.dp))
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { showAddLinkDialog = true }) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("הוסף פרופיל מקושר")
                }
            }
        }
    }

    if (showAddLinkDialog) {
        AddLinkDialog(
            onDismiss = { showAddLinkDialog = false },
            onAdd = { platform, username, url ->
                scope.launch {
                    db.identityLinkDao().insert(IdentityLink(identityId = identity.id, platform = platform, username = username, url = url))
                }
                showAddLinkDialog = false
            }
        )
    }
}

@Composable
private fun AddIdentityDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("כרטיס זהות חדש") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("שם/כינוי מזהה") }, singleLine = true)
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("הערות") }, maxLines = 3)
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name, notes) }, enabled = name.isNotBlank()) { Text("צור") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}

@Composable
private fun AddLinkDialog(onDismiss: () -> Unit, onAdd: (String, String, String) -> Unit) {
    var platform by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("הוסף פרופיל מקושר") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = platform, onValueChange = { platform = it }, label = { Text("פלטפורמה") }, singleLine = true)
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("שם משתמש") }, singleLine = true)
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("קישור מלא") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(platform, username, url) }, enabled = platform.isNotBlank() && url.isNotBlank()) { Text("הוסף") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}
