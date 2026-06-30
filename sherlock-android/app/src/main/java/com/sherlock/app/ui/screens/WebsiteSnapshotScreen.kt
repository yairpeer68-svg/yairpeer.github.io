package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sherlock.app.data.model.WebsiteSnapshot
import com.sherlock.app.data.repository.NetworkToolsRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebsiteSnapshotScreen(onNavigateBack: () -> Unit) {
    val repository = remember { NetworkToolsRepository() }
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<WebsiteSnapshot?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("תקציר אתר") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("שלוף כותרת, תיאור ואייקון של אתר מבלי לפתוח דפדפן", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("כתובת אתר") },
                placeholder = { Text("example.com") },
                leadingIcon = { Icon(Icons.Default.Language, null) },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    isLoading = true; error = null; result = null
                    scope.launch {
                        try {
                            result = repository.getWebsiteSnapshot(url)
                        } catch (e: Exception) {
                            error = "שגיאה: ${e.message}"
                        } finally { isLoading = false }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = url.isNotBlank() && !isLoading
            ) {
                if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                else { Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("שלוף תקציר") }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }

            result?.let { r ->
                Spacer(Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            r.faviconUrl?.let {
                                AsyncImage(
                                    model = it, contentDescription = null,
                                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp))
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(r.title ?: "ללא כותרת", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        }
                        if (r.description != null) {
                            Text(r.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(r.finalUrl, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        if (r.redirected) {
                            Text("⚠ הכתובת הופנתה לכתובת אחרת", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
