package com.studycheck.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 配色。
 *
 * 1.8.0 起支持深色模式，1.10.0 起主色可自选：所有颜色都从 [LocalPalette] 取，
 * 下面这些 `val Bg` / `val Ink` … 都是「当前主题下的颜色」，
 * 用处和以前的常量完全一样，只是会跟着深浅色、主色变。
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

/** 一套主色：浅色 / 深色各一个，深色下要提亮才够对比度 */
data class BrandSpec(
    val id: String,
    val label: String,
    val light: Color,
    val dark: Color,
    val softLight: Color,
    val softDark: Color,
)

/** 可选的主色。第一个是默认色（原来的琥珀色，保持老用户视觉不变） */
val BRANDS: List<BrandSpec> = listOf(
    BrandSpec("amber", "琥珀", Color(0xFFAB6B16), Color(0xFFE9B268), Color(0xFFD99B3D), Color(0xFFF3CB92)),
    BrandSpec("violet", "紫罗", Color(0xFF6C4CE0), Color(0xFFB7A6FF), Color(0xFF9B85F0), Color(0xFFD5CBFF)),
    BrandSpec("ocean", "海蓝", Color(0xFF1E6FD9), Color(0xFF7FB6FF), Color(0xFF5A9BEB), Color(0xFFB9D8FF)),
    BrandSpec("forest", "松绿", Color(0xFF1F7A4D), Color(0xFF6FD3A2), Color(0xFF4FA377), Color(0xFFA8E6C8)),
    BrandSpec("rose", "玫红", Color(0xFFC2185B), Color(0xFFFF9EC0), Color(0xFFE05588), Color(0xFFFFC4D8)),
    BrandSpec("graphite", "石墨", Color(0xFF4A5560), Color(0xFFAEB9C4), Color(0xFF7A8794), Color(0xFFD2DAE2)),
)

fun brandSpec(id: String?): BrandSpec = BRANDS.firstOrNull { it.id == id } ?: BRANDS[0]

/** 按主色 + 深浅色生成一整套配色 */
fun paletteOf(spec: BrandSpec, isDark: Boolean): Palette {
    if (!isDark) {
        return Palette(
            brand = spec.light,
            brandSoft = spec.softLight,
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
    }
    return Palette(
        brand = spec.dark,
        brandSoft = spec.softDark,
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
}

/** 兼容旧调用点：默认主色 + 浅色 */
val LightPalette: Palette = paletteOf(BRANDS[0], false)
val DarkPalette: Palette = paletteOf(BRANDS[0], true)

val LocalPalette = staticCompositionLocalOf { LightPalette }

/** 外观模式：auto（跟随系统）/ light / dark */
object ThemeController {
    /** auto / light / dark */
    val mode = MutableStateFlow("auto")

    /** 主色 id，见 [BRANDS] */
    val brandId = MutableStateFlow(BRANDS[0].id)

    fun resolve(systemDark: Boolean): Boolean = when (mode.value) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    fun current(systemDark: Boolean): Palette =
        paletteOf(brandSpec(brandId.value), resolve(systemDark))
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

/** 主色里挑一个「当前主题下」的示例色，给设置里的色板小圆点用 */
fun brandPreviewColor(id: String, isDark: Boolean): Color {
    val spec = brandSpec(id)
    return if (isDark) spec.dark else spec.light
}

private fun lightScheme(p: Palette) = lightColorScheme(
    primary = p.brand,
    onPrimary = Color.White,
    primaryContainer = p.brand.copy(alpha = 0.16f),
    onPrimaryContainer = p.brand,
    secondary = p.mint,
    onSecondary = Color.White,
    background = p.bg,
    onBackground = p.ink,
    surface = p.card,
    onSurface = p.ink,
    surfaceVariant = p.surface2,
    onSurfaceVariant = p.muted,
    outline = p.line,
    outlineVariant = p.line,
    error = p.danger,
    onError = Color.White,
)

private fun darkScheme(p: Palette) = darkColorScheme(
    primary = p.brand,
    onPrimary = Color(0xFF2A1B08),
    primaryContainer = p.brand.copy(alpha = 0.20f),
    onPrimaryContainer = p.brand,
    secondary = p.mint,
    onSecondary = Color(0xFF06281D),
    background = p.bg,
    onBackground = p.ink,
    surface = p.card,
    onSurface = p.ink,
    surfaceVariant = p.surface2,
    onSurfaceVariant = p.muted,
    outline = p.line,
    outlineVariant = p.line,
    error = p.danger,
    onError = Color(0xFF3A0A06),
)

@Composable
fun StudyCheckTheme(content: @Composable () -> Unit) {
    val mode by ThemeController.mode.collectAsState()
    val brandId by ThemeController.brandId.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (mode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }
    val palette = paletteOf(brandSpec(brandId), darkTheme)
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkScheme(palette) else lightScheme(palette),
            typography = Typography(),
            content = content,
        )
    }
}
