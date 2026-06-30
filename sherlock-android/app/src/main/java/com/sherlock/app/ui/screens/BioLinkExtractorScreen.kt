package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.repository.SocialRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BioLinkExtractorScreen(onNavigateBack: () -> Unit) {
    val repository = remember { SocialRepository() }
    val clipboard = LocalClipboardManager.current
    var bioText by remember { mutableStateOf("") }
    var extracted by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("חילוץ קישורים מביוגרפיה") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("הדבק טקסט ביוגרפיה/פוסט וחלץ ממנו קישורים, אימיילים, טלפונים ושמות משתמש", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = bioText,
                onValueChange = { bioText = it },
                modifier = Modifier.fillMaxWidth().height(160.dp),
                placeholder = { Text("הדבק כאן את תוכן הביו...") },
                minLines = 5
            )
            Button(
                onClick = { extracted = repository.extractContactsFromText(bioText) },
                modifier = Modifier.fillMaxWidth(),
                enabled = bioText.isNotBlank()
            ) {
                Icon(Icons.Default.ContentPaste, null)
                Spacer(Modifier.width(8.dp))
                Text("חלץ פרטי קשר")
            }

            extracted.forEach { (label, items) ->
                if (items.isNotEmpty()) {
                    ExtractedSection(label, items, clipboard)
                }
            }

            if (extracted.isNotEmpty() && extracted.values.all { it.isEmpty() }) {
                Text("לא נמצאו פרטי קשר בטקסט", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ExtractedSection(label: String, items: List<String>, clipboard: ClipboardManager) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("$label (${items.size})", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            items.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(item, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { clipboard.setText(AnnotatedString(item)) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ContentCopy, "העתק", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
