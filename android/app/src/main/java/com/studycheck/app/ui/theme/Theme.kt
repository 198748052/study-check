package com.studycheck.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 配色。
 *
 * 1.8.0 起支持深色模式：所有颜色都从 [LocalPalette] 取，
 * 下面这些 `val Bg` / `val Ink` … 都是「当前主题下的颜色」，
 * 用处和以前的常量完全一样，只是会跟着深浅色变。
 */
data class Palette(
    val brand: Color,
    val brandSoft: Color,
    val mint: Color,
    val bg: Color,
    val card: Color,
    val surface2: Color,
    val line: Color,
    val ink: Color,
    val muted: Color,
    val danger: Color,
    val isDark: Boolean,
)

val LightPalette = Palette(
    brand = Color(0xFFAB6B16),
    brandSoft = Color(0xFFD99B3D),
    mint = Color(0xFF0D8F66),
    bg = Color(0xFFF6F6F3),
    card = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF1F2EE),
    line = Color(0xFFE4E5DF),
    ink = Color(0xFF1B2027),
    muted = Color(0xFF6D7683),
    danger = Color(0xFFB3261E),
    isDark = false,
)

val DarkPalette = Palette(
    brand = Color(0xFFE9B268),
    brandSoft = Color(0xFFF3CB92),
    mint = Color(0xFF4FD1A5),
    bg = Color(0xFF101319),
    card = Color(0xFF181C24),
    surface2 = Color(0xFF20252F),
    line = Color(0xFF2B313D),
    ink = Color(0xFFE8ECF3),
    muted = Color(0xFF98A2B3),
    danger = Color(0xFFFF8A80),
    isDark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

/** 外观模式：auto（跟随系统）/ light / dark */
object ThemeController {
    val mode = MutableStateFlow("auto")

    fun resolve(systemDark: Boolean): Boolean = when (mode.value) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }
}

val Brand: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.brand

val BrandSoft: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.brandSoft

val Mint: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.mint

val Bg: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.bg

/** 卡片底色（浅色是白，深色是深灰）——原来写 Color.White 的地方改用它 */
val Card: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.card

val Surface2: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.surface2

val Line: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.line

val Ink: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.ink

val Muted: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.muted

val Danger: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.danger

private val LightColors = lightColorScheme(
    primary = LightPalette.brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6E7CB),
    onPrimaryContainer = Color(0xFF3A2405),
    secondary = LightPalette.mint,
    onSecondary = Color.White,
    background = LightPalette.bg,
    onBackground = LightPalette.ink,
    surface = LightPalette.card,
    onSurface = LightPalette.ink,
    surfaceVariant = LightPalette.surface2,
    onSurfaceVariant = LightPalette.muted,
    outline = LightPalette.line,
    outlineVariant = LightPalette.line,
    error = LightPalette.danger,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = DarkPalette.brand,
    onPrimary = Color(0xFF2A1B08),
    primaryContainer = Color(0xFF3A2B12),
    onPrimaryContainer = Color(0xFFF6DFC0),
    secondary = DarkPalette.mint,
    onSecondary = Color(0xFF06281D),
    background = DarkPalette.bg,
    onBackground = DarkPalette.ink,
    surface = DarkPalette.card,
    onSurface = DarkPalette.ink,
    surfaceVariant = DarkPalette.surface2,
    onSurfaceVariant = DarkPalette.muted,
    outline = DarkPalette.line,
    outlineVariant = DarkPalette.line,
    error = DarkPalette.danger,
    onError = Color(0xFF3A0A06),
)

@Composable
fun StudyCheckTheme(content: @Composable () -> Unit) {
    val mode by ThemeController.mode.collectAsState()
    val darkTheme = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val palette = if (darkTheme) DarkPalette else LightPalette
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = Typography(),
            content = content,
        )
    }
}
