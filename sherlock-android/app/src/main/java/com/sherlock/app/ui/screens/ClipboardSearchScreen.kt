package com.sherlock.app.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
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

private enum class ContentType(val label: String, val description: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    EMAIL("כתובת מייל", "חיפוש דליפות, בדיקת OSINT", Icons.Default.Email),
    DOMAIN("דומיין / URL", "WHOIS, DNS, Wayback Machine", Icons.Default.Language),
    IP("כתובת IP", "Geolocation, בדיקת Proxy", Icons.Default.Router),
    USERNAME("שם משתמש", "חיפוש ב-300+ אתרים", Icons.Default.Person),
    PHONE("מספר טלפון", "Truecaller, WhatsApp", Icons.Default.Phone),
    HASH("ערך Hash", "זיהוי סוג + חיפוש במאגר", Icons.Default.Tag),
    TEXT("טקסט חופשי", "חיפוש Paste, חילוץ נתונים", Icons.Default.Notes),
    UNKNOWN("לא ידוע", "בחר כלי ידנית", Icons.Default.HelpOutline)
}

private fun detectContentType(text: String): ContentType {
    val t = text.trim()
    return when {
        t.matches(Regex("""[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}""")) -> ContentType.EMAIL
        t.matches(Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")) -> ContentType.IP
        t.matches(Regex("""(?:https?://)?[a-zA-Z0-9\-]+\.[a-zA-Z]{2,}(?:/\S*)?""")) && !t.contains(" ") -> ContentType.DOMAIN
        t.matches(Regex("""[a-fA-F0-9]{32,128}""")) && !t.contains(" ") -> ContentType.HASH
        t.matches(Regex("""[\+]?[\d\s\-().]{7,20}""")) && t.any { it.isDigit() } -> ContentType.PHONE
        t.matches(Regex("""[a-zA-Z0-9_\-.]{2,30}""")) && !t.contains(" ") -> ContentType.USERNAME
        t.length > 50 -> ContentType.TEXT
        else -> ContentType.UNKNOWN
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardSearchScreen(
    onNavigateBack: () -> Unit,
    onNavigateToUsernameSearch: () -> Unit = {},
    onNavigateToEmailSearch: () -> Unit = {},
    onNavigateToBreachCheck: () -> Unit = {},
    onNavigateToDomainLookup: () -> Unit = {},
    onNavigateToIpGeolocation: () -> Unit = {},
    onNavigateToPhoneSearch: () -> Unit = {},
    onNavigateToHashLookup: () -> Unit = {},
    onNavigateToWaybackMachine: () -> Unit = {},
    onNavigateToTextExtractor: () -> Unit = {},
    onNavigateToPasteSearch: () -> Unit = {}
) {
    val context = LocalContext.current
    var clipboardText by remember { mutableStateOf("") }
    var detectedType by remember { mutableStateOf(ContentType.UNKNOWN) }
    var manualText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
        if (text.isNotBlank() && text.length < 500) {
            clipboardText = text
            detectedType = detectContentType(text)
            manualText = text
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("חיפוש מהיר מלוח") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "זיהוי אוטומטי של תוכן לוח הגזירות ועיתוד לכלי החיפוש המתאים",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = manualText,
                onValueChange = { manualText = it; detectedType = if (it.isNotBlank()) detectContentType(it) else ContentType.UNKNOWN },
                label = { Text("תוכן לחיפוש") },
                leadingIcon = { Icon(Icons.Default.ContentPaste, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (clipboardText.isNotBlank() && clipboardText != manualText) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ContentPaste, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("בלוח הגזירות:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(clipboardText.take(60) + if (clipboardText.length > 60) "…" else "", fontSize = 13.sp)
                        }
                        TextButton(onClick = { manualText = clipboardText; detectedType = detectContentType(clipboardText) }) {
                            Text("השתמש")
                        }
                    }
                }
            }

            if (manualText.isNotBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(detectedType.icon, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("זוהה כ: ${detectedType.label}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(detectedType.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Text("כלים מומלצים:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (detectedType) {
                        ContentType.EMAIL -> {
                            SuggestedTool("חיפוש שם משתמש / אימייל", Icons.Default.Person, onNavigateToEmailSearch)
                            SuggestedTool("בדיקת דליפות מידע", Icons.Default.Warning, onNavigateToBreachCheck)
                            SuggestedTool("חיפוש Paste", Icons.Default.Description, onNavigateToPasteSearch)
                        }
                        ContentType.DOMAIN -> {
                            SuggestedTool("בדיקת דומיין (WHOIS)", Icons.Default.Language, onNavigateToDomainLookup)
                            SuggestedTool("Wayback Machine", Icons.Default.History, onNavigateToWaybackMachine)
                        }
                        ContentType.IP -> {
                            SuggestedTool("מיקום IP / Geolocation", Icons.Default.LocationOn, onNavigateToIpGeolocation)
                        }
                        ContentType.USERNAME -> {
                            SuggestedTool("חיפוש שם משתמש", Icons.Default.Person, onNavigateToUsernameSearch)
                            SuggestedTool("חיפוש Paste", Icons.Default.Description, onNavigateToPasteSearch)
                        }
                        ContentType.PHONE -> {
                            SuggestedTool("חיפוש מספר טלפון", Icons.Default.Phone, onNavigateToPhoneSearch)
                        }
                        ContentType.HASH -> {
                            SuggestedTool("זיהוי וחיפוש Hash", Icons.Default.Tag, onNavigateToHashLookup)
                        }
                        ContentType.TEXT -> {
                            SuggestedTool("חילוץ נתונים מטקסט", Icons.Default.ManageSearch, onNavigateToTextExtractor)
                            SuggestedTool("חיפוש Paste", Icons.Default.Description, onNavigateToPasteSearch)
                        }
                        ContentType.UNKNOWN -> {
                            SuggestedTool("חיפוש שם משתמש", Icons.Default.Person, onNavigateToUsernameSearch)
                            SuggestedTool("חילוץ נתונים מטקסט", Icons.Default.ManageSearch, onNavigateToTextExtractor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestedTool(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, modifier = Modifier.weight(1f))
        Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp))
    }
}
