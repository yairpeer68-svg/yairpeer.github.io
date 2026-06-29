package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.data.local.SearchTypeStat
import com.sherlock.app.ui.components.StatCard
import com.sherlock.app.ui.theme.SherlockSuccess
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }

    var totalSearches by remember { mutableIntStateOf(0) }
    var totalFound by remember { mutableIntStateOf(0) }
    var avgSuccess by remember { mutableFloatStateOf(0f) }
    var typeStats by remember { mutableStateOf<List<SearchTypeStat>>(emptyList()) }
    val favorites by db.favoriteDao().getAllFavorites().collectAsState(initial = emptyList())
    val history by db.searchHistoryDao().getRecentHistory(10).collectAsState(initial = emptyList())

    LaunchedEffect(Unit) {
        scope.launch {
            totalSearches = db.searchHistoryDao().getTotalSearches()
            totalFound = db.searchHistoryDao().getTotalFound() ?: 0
            avgSuccess = db.searchHistoryDao().getAverageSuccessRate() ?: 0f
            typeStats = db.searchHistoryDao().getSearchTypeStats()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("סטטיסטיקות", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("חיפושים", "$totalSearches", Icons.Default.Search, modifier = Modifier.weight(1f))
                    StatCard("נמצאו", "$totalFound", Icons.Default.CheckCircle, color = SherlockSuccess, modifier = Modifier.weight(1f))
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("אחוז הצלחה", "${"%.1f".format(avgSuccess)}%", Icons.Default.TrendingUp, modifier = Modifier.weight(1f))
                    StatCard("מועדפים", "${favorites.size}", Icons.Default.Star, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(1f))
                }
            }

            if (typeStats.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("חיפושים לפי סוג:", fontWeight = FontWeight.Medium)
                }

                typeStats.forEach { stat ->
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stat.searchType, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Text("${stat.count} חיפושים", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            if (history.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("חיפושים אחרונים:", fontWeight = FontWeight.Medium)
                }

                history.forEach { item ->
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.query, fontWeight = FontWeight.Medium)
                                    Text("${item.searchType.hebrewName} • ${item.totalFound} נמצאו", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
