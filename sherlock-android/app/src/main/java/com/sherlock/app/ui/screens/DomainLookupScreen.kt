package com.sherlock.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DomainLookupScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    var domain by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var dnsResults by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun lookup() {
        if (domain.isBlank()) return
        keyboardController?.hide()
        isSearching = true
        errorMessage = null
        dnsResults = emptyList()

        scope.launch {
            try {
                val results = mutableListOf<Pair<String, String>>()
                withContext(Dispatchers.IO) {
                    val cleanDomain = domain.removePrefix("http://").removePrefix("https://").removeSuffix("/")
                    try {
                        val addresses = InetAddress.getAllByName(cleanDomain)
                        addresses.forEach { addr ->
                            results.add("IP Address" to addr.hostAddress.orEmpty())
                        }
                        results.add("Hostname" to addresses.first().canonicalHostName)
                    } catch (e: Exception) {
                        results.add("DNS Error" to (e.message ?: "Unknown"))
                    }

                    val extensions = listOf(".com", ".net", ".org", ".io", ".co", ".dev", ".me")
                    val baseName = cleanDomain.substringBefore(".")
                    for (ext in extensions) {
                        val testDomain = "$baseName$ext"
                        try {
                            InetAddress.getByName(testDomain)
                            results.add("Domain Available" to "$testDomain ✅ (registered)")
                        } catch (_: Exception) {
                            results.add("Domain Available" to "$testDomain ❌ (available)")
                        }
                    }
                }
                dnsResults = results
            } catch (e: Exception) {
                errorMessage = "שגיאה: ${e.message}"
            }
            isSearching = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("בדיקת דומיין", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("WHOIS, DNS Lookup וזמינות דומיינים", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it.trim() },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("example.com") },
                    leadingIcon = { Icon(Icons.Default.Language, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { lookup() })
                )

                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { lookup() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        enabled = domain.isNotEmpty() && !isSearching
                    ) {
                        if (isSearching) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        else { Icon(Icons.Default.Dns, null); Spacer(Modifier.width(8.dp)) }
                        Text("DNS Lookup")
                    }
                    OutlinedButton(
                        onClick = {
                            val cleanDomain = domain.removePrefix("http://").removePrefix("https://").removeSuffix("/")
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.whois.com/whois/$cleanDomain")))
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        enabled = domain.isNotEmpty()
                    ) {
                        Text("WHOIS")
                    }
                }
            }

            errorMessage?.let { error ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text(error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            if (dnsResults.isNotEmpty()) {
                item { Text("תוצאות:", fontWeight = FontWeight.Medium) }

                dnsResults.forEach { (type, value) ->
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Dns, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(type, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                    Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
