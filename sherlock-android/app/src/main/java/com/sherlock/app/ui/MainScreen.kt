package com.sherlock.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.sherlock.app.data.CaseRepository
import com.sherlock.app.data.ImageSearchRepository
import com.sherlock.app.data.SearchRepository
import com.sherlock.app.ui.nav.Navigator
import com.sherlock.app.ui.nav.Screen
import com.sherlock.app.ui.screens.CaseDetailScreen
import com.sherlock.app.ui.screens.CasesScreen
import com.sherlock.app.ui.screens.QuickToolsScreen

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val caseRepo = remember { CaseRepository(context) }
    val searchRepo = remember { SearchRepository(context) }
    val imageRepo = remember { ImageSearchRepository(context) }
    val navigator = remember { Navigator() }

    BackHandler(enabled = navigator.canGoBack) { navigator.pop() }

    when (val screen = navigator.current) {
        is Screen.Home -> HomeScaffold(
            caseRepo = caseRepo,
            searchRepo = searchRepo,
            imageRepo = imageRepo,
            onOpenCase = { navigator.push(Screen.CaseDetail(it)) }
        )
        is Screen.CaseDetail -> CaseDetailScreen(
            caseId = screen.caseId,
            repository = caseRepo,
            onBack = { navigator.pop() }
        )
    }
}

@Composable
private fun HomeScaffold(
    caseRepo: CaseRepository,
    searchRepo: SearchRepository,
    imageRepo: ImageSearchRepository,
    onOpenCase: (Long) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Folder, null) },
                    label = { Text("Cases") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Build, null) },
                    label = { Text("Quick tools") }
                )
            }
        }
    ) { pad ->
        when (tab) {
            0 -> CasesScreen(
                repository = caseRepo,
                onOpenCase = onOpenCase,
                modifier = Modifier.padding(pad)
            )
            1 -> QuickToolsScreen(
                searchRepository = searchRepo,
                imageRepository = imageRepo,
                modifier = Modifier.padding(pad)
            )
        }
    }
}
