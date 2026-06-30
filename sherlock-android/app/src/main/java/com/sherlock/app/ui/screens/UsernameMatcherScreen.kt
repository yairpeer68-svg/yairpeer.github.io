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
import com.sherlock.app.data.model.UsernameMatchResult
import com.sherlock.app.data.repository.SocialRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsernameMatcherScreen(onNavigateBack: () -> Unit) {
    val repository = remember { SocialRepository() }
    var usernameA by remember { mutableStateOf("") }
    var usernameB by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<UsernameMatchResult?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("התאמת שמות משתמש") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("בדוק כמה דומים שני שמות משתמש מפלטפורמות שונות, כדי להעריך אם מדובר באותו אדם", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = usernameA, onValueChange = { usernameA = it },
                label = { Text("שם משתמש א'") }, modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            OutlinedTextField(
                value = usernameB, onValueChange = { usernameB = it },
                label = { Text("שם משתמש ב'") }, modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Button(
                onClick = { result = repository.matchUsernames(usernameA, usernameB) },
                modifier = Modifier.fillMaxWidth(),
                enabled = usernameA.isNotBlank() && usernameB.isNotBlank()
            ) {
                Icon(Icons.Default.JoinInner, null)
                Spacer(Modifier.width(8.dp))
                Text("השווה שמות משתמש")
            }

            result?.let { r ->
                val color = when {
                    r.similarityPercent >= 70 -> MaterialTheme.colorScheme.primary
                    r.similarityPercent >= 40 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("אחוז דמיון", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${r.similarityPercent}%", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = color)
                        Text(r.verdict, fontWeight = FontWeight.SemiBold, color = color)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { r.similarityPercent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = color
                        )
                    }
                }

                if (r.notes.isNotEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("תצפיות", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            r.notes.forEach { note ->
                                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                    Icon(Icons.Default.Info, null, modifier = Modifier.size(16.dp), tint = color)
                                    Spacer(Modifier.width(8.dp))
                                    Text(note, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }

                Text(
                    "הערה: זוהי הערכה לפי דמיון טקסטואלי בלבד ואינה הוכחה לזהות משותפת",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
