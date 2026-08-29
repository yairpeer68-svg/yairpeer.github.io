package com.ghosteye.intelligence

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        CrashReporter.install(this)
        WatchtowerWorker.schedule(this)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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
