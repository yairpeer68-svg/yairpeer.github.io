package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingFlat
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.ui.theme.SherlockSuccess
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodComparisonScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }

    var currentWeek by remember { mutableIntStateOf(0) }
    var previousWeek by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        scope.launch {
            val now = System.currentTimeMillis()
            val weekMs = 7L * 24 * 3600_000
            currentWeek = db.searchHistoryDao().getSearchCountSince(now - weekMs)
            val sinceTwoWeeks = db.searchHistoryDao().getSearchCountSince(now - 2 * weekMs)
            previousWeek = sinceTwoWeeks - currentWeek
        }
    }

    val diff = currentWeek - previousWeek
    val pctChange = if (previousWeek > 0) diff * 100f / previousWeek else if (currentWeek > 0) 100f else 0f

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("השוואת תקופות", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("7 ימים אחרונים", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text("$currentWeek", fontWeight = FontWeight.Bold, fontSize = 28.sp)
                    }
                }
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("7 ימים קודמים", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text("$previousWeek", fontWeight = FontWeight.Bold, fontSize = 28.sp)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    val (icon, color) = when {
                        diff > 0 -> Icons.Default.TrendingUp to SherlockSuccess
                        diff < 0 -> Icons.Default.TrendingDown to MaterialTheme.colorScheme.error
                        else -> Icons.Default.TrendingFlat to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Icon(icon, null, tint = color)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            if (diff >= 0) "עלייה של ${"%.0f".format(pctChange)}%" else "ירידה של ${"%.0f".format(-pctChange)}%",
                            fontWeight = FontWeight.Medium,
                            color = color
                        )
                        Text("בהשוואה לשבוע הקודם", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
