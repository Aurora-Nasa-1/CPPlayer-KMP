package cp.player.app.ui.screen

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.screen.Screen
import cp.player.app.platform.PlatformRenderTuningContent

/**
 * 「渲染后端」设置页入口。
 *
 * 正文由 [PlatformRenderTuningContent] 提供：桌面端是真实可切换的界面，Android 端为空。
 * 该入口也只在桌面端注册（见 `SettingsScreen.settingsEntries`）。
 *
 * 放在 commonMain 只是为了让 `SettingsRegistry` 的入口表两端通用；真正有意义的逻辑
 * 全在 desktopMain 的 `DesktopRenderTuning` 里。
 */
class RenderTuningSettingsScreen : Screen {
    @Composable
    override fun Content() {
        PlatformRenderTuningContent()
    }
}
