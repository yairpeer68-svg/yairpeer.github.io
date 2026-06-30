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
import com.sherlock.app.data.model.SslCertInfo
import com.sherlock.app.data.repository.NetworkToolsRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SslCertificateScreen(onNavigateBack: () -> Unit) {
    val repository = remember { NetworkToolsRepository() }
    val scope = rememberCoroutineScope()
    var domain by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<SslCertInfo?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("בדיקת אישור SSL") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("בדוק את פרטי אישור האבטחה הציבורי של אתר - תוקף, מנפיק ודומיינים נוספים", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = domain, onValueChange = { domain = it },
                label = { Text("דומיין") },
                placeholder = { Text("example.com") },
                leadingIcon = { Icon(Icons.Default.Lock, null) },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Button(
                onClick = {
                    isLoading = true; error = null; result = null
                    scope.launch {
                        try {
                            result = repository.getSslCertificate(domain)
                        } catch (e: Exception) {
                            error = "שגיאה: ${e.message}"
                        } finally { isLoading = false }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = domain.isNotBlank() && !isLoading
            ) {
                if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                else { Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("בדוק אישור") }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            result?.let { r ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (r.isExpired) Icons.Default.Cancel else Icons.Default.VerifiedUser,
                                null,
                                tint = if (r.isExpired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (r.isExpired) "האישור פג תוקף" else "האישור בתוקף",
                                fontWeight = FontWeight.Bold, fontSize = 16.sp
                            )
                        }
                        SslInfoRow("דומיין", r.domain)
                        SslInfoRow("מנפיק", r.issuer)
                        SslInfoRow("נושא", r.subject)
                        SslInfoRow("בתוקף מ", r.validFrom)
                        SslInfoRow("בתוקף עד", r.validTo)
                        if (!r.isExpired) SslInfoRow("ימים עד פקיעה", "${r.daysUntilExpiry}")
                        if (r.sanDomains.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text("דומיינים נוספים באישור:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            r.sanDomains.forEach { Text("• $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SslInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text("$label: ", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.width(110.dp))
        Text(value, fontSize = 13.sp)
    }
}
