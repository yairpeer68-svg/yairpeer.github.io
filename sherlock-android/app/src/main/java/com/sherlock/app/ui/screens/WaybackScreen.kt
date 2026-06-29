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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sherlock.app.data.model.WaybackResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaybackScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<WaybackResult>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wayback Machine") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("כתובת URL") },
                leadingIcon = { Icon(Icons.Default.Language, null) },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    isLoading = true; error = null; results = emptyList()
                    scope.launch {
                        try {
                            results = withContext(Dispatchers.IO) { fetchWaybackSnapshots(url) }
                        } catch (e: Exception) {
                            error = "שגיאה: ${e.message}"
                        } finally { isLoading = false }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = url.isNotBlank() && !isLoading
            ) {
                Icon(Icons.Default.History, null)
                Spacer(Modifier.width(8.dp))
                Text("חפש גרסאות ישנות")
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val target = if (!url.startsWith("http")) "https://$url" else url
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://web.archive.org/web/*/$target")))
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = url.isNotBlank()
            ) {
                Text("פתח ב-Wayback Machine")
            }

            Spacer(Modifier.height(16.dp))

            if (isLoading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results) { snapshot ->
                    Card(modifier = Modifier.fillMaxWidth(), onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(snapshot.archiveUrl)))
                    }) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Archive, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(snapshot.timestamp, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(snapshot.url, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun fetchWaybackSnapshots(url: String): List<WaybackResult> {
    val target = if (!url.startsWith("http")) "https://$url" else url
    val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    val request = Request.Builder()
        .url("https://web.archive.org/cdx/search/cdx?url=$target&output=json&limit=20&fl=timestamp,original&collapse=timestamp:8")
        .build()
    val response = client.newCall(request).execute()
    val body = response.body?.string() ?: return emptyList()
    val type = object : TypeToken<List<List<String>>>() {}.type
    val data: List<List<String>> = Gson().fromJson(body, type)
    return data.drop(1).map { row ->
        WaybackResult(
            url = row.getOrElse(1) { target },
            timestamp = formatTimestamp(row.getOrElse(0) { "" }),
            archiveUrl = "https://web.archive.org/web/${row.getOrElse(0) { "" }}/$target"
        )
    }
}

private fun formatTimestamp(ts: String): String {
    if (ts.length < 8) return ts
    return "${ts.substring(0, 4)}-${ts.substring(4, 6)}-${ts.substring(6, 8)}"
}
