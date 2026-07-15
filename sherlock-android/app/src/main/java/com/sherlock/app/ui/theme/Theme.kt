package com.sherlock.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color

// Tactical / military intelligence terminal palette
private val Phosphor = Color(0xFF3DF07A)      // primary night-vision green
private val PhosphorDim = Color(0xFF1FA855)
private val Amber = Color(0xFFFFB300)          // alerts / accents (HUD amber)
private val Void = Color(0xFF060A06)           // background (near-black olive)
private val Panel = Color(0xFF0E140E)          // surface
private val PanelHi = Color(0xFF16211A)        // elevated surface
private val Ink = Color(0xFFC8E6C9)            // primary text (pale green)
private val InkDim = Color(0xFF6C8A72)         // secondary text
private val Alert = Color(0xFFFF3B30)          // red — negatives / danger

private val TacticalColors = darkColorScheme(
    primary = Phosphor,
    onPrimary = Color.Black,
    primaryContainer = PhosphorDim,
    onPrimaryContainer = Color.Black,
    secondary = Amber,
    onSecondary = Color.Black,
    tertiary = Amber,
    background = Void,
    onBackground = Ink,
    surface = Panel,
    onSurface = Ink,
    surfaceVariant = PanelHi,
    onSurfaceVariant = InkDim,
    error = Alert,
    onError = Color.Black,
    outline = Color(0xFF2C4030)
)

// Everything monospace — terminal feel.
private val Mono = Typography().run {
    val f = FontFamily.Monospace
    copy(
        displayLarge = displayLarge.mono(f), displayMedium = displayMedium.mono(f), displaySmall = displaySmall.mono(f),
        headlineLarge = headlineLarge.mono(f), headlineMedium = headlineMedium.mono(f), headlineSmall = headlineSmall.mono(f),
        titleLarge = titleLarge.mono(f), titleMedium = titleMedium.mono(f), titleSmall = titleSmall.mono(f),
        bodyLarge = bodyLarge.mono(f), bodyMedium = bodyMedium.mono(f), bodySmall = bodySmall.mono(f),
        labelLarge = labelLarge.mono(f), labelMedium = labelMedium.mono(f), labelSmall = labelSmall.mono(f)
    )
}

private fun TextStyle.mono(f: FontFamily) = copy(fontFamily = f)

@Composable
fun SherlockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TacticalColors,
        typography = Mono,
        content = content
    )
}
