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
import com.sherlock.app.data.model.DnsRecordResult
import com.sherlock.app.data.repository.NetworkToolsRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsRecordsScreen(onNavigateBack: () -> Unit) {
    val repository = remember { NetworkToolsRepository() }
    val scope = rememberCoroutineScope()
    var domain by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<DnsRecordResult>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("רשומות DNS מתקדמות") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("שלוף רשומות MX, TXT, NS, CNAME ועוד עבור דומיין", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = domain, onValueChange = { domain = it },
                label = { Text("דומיין") },
                placeholder = { Text("example.com") },
                leadingIcon = { Icon(Icons.Default.Dns, null) },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    isLoading = true; error = null; results = emptyList(); searched = true
                    scope.launch {
                        try {
                            results = repository.getDnsRecords(domain)
                        } catch (e: Exception) {
                            error = "שגיאה: ${e.message}"
                        } finally { isLoading = false }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = domain.isNotBlank() && !isLoading
            ) {
                if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                else { Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("שלוף רשומות") }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }

            if (searched && !isLoading && error == null && results.isEmpty()) {
                Text("לא נמצאו רשומות", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(results) { record ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AssistChip(onClick = {}, label = { Text(record.type) }, enabled = false)
                            Spacer(Modifier.width(10.dp))
                            Text(record.value, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
