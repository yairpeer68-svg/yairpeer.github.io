package com.sherlock.app.ui.screens

import android.content.Intent
import android.net.Uri
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
import com.sherlock.app.data.model.EmailPattern
import com.sherlock.app.data.repository.AnalysisRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailPatternScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("gmail.com") }
    var results by remember { mutableStateOf<List<EmailPattern>>(emptyList()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("יצירת תבניות אימייל") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = firstName, onValueChange = { firstName = it },
                label = { Text("שם פרטי") }, modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = lastName, onValueChange = { lastName = it },
                label = { Text("שם משפחה") }, modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = domain, onValueChange = { domain = it },
                label = { Text("דומיין") }, modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    if (firstName.isNotBlank() && lastName.isNotBlank() && domain.isNotBlank())
                        results = AnalysisRepository(context).generateEmailPatterns(firstName, lastName, domain)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = firstName.isNotBlank() && lastName.isNotBlank()
            ) {
                Icon(Icons.Default.Email, null)
                Spacer(Modifier.width(8.dp))
                Text("צור תבניות")
            }
            Spacer(Modifier.height(16.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results) { pattern ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(pattern.email, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("תבנית: ${pattern.pattern}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(8.dp))
                                    LinearProgressIndicator(
                                        progress = pattern.confidence,
                                        modifier = Modifier.width(60.dp).height(4.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("${(pattern.confidence * 100).toInt()}%", fontSize = 11.sp)
                                }
                            }
                            IconButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=\"${pattern.email}\"")))
                            }) {
                                Icon(Icons.Default.Search, "חפש")
                            }
                        }
                    }
                }
            }
        }
    }
}
