package com.ghosteye.intelligence

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private enum class MainTab(val label: String, val subtitle: String) {
    Home("בית", "Overview"),
    Investigate("חקירה", "Fabric"),
    Graph("גרף", "Entities"),
    Watchtower("מעקב", "24/7"),
    More("עוד", "CVE + OSINT")
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
    var showHistory by remember { mutableStateOf(false) }
    var showInvestigations by remember { mutableStateOf(false) }
    var selectedInvestigationId by remember { mutableStateOf<String?>(null) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    val online = rememberNetworkAvailable()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (showHistory || showInvestigations) {
                        IconButton(onClick = {
                            showHistory = false
                            showInvestigations = false
                            selectedInvestigationId = null
                        }) { Icon(Icons.Rounded.ArrowBack, contentDescription = "חזרה") }
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Image(
                            painter = painterResource(R.drawable.ghost_eye_brand),
                            contentDescription = "Ghost Eye",
                            modifier = Modifier.size(35.dp).clip(RoundedCornerShape(11.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Column {
                            Text(
                                when {
                                    showHistory -> "היסטוריה"
                                    showInvestigations -> "חקירות"
                                    else -> "Ghost Eye"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (!showHistory && !showInvestigations) {
                                Text("2.0 Intelligence Command Center", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                },
                actions = {
                    if (!showHistory && !showInvestigations) {
                        ConnectivityPill(online)
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = { showInvestigations = true; selectedInvestigationId = null }) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = "חקירות")
                        }
                        IconButton(onClick = { showHistory = true }) {
                            Icon(Icons.Rounded.History, contentDescription = "היסטוריה")
                        }
                    }
                    Box {
                        IconButton(onClick = { showSettingsMenu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "תפריט")
                        }
                        DropdownMenu(expanded = showSettingsMenu, onDismissRequest = { showSettingsMenu = false }) {
                            DropdownMenuItem(
                                leadingIcon = { Icon(if (darkMode) Icons.Rounded.LightMode else Icons.Rounded.DarkMode, null) },
                                text = { Text(if (darkMode) "מצב בהיר" else "מצב כהה") },
                                onClick = { onDarkModeChange(!darkMode); showSettingsMenu = false }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Rounded.Logout, null, tint = MaterialTheme.colorScheme.error) },
                                text = { Text("התנתקות", color = MaterialTheme.colorScheme.error) },
                                onClick = { showSettingsMenu = false; onLogout() }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            )
        },
        bottomBar = {
            if (!showHistory && !showInvestigations) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.98f),
                    tonalElevation = 0.dp
                ) {
                    MainTab.entries.forEach { item ->
                        val icon = when (item) {
                            MainTab.Home -> Icons.Rounded.SpaceDashboard
                            MainTab.Investigate -> Icons.Rounded.Search
                            MainTab.Graph -> Icons.Rounded.Hub
                            MainTab.Watchtower -> Icons.Rounded.Radar
                            MainTab.More -> Icons.Rounded.GridView
                        }
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Icon(icon, contentDescription = item.label) },
                            label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        GhostBackground(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                if (!online) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(17.dp))
                            Text("אין חיבור לאינטרנט — מידע שמור עדיין זמין", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        showHistory -> HistoryScreen(baseUrl, Modifier.fillMaxSize(), onLogout)
                        showInvestigations -> InvestigationsScreen(baseUrl, Modifier.fillMaxSize(), selectedInvestigationId, onLogout)
                        else -> when (tab) {
                            MainTab.Home -> HomeDashboardScreen(
                                baseUrl, Modifier.fillMaxSize(), onLogout,
                                onInvestigate = { tab = MainTab.Investigate },
                                onGraph = { tab = MainTab.Graph },
                                onWatchtower = { tab = MainTab.Watchtower },
                                onMore = { tab = MainTab.More }
                            )
                            MainTab.Investigate -> FabricInvestigationScreen(baseUrl, Modifier.fillMaxSize(), onLogout)
                            MainTab.Graph -> GlobalIntelligenceScreen(baseUrl, Modifier.fillMaxSize(), onLogout)
                            MainTab.Watchtower -> WatchtowerCenterScreen(baseUrl, Modifier.fillMaxSize(), onLogout)
                            MainTab.More -> MoreCenterScreen(baseUrl, Modifier.fillMaxSize(), onLogout)
                        }
                    }
                }
            }
        }
    }
}
