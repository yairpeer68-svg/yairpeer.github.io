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
import com.sherlock.app.data.repository.SocialRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsernameFormatValidatorScreen(onNavigateBack: () -> Unit) {
    val repository = remember { SocialRepository() }
    var username by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("בדיקת תקינות שם משתמש") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("בדוק האם שם משתמש עומד בכללי הפורמט של פלטפורמות נפוצות, לפני שמבזבזים זמן בחיפוש", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text("שם משתמש לבדיקה") },
                leadingIcon = { Icon(Icons.Default.Person, null) },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(Modifier.height(16.dp))

            if (username.isNotBlank()) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(repository.usernameFormatRules) { rule ->
                        val (isValid, issues) = repository.validateUsername(rule, username)
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (isValid) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                    null,
                                    tint = if (isValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(rule.platform, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    if (isValid) {
                                        Text("פורמט תקין", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        issues.forEach { issue ->
                                            Text("• $issue", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                    Text(rule.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
