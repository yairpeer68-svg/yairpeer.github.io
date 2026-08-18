package com.ghosteye.intelligence

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection

class MainActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent {
            var darkMode by rememberSaveable { mutableStateOf(true) }
            GhostEyeTheme(darkTheme = darkMode) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                AuthGate(baseUrl = ServerConfig.BASE_URL) { onLogout ->
                    MainShell(
                        baseUrl = ServerConfig.BASE_URL,
                        darkMode = darkMode,
                        onDarkModeChange = { darkMode = it },
                        onLogout = onLogout
                    )
                }
                }
            }
        }
    }
}
