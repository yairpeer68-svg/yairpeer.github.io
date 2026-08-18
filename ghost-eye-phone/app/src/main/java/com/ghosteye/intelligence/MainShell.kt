package com.ghosteye.intelligence

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private enum class MainTab(val label: String) {
    Home("בית"), Analysis("ניתוח"), Projects("פרויקטים"), History("היסטוריה"), Settings("הגדרות")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell(
    baseUrl: String,
    darkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    onLogout: () -> Unit
) {
    var tab by remember { mutableStateOf(MainTab.Home) }

    Scaffold(
        topBar = {
            Surface(tonalElevation = 2.dp) {
                CenterAlignedTopAppBar(
                    title = { Text("Ghost Eye", style = MaterialTheme.typography.titleLarge) },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                MainTab.entries.forEach { item ->
                    val icon = when (item) {
                        MainTab.Home -> Icons.Rounded.Home
                        MainTab.Analysis -> Icons.Rounded.Search
                        MainTab.Projects -> Icons.Rounded.Folder
                        MainTab.History -> Icons.Rounded.History
                        MainTab.Settings -> Icons.Rounded.Settings
                    }
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(icon, contentDescription = item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        when (tab) {
            MainTab.Home -> DashboardScreen(baseUrl, Modifier.padding(padding), onNewAnalysis = { tab = MainTab.Analysis }, onSessionExpired = onLogout)
            MainTab.Analysis -> AnalysisScreen(baseUrl, Modifier.padding(padding), onSessionExpired = onLogout)
            MainTab.Projects -> ProjectsScreen(baseUrl, Modifier.padding(padding), onSessionExpired = onLogout)
            MainTab.History -> HistoryScreen(baseUrl, Modifier.padding(padding), onSessionExpired = onLogout)
            MainTab.Settings -> SettingsScreen(
                baseUrl = baseUrl,
                darkMode = darkMode,
                onDarkModeChange = onDarkModeChange,
                onLogout = onLogout,
                onSessionExpired = onLogout,
                modifier = Modifier.padding(padding)
            )
        }
    }
}
