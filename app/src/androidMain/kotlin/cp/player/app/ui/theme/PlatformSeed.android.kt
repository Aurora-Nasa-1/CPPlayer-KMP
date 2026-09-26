package cp.player.app.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun supportsPlatformDynamicScheme(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
actual fun platformDynamicScheme(dark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    // remember 是必需的：androidx 的 dynamic*ColorScheme 每次调用都新建实例，
    // 而 ColorScheme 未重写 equals ⇒ 直接返回会让主题动画永远重启。详见 commonMain 的 KDoc。
    return remember(dark, context) {
        if (dark) androidx.compose.material3.dynamicDarkColorScheme(context)
        else androidx.compose.material3.dynamicLightColorScheme(context)
    }
}

/**
 * Android 恒为 null：Android 的系统色是完整的壁纸 Monet（几十个角色色都由系统算好），
 * 只用「一个种子色」复现不出来 —— 复现出来的是「另一个主题」，会和系统 UI 不一致。
 * 所以 Android 的系统色统一走 [platformDynamicScheme]。
 */
actual fun platformAccentSeed(): Color? = null

/**
 * Android 恒为 null —— 壁纸色由 Monet 直接给出整套方案，不是「一个种子色」。
 *
 * 「跟随封面但没有封面」的回退因此不在这里发生，而是在 `CpTheme` 里走
 * [platformDynamicScheme]（Android 12+）；12 以下两者都为 null，继续回退到默认配色。
 */
actual fun platformWallpaperSeed(): Color? = null
