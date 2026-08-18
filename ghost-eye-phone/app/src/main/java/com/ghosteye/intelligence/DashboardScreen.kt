package com.ghosteye.intelligence

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun DashboardScreen(baseUrl: String) {
    val context = LocalContext.current
    val api = remember { ApiClient(context, baseUrl) }
    var summary by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            summary = api.dashboardSummary()
        } catch (e: Exception) {
            error = e.message ?: "Dashboard unavailable"
        }
    }
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text("Dashboard", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("סה״כ ניתוחים: ${summary?.optInt("total_jobs", 0) ?: 0}")
        Text("הושלמו: ${summary?.optInt("completed", 0) ?: 0}")
        Text("נכשלו: ${summary?.optInt("failed", 0) ?: 0}")
    }
}
