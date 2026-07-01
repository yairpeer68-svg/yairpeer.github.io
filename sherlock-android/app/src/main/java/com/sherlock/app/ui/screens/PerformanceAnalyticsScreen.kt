package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.data.local.SiteResponseStat
import com.sherlock.app.ui.components.StatCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceAnalyticsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }

    var avgMs by remember { mutableFloatStateOf(0f) }
    var slowest by remember { mutableStateOf<List<SiteResponseStat>>(emptyList()) }

    LaunchedEffect(Unit) {
        scope.launch {
            avgMs = db.searchHistoryDao().getAverageResponseTime()
            slowest = db.searchHistoryDao().getSlowestSites()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ניתוח ביצועים", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                StatCard("זמן תגובה ממוצע", "${avgMs.toInt()} ms", Icons.Default.Speed, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }

            if (slowest.isNotEmpty()) {
                item {
                    Text("האתרים האיטיים ביותר", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                }
                itemsIndexed(slowest) { _, stat ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stat.siteName, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text("${stat.avgResponseMs.toInt()} ms", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            } else {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text("אין עדיין נתוני ביצועים", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
