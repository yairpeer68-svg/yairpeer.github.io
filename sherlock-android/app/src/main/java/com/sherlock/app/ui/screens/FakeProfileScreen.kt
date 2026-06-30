package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
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
import com.sherlock.app.data.model.FakeProfileScore
import com.sherlock.app.data.repository.AnalysisRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FakeProfileScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var username by remember { mutableStateOf("") }
    var hasProfilePic by remember { mutableStateOf(true) }
    var isVerified by remember { mutableStateOf(false) }
    var hasLink by remember { mutableStateOf(false) }
    var followers by remember { mutableStateOf("") }
    var following by remember { mutableStateOf("") }
    var posts by remember { mutableStateOf("") }
    var bioLength by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<FakeProfileScore?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("גלאי פרופיל מזויף") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text("שם משתמש") }, modifier = Modifier.fillMaxWidth(), singleLine = true
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = followers, onValueChange = { followers = it.filter { c -> c.isDigit() } },
                    label = { Text("עוקבים") }, modifier = Modifier.weight(1f), singleLine = true
                )
                OutlinedTextField(
                    value = following, onValueChange = { following = it.filter { c -> c.isDigit() } },
                    label = { Text("נעקבים") }, modifier = Modifier.weight(1f), singleLine = true
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = posts, onValueChange = { posts = it.filter { c -> c.isDigit() } },
                    label = { Text("פוסטים") }, modifier = Modifier.weight(1f), singleLine = true
                )
                OutlinedTextField(
                    value = bioLength, onValueChange = { bioLength = it.filter { c -> c.isDigit() } },
                    label = { Text("אורך ביו") }, modifier = Modifier.weight(1f), singleLine = true
                )
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = hasProfilePic, onCheckedChange = { hasProfilePic = it })
                Text("יש תמונת פרופיל")
                Spacer(Modifier.width(16.dp))
                Checkbox(checked = isVerified, onCheckedChange = { isVerified = it })
                Text("מאומת")
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = hasLink, onCheckedChange = { hasLink = it })
                Text("יש לינק בביו")
            }

            Button(
                onClick = {
                    result = AnalysisRepository(context).calculateFakeProfileScore(
                        hasProfilePic, null,
                        followers.toIntOrNull(), following.toIntOrNull(),
                        posts.toIntOrNull(), bioLength.toIntOrNull(),
                        hasLink, isVerified, username
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = username.isNotBlank()
            ) {
                Icon(Icons.Default.Shield, null)
                Spacer(Modifier.width(8.dp))
                Text("בדוק פרופיל")
            }

            result?.let { r ->
                val color = when {
                    r.score >= 70 -> MaterialTheme.colorScheme.error
                    r.score >= 40 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ציון סיכון", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${r.score}/100", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = color)
                        Text(r.riskLevel, fontWeight = FontWeight.SemiBold, color = color)
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { r.score / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = color
                        )
                    }
                }

                if (r.reasons.isNotEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("סיבות", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            r.reasons.forEach { reason ->
                                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                    Icon(Icons.Default.Info, null, modifier = Modifier.size(16.dp), tint = color)
                                    Spacer(Modifier.width(8.dp))
                                    Text(reason, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
