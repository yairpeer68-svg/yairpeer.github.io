package com.sherlock.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val SherlockDark = Color(0xFF0D1117)
val SherlockSurface = Color(0xFF161B22)
val SherlockPrimary = Color(0xFF58A6FF)
val SherlockSecondary = Color(0xFF00BFA5)
val SherlockAccent = Color(0xFFF78166)
val SherlockOnSurface = Color(0xFFC9D1D9)
val SherlockOnBackground = Color(0xFFE6EDF3)
val SherlockError = Color(0xFFF85149)
val SherlockSuccess = Color(0xFF3FB950)
val SherlockWarning = Color(0xFFD29922)

private val DarkColorScheme = darkColorScheme(
    primary = SherlockPrimary,
    secondary = SherlockSecondary,
    tertiary = SherlockAccent,
    background = SherlockDark,
    surface = SherlockSurface,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = SherlockOnBackground,
    onSurface = SherlockOnSurface,
    error = SherlockError,
    surfaceVariant = Color(0xFF21262D),
    onSurfaceVariant = Color(0xFF8B949E),
    outline = Color(0xFF30363D)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1A237E),
    secondary = Color(0xFF00897B),
    tertiary = Color(0xFFE65100),
    background = Color(0xFFF6F8FA),
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF24292F),
    onSurface = Color(0xFF24292F),
    surfaceVariant = Color(0xFFEAEEF2),
    onSurfaceVariant = Color(0xFF57606A),
    outline = Color(0xFFD0D7DE)
)

@Composable
fun SherlockTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
