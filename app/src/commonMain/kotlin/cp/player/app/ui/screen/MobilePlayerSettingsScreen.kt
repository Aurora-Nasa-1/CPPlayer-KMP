package cp.player.app.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.SettingsButtonItem
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSliderItem
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.component.settingsSearchTarget
import cp.player.app.ui.feedback.CpHaptic
import cp.player.app.ui.feedback.LocalCpHaptics
import cp.player.app.ui.util.popOrNotify
import kotlin.math.roundToInt

/** Behaviour and reading preferences; visual proportions are owned by the layout. */
class MobilePlayerSettingsScreen(private val targetSettingId: String? = null) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val haptics = LocalCpHaptics.current
        val config by AppModel.mobilePlayerCustomConfigFlow.collectAsState()
        var draggingSize by remember { mutableIntStateOf(-1) }
        val size = if (draggingSize >= 0) draggingSize else config.lyricsFontSizeSp

        CpRouteScaffold(title = "手机播放界面", onBack = { navigator.popOrNotify() }) { modifier ->
            SettingsPage(modifier) {
                SettingsSection("播放器") {
                    SettingsSwitchItem(
                        title = "单页播放布局",
                        subtitle = "封面优先，轻触歌词切换沉浸阅读",
                        icon = Icons.Filled.PhoneAndroid,
                        checked = config.enabled,
                        onCheckedChange = AppModel::setModernMobilePlayerEnabled,
                        index = 0, total = 2,
                        modifier = Modifier.settingsSearchTarget("appearance_modern_mobile_player", targetSettingId),
                    )
                    SettingsSwitchItem(
                        title = "封面左右滑动切歌",
                        subtitle = "向左下一首，向右上一首",
                        checked = config.coverSwipeGesture,
                        onCheckedChange = {
                            haptics.perform(CpHaptic.Tick)
                            AppModel.updateMobilePlayerCustomConfig { c -> c.copy(coverSwipeGesture = it) }
                        },
                        index = 1, total = 2,
                        modifier = Modifier.settingsSearchTarget("mobile_player_cover_swipe", targetSettingId),
                    )
                }
                SettingsSection("歌词") {
                    val lyricsTotal = if (config.lyricsImmersiveEnabled) 5 else 4
                    SettingsSwitchItem(
                        title = "歌词沉浸模式",
                        subtitle = "在完整歌词界面无操作几秒后自动隐藏顶部和底部控制栏，轻触屏幕任意位置恢复",
                        icon = Icons.Filled.Fullscreen,
                        checked = config.lyricsImmersiveEnabled,
                        onCheckedChange = {
                            haptics.perform(CpHaptic.Tick)
                            AppModel.updateMobilePlayerCustomConfig { c -> c.copy(lyricsImmersiveEnabled = it) }
                        },
                        index = 0, total = lyricsTotal,
                        modifier = Modifier.settingsSearchTarget("mobile_player_lyrics_immersive", targetSettingId),
                    )
                    if (config.lyricsImmersiveEnabled) {
                        SettingsSliderItem(
                            title = "沉浸等待时间",
                            subtitle = "无触控操作后自动收起控制栏的等待时长",
                            value = config.lyricsImmersiveTimeoutSeconds.toFloat(),
                            onValueChange = {
                                val s = it.roundToInt().coerceIn(2, 10)
                                if (s != config.lyricsImmersiveTimeoutSeconds) {
                                    AppModel.updateMobilePlayerCustomConfig { c -> c.copy(lyricsImmersiveTimeoutSeconds = s) }
                                }
                            },
                            valueRange = 2f..10f, steps = 7,
                            valueLabel = "${config.lyricsImmersiveTimeoutSeconds} 秒",
                            index = 1, total = lyricsTotal,
                            modifier = Modifier.settingsSearchTarget("mobile_player_lyrics_timeout", targetSettingId),
                        )
                    }
                    SettingsSliderItem(
                        title = "歌词预览字号",
                        subtitle = "仅调整封面下方的当前歌词",
                        value = size.toFloat(),
                        onValueChange = { draggingSize = it.roundToInt() },
                        valueRange = 14f..22f, steps = 7,
                        onValueChangeFinished = {
                            AppModel.updateMobilePlayerCustomConfig { it.copy(lyricsFontSizeSp = size) }
                            draggingSize = -1
                        },
                        valueLabel = "$size sp",
                        index = if (config.lyricsImmersiveEnabled) 2 else 1, total = lyricsTotal,
                        modifier = Modifier.settingsSearchTarget("mobile_player_lyrics_size", targetSettingId),
                    )
                    SettingsSwitchItem(
                        title = "预览逐字高亮",
                        subtitle = "歌词含逐字时间时显示演唱进度",
                        checked = config.karaokeEnabled,
                        onCheckedChange = {
                            AppModel.updateMobilePlayerCustomConfig { c -> c.copy(karaokeEnabled = it) }
                        },
                        index = if (config.lyricsImmersiveEnabled) 3 else 2, total = lyricsTotal,
                        modifier = Modifier.settingsSearchTarget("mobile_player_karaoke", targetSettingId),
                    )
                    SettingsSwitchItem(
                        title = "显示歌词翻译",
                        subtitle = "同步应用于歌词预览和完整歌词",
                        checked = config.showTranslation,
                        onCheckedChange = {
                            AppModel.updateMobilePlayerCustomConfig { c -> c.copy(showTranslation = it) }
                        },
                        index = if (config.lyricsImmersiveEnabled) 4 else 3, total = lyricsTotal,
                        modifier = Modifier.settingsSearchTarget("mobile_player_translation", targetSettingId),
                    )
                }
                SettingsSection("重置") {
                    SettingsButtonItem(
                        text = "恢复播放器默认设置",
                        icon = Icons.Filled.Refresh,
                        onClick = {
                            haptics.perform(CpHaptic.Confirm)
                            AppModel.resetMobilePlayerCustomConfig()
                        },
                        index = 0, total = 1,
                        modifier = Modifier.settingsSearchTarget("mobile_player_reset", targetSettingId),
                    )
                }
            }
        }
    }
}
