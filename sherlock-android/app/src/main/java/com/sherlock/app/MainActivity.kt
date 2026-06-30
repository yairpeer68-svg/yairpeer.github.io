package com.sherlock.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.navigation.compose.rememberNavController
import com.sherlock.app.data.model.AppTheme
import com.sherlock.app.data.model.FontScale
import com.sherlock.app.ui.navigation.SherlockNavGraph
import com.sherlock.app.ui.theme.SherlockTheme
import com.sherlock.app.util.NotificationHelper
import com.sherlock.app.util.SettingsManager
import com.sherlock.app.ui.screens.AppLockScreen
import androidx.compose.foundation.layout.fillMaxSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private lateinit var settings: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        settings = SettingsManager(this)
        NotificationHelper(this)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                CoroutineScope(Dispatchers.IO).launch { settings.recordBackgroundTime() }
            }
        })

        setContent {
            var currentTheme by remember { mutableStateOf(AppTheme.DARK_BLUE) }
            var currentFontScale by remember { mutableStateOf(FontScale.MEDIUM) }
            var isLocked by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                isLocked = settings.appLockEnabled.first()
            }

            DisposableEffect(Unit) {
                val observer = object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        CoroutineScope(Dispatchers.Main).launch {
                            if (settings.appLockEnabled.first() && settings.shouldRelockAfterBackground()) {
                                isLocked = true
                            }
                        }
                    }
                }
                ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
                onDispose { ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) }
            }

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

            LaunchedEffect(Unit) {
                settings.screenshotProtection.collect { protect ->
                    if (protect) {
                        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
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
                        if (isLocked) {
                            AppLockScreen(onUnlocked = { isLocked = false })
                        } else {
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
}
