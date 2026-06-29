package com.sherlock.app.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HashLookupScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var selectedAlgo by remember { mutableStateOf("MD5") }
    var hashResult by remember { mutableStateOf("") }
    val algorithms = listOf("MD5", "SHA-1", "SHA-256", "SHA-512")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hash & פענוח") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("יצירת Hash", fontWeight = FontWeight.Bold, fontSize = 18.sp)

            OutlinedTextField(
                value = input, onValueChange = { input = it },
                label = { Text("טקסט להצפנה") },
                modifier = Modifier.fillMaxWidth(), minLines = 2
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                algorithms.forEach { algo ->
                    FilterChip(selected = selectedAlgo == algo, onClick = { selectedAlgo = algo }, label = { Text(algo, fontSize = 11.sp) })
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        hashResult = withContext(Dispatchers.Default) {
                            val md = MessageDigest.getInstance(selectedAlgo)
                            md.update(input.toByteArray())
                            md.digest().joinToString("") { "%02x".format(it) }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = input.isNotBlank()
            ) {
                Icon(Icons.Default.Tag, null)
                Spacer(Modifier.width(8.dp))
                Text("צור Hash")
            }

            if (hashResult.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("$selectedAlgo:", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(onClick = { clipboard.setText(AnnotatedString(hashResult)) }) {
                                Icon(Icons.Default.ContentCopy, "העתק")
                            }
                        }
                        Text(hashResult, fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
            }

            HorizontalDivider()

            Text("פענוח Hash (שירותים חיצוניים)", fontWeight = FontWeight.Bold, fontSize = 18.sp)

            var hashToLookup by remember { mutableStateOf("") }
            OutlinedTextField(
                value = hashToLookup, onValueChange = { hashToLookup = it },
                label = { Text("Hash לפענוח") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )

            val lookupServices = listOf(
                "CrackStation" to "https://crackstation.net/",
                "Hashes.com" to "https://hashes.com/en/decrypt/hash",
                "MD5Decrypt" to "https://md5decrypt.net/en/",
                "HashKiller" to "https://hashkiller.io/listmanager"
            )

            lookupServices.forEach { (name, url) ->
                OutlinedButton(
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("פתח $name")
                }
            }
        }
    }
}
