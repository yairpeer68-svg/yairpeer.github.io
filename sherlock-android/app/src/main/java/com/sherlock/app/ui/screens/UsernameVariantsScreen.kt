package com.sherlock.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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

private fun generateVariants(input: String): List<String> {
    val cleaned = input.trim().lowercase()
    val noSpaces = cleaned.replace(" ", "")
    val withDot = cleaned.replace(" ", ".")
    val withDash = cleaned.replace(" ", "-")
    val withUnderscore = cleaned.replace(" ", "_")
    val parts = cleaned.split(" ")
    val first = parts.firstOrNull() ?: cleaned
    val last = parts.lastOrNull()?.takeIf { it != first } ?: ""

    val variants = mutableSetOf<String>()
    variants.add(noSpaces)
    if (withDot != noSpaces) variants.add(withDot)
    if (withDash != noSpaces) variants.add(withDash)
    variants.add(withUnderscore)
    if (last.isNotBlank()) {
        variants.add("$first$last")
        variants.add("$first.$last")
        variants.add("$first${last[0]}")
        variants.add("${first[0]}$last")
        variants.add("${first[0]}.$last")
        variants.add("${first[0]}_$last")
        variants.add("$last$first")
        variants.add("$last.$first")
        variants.add("$last${first[0]}")
    }
    listOf("", "1", "2", "123", "99", "0", "x", "_official", "_real", "the").forEach { suffix ->
        if (suffix.isNotBlank()) {
            variants.add("$noSpaces$suffix")
            if (last.isNotBlank()) variants.add("$first$suffix")
        }
    }
    return variants.filter { it.length >= 2 }.sorted()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsernameVariantsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var variants by remember { mutableStateOf<List<String>>(emptyList()) }

    fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("username", text))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("וריאנטים של שם משתמש") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "הזן שם אמיתי או שם משתמש ותקבל רשימת וריאנטים נפוצים לחיפוש בפלטפורמות שונות",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; variants = if (it.trim().length >= 2) generateVariants(it) else emptyList() },
                label = { Text("שם אמיתי או שם משתמש") },
                placeholder = { Text("ישראל ישראלי") },
                leadingIcon = { Icon(Icons.Default.Person, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (variants.isNotEmpty()) {
                Text("${variants.size} וריאנטים אפשריים:", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(variants) { variant ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(variant, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                IconButton(onClick = { copyToClipboard(variant) }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
