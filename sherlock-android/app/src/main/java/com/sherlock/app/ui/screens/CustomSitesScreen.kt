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
import com.sherlock.app.data.model.CustomSite
import com.sherlock.app.data.model.SiteCategory
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomSitesScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val sites by db.customSiteDao().getAll().collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("אתרים מותאמים אישית") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, "הוסף אתר")
            }
        }
    ) { padding ->
        if (sites.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Language, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Text("אין אתרים מותאמים אישית", fontSize = 16.sp)
                    Text("הוסף אתרים לחיפוש שלך", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(sites) { site ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(site.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(site.urlTemplate, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                Text(site.category.hebrewName, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { scope.launch { db.customSiteDao().delete(site) } }) {
                                Icon(Icons.Default.Delete, "מחק", modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddCustomSiteDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, urlTemplate, siteCategory ->
                scope.launch { db.customSiteDao().insert(CustomSite(name = name, urlTemplate = urlTemplate, category = siteCategory)) }
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun AddCustomSiteDialog(onDismiss: () -> Unit, onAdd: (String, String, SiteCategory) -> Unit) {
    var name by remember { mutableStateOf("") }
    var urlTemplate by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(SiteCategory.OTHER) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("הוסף אתר") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("שם האתר") }, singleLine = true)
                OutlinedTextField(
                    value = urlTemplate, onValueChange = { urlTemplate = it },
                    label = { Text("URL (השתמש ב-{} לשם)") },
                    placeholder = { Text("https://example.com/user/{}") }, singleLine = true
                )
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = selectedCategory.hebrewName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("קטגוריה") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        SiteCategory.entries.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat.hebrewName) },
                                onClick = { selectedCategory = cat; expanded = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name, urlTemplate, selectedCategory) }, enabled = name.isNotBlank() && urlTemplate.isNotBlank()) { Text("הוסף") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}
