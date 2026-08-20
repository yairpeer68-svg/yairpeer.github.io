package com.ghosteye.intelligence

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF61D6FF),
    onPrimary = Color(0xFF002A36),
    primaryContainer = Color(0xFF07384A),
    onPrimaryContainer = Color(0xFFB9ECFF),
    secondary = Color(0xFFB6A1FF),
    secondaryContainer = Color(0xFF30275A),
    background = Color(0xFF070B12),
    onBackground = Color(0xFFE5EAF2),
    surface = Color(0xFF0B111B),
    onSurface = Color(0xFFE5EAF2),
    surfaceVariant = Color(0xFF18212E),
    onSurfaceVariant = Color(0xFFB5C0CF),
    error = Color(0xFFFF6B78),
    errorContainer = Color(0xFF4A1720),
    onErrorContainer = Color(0xFFFFD9DD)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00677E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9ECFF),
    onPrimaryContainer = Color(0xFF001F28),
    secondary = Color(0xFF5E4C9E),
    background = Color(0xFFF6F8FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFE7ECF3),
    onSurfaceVariant = Color(0xFF414A56)
)

@Composable
fun GhostEyeTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
