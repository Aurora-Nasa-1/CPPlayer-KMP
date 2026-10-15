package cp.player.app.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import cp.player.app.AppModel
import cp.player.core.provider.BackendProvider

/**
 * 当前活跃音源的 CompositionLocal。
 */
val LocalActiveProvider = staticCompositionLocalOf<BackendProvider?> { null }

/**
 * 响应式检查当前音源是否支持特定能力。
 */
@Composable
fun rememberActiveProviderSupports(capability: String): Boolean {
    val activeProvider by AppModel.activeProviderFlow.collectAsState()
    return activeProvider?.isFeatureAvailable(capability) ?: true
}

/**
 * 音源能力门禁容器。
 *
 * 当当前音源支持 [capability] 时，渲染 [content]；
 * 不支持时，渲染 [fallback]（缺省为 null，即物理隐藏，不占用任何视觉与布局空间）。
 */
@Composable
fun ProviderFeatureGate(
    capability: String,
    fallback: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    if (rememberActiveProviderSupports(capability)) {
        content()
    } else {
        fallback?.invoke()
    }
}
