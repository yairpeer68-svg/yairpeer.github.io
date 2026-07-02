package com.sherlock.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF00E676)
private val DarkGreen = Color(0xFF00C853)
private val Background = Color(0xFF0D0D0D)
private val Surface = Color(0xFF1A1A1A)
private val SurfaceVariant = Color(0xFF2A2A2A)

private val SherlockColors = darkColorScheme(
    primary = Green,
    onPrimary = Color.Black,
    primaryContainer = DarkGreen,
    onPrimaryContainer = Color.Black,
    secondary = Color(0xFF00BCD4),
    onSecondary = Color.Black,
    background = Background,
    onBackground = Color(0xFFE0E0E0),
    surface = Surface,
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = Color(0xFFB0B0B0),
    error = Color(0xFFFF5252),
    onError = Color.Black,
    outline = Color(0xFF404040)
)

@Composable
fun SherlockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SherlockColors,
        typography = Typography(),
        content = content
    )
}
