package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.sherlock.app.data.model.UsernameAnalysis
import com.sherlock.app.data.repository.AnalysisRepository
import com.sherlock.app.data.repository.SitesDatabase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsernameAnalysisScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var username by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<UsernameAnalysis?>(null) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ניתוח שם משתמש") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text("שם משתמש לניתוח") },
                leadingIcon = { Icon(Icons.Default.Person, null) },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )

            Button(
                onClick = {
                    if (username.isNotBlank()) {
                        result = AnalysisRepository(context).analyzeUsername(username)
                        suggestions = SitesDatabase.generateSmartVariations(username).take(30)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = username.isNotBlank()
            ) {
                Icon(Icons.Default.Analytics, null)
                Spacer(Modifier.width(8.dp))
                Text("נתח שם משתמש")
            }

            if (suggestions.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("הצעות וריאציות חכמות (${suggestions.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("וריאציות אפשריות לחיפוש - leetspeak, סיומות שנה, מפרידים", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(suggestions) { s ->
                                AssistChip(onClick = {}, label = { Text(s, fontSize = 12.sp) })
                            }
                        }
                    }
                }
            }

            result?.let { r ->
                if (r.possibleFirstName != null || r.possibleLastName != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("זהות אפשרית", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(Modifier.height(8.dp))
                            r.possibleFirstName?.let { InfoRow("שם פרטי", it) }
                            r.possibleLastName?.let { InfoRow("שם משפחה", it) }
                            r.possibleBirthYear?.let { InfoRow("שנת לידה", it.toString()) }
                            r.possibleLocation?.let { InfoRow("מיקום", it) }
                        }
                    }
                }

                if (r.patterns.isNotEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("תבניות שזוהו", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(Modifier.height(8.dp))
                            r.patterns.forEach { p ->
                                Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(p, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$label: ", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(value, fontSize = 14.sp)
    }
}
