package cp.player.app.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cp.player.app.ui.theme.ColorSource
import cp.player.app.ui.theme.LocalIsDarkTheme

/**
 * 历史动效卡片兼容性存根包。
 *
 * 为保持 Android 原生简洁体验，设置项顶部的伪手机壳及展示动效已被精简，
 * 原生设置页面直接呈现清晰、纯粹的 Material 3 设置项与分段控件。
 */

@Deprecated("SettingsAnimationCard is deprecated. Use standard SettingsKit components instead.")
@Composable
fun SettingsAnimationCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    badgeText: String? = null,
    badgeColor: Color = MaterialTheme.colorScheme.primaryContainer,
    badgeContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier.padding(16.dp),
            content = content,
        )
    }
}

@Deprecated("Preview cards have been removed in favor of direct Android native settings items.")
@Composable
fun DarkThemePreviewCard(
    modifier: Modifier = Modifier,
    isDark: Boolean = LocalIsDarkTheme.current,
    pureBlack: Boolean = false,
    colorSource: ColorSource = ColorSource.FIXED,
    allowInteractiveToggle: Boolean = true,
) {
    // 简化留空，避免在设置页展示冗余动画模型
}

@Deprecated("Use direct settings items instead.")
@Composable
fun DarkThemePhonePreviewCard(
    modifier: Modifier = Modifier,
    isDark: Boolean = LocalIsDarkTheme.current,
    pureBlack: Boolean = false,
    colorSource: ColorSource = ColorSource.FIXED,
    allowInteractiveToggle: Boolean = true,
) {
}

@Deprecated("Preview cards have been removed in favor of direct Android native settings items.")
@Composable
fun AudioEnginePreviewCard(
    modifier: Modifier = Modifier,
    isFlickEngine: Boolean = true,
) {
}

@Deprecated("Use direct settings items instead.")
@Composable
fun AudioEnginePhonePreviewCard(
    modifier: Modifier = Modifier,
    isFlickEngine: Boolean = true,
) {
}

@Deprecated("Preview cards have been removed in favor of direct Android native settings items.")
@Composable
fun MotionDisplayPreviewCard(
    modifier: Modifier = Modifier,
    flightActive: Boolean = true,
    fluidActive: Boolean = true,
) {
}

@Deprecated("Use direct settings items instead.")
@Composable
fun MotionDisplayPhonePreviewCard(
    modifier: Modifier = Modifier,
    flightActive: Boolean = true,
    fluidActive: Boolean = true,
) {
}

@Deprecated("SettingsPhoneFrame is deprecated. Native Android settings do not embed miniature phone cases.")
@Composable
fun SettingsPhoneFrame(
    modifier: Modifier = Modifier,
    width: Dp = 142.dp,
    height: Dp = 224.dp,
    chassisColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    screenColor: Color = MaterialTheme.colorScheme.surface,
    isDarkContent: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier, content = content)
}
