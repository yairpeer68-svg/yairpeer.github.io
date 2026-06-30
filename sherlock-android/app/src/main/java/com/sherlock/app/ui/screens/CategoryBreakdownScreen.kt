package com.sherlock.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.data.local.CategoryCountStat
import kotlinx.coroutines.launch

private val categoryColors = listOf(
    Color(0xFF58A6FF), Color(0xFF3FB950), Color(0xFFD29922), Color(0xFFF85149),
    Color(0xFFA371F7), Color(0xFFDB61A2), Color(0xFF39C5CF), Color(0xFFE3B341)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryBreakdownScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }

    var stats by remember { mutableStateOf<List<CategoryCountStat>>(emptyList()) }
    val total = stats.sumOf { it.count }.coerceAtLeast(1)

    LaunchedEffect(Unit) {
        scope.launch { stats = db.searchHistoryDao().getCategoryStats() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("פילוח לפי קטגוריה", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        if (stats.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.PieChart, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("אין עדיין תוצאות לניתוח", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                item {
                    Row(Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(8.dp))) {
                        stats.forEachIndexed { index, stat ->
                            Box(
                                Modifier
                                    .weight(stat.count.toFloat().coerceAtLeast(0.01f))
                                    .fillMaxHeight()
                                    .background(categoryColors[index % categoryColors.size])
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                itemsIndexed(stats) { index, stat ->
                    val pct = stat.count * 100f / total
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(categoryColors[index % categoryColors.size]))
                            Spacer(Modifier.width(10.dp))
                            Text(stat.category.hebrewName, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text("${stat.count} (${"%.1f".format(pct)}%)", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}
