package cp.player.app.ui.screen

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.screen.Screen

/**
 * 歌词来源页（已与音源管理整合，默认定位至歌词来源 Tab）。
 */
class LyricsPluginSettingsScreen : Screen {

    @Composable
    override fun Content() {
        ProviderManagementScreen(initialTab = 1).Content()
    }
}
