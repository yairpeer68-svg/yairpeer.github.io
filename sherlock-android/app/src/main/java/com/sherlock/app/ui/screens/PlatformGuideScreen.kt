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
fun PlatformGuideScreen(onNavigateBack: () -> Unit) {
    val repository = remember { SocialRepository() }
    var expandedPlatform by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("מדריך OSINT לפי פלטפורמה") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(repository.platformGuides) { guide ->
                val isExpanded = expandedPlatform == guide.platform
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { expandedPlatform = if (isExpanded) null else guide.platform }
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(guide.platform, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Icon(if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                        }
                        if (isExpanded) {
                            Spacer(Modifier.height(8.dp))
                            guide.tips.forEach { tip ->
                                Row(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Icon(Icons.Default.Lightbulb, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text(tip, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
