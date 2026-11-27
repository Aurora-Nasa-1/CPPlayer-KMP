package cp.player.app.ui.screen

import androidx.compose.material.icons.Icons
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
import cp.player.app.ui.navigation.RoutePage
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.desktopPlatform
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.platform.isIgnoringBatteryOptimizations
import cp.player.app.platform.requestIgnoreBatteryOptimizations
import cp.player.app.presentation.navigation.AppRoute
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.SettingsBadge
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsDropdownItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.navigation.LocalAppNavigator
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSegmentedItem
import cp.player.app.ui.component.SettingsSliderItem
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.component.settingsSearchTarget
import cp.player.app.ui.component.AutoPlayCustomTargetDialog
import cp.player.app.ui.component.PlaylistPickerSheet
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
) : RoutePage {
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.current
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
        val androidUsbExclusive by AppModel.androidUsbExclusiveFlow.collectAsState()
        val androidUsbAutoResume by AppModel.androidUsbAutoResumeFlow.collectAsState()
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

                // 2. 音频输出内核 / 输出模式
                //
                // ⚠️ 两端的「选择」不是同一件事，文案必须分开写（详见 AudioEngineSettingsScreen 的对照表）：
                // - Android：Media3（默认）↔ FlickEngine（VIP），换的是**内核**；
                // - 桌面：共享（默认）↔ WASAPI 独占（VIP），换的是**同一内核的输出模式**。
                //   这里曾写成「系统标准引擎 / FlickEngine 发烧引擎」—— 桌面端根本没有第二个
                //   引擎，用户以为关掉就能回系统播放器，实际关掉的只是独占。
                SettingsSection(
                    if (isAndroid) s.playback.sectionAudioEngineAndroid else s.playback.sectionAudioOutputDesktop,
                ) {
                    val engineOptions = if (isAndroid) {
                        listOf(s.playback.engineMedia3, s.playback.engineFlick)
                    } else {
                        listOf(s.playback.outputShared, s.playback.outputExclusive)
                    }
                    val selectedKernel = if (isAndroid) {
                        if (audioEngine == PlaybackSessionSettings.ENGINE_FLICK_HIRES) 1 else 0
                    } else {
                        if (exclusiveActive) 1 else 0
                    }
                    val showAndroidUsbExclusive = isAndroid && flickEngineActive
                    val kernelTotal = (if (isAndroid) 4 else 3) + (if (showAndroidUsbExclusive) 1 else 0)
                    var kernelIndex = 0
                    SettingsSegmentedItem(
                        title = if (isAndroid) s.playback.activeKernel else s.playback.wasapiMode,
                        modifier = Modifier.settingsSearchTarget("playback_audio_engine", targetSettingId),
                        subtitle = when {
                            isAndroid && audioEngine == PlaybackSessionSettings.ENGINE_FLICK_HIRES && !vipInfo.isActivated ->
                                s.playback.kernelVipInactive
                            isAndroid && flickEngineActive ->
                                s.playback.kernelFlickActive
                            isAndroid ->
                                s.playback.kernelMedia3
                            exclusiveActive ->
                                s.playback.kernelExclusiveActive
                            else ->
                                s.playback.kernelShared
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
                        index = kernelIndex++,
                        total = kernelTotal,
                    )
                    if (showAndroidUsbExclusive) {
                        SettingsSwitchItem(
                            title = s.engine.androidUsbExclusiveTitle,
                            modifier = Modifier.settingsSearchTarget("playback_android_usb_exclusive", targetSettingId),
                            subtitle = s.engine.androidUsbExclusiveNote,
                            checked = androidUsbExclusive,
                            onCheckedChange = { enabled ->
                                if (enabled && !vipInfo.isActivated) {
                                    afterVipAction = { AppModel.setAndroidUsbExclusive(true) }
                                    showVipDialog = true
                                } else {
                                    AppModel.setAndroidUsbExclusive(enabled)
                                }
                            },
                            index = kernelIndex++,
                            total = kernelTotal,
                        )
                    }
                    if (isAndroid) {
                        SettingsSwitchItem(
                            title = s.engine.androidUsbAutoResumeTitle,
                            modifier = Modifier.settingsSearchTarget("playback_android_usb_auto_resume", targetSettingId),
                            subtitle = s.engine.androidUsbAutoResumeNote,
                            checked = androidUsbAutoResume,
                            onCheckedChange = AppModel::setAndroidUsbAutoResume,
                            index = kernelIndex++,
                            total = kernelTotal,
                        )
                    }
                    SettingsClickItem(
                        title = if (isAndroid) {
                            s.playback.engineDetailAndroid
                        } else {
                            s.playback.engineDetailDesktop
                        },
                        modifier = Modifier.settingsSearchTarget("playback_wasapi_exclusive", targetSettingId),
                        subtitle = if (isAndroid) {
                            s.playback.engineDetailAndroidNote
                        } else if (!isWindowsDesktop) {
                            s.playback.engineDetailDesktopUnsupportedNote
                        } else {
                            s.playback.engineDetailDesktopNote
                        },
                        index = kernelIndex++,
                        total = kernelTotal,
                        onClick = { navigator.push(AppRoute.AudioEngineSettings) },
                        trailingContent = {
                            SettingsBadge(
                                text = s.playback.featureDetails,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        },
                    )
                    SettingsClickItem(
                        title = s.playback.dspSection,
                        // ⚠️ 不要写成「空间环绕」：引擎的 FX 只有「拉宽侧声道」（set_fx 的 width），
                        // 不是真正的环绕声。见 docs/dev/FLICK_ENGINE.md §2 能力矩阵。
                        subtitle = s.playback.dspNote,
                        icon = Icons.Filled.Equalizer,
                        index = kernelIndex++,
                        total = kernelTotal,
                        onClick = { navigator.push(AppRoute.AudioEffectSettings) },
                        trailingContent = {
                            CpText(
                                text = s.playback.mixer,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                }

                // 3. 播放控制与过渡
                SettingsSection(s.playback.sectionFade) {
                    fun commitFade(next: cp.player.core.playback.FadeConfig) {
                        // 单一开关必须两段同时生效：旧版允许只开一半，
                        // 这里顺手规整，避免“开关开着却只淡出”。
                        val half = next.fadeIn != next.fadeOut
                        val both = next.fadeIn || next.fadeOut
                        val normalized = next.copy(
                            fadeIn = if (half) both else next.fadeIn,
                            fadeOut = if (half) both else next.fadeOut,
                            enabled = both,
                        )
                        fadeDraft = normalized
                        AppModel.setFade(normalized)
                    }
                    SettingsSwitchItem(
                        title = s.playback.fadeToggle,
                        modifier = Modifier.settingsSearchTarget("playback_crossfade", targetSettingId),
                        subtitle = s.playback.fadeNote,
                        checked = fadeDraft.enabled,
                        onCheckedChange = { enabled ->
                            commitFade(fadeDraft.copy(fadeIn = enabled, fadeOut = enabled))
                        },
                        index = 0,
                        total = 2,
                    )
                    SettingsSliderItem(
                        title = s.playback.fadeDuration,
                        subtitle = s.playback.fadeDurationNote,
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
                        index = 1,
                        total = 2,
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
                                    text = if (autoPlayCustomId.isNotBlank()) s.playback.edit else s.playback.set,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                        )
                    }
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
            }
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
            onBack = { navigator.pop() },
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
