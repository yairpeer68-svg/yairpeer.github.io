package com.sherlock.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.sherlock.app.data.model.AppTheme

val SherlockSuccess = Color(0xFF3FB950)
val SherlockError = Color(0xFFF85149)
val SherlockWarning = Color(0xFFD29922)

private val DarkBlueScheme = darkColorScheme(
    primary = Color(0xFF58A6FF),
    secondary = Color(0xFF00BFA5),
    tertiary = Color(0xFFF78166),
    background = Color(0xFF0D1117),
    surface = Color(0xFF161B22),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFC9D1D9),
    error = SherlockError,
    surfaceVariant = Color(0xFF21262D),
    onSurfaceVariant = Color(0xFF8B949E),
    outline = Color(0xFF30363D)
)

private val HackerGreenScheme = darkColorScheme(
    primary = Color(0xFF00FF41),
    secondary = Color(0xFF39FF14),
    tertiary = Color(0xFFFFD700),
    background = Color(0xFF000000),
    surface = Color(0xFF0A0A0A),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color(0xFF00FF41),
    onSurface = Color(0xFF00DD35),
    error = Color(0xFFFF0000),
    surfaceVariant = Color(0xFF111111),
    onSurfaceVariant = Color(0xFF00AA30),
    outline = Color(0xFF003300)
)

private val OceanBlueScheme = darkColorScheme(
    primary = Color(0xFF64B5F6),
    secondary = Color(0xFF4FC3F7),
    tertiary = Color(0xFF81D4FA),
    background = Color(0xFF0A1929),
    surface = Color(0xFF0D2137),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color(0xFFE3F2FD),
    onSurface = Color(0xFFBBDEFB),
    error = Color(0xFFEF5350),
    surfaceVariant = Color(0xFF112A45),
    onSurfaceVariant = Color(0xFF90CAF9),
    outline = Color(0xFF1A3A5C)
)

private val SunsetScheme = darkColorScheme(
    primary = Color(0xFFFF7043),
    secondary = Color(0xFFFFAB40),
    tertiary = Color(0xFFFF5252),
    background = Color(0xFF1A0A00),
    surface = Color(0xFF2D1400),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color(0xFFFFE0B2),
    onSurface = Color(0xFFFFCC80),
    error = Color(0xFFFF1744),
    surfaceVariant = Color(0xFF3E1E00),
    onSurfaceVariant = Color(0xFFFFB74D),
    outline = Color(0xFF4E2800)
)

private val PurpleNightScheme = darkColorScheme(
    primary = Color(0xFFCE93D8),
    secondary = Color(0xFFBA68C8),
    tertiary = Color(0xFFE040FB),
    background = Color(0xFF0D0015),
    surface = Color(0xFF1A0026),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color(0xFFF3E5F5),
    onSurface = Color(0xFFE1BEE7),
    error = Color(0xFFFF5252),
    surfaceVariant = Color(0xFF250038),
    onSurfaceVariant = Color(0xFFCE93D8),
    outline = Color(0xFF380050)
)

private val AmoledScheme = darkColorScheme(
    primary = Color(0xFF58A6FF),
    secondary = Color(0xFF00BFA5),
    tertiary = Color(0xFFF78166),
    background = Color(0xFF000000),
    surface = Color(0xFF0A0A0A),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFE0E0E0),
    error = SherlockError,
    surfaceVariant = Color(0xFF121212),
    onSurfaceVariant = Color(0xFF9E9E9E),
    outline = Color(0xFF1E1E1E)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1A237E),
    secondary = Color(0xFF00897B),
    tertiary = Color(0xFFE65100),
    background = Color(0xFFF6F8FA),
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF24292F),
    onSurface = Color(0xFF24292F),
    error = Color(0xFFD32F2F),
    surfaceVariant = Color(0xFFEAEEF2),
    onSurfaceVariant = Color(0xFF57606A),
    outline = Color(0xFFD0D7DE)
)

@Composable
fun SherlockTheme(
    appTheme: AppTheme = AppTheme.DARK_BLUE,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when (appTheme) {
        AppTheme.DARK_BLUE -> DarkBlueScheme
        AppTheme.HACKER_GREEN -> HackerGreenScheme
        AppTheme.OCEAN_BLUE -> OceanBlueScheme
        AppTheme.SUNSET -> SunsetScheme
        AppTheme.PURPLE_NIGHT -> PurpleNightScheme
        AppTheme.AMOLED -> AmoledScheme
        AppTheme.LIGHT -> LightScheme
        AppTheme.MATERIAL_YOU -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicDarkColorScheme(context)
            } else {
                DarkBlueScheme
            }
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                appTheme == AppTheme.LIGHT
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
