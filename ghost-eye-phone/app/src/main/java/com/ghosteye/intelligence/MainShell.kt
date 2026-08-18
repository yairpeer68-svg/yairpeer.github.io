package com.ghosteye.intelligence

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MainShell() {
    var tab by remember { mutableStateOf(0) }
    val titles = listOf("ניתוח", "Projects", "Graph", "Audit")
    Scaffold(
        bottomBar = {
            NavigationBar {
                titles.forEachIndexed { i, title ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {},
                        label = { Text(title) }
                    )
                }
            }
        }
    ) { padding ->
        when (tab) {
            0 -> AnalysisHome(padding)
            1 -> ProjectsScreen()
            2 -> GraphScreen()
            else -> AuditScreen()
        }
    }
}

@Composable
private fun AnalysisHome(padding: androidx.compose.foundation.layout.PaddingValues) {
    androidx.compose.foundation.layout.Column(
        Modifier.padding(padding).padding(20.dp)
    ) { Text("Analysis") }
}

@Composable
private fun AuditScreen() {
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize().padding(20.dp)
    ) {
        Text("Audit Log", style = MaterialTheme.typography.headlineSmall)
        androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
        Text("כאן יוצגו פעולות משתמש ואירועי מערכת.")
    }
}
