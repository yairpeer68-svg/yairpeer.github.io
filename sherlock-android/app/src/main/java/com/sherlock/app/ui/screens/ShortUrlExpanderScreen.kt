package com.sherlock.app.ui.screens

import android.content.ClipData
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class RedirectHop(val step: Int, val url: String, val statusCode: Int)

private suspend fun expandUrl(inputUrl: String): List<RedirectHop> = withContext(Dispatchers.IO) {
    val hops = mutableListOf<RedirectHop>()
    val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    var currentUrl = inputUrl.trim().let { if (!it.startsWith("http")) "https://$it" else it }
    var step = 1
    val visited = mutableSetOf<String>()

    while (step <= 15 && currentUrl.isNotBlank() && !visited.contains(currentUrl)) {
        visited.add(currentUrl)
        try {
            val request = Request.Builder().url(currentUrl).head().build()
            val response = client.newCall(request).execute()
            hops.add(RedirectHop(step, currentUrl, response.code))
            val location = response.header("Location")
            if (response.code in 300..399 && location != null) {
                currentUrl = if (location.startsWith("http")) location
                else {
                    val base = Uri.parse(currentUrl)
                    base.scheme + "://" + base.host + location
                }
                step++
            } else break
        } catch (e: Exception) {
            hops.add(RedirectHop(step, currentUrl, -1))
            break
        }
    }
    hops
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortUrlExpanderScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var hops by remember { mutableStateOf<List<RedirectHop>?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("url", text))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("הרחבת קישורים מקוצרים") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "הזן קישור מקוצר (bit.ly, tinyurl, t.co וכד') וגלה לאן הוא מוביל — כולל כל שרשרת ההפניות",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it.trim() },
                label = { Text("קישור מקוצר") },
                placeholder = { Text("bit.ly/abc123") },
                leadingIcon = { Icon(Icons.Default.Link, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    isLoading = true; error = null; hops = null
                    scope.launch {
                        try {
                            hops = expandUrl(url)
                        } catch (e: Exception) {
                            error = "שגיאה: ${e.message}"
                        } finally { isLoading = false }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = url.isNotBlank() && !isLoading
            ) {
                if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                else { Icon(Icons.Default.AltRoute, null); Spacer(Modifier.width(8.dp)); Text("הרחב קישור") }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }

            hops?.let { list ->
                if (list.isEmpty()) {
                    Text("לא ניתן לעקוב אחר הקישור", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val final = list.last()
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("קישור סופי:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(final.url, fontSize = 13.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                OutlinedButton(onClick = { copyToClipboard(final.url) }) {
                                    Icon(Icons.Default.ContentCopy, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("העתק")
                                }
                                OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(final.url))) }) {
                                    Icon(Icons.Default.OpenInNew, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("פתח")
                                }
                            }
                        }
                    }

                    if (list.size > 1) {
                        Text("שרשרת הפניות (${list.size} שלבים):", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(list) { hop ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                    Text("${hop.step}.", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(20.dp))
                                    Text(
                                        hop.url.take(60) + if (hop.url.length > 60) "…" else "",
                                        fontSize = 11.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        if (hop.statusCode > 0) "${hop.statusCode}" else "?",
                                        fontSize = 11.sp,
                                        color = if (hop.statusCode in 200..299) androidx.compose.ui.graphics.Color(0xFF4CAF50)
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
