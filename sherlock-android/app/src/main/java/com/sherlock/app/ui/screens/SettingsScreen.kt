package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.data.model.AppLanguage
import com.sherlock.app.data.model.AppTheme
import com.sherlock.app.data.model.FontScale
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
    val currentLanguage by settings.language.collectAsState(initial = AppLanguage.HEBREW)
    val currentFontScale by settings.fontScale.collectAsState(initial = FontScale.MEDIUM)
    val reduceMotion by settings.reduceMotion.collectAsState(initial = false)
    val compactDensity by settings.compactDensity.collectAsState(initial = false)
    val autoFavorite by settings.autoFavoriteOnFound.collectAsState(initial = false)
    val autoExport by settings.autoExportOnComplete.collectAsState(initial = false)
    val autoCleanDays by settings.autoCleanDays.collectAsState(initial = 0)
    val appLockEnabled by settings.appLockEnabled.collectAsState(initial = false)
    val autoLockTimeout by settings.autoLockTimeoutMinutes.collectAsState(initial = 0)
    val screenshotProtection by settings.screenshotProtection.collectAsState(initial = false)
    val globalIncognito by settings.globalIncognito.collectAsState(initial = false)
    val hibpApiKey by settings.hibpApiKey.collectAsState(initial = "")
    val keepScreenOn by settings.keepScreenOn.collectAsState(initial = false)
    val clipboardMonitor by settings.clipboardMonitor.collectAsState(initial = false)

    var showThemeDialog by remember { mutableStateOf(false) }
    var showPanicDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showFontScaleDialog by remember { mutableStateOf(false) }
    var showAutoCleanDialog by remember { mutableStateOf(false) }
    var showSetPinDialog by remember { mutableStateOf(false) }
    var showAutoLockDialog by remember { mutableStateOf(false) }
    var showAccessLogDialog by remember { mutableStateOf(false) }
    var showHibpKeyDialog by remember { mutableStateOf(false) }
    var hibpKeyInput by remember { mutableStateOf("") }
    var panicPin by remember { mutableStateOf("") }
    var panicError by remember { mutableStateOf<String?>(null) }

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

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text("שפה") },
            text = {
                Column {
                    AppLanguage.entries.forEach { lang ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentLanguage == lang,
                                onClick = {
                                    scope.launch { settings.setLanguage(lang) }
                                    showLanguageDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(lang.displayName)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLanguageDialog = false }) { Text("סגור") } }
        )
    }

    if (showFontScaleDialog) {
        AlertDialog(
            onDismissRequest = { showFontScaleDialog = false },
            title = { Text("גודל טקסט") },
            text = {
                Column {
                    FontScale.entries.forEach { scale ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentFontScale == scale,
                                onClick = {
                                    scope.launch { settings.setFontScale(scale) }
                                    showFontScaleDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(scale.hebrewName)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFontScaleDialog = false }) { Text("סגור") } }
        )
    }

    if (showAutoCleanDialog) {
        val options = listOf(0, 30, 60, 90)
        AlertDialog(
            onDismissRequest = { showAutoCleanDialog = false },
            title = { Text("ניקוי היסטוריה אוטומטי") },
            text = {
                Column {
                    options.forEach { days ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = autoCleanDays == days,
                                onClick = {
                                    scope.launch { settings.setAutoCleanDays(days) }
                                    showAutoCleanDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (days == 0) "כבוי" else "מחק חיפושים מעל $days יום")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAutoCleanDialog = false }) { Text("סגור") } }
        )
    }

    if (showSetPinDialog) {
        var pin1 by remember { mutableStateOf("") }
        var pin2 by remember { mutableStateOf("") }
        var pinError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showSetPinDialog = false },
            title = { Text("הגדרת קוד PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("קוד ה-PIN ישמש כגיבוי לנעילה הביומטרית.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = pin1,
                        onValueChange = { if (it.length <= 8) pin1 = it },
                        label = { Text("קוד PIN חדש") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    OutlinedTextField(
                        value = pin2,
                        onValueChange = { if (it.length <= 8) pin2 = it },
                        label = { Text("אימות קוד PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = pinError != null,
                        supportingText = { pinError?.let { Text(it) } }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (pin1.length < 4) {
                        pinError = "הקוד חייב להכיל לפחות 4 ספרות"
                    } else if (pin1 != pin2) {
                        pinError = "הקודים אינם תואמים"
                    } else {
                        scope.launch {
                            settings.setAppLockPin(pin1)
                            settings.setAppLockEnabled(true)
                        }
                        showSetPinDialog = false
                    }
                }) { Text("שמור") }
            },
            dismissButton = { TextButton(onClick = { showSetPinDialog = false }) { Text("ביטול") } }
        )
    }

    if (showAutoLockDialog) {
        val options = listOf(0, 1, 5, 15, 30)
        AlertDialog(
            onDismissRequest = { showAutoLockDialog = false },
            title = { Text("נעילה אוטומטית") },
            text = {
                Column {
                    options.forEach { minutes ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = autoLockTimeout == minutes,
                                onClick = {
                                    scope.launch { settings.setAutoLockTimeoutMinutes(minutes) }
                                    showAutoLockDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (minutes == 0) "מידי" else "אחרי $minutes דקות ברקע")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAutoLockDialog = false }) { Text("סגור") } }
        )
    }

    if (showAccessLogDialog) {
        val accessLog by db.accessLogDao().getRecent().collectAsState(initial = emptyList())
        val dateFormat = remember { java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault()) }
        AlertDialog(
            onDismissRequest = { showAccessLogDialog = false },
            title = { Text("יומן גישה") },
            text = {
                if (accessLog.isEmpty()) {
                    Text("אין רשומי גישה")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        items(accessLog) { entry ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (entry.success) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                    null,
                                    tint = if (entry.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(entry.method, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text(dateFormat.format(java.util.Date(entry.timestamp)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAccessLogDialog = false }) { Text("סגור") } },
            dismissButton = {
                TextButton(onClick = { scope.launch { db.accessLogDao().clearAll() } }) { Text("נקה יומן") }
            }
        )
    }

    if (showHibpKeyDialog) {
        AlertDialog(
            onDismissRequest = { showHibpKeyDialog = false },
            title = { Text("מפתח API - HaveIBeenPwned") },
            text = {
                Column {
                    Text(
                        "בדיקת דליפות מידע דורשת מפתח API אישי מ-haveibeenpwned.com (בתשלום סמלי). המפתח נשמר מקומית במכשיר בלבד.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = hibpKeyInput,
                        onValueChange = { hibpKeyInput = it },
                        label = { Text("מפתח API") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { settings.setHibpApiKey(hibpKeyInput.trim()) }
                    showHibpKeyDialog = false
                }) { Text("שמור") }
            },
            dismissButton = { TextButton(onClick = { showHibpKeyDialog = false }) { Text("ביטול") } }
        )
    }

    if (showPanicDialog) {
        AlertDialog(
            onDismissRequest = { showPanicDialog = false; panicPin = ""; panicError = null },
            title = { Text("כפתור פאניקה", color = MaterialTheme.colorScheme.error) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("פעולה זו תמחק את כל הנתונים באפליקציה: היסטוריה, מועדפים, פרויקטים, הערות, זהויות דיגיטליות והגדרות. פעולה זו בלתי הפיכה!")
                    if (appLockEnabled) {
                        OutlinedTextField(
                            value = panicPin,
                            onValueChange = { panicPin = it; panicError = null },
                            label = { Text("הזן קוד PIN לאישור") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            isError = panicError != null,
                            supportingText = { panicError?.let { Text(it) } }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (appLockEnabled && !settings.verifyAppLockPin(panicPin)) {
                            panicError = "קוד שגוי"
                            return@launch
                        }
                        db.searchHistoryDao().clearAllHistory()
                        db.searchHistoryDao().clearAllResults()
                        db.favoriteDao().clearAllFavorites()
                        db.favoriteDao().clearAllTags()
                        db.monitoredProfileDao().clearAll()
                        db.projectDao().clearAll()
                        db.noteDao().clearAll()
                        db.templateDao().clearAll()
                        db.customSiteDao().clearAll()
                        db.imageHashDao().clearAll()
                        db.projectTaskDao().clearAll()
                        db.digitalIdentityDao().clearAll()
                        db.identityLinkDao().clearAll()
                        db.scheduledSearchDao().clearAll()
                        db.savedLinkDao().clearAll()
                        db.accessLogDao().clearAll()
                        settings.panicClear()
                        panicPin = ""
                        showPanicDialog = false
                    }
                }) { Text("מחק הכל", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showPanicDialog = false; panicPin = ""; panicError = null }) { Text("ביטול") } }
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
            item {
                SettingsClickItem("שפה", currentLanguage.displayName, Icons.Default.Language) { showLanguageDialog = true }
            }
            item {
                SettingsClickItem("גודל טקסט", currentFontScale.hebrewName, Icons.Default.FormatSize) { showFontScaleDialog = true }
            }
            item {
                SettingsToggle("הפחתת אנימציות", "ביטול אפקטי מעבר וגלילה", Icons.Default.MotionPhotosOff, reduceMotion) {
                    scope.launch { settings.setReduceMotion(it) }
                }
            }
            item {
                SettingsToggle("תצוגה קומפקטית", "רשימות צפופות יותר", Icons.Default.ViewCompact, compactDensity) {
                    scope.launch { settings.setCompactDensity(it) }
                }
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
            item {
                SettingsToggle("שמור מסך דלוק", "מנע כיבוי מסך בזמן חיפוש", Icons.Default.ScreenLockPortrait, keepScreenOn) {
                    scope.launch { settings.setKeepScreenOn(it) }
                }
            }
            item {
                SettingsToggle("זיהוי לוח גזירות", "הצע חיפוש אוטומטי מתוכן שהועתק", Icons.Default.ContentPaste, clipboardMonitor) {
                    scope.launch { settings.setClipboardMonitor(it) }
                }
            }

            item { SectionHeader("אוטומציה") }
            item {
                SettingsToggle("הוספה אוטומטית למועדפים", "תוצאות שנמצאו יתווספו אוטומטית", Icons.Default.Star, autoFavorite) {
                    scope.launch { settings.setAutoFavoriteOnFound(it) }
                }
            }
            item {
                SettingsToggle("ייצוא אוטומטי בסיום חיפוש", "שמירת CSV אוטומטית", Icons.Default.Download, autoExport) {
                    scope.launch { settings.setAutoExportOnComplete(it) }
                }
            }
            item {
                SettingsClickItem(
                    "ניקוי היסטוריה אוטומטי",
                    if (autoCleanDays > 0) "מעל $autoCleanDays יום" else "כבוי",
                    Icons.Default.AutoDelete
                ) { showAutoCleanDialog = true }
            }

            item { SectionHeader("אבטחה ופרטיות") }
            item {
                SettingsToggle("נעילת אפליקציה", "ביומטריה/PIN בכניסה לאפליקציה", Icons.Default.Fingerprint, appLockEnabled) { enabled ->
                    if (enabled) {
                        showSetPinDialog = true
                    } else {
                        scope.launch { settings.clearAppLockPin() }
                    }
                }
            }
            if (appLockEnabled) {
                item {
                    SettingsClickItem(
                        "נעילה אוטומטית",
                        if (autoLockTimeout == 0) "מידי" else "אחרי $autoLockTimeout דקות",
                        Icons.Default.LockClock
                    ) { showAutoLockDialog = true }
                }
            }
            item {
                SettingsToggle("הגנת צילום מסך", "חסימת צילומי מסך והצגה ברשימת אפליקציות", Icons.Default.Shield, screenshotProtection) {
                    scope.launch { settings.setScreenshotProtection(it) }
                }
            }
            item {
                SettingsToggle("מצב סמוי גלובלי", "ברירת מחדל: אל תשמור חיפושים חדשים בהיסטוריה", Icons.Default.VisibilityOff, globalIncognito) {
                    scope.launch { settings.setGlobalIncognito(it) }
                }
            }
            item {
                SettingsClickItem(
                    "יומן גישה",
                    "היסטוריית ניסיונות כניסה לאפליקציה",
                    Icons.Default.History
                ) { showAccessLogDialog = true }
            }
            item {
                SettingsClickItem(
                    "מפתח API - HaveIBeenPwned",
                    if (hibpApiKey.isNotBlank()) "מוגדר" else "לא מוגדר - נדרש לבדיקת דליפות",
                    Icons.Default.Key
                ) { hibpKeyInput = hibpApiKey; showHibpKeyDialog = true }
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
                            Text("מחיקת כל הנתונים מידית", fontSize = 12.sp, color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
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
            Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
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
                Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
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
