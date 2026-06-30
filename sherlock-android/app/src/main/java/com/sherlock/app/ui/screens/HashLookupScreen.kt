package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
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
import com.sherlock.app.data.model.HashIdentification
import com.sherlock.app.data.repository.OsintToolsRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HashLookupScreen(onNavigateBack: () -> Unit) {
    val repository = remember { OsintToolsRepository() }
    var hash by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<HashIdentification?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("זיהוי וחיפוש Hash") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "זיהוי סוג ה-hash לפי תבנית, ובדיקה מול מאגר מקומי של ערכי hash נפוצים וידועים (ללא פיצוח פעיל)",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = hash,
                onValueChange = { hash = it.trim(); result = if (hash.isNotBlank()) repository.identifyHash(hash) else null },
                label = { Text("ערך Hash") },
                leadingIcon = { Icon(Icons.Default.Tag, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            result?.let { r ->
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Fingerprint, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("סוגים אפשריים:", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        r.possibleTypes.forEach { type ->
                            Text("• $type", fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        if (r.knownPlaintext != null) {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text("נמצאה התאמה במאגר מקומי", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onErrorContainer)
                                        Text("הערך המקורי: ${r.knownPlaintext}", color = MaterialTheme.colorScheme.onErrorContainer)
                                    }
                                }
                            }
                        } else {
                            Text("לא נמצאה התאמה במאגר הסיסמאות הנפוצות המקומי", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
