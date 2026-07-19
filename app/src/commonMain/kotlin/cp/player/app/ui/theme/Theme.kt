package cp.player.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 主题模式：跟随系统 / 浅色 / 深色。
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 平台动态取色能力（Android 12+可用）。-desktop 默认不可用。
 * 在 @Composable 上下文中读取平台 LocalContext（Compose MP 提供 no-arg 变体）。
 */
@Composable
expect fun supportsDynamicColor(): Boolean

@Composable
internal expect fun dynamicColorScheme(dark: Boolean): androidx.compose.material3.ColorScheme?

/**
 * 应用主题入口。
 * @param themeMode 主题模式
 * @param dynamicColor 是否使用动态取色（仅 Android 12+ 生效）
 */
@Composable
fun CpTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> systemDark }
    val dynamicAvailable = supportsDynamicColor()
    val dynamicScheme = if (dynamicColor && dynamicAvailable) dynamicColorScheme(dark) else null
    val baseScheme = dynamicScheme ?: if (dark) DarkColors else LightColors
    val colorScheme = if (dark && pureBlack) {
        baseScheme.copy(
            surface = Color.Black,
            background = Color.Black,
            surfaceVariant = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color.Black,
            surfaceContainer = Color.Black,
            surfaceContainerHigh = Color.Black,
            surfaceContainerHighest = Color.Black,
        )
    } else {
        baseScheme
    }
    CompositionLocalProvider(LocalThemeMode provides themeMode) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

val LocalThemeMode = staticCompositionLocalOf { ThemeMode.SYSTEM }