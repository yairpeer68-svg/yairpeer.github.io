package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import java.net.InetAddress

data class SubdomainResult(val subdomain: String, val exists: Boolean, val ip: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubdomainScreen(onNavigateBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var domain by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SubdomainResult>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    val commonSubdomains = listOf(
        "www", "mail", "ftp", "admin", "blog", "dev", "staging", "api",
        "app", "cdn", "docs", "forum", "git", "help", "img", "login",
        "m", "media", "mobile", "mx", "ns1", "ns2", "pop", "portal",
        "shop", "smtp", "ssl", "store", "support", "test", "vpn", "webmail",
        "wiki", "cloud", "cpanel", "dashboard", "db", "demo", "dns",
        "download", "email", "files", "gateway", "host", "imap", "info",
        "intranet", "jira", "lab", "ldap", "manage", "monitor", "mysql",
        "news", "office", "panel", "proxy", "remote", "search", "secure",
        "server", "service", "share", "sip", "sql", "ssh", "status",
        "storage", "streaming", "tools", "upload", "video", "voip", "web"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("סריקת תתי-דומיינים") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
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
                    isScanning = true; results = emptyList()
                    scope.launch {
                        val found = mutableListOf<SubdomainResult>()
                        commonSubdomains.forEachIndexed { index, sub ->
                            progress = (index + 1).toFloat() / commonSubdomains.size
                            val fullDomain = "$sub.$domain"
                            try {
                                val ip = withContext(Dispatchers.IO) {
                                    InetAddress.getByName(fullDomain).hostAddress
                                }
                                found.add(SubdomainResult(fullDomain, true, ip))
                                results = found.toList()
                            } catch (_: Exception) { }
                        }
                        isScanning = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = domain.isNotBlank() && !isScanning
            ) {
                Icon(Icons.Default.Radar, null)
                Spacer(Modifier.width(8.dp))
                Text("סרוק ${commonSubdomains.size} תתי-דומיינים")
            }

            if (isScanning) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                Text("סורק... ${(progress * 100).toInt()}%", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }

            Spacer(Modifier.height(8.dp))
            if (results.isNotEmpty()) {
                Text("נמצאו ${results.size} תתי-דומיינים", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(results) { result ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(result.subdomain, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("IP: ${result.ip ?: "N/A"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
