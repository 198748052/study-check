package com.studycheck.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Brand = Color(0xFFAB6B16)
val BrandSoft = Color(0xFFD99B3D)
val Mint = Color(0xFF0D8F66)
val Bg = Color(0xFFF6F6F3)
val Surface2 = Color(0xFFF1F2EE)
val Line = Color(0xFFE4E5DF)
val Ink = Color(0xFF1B2027)
val Muted = Color(0xFF6D7683)
val Danger = Color(0xFFB3261E)

private val Colors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6E7CB),
    onPrimaryContainer = Color(0xFF3A2405),
    secondary = Mint,
    onSecondary = Color.White,
    background = Bg,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Surface2,
    onSurfaceVariant = Muted,
    outline = Line,
    outlineVariant = Line,
    error = Danger,
    onError = Color.White,
)

@Composable
fun StudyCheckTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Colors,
        typography = Typography(),
        content = content,
    )
}
