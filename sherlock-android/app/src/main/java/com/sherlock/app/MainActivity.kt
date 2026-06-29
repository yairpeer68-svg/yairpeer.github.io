package com.sherlock.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.sherlock.app.data.model.AppTheme
import com.sherlock.app.ui.navigation.SherlockNavGraph
import com.sherlock.app.ui.theme.SherlockTheme
import com.sherlock.app.util.NotificationHelper
import com.sherlock.app.util.SettingsManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        settings = SettingsManager(this)
        NotificationHelper(this)

        lifecycleScope.launch {
            val screenshotProtection = settings.screenshotProtection.first()
            if (screenshotProtection) {
                window.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE
                )
            }
        }

        setContent {
            var currentTheme by remember { mutableStateOf(AppTheme.DARK_BLUE) }

            LaunchedEffect(Unit) {
                settings.theme.collect { theme ->
                    currentTheme = theme
                }
            }

            SherlockTheme(appTheme = currentTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    SherlockNavGraph(
                        navController = navController,
                        onThemeChange = { newTheme ->
                            currentTheme = newTheme
                        }
                    )
                }
            }
        }
    }
}
