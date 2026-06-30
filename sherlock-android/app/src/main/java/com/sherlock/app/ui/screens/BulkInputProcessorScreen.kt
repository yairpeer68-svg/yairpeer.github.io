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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class InputType(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    USERNAME("שמות משתמש", Icons.Default.Person),
    EMAIL("כתובות מייל", Icons.Default.Email),
    DOMAIN("דומיינים", Icons.Default.Language),
    IP("כתובות IP", Icons.Default.Router)
}

private fun classifyEntry(entry: String): InputType {
    return when {
        entry.contains("@") -> InputType.EMAIL
        entry.matches(Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")) -> InputType.IP
        entry.matches(Regex("""[a-zA-Z0-9\-]+\.[a-zA-Z]{2,}""")) && !entry.contains(" ") -> InputType.DOMAIN
        else -> InputType.USERNAME
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkInputProcessorScreen(
    onNavigateBack: () -> Unit,
    onSearchUsername: ((String) -> Unit)? = null,
    onSearchEmail: ((String) -> Unit)? = null
) {
    var rawInput by remember { mutableStateOf("") }
    var forcedType by remember { mutableStateOf<InputType?>(null) }
    var processed by remember { mutableStateOf<List<Pair<String, InputType>>>(emptyList()) }

    fun process() {
        processed = rawInput.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { entry -> entry to (forcedType ?: classifyEntry(entry)) }
            .distinctBy { it.first }
    }

    val typeCounts = remember(processed) {
        InputType.values().associate { type -> type to processed.count { it.second == type } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("עיבוד קלט מרוכז") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "הדבד רשימה של שמות משתמש, כתובות מייל, דומיינים או IPs (שורה אחת לכל פריט) לניתוח ועיבוד מהיר",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = rawInput,
                onValueChange = { rawInput = it },
                label = { Text("הדבד רשימה") },
                modifier = Modifier.fillMaxWidth().height(150.dp),
                maxLines = 8
            )

            Text("סוג קלט:", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = forcedType == null, onClick = { forcedType = null }, label = { Text("זיהוי אוטומטי") })
                InputType.values().forEach { type ->
                    FilterChip(
                        selected = forcedType == type,
                        onClick = { forcedType = if (forcedType == type) null else type },
                        label = { Text(type.label) },
                        leadingIcon = { Icon(type.icon, null, Modifier.size(14.dp)) }
                    )
                }
            }

            Button(
                onClick = { process() },
                modifier = Modifier.fillMaxWidth(),
                enabled = rawInput.isNotBlank()
            ) {
                Icon(Icons.Default.PlaylistAddCheck, null)
                Spacer(Modifier.width(8.dp))
                Text("עבד רשימה")
            }

            if (processed.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InputType.values().forEach { type ->
                        val count = typeCounts[type] ?: 0
                        if (count > 0) {
                            AssistChip(
                                onClick = {},
                                label = { Text("${type.label}: $count") },
                                leadingIcon = { Icon(type.icon, null, Modifier.size(14.dp)) }
                            )
                        }
                    }
                }

                Text("${processed.size} פריטים:", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(processed) { (entry, type) ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(type.icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(entry, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Text(type.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
