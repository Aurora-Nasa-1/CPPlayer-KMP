package cp.player.app.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import cp.player.app.ui.component.SettingsBadge
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsDropdownItem
import cp.player.app.ui.component.SettingsHeroCard
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsNoteEmphasis
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSegmentedItem
import cp.player.app.ui.component.SettingsSliderItem
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.component.SleepTimerDialog
import cp.player.app.ui.component.VipActivationDialog
import cp.player.core.license.VipLicenseManager
import cp.player.core.playback.PlaybackSessionSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 播放与音质设置页面。
 */
class PlaybackSettingsScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val s = cpStrings()
        val quality by AppModel.playbackQualityFlow.collectAsState()
        val meteredQuality by AppModel.meteredPlaybackQualityFlow.collectAsState()
        val keepLastPlayback by AppModel.keepLastPlaybackFlow.collectAsState()
        val playbackState by AppModel.playback.state.collectAsState()
        // 淡入淡出配置：拖动滑杆时只改本地 draft，松手才提交（与音效页同一节奏，
        // 因为 SettingsStorage 是全量文件回写，拖一次就写一次盘会很浪费）。
        val fadePersisted by AppModel.fadeFlow.collectAsState()
        var fadeDraft by remember { mutableStateOf(fadePersisted) }
        var showSleepTimer by remember { mutableStateOf(false) }

        val qualityIndex = AppModel.qualityLevels.indexOfFirst { it == quality }.coerceAtLeast(0)
        val meteredQualityIndex =
            AppModel.qualityLevels.indexOfFirst { it == meteredQuality }.coerceAtLeast(0)
        // 移动数据音质只在 Android 有意义：桌面端没有计费网络概念，恒走默认音质。
        val showMeteredQuality = isAndroidPlatform()
        val qualityLabels = AppModel.qualityLevels.map { s.quality.labelOf(it) }

        val audioEngine by AppModel.audioEngineFlow.collectAsState()
        val isFlickEngine = audioEngine == PlaybackSessionSettings.ENGINE_FLICK_HIRES
        val vipInfo by VipLicenseManager.licenseState.collectAsState()
        var showVipDialog by remember { mutableStateOf(false) }

        if (showVipDialog) {
            VipActivationDialog(
                onDismiss = { showVipDialog = false },
                onSuccess = {
                    AppModel.setAudioEngine(PlaybackSessionSettings.ENGINE_FLICK_HIRES)
                },
            )
        }

        val isAndroid = isAndroidPlatform()

        val body: @Composable (Modifier) -> Unit = { pageModifier ->
            SettingsPage(pageModifier) {
                // M3 Expressive 音频架构与输出模式卡片
                val heroSubtitle = if (isFlickEngine) {
                    if (isAndroid) {
                        "Oboe 低延迟硬件直通 · USB DAC 源码输出 · DSD 软解已就绪"
                    } else {
                        "WASAPI Exclusive 硬件独占直通 · Bit-Perfect 源码输出 (绕过 Windows 混音器)"
                    }
                } else {
                    if (isAndroid) {
                        "AndroidX Media3 (ExoPlayer) 标准混音通道"
                    } else {
                        "系统混音共享输出 (轻量兼容，受 Windows 混音器影响)"
                    }
                }
                SettingsHeroCard(
                    title = if (isFlickEngine) "FlickEngine 发烧架构" else "系统标准音频架构",
                    subtitle = heroSubtitle,
                    icon = Icons.Filled.Memory,
                    badgeText = if (isFlickEngine) {
                        if (vipInfo.isActivated) "Hi-Res VIP · 源码独占" else "VIP 待激活"
                    } else {
                        "标准共享通道"
                    },
                    badgeContainerColor = if (isFlickEngine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    badgeContentColor = if (isFlickEngine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SettingsSection("音频输出内核架构") {
                    val engineOptions = listOf(
                        "系统标准引擎",
                        "FlickEngine 发烧引擎 (VIP)",
                    )
                    SettingsSegmentedItem(
                        title = "回放内核架构",
                        subtitle = if (isFlickEngine) "已启用发烧内核，绕过系统混音器" else "使用系统默认通道，轻量兼容",
                        options = engineOptions,
                        selectedIndex = if (isFlickEngine) 1 else 0,
                        onSelect = { index ->
                            if (index == 1) {
                                if (!vipInfo.isActivated) {
                                    showVipDialog = true
                                } else {
                                    AppModel.setAudioEngine(PlaybackSessionSettings.ENGINE_FLICK_HIRES)
                                }
                            } else {
                                AppModel.setAudioEngine(PlaybackSessionSettings.ENGINE_STANDARD)
                            }
                        },
                        index = 0,
                        total = 1,
                    )
                }

                if (isFlickEngine) {
                    SettingsSection("硬件直通与发烧特性") {
                        SettingsClickItem(
                            title = if (isAndroid) "Oboe / AAudio 直通" else "WASAPI Exclusive 独占直通",
                            subtitle = if (isAndroid) {
                                "绕过 Android AudioFlinger 重采样，直通底层音频 HAL / USB DAC"
                            } else {
                                "完全绕过 Windows 系统混音器与音频增强，由播放器直接接管声卡终端"
                            },
                            index = 0,
                            total = 4,
                            onClick = {
                                navigator.push(AudioEngineSettingsScreen())
                            },
                            trailingContent = {
                                SettingsBadge(
                                    text = "已启用",
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            },
                        )
                        SettingsClickItem(
                            title = "Bit-Perfect 位完美源码输出",
                            subtitle = "声卡硬件采样率根据音轨物理规格自动重协商（44.1k/48k/96k/192kHz 自适应）",
                            index = 1,
                            total = 4,
                            onClick = {},
                            trailingContent = {
                                SettingsBadge(
                                    text = "自动自适应",
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            },
                        )
                        SettingsClickItem(
                            title = "DSD 母带级原生软解",
                            subtitle = "支持 DSF / DFF 容器，支持 DSD64 / DSD128 高规格直接解码播放",
                            index = 2,
                            total = 4,
                            onClick = {},
                            trailingContent = {
                                SettingsBadge(
                                    text = "内置支持",
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            },
                        )
                        SettingsClickItem(
                            title = "特权授权管理",
                            subtitle = if (vipInfo.isActivated) "已激活：${vipInfo.planName} (${vipInfo.licenseKey ?: "永久授权"})" else "未激活，点击输入卡密解锁",
                            index = 3,
                            total = 4,
                            onClick = { showVipDialog = true },
                            trailingContent = {
                                Text(
                                    text = if (vipInfo.isActivated) "更换卡密" else "激活特权",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                        )
                    }
                    SettingsNote(
                        if (isAndroid) "在 USB DAC 直通模式下，音频流直接送至外接解码耳放，实现全链路原生 Hi-Res 还原。"
                        else "在 WASAPI Exclusive 独占模式下，声卡将独占输出通道，播放期间其他应用程序的声音将被静音，以确保 0 混音劣化与极致信噪比。",
                        emphasis = SettingsNoteEmphasis.INFO,
                    )
                }

                SettingsSection("DSP 音效与调音台") {
                    SettingsClickItem(
                        title = "64位浮点 DSP 音效管线",
                        subtitle = "内置 5段参数均衡器 (PEQ)、声道混音平衡与响度均衡调节",
                        icon = Icons.Filled.Equalizer,
                        index = 0,
                        total = 1,
                        onClick = {
                            navigator.push(AudioEffectSettingsScreen())
                        },
                        trailingContent = {
                            Text(
                                text = "调音台",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                }

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
                SettingsSection(s.playback.sectionFade) {
                    // 提交助手：先归一化 `enabled`（它等于"至少有一个子开关开着"），
                    // 再落盘 + 下发。没有 UI 总开关 —— 总开关与子开关是同一状态的两个
                    // 入口，必然出现「总开关开着但子项全关」的白挂状态、
                    // 以及两处不同步（与音效页同一套判断）。
                    fun commitFade(next: cp.player.core.playback.FadeConfig) {
                        val normalized = next.copy(
                            enabled = next.fadeIn || next.fadeOut,
                        )
                        fadeDraft = normalized
                        AppModel.setFade(normalized)
                    }
                    SettingsSwitchItem(
                        title = s.playback.fadeIn,
                        subtitle = s.playback.fadeInNote,
                        checked = fadeDraft.fadeIn,
                        onCheckedChange = { commitFade(fadeDraft.copy(fadeIn = it)) },
                        index = 0,
                        total = 3,
                    )
                    SettingsSwitchItem(
                        title = s.playback.fadeOut,
                        subtitle = s.playback.fadeOutNote,
                        checked = fadeDraft.fadeOut,
                        onCheckedChange = { commitFade(fadeDraft.copy(fadeOut = it)) },
                        index = 1,
                        total = 3,
                    )
                    // 时长滑杆：两个子开关都关着时禁用（没有过渡发生，调时长无意义）。
                    SettingsSliderItem(
                        title = s.playback.fadeDuration,
                        value = fadeDraft.durationMs.toFloat(),
                        valueLabel = fadeDurationLabel(s, fadeDraft.durationMs),
                        valueRange = cp.player.core.playback.FadeConfig.MIN_DURATION_MS.toFloat()..
                            cp.player.core.playback.FadeConfig.MAX_DURATION_MS.toFloat(),
                        steps = (
                            (cp.player.core.playback.FadeConfig.MAX_DURATION_MS -
                                cp.player.core.playback.FadeConfig.MIN_DURATION_MS) /
                                cp.player.core.playback.FadeConfig.DURATION_STEP_MS
                            ) - 1,
                        enabled = fadeDraft.enabled,
                        // 拖动只改显示，松手才提交（SettingsStorage 是全量文件回写）。
                        onValueChange = { fadeDraft = fadeDraft.copy(durationMs = it.toInt()) },
                        onValueChangeFinished = { commitFade(fadeDraft) },
                        index = 2,
                        total = 3,
                    )
                }
                SettingsSection(s.playback.sectionLyrics) {
                    // 三档下拉已废弃：来源顺序改由「歌词来源」页的**有序列表**表达
                    // （内置三源 + 插件可混排）。这里保留一行跳转而不是删掉整个分段 ——
                    // 「播放与音质」是用户找歌词设置的主入口，直接删会让人以为功能没了。
                    SettingsClickItem(
                        title = s.playback.lyricsSource,
                        subtitle = s.playback.lyricsSourceNote,
                        icon = Icons.Filled.ClosedCaption,
                        index = 0,
                        total = 1,
                        onClick = { navigator.push(LyricsPluginSettingsScreen()) },
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

/**
 * 时长标签：把毫秒转成「3.5 秒」这种一位小数的秒数。
 *
 * 抽出来是为了让单位与数字分开拼（中英语序不同，整句必须由文案层给），
 * 而且 3500ms 要显示成 `3.5` 而不是 `3`（滑杆步进是 500ms，
 * 整除截断会让相邻两档显示成同一个数字，用户以为滑杆坏了）。
 */
private fun fadeDurationLabel(
    s: cp.player.app.i18n.CpStrings,
    ms: Int,
): String {
    val tenths = (ms / 100f).toInt()
    val text = if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
    return s.playback.fadeDurationSeconds(text)
}
