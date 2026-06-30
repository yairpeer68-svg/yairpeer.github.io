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
import com.sherlock.app.data.repository.SocialRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformFootprintScreen(onNavigateBack: () -> Unit) {
    val repository = remember { SocialRepository() }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    val totalPlatforms = remember { repository.platformCategories.sumOf { it.platforms.size } }
    val selectedCount = selected.values.count { it }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ציון נוכחות רשתית") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ציון נוכחות", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$selectedCount / $totalPlatforms", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { if (totalPlatforms > 0) selectedCount.toFloat() / totalPlatforms else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())
            ) {
                Text(
                    "סמן את הפלטפורמות שבהן ידועה נוכחות של מושא החקירה:",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                repository.platformCategories.forEach { category ->
                    val categorySelectedCount = category.platforms.count { selected[it] == true }
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(category.category, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text("$categorySelectedCount/${category.platforms.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(4.dp))
                            category.platforms.forEach { platform ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = selected[platform] == true,
                                        onCheckedChange = { checked -> selected[platform] = checked }
                                    )
                                    Text(platform, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
