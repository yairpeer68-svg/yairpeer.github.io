package com.sherlock.app.ui.screens

import android.content.Intent
import android.net.Uri
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class PasteHit(val title: String, val url: String, val engine: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasteSearchScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PasteHit>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    val pasteEngines = listOf(
        "Pastebin" to "https://www.google.com/search?q=site:pastebin.com+",
        "Ghostbin" to "https://www.google.com/search?q=site:ghostbin.co+",
        "Dpaste" to "https://www.google.com/search?q=site:dpaste.org+",
        "Hastebin" to "https://www.google.com/search?q=site:hastebin.com+",
        "Rentry" to "https://www.google.com/search?q=site:rentry.co+",
        "Paste.ee" to "https://www.google.com/search?q=site:paste.ee+",
        "GitHub Gist" to "https://www.google.com/search?q=site:gist.github.com+"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("חיפוש Paste") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("חפש שם / אימייל / מידע") },
                leadingIcon = { Icon(Icons.Default.ContentPaste, null) },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    isLoading = true
                    results = pasteEngines.map { (name, baseUrl) ->
                        PasteHit(name, "$baseUrl${Uri.encode(query)}", name)
                    }
                    isLoading = false
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = query.isNotBlank()
            ) {
                Icon(Icons.Default.Search, null)
                Spacer(Modifier.width(8.dp))
                Text("חפש ב-${pasteEngines.size} מנועי paste")
            }

            Spacer(Modifier.height(16.dp))

            if (isLoading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results) { hit ->
                    Card(modifier = Modifier.fillMaxWidth(), onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(hit.url)))
                    }) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ContentPaste, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(hit.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text("חפש \"$query\"", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
