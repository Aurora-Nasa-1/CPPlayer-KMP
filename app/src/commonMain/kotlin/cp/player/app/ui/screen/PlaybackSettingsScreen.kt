package cp.player.app.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.MaterialTheme
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
import cp.player.app.platform.desktopPlatform
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.platform.isIgnoringBatteryOptimizations
import cp.player.app.platform.requestIgnoreBatteryOptimizations
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.util.popOrNotify
import cp.player.app.ui.component.SettingsBadge
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsDropdownItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSegmentedItem
import cp.player.app.ui.component.SettingsSliderItem
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.component.settingsSearchTarget
import cp.player.app.ui.component.AutoPlayCustomTargetDialog
import cp.player.app.ui.component.PlaylistPickerSheet
import cp.player.app.ui.component.SleepTimerDialog
import cp.player.app.ui.component.VipActivationDialog
import cp.player.core.license.VipLicenseManager
import cp.player.core.playback.AutoPlayCustomType
import cp.player.core.playback.AutoPlayMode
import cp.player.core.playback.PlaybackSessionSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import cp.player.app.ui.component.CpText

/**
 * 播放与音质设置页面。
 */
class PlaybackSettingsScreen(
    private val targetSettingId: String? = null,
) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val s = cpStrings()
        val quality by AppModel.playbackQualityFlow.collectAsState()
        val meteredQuality by AppModel.meteredPlaybackQualityFlow.collectAsState()
        val keepLastPlayback by AppModel.keepLastPlaybackFlow.collectAsState()
        val autoPlayMode by AppModel.autoPlayModeFlow.collectAsState()
        val autoPlayCustomType by AppModel.autoPlayCustomTypeFlow.collectAsState()
        val autoPlayCustomId by AppModel.autoPlayCustomIdFlow.collectAsState()
        val autoPlayCustomTitle by AppModel.autoPlayCustomTitleFlow.collectAsState()
        val preloadNext by AppModel.preloadNextFlow.collectAsState()
        val listenTogetherAddNext by AppModel.listenTogetherAddNextFlow.collectAsState()
        val playbackState by AppModel.playback.state.collectAsState()
        // 淡入淡出配置：拖动滑杆时只改本地 draft，松手才提交（与音效页同一节奏，
        // 因为 SettingsStorage 是全量文件回写，拖一次就写一次盘会很浪费）。
        val fadePersisted by AppModel.fadeFlow.collectAsState()
        var fadeDraft by remember { mutableStateOf(fadePersisted) }
        var showSleepTimer by remember { mutableStateOf(false) }
        var showAutoPlayTargetDialog by remember { mutableStateOf(false) }
        var showPlaylistPicker by remember { mutableStateOf(false) }

        val qualityIndex = AppModel.qualityLevels.indexOfFirst { it == quality }.coerceAtLeast(0)
        val meteredQualityIndex =
            AppModel.qualityLevels.indexOfFirst { it == meteredQuality }.coerceAtLeast(0)
        // 移动数据音质只在 Android 有意义：桌面端没有计费网络概念，恒走默认音质。
        val showMeteredQuality = isAndroidPlatform()
        val qualityLabels = AppModel.qualityLevels.map { s.quality.labelOf(it) }

        val audioEngine by AppModel.audioEngineFlow.collectAsState()
        // ⚠️ 桌面端「发烧」不是一个二选一的引擎，而是一个独占开关（默认关）。
        // 见 `PlaybackSessionSettings.KEY_WASAPI_EXCLUSIVE` 与 [AudioEngineSettingsScreen] 的平台对照表。
        val wasapiExclusive by AppModel.wasapiExclusiveFlow.collectAsState()
        val vipInfo by VipLicenseManager.licenseState.collectAsState()
        var showVipDialog by remember { mutableStateOf(false) }
        // 卡密框可能从多个入口打开（换内核 / 开独占 / 只想看看状态），
        // 激活成功后要接着做的是**用户刚才被打断的那件事**，而不是无脑切引擎。
        var afterVipAction by remember { mutableStateOf<(() -> Unit)?>(null) }

        if (showVipDialog) {
            VipActivationDialog(
                onDismiss = { showVipDialog = false },
                onSuccess = {
                    afterVipAction?.invoke()
                    afterVipAction = null
                },
            )
        }

        val isAndroid = isAndroidPlatform()
        val isWindowsDesktop = !isAndroid && desktopPlatform() == "windows"
        // 独占是否真的在生效：平台支持 + VIP 有效 + 开关打开
        val exclusiveActive = isWindowsDesktop && vipInfo.isActivated && wasapiExclusive
        // 发烧内核是否真的在生效（Android 才有「内核」概念）
        val flickEngineActive = if (isAndroid) {
            vipInfo.isActivated && audioEngine == PlaybackSessionSettings.ENGINE_FLICK_HIRES
        } else {
            true
        }

        val body: @Composable (Modifier) -> Unit = { pageModifier ->
            SettingsPage(pageModifier) {
                // 1. 流媒体与音频音质
                SettingsSection(s.playback.sectionQuality) {
                    val qualityTotal = (if (showMeteredQuality) 1 else 0) + 2
                    SettingsDropdownItem(
                        title = s.playback.defaultQuality,
                        modifier = Modifier.settingsSearchTarget("playback_quality", targetSettingId),
                        subtitle = s.playback.defaultQualityNote,
                        options = qualityLabels,
                        selectedIndex = qualityIndex,
                        onSelect = { index ->
                            AppModel.qualityLevels.getOrNull(index)?.let(AppModel::setPlaybackQuality)
                        },
                        index = 0,
                        total = qualityTotal,
                    )
                    if (showMeteredQuality) {
                        SettingsDropdownItem(
                            title = s.playback.meteredQuality,
                            modifier = Modifier.settingsSearchTarget("playback_metered_quality", targetSettingId),
                            subtitle = s.playback.meteredQualityNote,
                            options = qualityLabels,
                            selectedIndex = meteredQualityIndex,
                            onSelect = { index ->
                                AppModel.qualityLevels.getOrNull(index)
                                    ?.let(AppModel::setMeteredPlaybackQuality)
                            },
                            index = 1,
                            total = qualityTotal,
                        )
                    }
                    SettingsSwitchItem(
                        title = s.playback.preloadNext,
                        modifier = Modifier.settingsSearchTarget("playback_preload", targetSettingId),
                        subtitle = s.playback.preloadNextNote,
                        checked = preloadNext,
                        onCheckedChange = { AppModel.setPreloadNext(it) },
                        index = if (showMeteredQuality) 2 else 1,
                        total = qualityTotal,
                    )
                }

                // 1.5 播放速度（ExoPlayer 式能力位：不支持的引擎上整块隐藏）
                //
                // 能力位来自平台播放器而不是写死"桌面才显示"：将来 Android 补齐
                // Media3 的 PlaybackParameters，只要那边把 supportsPlaybackSpeed 打开，
                // 这个控件就会自动出现，不需要再改设置页。
                if (AppModel.playback.supportsPlaybackSpeed) {
                    SettingsSection("播放速度") {
                        val speedValues = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                        val speedLabels = speedValues.map { "${it}×" }
                        val selectedSpeed = speedValues
                            .indexOfFirst { kotlin.math.abs(it - playbackState.playbackSpeed) < 0.01f }
                            .coerceAtLeast(0)
                        SettingsDropdownItem(
                            title = "播放速度",
                            modifier = Modifier.settingsSearchTarget("playback_speed", targetSettingId),
                            subtitle = "由音频引擎变速，不改变音调",
                            options = speedLabels,
                            selectedIndex = selectedSpeed,
                            onSelect = { index ->
                                speedValues.getOrNull(index)?.let(AppModel.playback::setPlaybackSpeed)
                            },
                            index = 0,
                            total = 1,
                        )
                    }
                }

                // 2. 音频输出内核 / 输出模式
                //
                // ⚠️ 两端的「选择」不是同一件事，文案必须分开写（详见 AudioEngineSettingsScreen 的对照表）：
                // - Android：Media3（默认）↔ FlickEngine（VIP），换的是**内核**；
                // - 桌面：共享（默认）↔ WASAPI 独占（VIP），换的是**同一内核的输出模式**。
                //   这里曾写成「系统标准引擎 / FlickEngine 发烧引擎」—— 桌面端根本没有第二个
                //   引擎，用户以为关掉就能回系统播放器，实际关掉的只是独占。
                SettingsSection(if (isAndroid) "播放内核" else "音频输出模式") {
                    val engineOptions = if (isAndroid) {
                        listOf("系统 Media3（默认）", "FlickEngine（VIP）")
                    } else {
                        listOf("共享（默认）", "独占（VIP）")
                    }
                    val selectedKernel = if (isAndroid) {
                        if (audioEngine == PlaybackSessionSettings.ENGINE_FLICK_HIRES) 1 else 0
                    } else {
                        if (exclusiveActive) 1 else 0
                    }
                    val kernelTotal = 4
                    SettingsSegmentedItem(
                        title = if (isAndroid) "当前生效内核" else "WASAPI 输出模式",
                        modifier = Modifier.settingsSearchTarget("playback_audio_engine", targetSettingId),
                        subtitle = when {
                            isAndroid && audioEngine == PlaybackSessionSettings.ENGINE_FLICK_HIRES && !vipInfo.isActivated ->
                                "卡密未激活：已自动回落到系统内核"
                            isAndroid && flickEngineActive ->
                                "FlickEngine 低延迟直通 · 下次起播生效"
                            isAndroid ->
                                "系统 Media3：兼容与耗电更稳，发烧特性不可用"
                            exclusiveActive ->
                                "已接管声卡：播放期间其它应用静音"
                            else ->
                                "共享声卡：可与其它应用同时出声"
                        },
                        options = engineOptions,
                        selectedIndex = selectedKernel,
                        onSelect = { index ->
                            if (isAndroid) {
                                if (index == 1) {
                                    if (!vipInfo.isActivated) {
                                        afterVipAction = {
                                            AppModel.setAudioEngine(PlaybackSessionSettings.ENGINE_FLICK_HIRES)
                                        }
                                        showVipDialog = true
                                    } else {
                                        AppModel.setAudioEngine(PlaybackSessionSettings.ENGINE_FLICK_HIRES)
                                    }
                                } else {
                                    AppModel.setAudioEngine(PlaybackSessionSettings.ENGINE_STANDARD)
                                }
                            } else {
                                if (index == 1) {
                                    if (!vipInfo.isActivated) {
                                        afterVipAction = { AppModel.setWasapiExclusive(true) }
                                        showVipDialog = true
                                    } else {
                                        AppModel.setWasapiExclusive(true)
                                    }
                                } else {
                                    AppModel.setWasapiExclusive(false)
                                }
                            }
                        },
                        index = 0,
                        total = kernelTotal,
                    )
                    SettingsClickItem(
                        title = if (isAndroid) "Oboe 直通、USB DAC 与 DSD" else "独占、Bit-Perfect 与 DSD",
                        modifier = Modifier.settingsSearchTarget("playback_wasapi_exclusive", targetSettingId),
                        subtitle = if (isAndroid) {
                            "Oboe 低延迟直通 · UAC 2.0 USB DAC 源码输出 · DSD64/128 软解"
                        } else if (!isWindowsDesktop) {
                            "当前平台没有 WASAPI 独占通道，仅提供共享输出"
                        } else {
                            "WASAPI 独占 · Bit-Perfect 采样率自适应 · DSD64/128 软解"
                        },
                        index = 1,
                        total = kernelTotal,
                        onClick = { navigator.push(AudioEngineSettingsScreen()) },
                        trailingContent = {
                            SettingsBadge(
                                text = "特性说明",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        },
                    )
                    SettingsClickItem(
                        title = "发烧特权授权管理",
                        subtitle = if (vipInfo.isActivated) {
                            // 没有卡密时不补括号（否则会拼出「… (永久授权) (永久授权)」）
                            val key = vipInfo.licenseKey?.takeIf { it.isNotBlank() }
                            if (key != null) "已激活：${vipInfo.planName} ($key)" else "已激活：${vipInfo.planName}"
                        } else {
                            "未激活，点击输入卡密解锁 FlickEngine 与独占直通"
                        },
                        index = 2,
                        total = kernelTotal,
                        onClick = {
                            afterVipAction = null
                            showVipDialog = true
                        },
                        trailingContent = {
                            CpText(
                                text = if (vipInfo.isActivated) "更换卡密" else "激活特权",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                    SettingsClickItem(
                        title = "DSP 音效与调音台",
                        // ⚠️ 不要写成「空间环绕」：引擎的 FX 只有「拉宽侧声道」（set_fx 的 width），
                        // 不是真正的环绕声。见 docs/dev/FLICK_ENGINE.md §2 能力矩阵。
                        subtitle = "5 段参数均衡器 (PEQ)、风格预设、声道平衡与立体声宽度",
                        icon = Icons.Filled.Equalizer,
                        index = 3,
                        total = kernelTotal,
                        onClick = { navigator.push(AudioEffectSettingsScreen()) },
                        trailingContent = {
                            CpText(
                                text = "调音台",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                }

                // 3. 播放控制与过渡
                SettingsSection(s.playback.sectionFade) {
                    fun commitFade(next: cp.player.core.playback.FadeConfig) {
                        val normalized = next.copy(
                            enabled = next.fadeIn || next.fadeOut,
                        )
                        fadeDraft = normalized
                        AppModel.setFade(normalized)
                    }
                    SettingsSwitchItem(
                        title = s.playback.fadeIn,
                        modifier = Modifier.settingsSearchTarget("playback_crossfade", targetSettingId),
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
                        onValueChange = { fadeDraft = fadeDraft.copy(durationMs = it.toInt()) },
                        onValueChangeFinished = { commitFade(fadeDraft) },
                        index = 2,
                        total = 3,
                    )
                }

                SettingsSection(s.playback.sectionLastPlayback) {
                    SettingsSwitchItem(
                        title = s.playback.keepLastPlayback,
                        modifier = Modifier.settingsSearchTarget("playback_keep_progress", targetSettingId),
                        subtitle = s.playback.keepLastPlaybackNote,
                        checked = keepLastPlayback,
                        onCheckedChange = { checked ->
                            AppModel.setKeepLastPlayback(checked)
                            if (!checked && autoPlayMode == AutoPlayMode.CONTINUE) {
                                AppModel.setAutoPlayMode(AutoPlayMode.OFF)
                            }
                        },
                        index = 0,
                        total = 1,
                    )
                }

                // 4. 定时与自动化
                SettingsSection(s.playback.sectionSleepTimer) {
                    SettingsClickItem(
                        title = s.playback.sleepTimer,
                        modifier = Modifier.settingsSearchTarget("playback_sleep_timer", targetSettingId),
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

                val autoPlayOptions = listOf(
                    s.playback.autoPlayModeOff,
                    s.playback.autoPlayModeContinue,
                    s.playback.autoPlayModeDaily,
                    s.playback.autoPlayModeCustom,
                )
                val autoPlaySelectedIndex = when (autoPlayMode) {
                    AutoPlayMode.OFF -> 0
                    AutoPlayMode.CONTINUE -> 1
                    AutoPlayMode.DAILY -> 2
                    AutoPlayMode.CUSTOM -> 3
                }
                SettingsSection(s.playback.sectionAutoPlay) {
                    val totalItems = if (autoPlayMode == AutoPlayMode.CUSTOM) 2 else 1
                    SettingsDropdownItem(
                        title = s.playback.autoPlayMode,
                        modifier = Modifier.settingsSearchTarget("playback_auto_play", targetSettingId),
                        subtitle = s.playback.autoPlayModeNote,
                        options = autoPlayOptions,
                        selectedIndex = autoPlaySelectedIndex,
                        onSelect = { index ->
                            val mode = when (index) {
                                1 -> AutoPlayMode.CONTINUE
                                2 -> AutoPlayMode.DAILY
                                3 -> AutoPlayMode.CUSTOM
                                else -> AutoPlayMode.OFF
                            }
                            AppModel.setAutoPlayMode(mode)
                        },
                        index = 0,
                        total = totalItems,
                    )
                    if (autoPlayMode == AutoPlayMode.CUSTOM) {
                        val targetSubtitle = if (autoPlayCustomId.isNotBlank()) {
                            val typeLabel = if (autoPlayCustomType == AutoPlayCustomType.PLAYLIST) {
                                s.playback.autoPlayTypePlaylist
                            } else {
                                s.playback.autoPlayTypeTrack
                            }
                            val titleLabel = autoPlayCustomTitle.ifBlank { autoPlayCustomId }
                            "$typeLabel: $titleLabel"
                        } else {
                            s.playback.autoPlayCustomTargetEmpty
                        }
                        SettingsClickItem(
                            title = s.playback.autoPlayCustomTarget,
                            subtitle = targetSubtitle,
                            index = 1,
                            total = totalItems,
                            onClick = { showAutoPlayTargetDialog = true },
                            trailingContent = {
                                CpText(
                                    text = if (autoPlayCustomId.isNotBlank()) "修改" else "设置",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                        )
                    }
                }

                // 5. 歌词呈现与解析
                SettingsSection(s.playback.sectionLyrics) {
                    val showMiniPlayerLyrics by AppModel.showMiniPlayerLyricsFlow.collectAsState()
                    val showRuby by AppModel.showRubyLyricsFlow.collectAsState()
                    val wordSeg by AppModel.lyricsWordSegmentationFlow.collectAsState()
                    val smartKaraoke by AppModel.smartKaraokeLrcFlow.collectAsState()
                    SettingsSwitchItem(
                        title = s.playback.showMiniPlayerLyrics,
                        subtitle = s.playback.showMiniPlayerLyricsSubtitle,
                        checked = showMiniPlayerLyrics,
                        onCheckedChange = { AppModel.setShowMiniPlayerLyrics(it) },
                        index = 0,
                        total = 5,
                    )
                    SettingsSwitchItem(
                        title = s.playback.showRubyLyrics,
                        subtitle = s.playback.showRubyLyricsSubtitle,
                        checked = showRuby,
                        onCheckedChange = { AppModel.setShowRubyLyrics(it) },
                        index = 1,
                        total = 5,
                    )
                    SettingsSwitchItem(
                        title = s.playback.lyricsWordSegmentation,
                        subtitle = s.playback.lyricsWordSegmentationSubtitle,
                        checked = wordSeg,
                        onCheckedChange = { AppModel.setLyricsWordSegmentation(it) },
                        index = 2,
                        total = 5,
                    )
                    SettingsSwitchItem(
                        title = s.playback.smartKaraokeLrc,
                        subtitle = s.playback.smartKaraokeLrcSubtitle,
                        checked = smartKaraoke,
                        onCheckedChange = { AppModel.setSmartKaraokeLrc(it) },
                        index = 3,
                        total = 5,
                    )
                    SettingsClickItem(
                        title = s.playback.lyricsSource,
                        subtitle = s.playback.lyricsSourceNote,
                        icon = Icons.Filled.ClosedCaption,
                        index = 4,
                        total = 5,
                        onClick = { navigator.push(LyricsPluginSettingsScreen()) },
                    )
                }

                // 6. 社交互动与系统协作
                SettingsSection(s.player.listenTogether) {
                    SettingsSwitchItem(
                        title = s.social.together.addNextOnPlay,
                        modifier = Modifier.settingsSearchTarget("playback_listen_together_add_next", targetSettingId),
                        subtitle = s.social.together.addNextOnPlayNote,
                        checked = listenTogetherAddNext,
                        onCheckedChange = { AppModel.setListenTogetherAddNext(it) },
                        index = 0,
                        total = 1,
                    )
                }

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

        if (showAutoPlayTargetDialog) {
            AutoPlayCustomTargetDialog(
                currentType = autoPlayCustomType,
                currentId = autoPlayCustomId,
                currentTitle = autoPlayCustomTitle,
                playbackState = playbackState,
                onDismiss = { showAutoPlayTargetDialog = false },
                onPickFromPlaylists = {
                    showAutoPlayTargetDialog = false
                    showPlaylistPicker = true
                },
                onSetTarget = { type, id, title ->
                    AppModel.setAutoPlayCustomTarget(type, id, title)
                    showAutoPlayTargetDialog = false
                },
                onClearTarget = {
                    AppModel.clearAutoPlayCustomTarget()
                    showAutoPlayTargetDialog = false
                },
            )
        }

        if (showPlaylistPicker) {
            PlaylistPickerSheet(
                title = s.playback.autoPlayPickFromPlaylists,
                onDismiss = { showPlaylistPicker = false },
                onSelected = { playlist ->
                    AppModel.setAutoPlayCustomTarget(
                        type = AutoPlayCustomType.PLAYLIST,
                        id = playlist.resourceId.ifBlank { playlist.id.toString() },
                        title = playlist.name,
                    )
                    showPlaylistPicker = false
                },
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
