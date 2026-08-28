package com.ghosteye.intelligence

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

private enum class MainTab(val label: String) {
    Analysis("קובץ"),
    Target("דומיין"),
    Knowledge("מודיעין"),
    Cyber("SOC"),
    Advanced("מרכז")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell(
    baseUrl: String,
    darkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    onLogout: () -> Unit
) {
    var tab by remember { mutableStateOf(MainTab.Analysis) }
    var showHistory by remember { mutableStateOf(false) }
    var showInvestigations by remember { mutableStateOf(false) }
    var selectedInvestigationId by remember { mutableStateOf<String?>(null) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var targetPrefill by remember { mutableStateOf("") }
    val online = rememberNetworkAvailable()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    if (showHistory || showInvestigations) {
                        IconButton(onClick = {
                            showHistory = false
                            showInvestigations = false
                            selectedInvestigationId = null
                        }) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "חזרה")
                        }
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ghost_eye_brand),
                            contentDescription = "Ghost Eye",
                            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Text(
                            when {
                                showHistory -> "היסטוריה"
                                showInvestigations -> "חקירות"
                                else -> "Ghost Eye"
                            },
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                },
                actions = {
                    if (!showHistory && !showInvestigations) {
                        IconButton(onClick = {
                            showInvestigations = true
                            selectedInvestigationId = null
                        }) {
                            Icon(Icons.Rounded.AccountTree, contentDescription = "חקירות")
                        }
                        IconButton(onClick = { showHistory = true }) {
                            Icon(Icons.Rounded.History, contentDescription = "היסטוריה")
                        }
                    }
                    Box {
                        IconButton(onClick = { showSettingsMenu = true }) {
                            Icon(Icons.Rounded.Settings, contentDescription = "הגדרות")
                        }
                        DropdownMenu(
                            expanded = showSettingsMenu,
                            onDismissRequest = { showSettingsMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (darkMode) "מצב בהיר" else "מצב כהה") },
                                onClick = {
                                    onDarkModeChange(!darkMode)
                                    showSettingsMenu = false
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("התנתקות", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showSettingsMenu = false
                                    onLogout()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            if (!showHistory && !showInvestigations) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    MainTab.entries.forEach { item ->
                        val icon = when (item) {
                            MainTab.Analysis -> Icons.Rounded.Search
                            MainTab.Target -> Icons.Rounded.Public
                            MainTab.Knowledge -> Icons.Rounded.AccountTree
                            MainTab.Cyber -> Icons.Rounded.Public
                            MainTab.Advanced -> Icons.Rounded.Settings
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
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!online) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "אין חיבור לאינטרנט — פעולות רשת אינן זמינות כרגע",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    showHistory -> HistoryScreen(
                        baseUrl = baseUrl,
                        modifier = Modifier.fillMaxSize(),
                        onSessionExpired = onLogout
                    )
                    showInvestigations -> InvestigationsScreen(
                        baseUrl = baseUrl,
                        modifier = Modifier.fillMaxSize(),
                        initialInvestigationId = selectedInvestigationId,
                        onSessionExpired = onLogout
                    )
                    else -> when (tab) {
                        MainTab.Analysis -> AnalysisScreen(
                            baseUrl,
                            Modifier.fillMaxSize(),
                            onSessionExpired = onLogout,
                            onPivotTarget = { candidate ->
                                targetPrefill = candidate
                                tab = MainTab.Target
                            },
                            onInvestigationCreated = { id ->
                                selectedInvestigationId = id
                                showInvestigations = true
                            }
                        )
                        MainTab.Target -> TargetScanScreen(
                            baseUrl,
                            Modifier.fillMaxSize(),
                            onSessionExpired = onLogout,
                            initialTarget = targetPrefill,
                            onInvestigationCreated = { id ->
                                selectedInvestigationId = id
                                showInvestigations = true
                            }
                        )
                        MainTab.Knowledge -> GlobalIntelligenceScreen(
                            baseUrl = baseUrl,
                            modifier = Modifier.fillMaxSize(),
                            onSessionExpired = onLogout
                        )
                        MainTab.Cyber -> CyberOperationsScreen(
                            baseUrl = baseUrl,
                            modifier = Modifier.fillMaxSize(),
                            onSessionExpired = onLogout
                        )
                        MainTab.Advanced -> UnifiedIntelligenceScreen(
                            baseUrl = baseUrl,
                            modifier = Modifier.fillMaxSize(),
                            onSessionExpired = onLogout
                        )
                    }
                }
            }
        }
    }
}
