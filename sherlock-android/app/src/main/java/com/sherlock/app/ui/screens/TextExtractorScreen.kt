package com.sherlock.app.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import android.content.ClipData

data class ExtractedItems(
    val emails: List<String>,
    val phones: List<String>,
    val urls: List<String>,
    val ips: List<String>,
    val usernames: List<String>
) {
    val total: Int get() = emails.size + phones.size + urls.size + ips.size + usernames.size
}

private fun extractAll(text: String): ExtractedItems {
    val emails = Regex("""[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}""")
        .findAll(text).map { it.value }.distinct().toList()
    val phones = Regex("""(?:[\+]?[(]?[0-9]{1,4}[)]?[-\s\.]?){1,3}[0-9]{6,10}""")
        .findAll(text).map { it.value.trim() }.filter { it.length >= 7 }.distinct().toList()
    val urls = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""")
        .findAll(text).map { it.value.trimEnd('.', ',', ')') }.distinct().toList()
    val ips = Regex("""\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\b""")
        .findAll(text).map { it.value }.distinct().toList()
    val atMentions = Regex("""@([a-zA-Z0-9_]{2,30})""")
        .findAll(text).map { it.groupValues[1] }.distinct().toList()
    return ExtractedItems(emails, phones, urls, ips, atMentions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextExtractorScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ExtractedItems?>(null) }

    fun pasteFromClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.getItemAt(0)?.text?.toString() ?: return
        inputText = text
        result = extractAll(text)
    }

    fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("text", text))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("חילוץ נתונים מטקסט") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "הדבק כל טקסט וחלץ ממנו אוטומטית כתובות מייל, מספרי טלפון, כתובות URL, כתובות IP ושמות משתמש",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it; result = if (it.isNotBlank()) extractAll(it) else null },
                label = { Text("הדבק טקסט כאן") },
                modifier = Modifier.fillMaxWidth().height(140.dp),
                maxLines = 6
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pasteFromClipboard() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ContentPaste, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("הדבק מלוח")
                }
                Button(
                    onClick = { if (inputText.isNotBlank()) result = extractAll(inputText) },
                    enabled = inputText.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Search, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("חלץ")
                }
            }

            result?.let { r ->
                if (r.total == 0) {
                    Text("לא נמצאו נתונים מובנים בטקסט", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                } else {
                    Text("נמצאו ${r.total} פריטים:", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (r.emails.isNotEmpty()) {
                            item {
                                ExtractCategory("כתובות מייל (${r.emails.size})", Icons.Default.Email, r.emails,
                                    onCopy = { copyToClipboard(it) },
                                    onOpen = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("mailto:$it"))) })
                            }
                        }
                        if (r.phones.isNotEmpty()) {
                            item {
                                ExtractCategory("מספרי טלפון (${r.phones.size})", Icons.Default.Phone, r.phones,
                                    onCopy = { copyToClipboard(it) },
                                    onOpen = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$it"))) })
                            }
                        }
                        if (r.urls.isNotEmpty()) {
                            item {
                                ExtractCategory("כתובות URL (${r.urls.size})", Icons.Default.Language, r.urls,
                                    onCopy = { copyToClipboard(it) },
                                    onOpen = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) })
                            }
                        }
                        if (r.ips.isNotEmpty()) {
                            item {
                                ExtractCategory("כתובות IP (${r.ips.size})", Icons.Default.Router, r.ips,
                                    onCopy = { copyToClipboard(it) },
                                    onOpen = { copyToClipboard(it) })
                            }
                        }
                        if (r.usernames.isNotEmpty()) {
                            item {
                                ExtractCategory("שמות משתמש @(${r.usernames.size})", Icons.Default.Person, r.usernames,
                                    onCopy = { copyToClipboard(it) },
                                    onOpen = { copyToClipboard(it) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtractCategory(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    items: List<String>,
    onCopy: (String) -> Unit,
    onOpen: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onCopy(item) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(14.dp))
                    }
                    IconButton(onClick = { onOpen(item) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.OpenInNew, null, Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}
