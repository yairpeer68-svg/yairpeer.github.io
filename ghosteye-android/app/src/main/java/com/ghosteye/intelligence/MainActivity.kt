package com.ghosteye.intelligence

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

class MainActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        CrashReporter.install(this)
        val prefs = AppPreferences(this)
        setContent {
            var darkMode by remember { mutableStateOf(prefs.darkMode) }
            GhostEyeTheme(darkTheme = darkMode) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AuthGate(baseUrl = ServerConfig.BASE_URL) { onLogout ->
                        MainShell(
                            baseUrl = ServerConfig.BASE_URL,
                            darkMode = darkMode,
                            onDarkModeChange = {
                                darkMode = it
                                prefs.darkMode = it
                            },
                            onLogout = onLogout
                        )
                    }
                }
            }
        }
    }
}
