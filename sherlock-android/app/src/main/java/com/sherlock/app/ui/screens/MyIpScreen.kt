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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.model.IpGeoResult
import com.sherlock.app.data.repository.NetworkToolsRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyIpScreen(onNavigateBack: () -> Unit) {
    val repository = remember { NetworkToolsRepository() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var result by remember { mutableStateOf<IpGeoResult?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        isLoading = true
        try {
            result = repository.getMyPublicIp()
        } catch (e: Exception) {
            error = "שגיאה: ${e.message}"
        } finally { isLoading = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("כתובת ה-IP הציבורית שלי") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isLoading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            result?.let { r ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Public, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(r.ip, fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { clipboard.setText(AnnotatedString(r.ip)) }) {
                                Icon(Icons.Default.ContentCopy, "העתק", modifier = Modifier.size(18.dp))
                            }
                        }
                        Divider()
                        MyIpInfoRow("מדינה", r.country)
                        MyIpInfoRow("עיר", r.city)
                        MyIpInfoRow("אזור", r.region)
                        MyIpInfoRow("ספק אינטרנט", r.isp)
                        MyIpInfoRow("אזור זמן", r.timezone)
                    }
                }
            }

            Button(
                onClick = {
                    isLoading = true; error = null; result = null
                    scope.launch {
                        try {
                            result = repository.getMyPublicIp()
                        } catch (e: Exception) {
                            error = "שגיאה: ${e.message}"
                        } finally { isLoading = false }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                Icon(Icons.Default.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text("רענן")
            }
        }
    }
}

@Composable
private fun MyIpInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text("$label: ", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.width(110.dp))
        Text(value, fontSize = 14.sp)
    }
}
