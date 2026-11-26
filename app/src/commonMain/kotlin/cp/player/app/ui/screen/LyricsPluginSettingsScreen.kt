package cp.player.app.ui.screen

import androidx.compose.runtime.Composable
import cp.player.app.ui.navigation.RoutePage

/**
 * 歌词来源深链：合并后的插件中心仍以「已安装」为首屏，歌词来源就在首屏内。
 */
class LyricsPluginSettingsScreen : RoutePage {

    @Composable
    override fun Content() {
        PluginHubScreen(initialView = PluginHubView.INSTALLED).Content()
    }
}
