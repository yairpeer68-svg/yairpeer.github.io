package com.sherlock.app.ui.screens

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.data.model.MonitoredProfile
import com.sherlock.app.ui.theme.SherlockSuccess
import com.sherlock.app.ui.theme.SherlockError
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val profiles by db.monitoredProfileDao().getAllProfiles().collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }
    var newUrl by remember { mutableStateOf("") }
    var newSite by remember { mutableStateOf("") }
    var newUsername by remember { mutableStateOf("") }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("הוסף פרופיל לניטור") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newSite, onValueChange = { newSite = it }, placeholder = { Text("שם האתר (Instagram, Twitter...)") }, singleLine = true)
                    OutlinedTextField(value = newUsername, onValueChange = { newUsername = it }, placeholder = { Text("שם משתמש") }, singleLine = true)
                    OutlinedTextField(value = newUrl, onValueChange = { newUrl = it }, placeholder = { Text("URL מלא") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newSite.isNotBlank() && newUrl.isNotBlank()) {
                        scope.launch {
                            db.monitoredProfileDao().insertProfile(
                                MonitoredProfile(siteName = newSite, url = newUrl, username = newUsername)
                            )
                        }
                    }
                    showAddDialog = false; newUrl = ""; newSite = ""; newUsername = ""
                }) { Text("הוסף") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("ביטול") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ניטור פרופילים", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, "הוסף")
            }
        }
    ) { padding ->
        if (profiles.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Notifications, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(16.dp))
                    Text("אין פרופילים מנוטרים", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("לחץ + להוסיף פרופיל לניטור", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    Text("תקבל התראה כשיש שינוי", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        } else {
            val dateFormat = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text("${profiles.count { it.isActive }} פרופילים פעילים", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                items(profiles, key = { it.id }) { profile ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (profile.isActive) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                                null, Modifier.size(24.dp),
                                tint = if (profile.isActive) SherlockSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${profile.siteName} - @${profile.username}", fontWeight = FontWeight.Medium)
                                if (profile.lastChecked > 0) {
                                    Text("בדיקה אחרונה: ${dateFormat.format(Date(profile.lastChecked))}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Switch(
                                checked = profile.isActive,
                                onCheckedChange = { active ->
                                    scope.launch { db.monitoredProfileDao().updateProfile(profile.copy(isActive = active)) }
                                },
                                modifier = Modifier.size(32.dp)
                            )
                            IconButton(onClick = { scope.launch { db.monitoredProfileDao().deleteProfile(profile) } }, Modifier.size(32.dp)) {
                                Icon(Icons.Default.Delete, "מחק", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
            }
        }
    }
}
