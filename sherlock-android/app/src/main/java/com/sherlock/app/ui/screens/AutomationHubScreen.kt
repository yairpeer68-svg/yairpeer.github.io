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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.ui.theme.SherlockSuccess
import com.sherlock.app.util.SettingsManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationHubScreen(
    onNavigateBack: () -> Unit,
    onNavigateToMonitor: () -> Unit,
    onNavigateToScheduledSearches: () -> Unit,
    onNavigateToInvestigationWorkflow: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val settings = remember { SettingsManager(context) }

    val monitoredProfiles by db.monitoredProfileDao().getAllProfiles().collectAsState(initial = emptyList())
    val scheduledSearches by db.scheduledSearchDao().getAll().collectAsState(initial = emptyList())
    val autoFavorite by settings.autoFavoriteOnFound.collectAsState(initial = false)
    val autoExport by settings.autoExportOnComplete.collectAsState(initial = false)
    val autoCleanDays by settings.autoCleanDays.collectAsState(initial = 0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("מרכז אוטומציה", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AutomationCard(
                icon = Icons.Default.NotificationsActive,
                title = "ניטור פרופילים",
                subtitle = "${monitoredProfiles.count { it.isActive }} פרופילים פעילים מתוך ${monitoredProfiles.size}",
                active = monitoredProfiles.any { it.isActive },
                onClick = onNavigateToMonitor
            )
            AutomationCard(
                icon = Icons.Default.Schedule,
                title = "חיפושים מתוזמנים",
                subtitle = "${scheduledSearches.count { it.isActive }} חיפושים פעילים מתוך ${scheduledSearches.size}",
                active = scheduledSearches.any { it.isActive },
                onClick = onNavigateToScheduledSearches
            )
            AutomationCard(
                icon = Icons.Default.AccountTree,
                title = "אשף חקירה מודרך",
                subtitle = "המשך בתהליך חקירה שלב-אחר-שלב",
                active = true,
                onClick = onNavigateToInvestigationWorkflow
            )

            Spacer(Modifier.height(4.dp))
            Text("כללי אוטומציה", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)

            AutomationToggleCard(
                icon = Icons.Default.Star,
                title = "הוספה אוטומטית למועדפים",
                subtitle = "כל תוצאה שנמצאה תתווסף למועדפים",
                checked = autoFavorite,
                onToggle = { scope.launch { settings.setAutoFavoriteOnFound(it) } }
            )
            AutomationToggleCard(
                icon = Icons.Default.Download,
                title = "ייצוא אוטומטי בסיום חיפוש",
                subtitle = "שמירת קובץ CSV אוטומטית בכל חיפוש",
                checked = autoExport,
                onToggle = { scope.launch { settings.setAutoExportOnComplete(it) } }
            )
            AutomationToggleCard(
                icon = Icons.Default.AutoDelete,
                title = "ניקוי היסטוריה אוטומטי",
                subtitle = if (autoCleanDays > 0) "מחיקת חיפושים ישנים מ-$autoCleanDays יום" else "כבוי - ערוך בהגדרות",
                checked = autoCleanDays > 0,
                onToggle = { enabled -> scope.launch { settings.setAutoCleanDays(if (enabled) 30 else 0) } }
            )
        }
    }
}

@Composable
private fun AutomationCard(icon: ImageVector, title: String, subtitle: String, active: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(24.dp), tint = if (active) SherlockSuccess else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun AutomationToggleCard(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onToggle)
        }
    }
}
