package cp.player.app.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cp.player.app.ui.navigation.RoutePage
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.presentation.navigation.AppRoute
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.SettingsButtonItem
import cp.player.app.ui.navigation.LocalAppNavigator
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsHeroCard
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSegmentedItem
import cp.player.app.ui.component.SettingsSliderItem
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.component.settingsSearchTarget
import cp.player.app.ui.feedback.CpHaptic
import cp.player.app.ui.feedback.LocalCpHaptics
import kotlin.math.roundToInt

/**
 * 播放器设置：两种手机播放器共用同一份偏好。
 *
 * 歌词设置（翻译 / 注音 / 分词 / 逐字 / 来源）原先散在「播放与音质」，
 * 但它只影响播放页的呈现，跟着播放器样式走更符合用户心智；这里集中后
 * 三段式与标准播放器都能拿到同一组开关。
 */
class MobilePlayerSettingsScreen(private val targetSettingId: String? = null) : RoutePage {
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.current
        val haptics = LocalCpHaptics.current
        val s = cpStrings()
        val config by AppModel.mobilePlayerCustomConfigFlow.collectAsState()
        val showMiniPlayerLyrics by AppModel.showMiniPlayerLyricsFlow.collectAsState()
        val showRuby by AppModel.showRubyLyricsFlow.collectAsState()
        val wordSeg by AppModel.lyricsWordSegmentationFlow.collectAsState()
        val smartKaraoke by AppModel.smartKaraokeLrcFlow.collectAsState()
        var draggingTimeout by remember { mutableIntStateOf(-1) }
        val timeout = if (draggingTimeout >= 0) draggingTimeout else config.lyricsImmersiveTimeoutSeconds

        CpRouteScaffold(title = s.appearance.playerSettingsTitle, onBack = { navigator.pop() }) { modifier ->
            SettingsPage(modifier) {
                val isAndroid = isAndroidPlatform()
                if (isAndroid) {
                    SettingsHeroCard(
                        title = if (config.enabled) s.appearance.playerStyleStandard else s.appearance.playerStyleThreePane,
                        subtitle = if (config.enabled) {
                            s.appearance.playerStyleStandardNote
                        } else {
                            s.appearance.playerStyleThreePaneNote
                        },
                        icon = Icons.Filled.Tune,
                        badgeText = s.appearance.playerSettingsHeroNote,
                    )

                    SettingsSection(s.appearance.playerStyle) {
                        SettingsSegmentedItem(
                            title = s.appearance.playerStyle,
                            subtitle = if (config.enabled) {
                                s.appearance.playerStyleStandardNote
                            } else {
                                s.appearance.playerStyleThreePaneNote
                            },
                            options = listOf(
                                s.appearance.playerStyleThreePane,
                                s.appearance.playerStyleStandard,
                            ),
                            selectedIndex = if (config.enabled) 1 else 0,
                            onSelect = { index ->
                                haptics.perform(CpHaptic.Tick)
                                AppModel.setModernMobilePlayerEnabled(index == 1)
                            },
                            index = 0,
                            total = 1,
                            modifier = Modifier.settingsSearchTarget(
                                "appearance_modern_mobile_player",
                                targetSettingId,
                            ),
                        )
                    }
                }

                PlayerLyricsPresentationSection(
                    targetSettingId = targetSettingId,
                    onNavigateLyricsPlugin = { navigator.push(AppRoute.LyricsPluginSettings) },
                )

                if (isAndroid) {
                    SettingsSection(s.appearance.playerLyricsImmersive) {
                        SettingsSwitchItem(
                            title = s.appearance.playerLyricsImmersiveToggle,
                            subtitle = s.appearance.playerLyricsImmersiveNote,
                            icon = Icons.Filled.Fullscreen,
                            checked = config.lyricsImmersiveEnabled,
                            onCheckedChange = { checked ->
                                haptics.perform(CpHaptic.Tick)
                                AppModel.updateMobilePlayerCustomConfig {
                                    it.copy(lyricsImmersiveEnabled = checked)
                                }
                            },
                            index = 0,
                            total = 2,
                            modifier = Modifier.settingsSearchTarget(
                                "mobile_player_lyrics_immersive",
                                targetSettingId,
                            ),
                        )
                        SettingsSliderItem(
                            title = s.appearance.playerLyricsImmersiveTimeout,
                            subtitle = s.appearance.playerLyricsImmersiveTimeoutNote,
                            value = timeout.toFloat(),
                            onValueChange = { draggingTimeout = it.roundToInt() },
                            onValueChangeFinished = {
                                AppModel.updateMobilePlayerCustomConfig {
                                    it.copy(lyricsImmersiveTimeoutSeconds = timeout.coerceIn(2, 15))
                                }
                                draggingTimeout = -1
                            },
                            valueRange = 2f..15f,
                            steps = 12,
                            valueLabel = s.appearance.playerLyricsImmersiveSeconds(timeout),
                            enabled = config.lyricsImmersiveEnabled,
                            index = 1,
                            total = 2,
                            modifier = Modifier.settingsSearchTarget(
                                "mobile_player_lyrics_timeout",
                                targetSettingId,
                            ),
                        )
                    }
                }

                SettingsSection(s.appearance.playerResetSection) {
                    SettingsButtonItem(
                        text = s.appearance.playerReset,
                        subtitle = s.appearance.playerResetNote,
                        icon = Icons.Filled.Refresh,
                        onClick = {
                            haptics.perform(CpHaptic.Confirm)
                            AppModel.resetMobilePlayerCustomConfig()
                        },
                        index = 0,
                        total = 1,
                        modifier = Modifier.settingsSearchTarget("mobile_player_reset", targetSettingId),
                    )
                }
            }
        }
    }
}

