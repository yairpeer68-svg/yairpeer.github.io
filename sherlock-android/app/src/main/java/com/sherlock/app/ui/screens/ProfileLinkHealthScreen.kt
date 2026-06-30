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
import com.sherlock.app.data.model.LinkHealthResult
import com.sherlock.app.data.repository.SocialRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileLinkHealthScreen(onNavigateBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val repository = remember { SocialRepository() }
    var urlsText by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<LinkHealthResult>>(emptyList()) }
    var isChecking by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("בדיקת תקינות קישורים") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("הדבק קישורי פרופיל, שורה לכל קישור:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = urlsText,
                onValueChange = { urlsText = it },
                modifier = Modifier.fillMaxWidth().height(140.dp),
                placeholder = { Text("https://...\nhttps://...") },
                minLines = 4
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val urls = urlsText.lines().map { it.trim() }.filter { it.isNotBlank() }
                    if (urls.isNotEmpty()) {
                        isChecking = true
                        results = emptyList()
                        scope.launch {
                            results = urls.map { repository.checkLinkHealth(it) }
                            isChecking = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = urlsText.isNotBlank() && !isChecking
            ) {
                if (isChecking) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.NetworkCheck, null)
                    Spacer(Modifier.width(8.dp))
                    Text("בדוק תקינות קישורים")
                }
            }
            Spacer(Modifier.height(16.dp))

            if (results.isNotEmpty()) {
                Text(
                    "${results.count { it.isAlive }}/${results.size} קישורים פעילים",
                    fontWeight = FontWeight.Bold, fontSize = 14.sp
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(results) { result ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (result.isAlive) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                    null,
                                    tint = if (result.isAlive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(result.url, fontSize = 12.sp, maxLines = 1)
                                    Text(
                                        result.statusCode?.let { "קוד תגובה: $it" } ?: (result.errorMessage ?: "לא ניתן להתחבר"),
                                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
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
