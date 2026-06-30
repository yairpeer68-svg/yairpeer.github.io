package com.sherlock.app.ui.screens

import android.content.Intent
import android.net.Uri
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SideBySideScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var username1 by remember { mutableStateOf("") }
    var username2 by remember { mutableStateOf("") }
    var comparison by remember { mutableStateOf<List<ComparisonItem>>(emptyList()) }

    val platforms = listOf(
        "Instagram" to "https://www.instagram.com/",
        "Twitter/X" to "https://x.com/",
        "Facebook" to "https://www.facebook.com/",
        "TikTok" to "https://www.tiktok.com/@",
        "LinkedIn" to "https://www.linkedin.com/in/",
        "GitHub" to "https://github.com/",
        "YouTube" to "https://www.youtube.com/@",
        "Reddit" to "https://www.reddit.com/user/",
        "Pinterest" to "https://www.pinterest.com/",
        "Telegram" to "https://t.me/"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("השוואת פרופילים") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = username1, onValueChange = { username1 = it },
                    label = { Text("פרופיל 1") },
                    modifier = Modifier.weight(1f), singleLine = true
                )
                OutlinedTextField(
                    value = username2, onValueChange = { username2 = it },
                    label = { Text("פרופיל 2") },
                    modifier = Modifier.weight(1f), singleLine = true
                )
            }

            Button(
                onClick = {
                    comparison = platforms.map { (name, baseUrl) ->
                        ComparisonItem(name, "$baseUrl$username1", "$baseUrl$username2")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = username1.isNotBlank() && username2.isNotBlank()
            ) {
                Icon(Icons.Default.Compare, null)
                Spacer(Modifier.width(8.dp))
                Text("השווה פרופילים")
            }

            comparison.forEach { item ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(item.platform, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url1))) },
                                modifier = Modifier.weight(1f)
                            ) { Text(username1, fontSize = 12.sp, maxLines = 1) }
                            OutlinedButton(
                                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url2))) },
                                modifier = Modifier.weight(1f)
                            ) { Text(username2, fontSize = 12.sp, maxLines = 1) }
                        }
                    }
                }
            }
        }
    }
}

private data class ComparisonItem(val platform: String, val url1: String, val url2: String)
