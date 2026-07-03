package com.sherlock.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.CaseRepository
import com.sherlock.app.data.ImageSearchRepository
import com.sherlock.app.data.SearchRepository
import com.sherlock.app.ui.nav.Navigator
import com.sherlock.app.ui.nav.Screen
import com.sherlock.app.ui.screens.CaseDetailScreen
import com.sherlock.app.ui.screens.CasesScreen
import com.sherlock.app.ui.screens.QuickToolsScreen
import com.sherlock.app.util.CrashLog

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val caseRepo = remember { CaseRepository(context) }
    val searchRepo = remember { SearchRepository(context) }
    val imageRepo = remember { ImageSearchRepository(context) }
    val navigator = remember { Navigator() }

    var crash by remember { mutableStateOf(CrashLog.read(context)) }
    crash?.let { text ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("App crashed last time") },
            text = {
                Text(
                    text = text,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Sherlock crash log")
                                    putExtra(Intent.EXTRA_TEXT, text)
                                },
                                "Share crash log"
                            )
                        )
                    }
                }) { Text("Share") }
            },
            dismissButton = {
                TextButton(onClick = { CrashLog.clear(context); crash = null }) { Text("Dismiss") }
            }
        )
    }

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
        topBar = { ClassificationBar(right = if (tab == 0) "OPERATIONS" else "FIELD TOOLS") },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Folder, null) },
                    label = { Text("OPS") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Build, null) },
                    label = { Text("TOOLS") }
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
