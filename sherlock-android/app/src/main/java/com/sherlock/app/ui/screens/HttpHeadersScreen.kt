package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.model.HttpHeaderResult
import com.sherlock.app.data.repository.NetworkToolsRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HttpHeadersScreen(onNavigateBack: () -> Unit) {
    val repository = remember { NetworkToolsRepository() }
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<HttpHeaderResult?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ניתוח HTTP Headers") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("בדוק כותרות תגובה וזהה טכנולוגיות בהן משתמש האתר", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("כתובת אתר") },
                placeholder = { Text("example.com") },
                leadingIcon = { Icon(Icons.Default.Http, null) },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    isLoading = true; error = null; result = null
                    scope.launch {
                        try {
                            result = repository.getHttpHeaders(url)
                        } catch (e: Exception) {
                            error = "שגיאה: ${e.message}"
                        } finally { isLoading = false }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = url.isNotBlank() && !isLoading
            ) {
                if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                else { Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("נתח") }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }

            result?.let { r ->
                Spacer(Modifier.height(12.dp))
                Text("קוד תגובה: ${r.statusCode}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (r.detectedTech.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("טכנולוגיות שזוהו:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    FlowRowChips(r.detectedTech)
                }
                Spacer(Modifier.height(8.dp))
                Text("כותרות:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(r.headers) { (name, value) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text("$name: ", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FlowRowChips(tech: List<String>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tech.take(3).forEach { AssistChip(onClick = {}, label = { Text(it, fontSize = 11.sp) }) }
    }
    if (tech.size > 3) {
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tech.drop(3).forEach { AssistChip(onClick = {}, label = { Text(it, fontSize = 11.sp) }) }
        }
    }
}
