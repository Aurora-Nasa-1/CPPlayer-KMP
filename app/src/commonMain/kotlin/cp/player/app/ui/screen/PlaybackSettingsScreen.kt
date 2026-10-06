package cp.player.app.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.platform.isIgnoringBatteryOptimizations
import cp.player.app.platform.requestIgnoreBatteryOptimizations
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.util.popOrNotify
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsDropdownItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.component.SleepTimerDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 播放与音质。
 *
 * ### 与重构前的差异
 *
 * 1. **删掉了「立即播放」开关。** 它的持久化键 `play_immediately` 全仓库只有本文件与
 *    已删除的「交互逻辑」页读写 —— **播放链路从不读它**。也就是说这个开关做什么都不影响，
 *    而它的副标题还描述了一个（未实现的）「只加入队列」行为。留着它比没有更糟：
 *    用户会以为自己关掉的东西生效了。
 * 2. **睡眠定时从「一个开关 + 一个下拉」收敛成一个入口行。** 那两者操作的是同一份
 *    运行时状态（`sleepAfterTrack` 既是开关的值，又是下拉的一个选项），
 *    而且下拉的「剩余 N 分钟」是算出来的、没法反选回原预设。
 *    现在点开的是**播放页同一个** [SleepTimerDialog] —— 单一事实源，两边状态一致。
 * 3. **音质副标题说清了降级行为**，不再只重复标题。
 */
class PlaybackSettingsScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val s = cpStrings()
        val quality by AppModel.playbackQualityFlow.collectAsState()
        val meteredQuality by AppModel.meteredPlaybackQualityFlow.collectAsState()
        val lyricsMode by AppModel.lyricsSourceModeFlow.collectAsState()
        val keepLastPlayback by AppModel.keepLastPlaybackFlow.collectAsState()
        val playbackState by AppModel.playback.state.collectAsState()
        var showSleepTimer by remember { mutableStateOf(false) }

        val qualityIndex = AppModel.qualityLevels.indexOfFirst { it == quality }.coerceAtLeast(0)
        val meteredQualityIndex =
            AppModel.qualityLevels.indexOfFirst { it == meteredQuality }.coerceAtLeast(0)
        // 移动数据音质只在 Android 有意义：桌面端没有计费网络概念，恒走默认音质。
        val showMeteredQuality = isAndroidPlatform()
        val qualityLabels = AppModel.qualityLevels.map { s.quality.labelOf(it) }

        // 歌词来源三档（对齐旧版 CPPlayer）：key 顺序即下拉顺序
        val lyricsModeOptions = listOf(
            cp.player.core.api.LyricsSourceMode.PROVIDER_ONLY to s.playback.lyricsProviderOnly,
            cp.player.core.api.LyricsSourceMode.AMLL_FIRST to s.playback.lyricsAmllFirst,
            cp.player.core.api.LyricsSourceMode.AMLL_ONLY to s.playback.lyricsAmllOnly,
        )
        val lyricsModeIndex = lyricsModeOptions.indexOfFirst { it.first == lyricsMode }.coerceAtLeast(0)

        val body: @Composable (Modifier) -> Unit = { pageModifier ->
            SettingsPage(pageModifier) {
                SettingsSection(s.playback.sectionQuality) {
                    SettingsDropdownItem(
                        title = s.playback.defaultQuality,
                        subtitle = s.playback.defaultQualityNote,
                        options = qualityLabels,
                        selectedIndex = qualityIndex,
                        onSelect = { index ->
                            AppModel.qualityLevels.getOrNull(index)?.let(AppModel::setPlaybackQuality)
                        },
                        index = 0,
                        total = if (showMeteredQuality) 2 else 1,
                    )
                    if (showMeteredQuality) {
                        SettingsDropdownItem(
                            title = s.playback.meteredQuality,
                            subtitle = s.playback.meteredQualityNote,
                            options = qualityLabels,
                            selectedIndex = meteredQualityIndex,
                            onSelect = { index ->
                                AppModel.qualityLevels.getOrNull(index)
                                    ?.let(AppModel::setMeteredPlaybackQuality)
                            },
                            index = 1,
                            total = 2,
                        )
                    }
                }
                SettingsSection(s.playback.sectionLyrics) {
                    SettingsDropdownItem(
                        title = s.playback.lyricsSource,
                        subtitle = s.playback.lyricsSourceNote,
                        options = lyricsModeOptions.map { it.second },
                        selectedIndex = lyricsModeIndex,
                        onSelect = { index ->
                            lyricsModeOptions.getOrNull(index)?.let { (mode, _) ->
                                AppModel.setLyricsSourceMode(mode)
                            }
                        },
                        index = 0,
                        total = 1,
                    )
                }
                SettingsSection(s.playback.sectionSleepTimer) {
                    SettingsClickItem(
                        title = s.playback.sleepTimer,
                        subtitle = when {
                            playbackState.sleepAfterTrack -> s.playback.sleepAfterTrack
                            playbackState.sleepTimerRemainingMs != null ->
                                s.playback.sleepRemaining(
                                    (playbackState.sleepTimerRemainingMs!! / 60_000L) + 1,
                                )
                            else -> s.playback.sleepOff
                        },
                        icon = Icons.Filled.Bedtime,
                        index = 0,
                        total = 1,
                        onClick = { showSleepTimer = true },
                    )
                }
                SettingsSection(s.playback.sectionLastPlayback) {
                    SettingsSwitchItem(
                        title = s.playback.keepLastPlayback,
                        subtitle = s.playback.keepLastPlaybackNote,
                        checked = keepLastPlayback,
                        onCheckedChange = { AppModel.setKeepLastPlayback(it) },
                        index = 0,
                        total = 1,
                    )
                }
                // 熄屏后台保活的用户侧开关：媒体前台服务（Service 层已做）只解决
                // 「应用自愿降级」，电池优化白名单解决「系统/厂商主动杀」。
                if (isAndroidPlatform()) {
                    SettingsSection(s.playback.sectionBackground) {
                        val scope = rememberCoroutineScope()
                        var batteryIgnored by remember {
                            mutableStateOf(isIgnoringBatteryOptimizations())
                        }
                        SettingsClickItem(
                            title = s.playback.batteryWhitelist,
                            subtitle = if (batteryIgnored) {
                                s.playback.batteryWhitelistOn
                            } else {
                                s.playback.batteryWhitelistOff
                            },
                            icon = Icons.Filled.Lock,
                            index = 0,
                            total = 1,
                            onClick = {
                                batteryIgnored = isIgnoringBatteryOptimizations()
                                requestIgnoreBatteryOptimizations()
                                // 系统授权弹窗没有返回回调：延迟一轮重读状态，
                                // 覆盖用户在弹窗里做出选择后回到页面的时机。
                                scope.launch {
                                    delay(2_000)
                                    batteryIgnored = isIgnoringBatteryOptimizations()
                                }
                            },
                        )
                    }
                    SettingsNote(s.playback.vendorNote)
                }
                SettingsNote(s.playback.sharedTimerNote)
            }
        }

        if (showSleepTimer) {
            SleepTimerDialog(
                activeRemainingMs = playbackState.sleepTimerRemainingMs,
                afterTrackActive = playbackState.sleepAfterTrack,
                onSelect = AppModel.playback::setSleepTimer,
                onCancelTimer = AppModel.playback::cancelSleepTimer,
                onDismiss = { showSleepTimer = false },
            )
        }

        CpRouteScaffold(
            title = s.playback.screenTitle,
            onBack = { navigator.popOrNotify() },
        ) { pageModifier -> body(pageModifier) }
    }
}
