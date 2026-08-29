package com.ghosteye.intelligence

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

object GhostEyePalette {
    val Cyan = Color(0xFF56D7FF)
    val CyanSoft = Color(0xFF9FE7FF)
    val Violet = Color(0xFF9C8CFF)
    val Emerald = Color(0xFF48D7A4)
    val Amber = Color(0xFFFFC75B)
    val Rose = Color(0xFFFF6D8A)
    val Ink = Color(0xFF05080E)
    val Panel = Color(0xFF0A111C)
    val PanelRaised = Color(0xFF101A28)
}

private val DarkColors = darkColorScheme(
    primary = GhostEyePalette.Cyan,
    onPrimary = Color(0xFF001F2A),
    primaryContainer = Color(0xFF083749),
    onPrimaryContainer = Color(0xFFC5F1FF),
    secondary = GhostEyePalette.Violet,
    onSecondary = Color(0xFF21194D),
    secondaryContainer = Color(0xFF2C2754),
    onSecondaryContainer = Color(0xFFE7E1FF),
    tertiary = GhostEyePalette.Emerald,
    onTertiary = Color(0xFF002118),
    tertiaryContainer = Color(0xFF0C3B2D),
    onTertiaryContainer = Color(0xFFB8F3DD),
    background = GhostEyePalette.Ink,
    onBackground = Color(0xFFF2F5FA),
    surface = GhostEyePalette.Panel,
    onSurface = Color(0xFFF2F5FA),
    surfaceVariant = Color(0xFF172232),
    onSurfaceVariant = Color(0xFFAEB9C9),
    surfaceContainer = Color(0xFF0D1622),
    surfaceContainerHigh = GhostEyePalette.PanelRaised,
    outline = Color(0xFF526276),
    outlineVariant = Color(0xFF29384A),
    error = GhostEyePalette.Rose,
    errorContainer = Color(0xFF4A1622),
    onErrorContainer = Color(0xFFFFD9E1)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006783),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBCEBFF),
    onPrimaryContainer = Color(0xFF001F2A),
    secondary = Color(0xFF5A4AA1),
    tertiary = Color(0xFF006C51),
    background = Color(0xFFF4F7FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8EEF5),
    onSurfaceVariant = Color(0xFF44515E),
    outlineVariant = Color(0xFFCFD9E5)
)

private val GhostTypography = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
)

private val GhostShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
fun GhostEyeTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = GhostTypography,
        shapes = GhostShapes,
        content = content
    )
}
