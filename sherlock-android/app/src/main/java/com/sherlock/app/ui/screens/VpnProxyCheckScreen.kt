package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.model.VpnProxyResult
import com.sherlock.app.data.repository.NetworkToolsRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnProxyCheckScreen(onNavigateBack: () -> Unit) {
    val repository = remember { NetworkToolsRepository() }
    val scope = rememberCoroutineScope()
    var ip by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<VpnProxyResult?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("זיהוי VPN / Proxy / Hosting") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("בדוק האם כתובת IP שייכת לשירות VPN, פרוקסי או ספק אחסון", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = ip, onValueChange = { ip = it },
                label = { Text("כתובת IP") },
                placeholder = { Text("8.8.8.8") },
                leadingIcon = { Icon(Icons.Default.Shield, null) },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Button(
                onClick = {
                    isLoading = true; error = null; result = null
                    scope.launch {
                        try {
                            result = repository.checkVpnProxyHosting(ip.trim())
                        } catch (e: Exception) {
                            error = "שגיאה: ${e.message}"
                        } finally { isLoading = false }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = ip.isNotBlank() && !isLoading
            ) {
                if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                else { Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("בדוק") }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            result?.let { r ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(r.ip, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        VpnFlagRow("VPN / Proxy", r.isProxy)
                        VpnFlagRow("שרת אחסון (Hosting/Data Center)", r.isHosting)
                        VpnFlagRow("רשת סלולרית", r.isMobile)
                        Divider()
                        Row(Modifier.fillMaxWidth()) {
                            Text("ארגון: ", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(r.org, fontSize = 13.sp)
                        }
                        Row(Modifier.fillMaxWidth()) {
                            Text("ASN: ", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(r.asn, fontSize = 13.sp)
                        }
                        if (r.isProxy || r.isHosting) {
                            Text(
                                "⚠ כתובת זו עשויה להסתיר את מיקומו האמיתי של המשתמש",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VpnFlagRow(label: String, value: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (value) Icons.Default.Warning else Icons.Default.CheckCircle,
            null,
            tint = if (value) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.sp)
    }
}
