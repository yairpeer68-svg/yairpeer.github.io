package com.sherlock.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.navigation.compose.rememberNavController
import com.sherlock.app.data.model.AppTheme
import com.sherlock.app.data.model.FontScale
import com.sherlock.app.ui.navigation.SherlockNavGraph
import com.sherlock.app.ui.theme.SherlockTheme
import com.sherlock.app.util.NotificationHelper
import com.sherlock.app.util.SettingsManager
import androidx.compose.foundation.layout.fillMaxSize

class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        settings = SettingsManager(this)
        NotificationHelper(this)

        setContent {
            var currentTheme by remember { mutableStateOf(AppTheme.DARK_BLUE) }
            var currentFontScale by remember { mutableStateOf(FontScale.MEDIUM) }

            LaunchedEffect(Unit) {
                settings.theme.collect { theme ->
                    currentTheme = theme
                }
            }

            LaunchedEffect(Unit) {
                settings.fontScale.collect { scale ->
                    currentFontScale = scale
                }
            }

            SherlockTheme(appTheme = currentTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val baseDensity = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(baseDensity.density, currentFontScale.scale)
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
}
