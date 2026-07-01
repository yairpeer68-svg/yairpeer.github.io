package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.data.model.SearchHistory
import com.sherlock.app.data.model.SearchResult
import com.sherlock.app.data.model.SearchType
import com.sherlock.app.data.model.SiteCategory
import com.sherlock.app.util.SettingsManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val DEMO_PREFIX = "DEMO::"

private val demoSites = listOf(
    Triple("Instagram", SiteCategory.SOCIAL_MEDIA, true),
    Triple("Twitter / X", SiteCategory.SOCIAL_MEDIA, true),
    Triple("GitHub", SiteCategory.TECH, true),
    Triple("Reddit", SiteCategory.FORUM, true),
    Triple("LinkedIn", SiteCategory.BUSINESS, false),
    Triple("Pinterest", SiteCategory.PHOTO_VIDEO, false),
    Triple("TikTok", SiteCategory.SOCIAL_MEDIA, true),
    Triple("Spotify", SiteCategory.MUSIC, false)
)

private suspend fun generateDemoData(db: AppDatabase) {
    val sampleQueries = listOf(
        "ploni_almoni" to SearchType.USERNAME,
        "demo.user@example.com" to SearchType.EMAIL,
        "0501234567" to SearchType.PHONE,
        "ישראל ישראלי" to SearchType.FULL_NAME
    )
    sampleQueries.forEachIndexed { idx, (query, type) ->
        val chosenSites = demoSites.shuffled().take(5)
        val foundCount = chosenSites.count { it.third }
        val historyId = db.searchHistoryDao().insertHistory(
            SearchHistory(
                query = "$DEMO_PREFIX$query",
                searchType = type,
                timestamp = System.currentTimeMillis() - idx * 3_600_000L,
                totalFound = foundCount,
                totalChecked = chosenSites.size
            )
        )
        val results = chosenSites.map { (name, category, exists) ->
            SearchResult(
                historyId = historyId,
                siteName = name,
                url = "https://example.com/$name",
                username = query,
                exists = exists,
                category = category,
                responseTimeMs = (150..900).random().toLong(),
                httpStatus = if (exists) 200 else 404
            )
        }
        db.searchHistoryDao().insertResults(results)
    }
}

private suspend fun clearDemoData(db: AppDatabase) {
    val all = db.searchHistoryDao().getAllHistory()
    val snapshot = all.first()
    val toDelete = snapshot.filter { it.query.startsWith(DEMO_PREFIX) }
    for (history in toDelete) {
        db.searchHistoryDao().deleteResultsForHistory(history.id)
        db.searchHistoryDao().deleteHistory(history)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoModeScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val settings = remember { SettingsManager(context) }
    val demoEnabled by settings.demoMode.collectAsState(initial = false)
    var isWorking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("מצב הדגמה", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
            Text(
                "מאכלס את האפליקציה בנתוני דוגמה לצורך הדגמה או מצגת, ללא ביצוע חיפושים אמיתיים ברשת",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Theaters, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("מצב הדגמה פעיל", fontWeight = FontWeight.Medium)
                        Text("מציין שהנתונים המוצגים הם לדוגמה בלבד", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = demoEnabled, onCheckedChange = { scope.launch { settings.setDemoMode(it) } })
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    isWorking = true
                    scope.launch {
                        generateDemoData(db)
                        settings.setDemoMode(true)
                        message = "נוצרו נתוני דוגמה בהצלחה"
                        isWorking = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = !isWorking
            ) {
                Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("צור נתוני דוגמה")
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    isWorking = true
                    scope.launch {
                        clearDemoData(db)
                        settings.setDemoMode(false)
                        message = "נתוני הדוגמה נוקו"
                        isWorking = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = !isWorking
            ) {
                Icon(Icons.Default.DeleteSweep, null); Spacer(Modifier.width(8.dp)); Text("נקה נתוני דוגמה")
            }

            if (isWorking) {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            message?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            }
        }
    }
}
