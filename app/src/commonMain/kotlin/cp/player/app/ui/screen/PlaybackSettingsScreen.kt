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

                // 2. 音频输出内核架构
                SettingsSection("音频输出内核架构") {
                    val engineOptions = listOf(
                        "系统标准引擎",
                        "FlickEngine 发烧引擎 (VIP)",
                    )
                    val kernelTotal = if (isFlickEngine) 4 else 2
                    SettingsSegmentedItem(
                        title = "回放内核架构",
                        modifier = Modifier.settingsSearchTarget("playback_audio_engine", targetSettingId),
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
                        total = kernelTotal,
                    )
                    if (isFlickEngine) {
                        SettingsClickItem(
                            title = if (isAndroid) "Oboe 硬件直通与母带解码" else "WASAPI Exclusive 独占直通",
                            modifier = Modifier.settingsSearchTarget("playback_wasapi_exclusive", targetSettingId),
                            subtitle = if (isAndroid) {
                                "绕过 Android AudioFlinger 重采样，直通底层音频 HAL / USB DAC，支持 DSD 软解"
                            } else {
                                "完全绕过 Windows 混音器，独占接管声卡终端，支持 Bit-Perfect 与 DSD 软解"
                            },
                            index = 1,
                            total = kernelTotal,
                            onClick = { navigator.push(AudioEngineSettingsScreen()) },
                            trailingContent = {
                                SettingsBadge(
                                    text = "详细配置",
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            },
                        )
                        SettingsClickItem(
                            title = "发烧特权授权管理",
                            subtitle = if (vipInfo.isActivated) "已激活：${vipInfo.planName} (${vipInfo.licenseKey ?: "永久授权"})" else "未激活，点击输入卡密解锁发烧特性",
                            index = 2,
                            total = kernelTotal,
                            onClick = { showVipDialog = true },
                            trailingContent = {
                                CpText(
                                    text = if (vipInfo.isActivated) "更换卡密" else "激活特权",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                        )
                    }
                    SettingsClickItem(
                        title = "DSP 音效与调音台",
                        subtitle = "内置 10 频段参数均衡器 (EQ)、风格预设、声道平衡与响度均衡调节",
                        icon = Icons.Filled.Equalizer,
                        index = if (isFlickEngine) 3 else 1,
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
