package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.data.model.AppTheme
import com.sherlock.app.util.SettingsManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onThemeChange: (AppTheme) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsManager(context) }
    val db = remember { AppDatabase.getInstance(context) }

    val currentTheme by settings.theme.collectAsState(initial = AppTheme.DARK_BLUE)
    val haptic by settings.hapticFeedback.collectAsState(initial = true)
    val parallelThreads by settings.parallelThreads.collectAsState(initial = 10)
    val timeout by settings.searchTimeout.collectAsState(initial = 10)
    val autoVar by settings.autoVariations.collectAsState(initial = true)
    val notifications by settings.notifications.collectAsState(initial = true)

    var showThemeDialog by remember { mutableStateOf(false) }
    var showPanicDialog by remember { mutableStateOf(false) }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("ערכת נושא") },
            text = {
                Column {
                    AppTheme.entries.forEach { theme ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentTheme == theme,
                                onClick = {
                                    scope.launch { settings.setTheme(theme) }
                                    onThemeChange(theme)
                                    showThemeDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(theme.hebrewName)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showThemeDialog = false }) { Text("סגור") } }
        )
    }

    if (showPanicDialog) {
        AlertDialog(
            onDismissRequest = { showPanicDialog = false },
            title = { Text("כפתור פאניקה", color = MaterialTheme.colorScheme.error) },
            text = { Text("פעולה זו תמחק את כל הנתונים: היסטוריה, מועדפים, פרופילים מנוטרים והגדרות. פעולה זו בלתי הפיכה!") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        db.searchHistoryDao().clearAllHistory()
                        db.favoriteDao().clearAllFavorites()
                        settings.panicClear()
                    }
                    showPanicDialog = false
                }) { Text("מחק הכל", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showPanicDialog = false }) { Text("ביטול") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("הגדרות", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item { SectionHeader("מראה") }
            item {
                SettingsClickItem("ערכת נושא", currentTheme.hebrewName, Icons.Default.Palette) { showThemeDialog = true }
            }

            item { SectionHeader("חיפוש") }
            item {
                SettingsSlider("חיפושים במקביל", parallelThreads, 1, 20, Icons.Default.Speed) {
                    scope.launch { settings.setParallelThreads(it) }
                }
            }
            item {
                SettingsSlider("Timeout (שניות)", timeout, 5, 30, Icons.Default.Timer) {
                    scope.launch { settings.setSearchTimeout(it) }
                }
            }
            item {
                SettingsToggle("וריאציות אוטומטיות", "חפש גם user123, _user...", Icons.Default.AutoFixHigh, autoVar) {
                    scope.launch { settings.setAutoVariations(it) }
                }
            }

            item { SectionHeader("התראות") }
            item {
                SettingsToggle("התראות", "התראות על שינויי פרופיל", Icons.Default.Notifications, notifications) {
                    scope.launch { settings.setNotifications(it) }
                }
            }
            item {
                SettingsToggle("רטט (Haptic)", "רטט קל כשנמצאת תוצאה", Icons.Default.Vibration, haptic) {
                    scope.launch { settings.setHapticFeedback(it) }
                }
            }

            item { SectionHeader("מתקדם") }
            item {
                Card(
                    onClick = { showPanicDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0x20FF0000))
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("כפתור פאניקה", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
                            Text("מחיקת כל הנתונים מיידית", fontSize = 12.sp, color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Sherlock v2.0 for Android",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Spacer(Modifier.height(12.dp))
    Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SettingsToggle(title: String, subtitle: String, icon: ImageVector, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun SettingsClickItem(title: String, value: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(title, fontWeight = FontWeight.Medium, Modifier.weight(1f))
            Text(value, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SettingsSlider(title: String, value: Int, min: Int, max: Int, icon: ImageVector, onChange: (Int) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(title, fontWeight = FontWeight.Medium, Modifier.weight(1f))
                Text("$value", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { onChange(it.toInt()) },
                valueRange = min.toFloat()..max.toFloat(),
                steps = max - min - 1
            )
        }
    }
}