/**
 * 歌词呈现设置分区：全平台通用（桌面端与移动端共享）。
 */
@Composable
fun PlayerLyricsPresentationSection(
    targetSettingId: String? = null,
    onNavigateLyricsPlugin: () -> Unit,
) {
    val s = cpStrings()
    val config by AppModel.mobilePlayerCustomConfigFlow.collectAsState()
    val showMiniPlayerLyrics by AppModel.showMiniPlayerLyricsFlow.collectAsState()
    val showRuby by AppModel.showRubyLyricsFlow.collectAsState()
    val wordSeg by AppModel.lyricsWordSegmentationFlow.collectAsState()
    val smartKaraoke by AppModel.smartKaraokeLrcFlow.collectAsState()

    SettingsSection(s.appearance.playerLyricsPresentation) {
        SettingsSwitchItem(
            title = s.appearance.playerLyricsTranslation,
            subtitle = s.appearance.playerLyricsTranslationNote,
            checked = config.showTranslation,
            onCheckedChange = { checked ->
                AppModel.updateMobilePlayerCustomConfig { it.copy(showTranslation = checked) }
            },
            index = 0,
            total = 6,
            modifier = Modifier.settingsSearchTarget("mobile_player_translation", targetSettingId),
        )
        SettingsSwitchItem(
            title = s.playback.showMiniPlayerLyrics,
            subtitle = s.playback.showMiniPlayerLyricsSubtitle,
            checked = showMiniPlayerLyrics,
            onCheckedChange = { AppModel.setShowMiniPlayerLyrics(it) },
            index = 1,
            total = 6,
            modifier = Modifier.settingsSearchTarget(
                "mobile_player_lyrics_mini",
                targetSettingId,
            ),
        )
        SettingsSwitchItem(
            title = s.playback.showRubyLyrics,
            subtitle = s.playback.showRubyLyricsSubtitle,
            checked = showRuby,
            onCheckedChange = { AppModel.setShowRubyLyrics(it) },
            index = 2,
            total = 6,
            modifier = Modifier.settingsSearchTarget(
                "mobile_player_lyrics_ruby",
                targetSettingId,
            ),
        )
        SettingsSwitchItem(
            title = s.playback.lyricsWordSegmentation,
            subtitle = s.playback.lyricsWordSegmentationSubtitle,
            checked = wordSeg,
            onCheckedChange = { AppModel.setLyricsWordSegmentation(it) },
            index = 3,
            total = 6,
            modifier = Modifier.settingsSearchTarget(
                "mobile_player_lyrics_segmentation",
                targetSettingId,
            ),
        )
        SettingsSwitchItem(
            title = s.playback.smartKaraokeLrc,
            subtitle = s.playback.smartKaraokeLrcSubtitle,
            checked = smartKaraoke,
            onCheckedChange = { AppModel.setSmartKaraokeLrc(it) },
            index = 4,
            total = 6,
            modifier = Modifier.settingsSearchTarget(
                "mobile_player_lyrics_karaoke",
                targetSettingId,
            ),
        )
        SettingsClickItem(
            title = s.playback.lyricsSource,
            subtitle = s.playback.lyricsSourceNote,
            icon = Icons.Filled.Lyrics,
            index = 5,
            total = 6,
            onClick = onNavigateLyricsPlugin,
            showChevron = true,
            modifier = Modifier.settingsSearchTarget(
                "mobile_player_lyrics_source",
                targetSettingId,
            ),
        )
    }
}
