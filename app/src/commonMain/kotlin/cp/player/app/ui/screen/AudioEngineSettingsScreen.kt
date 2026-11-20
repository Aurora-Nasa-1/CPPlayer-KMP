package cp.player.app.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.desktopPlatform
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.platform.openUrl
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.SettingsBadge
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsHeroCard
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsNoteEmphasis
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSegmentedItem
import cp.player.app.ui.component.VipActivationDialog
import cp.player.app.ui.theme.cpNestedSurface
import cp.player.core.license.VipLicenseManager
import cp.player.core.playback.PlaybackSessionSettings
import cafe.adriel.voyager.core.screen.Screen
import cp.player.app.presentation.navigation.AppRoute
import cp.player.app.ui.navigation.LocalAppNavigator

/**
 * 「音频输出与发烧特性」设置页。
 *
 * ## 这一页要回答的唯一问题：我现在的音频链路到底走的是谁
 *
 * 三个平台/后端的**真实**能力并不一样，旧版文案把它们混成了一套说法（页面上写着
 * 「系统标准引擎 / FlickEngine 二选一」，而桌面端根本没有第二个引擎），用户按字面
 * 理解必然误解。现在按平台分开陈述，且**只写代码里真的存在的能力**：
 *
 * | | Windows 桌面 | 其它桌面 (Linux/macOS) | Android |
 * | --- | --- | --- | --- |
 * | 内核 | FlickEngine（**唯一**） | FlickEngine（唯一） | Media3 ExoPlayer / FlickEngine |
 * | 独占直通 | WASAPI Exclusive（VIP，**默认关**） | 不存在 | Oboe / USB DAC（FlickEngine 自带） |
 * | Bit-Perfect | 仅独占模式 | 否（经系统混音） | FlickEngine 直通时不重采样 |
 * | DSD 软解 | 引擎内置（两模式均可用） | 引擎内置 | 引擎内置（FlickEngine） |
 * | DSP | 引擎内 5 段 PEQ + 声道平衡/宽度 | 同左 | 引擎内 PEQ（FlickEngine）/ 系统音效链（Media3） |
 *
 * ⚠️ 两个「不要」：
 * 1. 不要把「独占」写成 FlickEngine 的默认属性。独占会**让其它应用全部静音**，
 *    是必须由用户自己打开的选择（`PlaybackSessionSettings.KEY_WASAPI_EXCLUSIVE`，默认 false）；
 * 2. 不要在 Android 上提 WASAPI，也不要在桌面上提「系统引擎」。
 *
 * ## 为什么把「取状态」与「画界面」拆开
 * 平台差异是这一页的**全部难点**，而平台在真机上不可切换 —— 只有把渲染输入收敛成
 * [AudioEngineUiState]，才能在离屏测试里把 Windows / 其它桌面 / Android 三种形态
 * 各出一张图核对（见 `AudioEngineSettingsVisualTest`）。改动版式请顺手重跑那个测试。
 */
class AudioEngineSettingsScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.current
        val isAndroid = isAndroidPlatform()
        val isWindowsDesktop = !isAndroid && desktopPlatform() == "windows"

        val vipInfo by VipLicenseManager.licenseState.collectAsState()
        val engineKey by AppModel.audioEngineFlow.collectAsState()
        // ⚠️ 桌面端不再用 KEY_AUDIO_ENGINE 决定内核（只有 FlickEngine），独占才是那个选择。
        val exclusivePref by AppModel.wasapiExclusiveFlow.collectAsState()

        var showVipDialog by remember { mutableStateOf(false) }
        // 卡密框可能从「换内核 / 开独占 / 只想看看状态」三处打开，激活成功后要接着做的是
        // **用户刚才被打断的那件事**。
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

        AudioEngineSettingsContent(
            state = AudioEngineUiState(
                isAndroid = isAndroid,
                isWindowsDesktop = isWindowsDesktop,
                vipActivated = vipInfo.isActivated,
                vipPlanName = vipInfo.planName,
                vipLicenseKey = vipInfo.licenseKey,
                engineKey = engineKey,
                exclusivePref = exclusivePref,
            ),
            onSelectEngine = { index ->
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
            },
            onSelectExclusive = { index ->
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
            },
            onOpenVipDialog = {
                afterVipAction = null
                showVipDialog = true
            },
            onOpenAudioEffect = { navigator.push(AppRoute.AudioEffectSettings) },
            onOpenFlickHome = { openUrl("https://github.com/moss-apps/Flick") },
            onBack = { navigator.pop() },
        )
    }
}

/**
 * 页面渲染所需的**全部**输入：平台 + 授权 + 两个开关。
 *
 * 不可变快照，不含 `AppModel` / CompositionLocal 依赖 —— 这样离屏测试能直接构造
 * 「Windows 且已激活独占」「Android 未激活」这类真机上很难复现的组合。
 */
internal data class AudioEngineUiState(
    val isAndroid: Boolean,
    val isWindowsDesktop: Boolean,
    val vipActivated: Boolean,
    val vipPlanName: String = "",
    val vipLicenseKey: String? = null,
    val engineKey: String = PlaybackSessionSettings.ENGINE_STANDARD,
    val exclusivePref: Boolean = false,
) {
    /** 发烧内核是否**真的**在跑（Android 才存在「换内核」这件事）。 */
    val flickEngineActive: Boolean
        get() = if (isAndroid) {
            vipActivated && engineKey == PlaybackSessionSettings.ENGINE_FLICK_HIRES
        } else {
            // 桌面只有这一个内核；原生库缺失时是无输出，不在这里假装「未启用」。
            true
        }

    /** 独占是否**真的**生效：平台支持 + VIP 有效 + 用户显式打开，三者缺一不可。 */
    val exclusiveActive: Boolean
        get() = isWindowsDesktop && vipActivated && exclusivePref
}

/**
 * 页面本体（无状态）。调用方负责取状态与执行副作用。
 *
 * 拆出来是为了离屏出图，理由见 [AudioEngineSettingsScreen] 的 KDoc。
 */
@Composable
internal fun AudioEngineSettingsContent(
    state: AudioEngineUiState,
    onSelectEngine: (Int) -> Unit,
    onSelectExclusive: (Int) -> Unit,
    onOpenVipDialog: () -> Unit,
    onOpenAudioEffect: () -> Unit,
    onOpenFlickHome: () -> Unit,
    onBack: () -> Unit,
) {
    val isAndroid = state.isAndroid
    val s = cpStrings()
    val engine = s.engine

    val body: @Composable (Modifier) -> Unit = { pageModifier ->
        SettingsPage(pageModifier) {
            SettingsHeroCard(
                title = if (!isAndroid || state.flickEngineActive) "FlickEngine" else "Media3 ExoPlayer",
                subtitle = when {
                    !isAndroid && state.exclusiveActive -> engine.heroExclusive
                    // ⚠️ 「WASAPI」只属于 Windows。其它桌面（Linux/macOS）上写它，
                    // 用户会照着去系统里找一个并不存在的设置（离屏出图时抓到的第一处）。
                    !isAndroid && state.isWindowsDesktop -> engine.heroSharedWasapi
                    !isAndroid -> engine.heroSharedSystem
                    state.flickEngineActive -> engine.heroAndroidFlick
                    else -> engine.heroAndroidStandard
                },
                icon = Icons.Filled.GraphicEq,
                badgeText = when {
                    !isAndroid && state.exclusiveActive -> engine.badgeExclusive
                    !isAndroid -> engine.badgeSharedDefault
                    state.flickEngineActive -> engine.badgeVip
                    else -> engine.badgeDefault
                },
                badgeContainerColor = if ((!isAndroid && state.exclusiveActive) || (isAndroid && state.flickEngineActive)) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                badgeContentColor = if ((!isAndroid && state.exclusiveActive) || (isAndroid && state.flickEngineActive)) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            )

            // ---------------- 内核 / 输出模式 ----------------
            if (isAndroid) {
                SettingsSection(engine.sectionKernel) {
                    SettingsSegmentedItem(
                        title = engine.kernelCurrent,
                        subtitle = when {
                            state.engineKey == PlaybackSessionSettings.ENGINE_FLICK_HIRES && !state.vipActivated ->
                                engine.kernelNeedsVip
                            state.engineKey == PlaybackSessionSettings.ENGINE_FLICK_HIRES ->
                                engine.kernelEnabled
                            else -> engine.kernelSystemNote
                        },
                        options = listOf(engine.kernelSystemOption, engine.kernelFlickOption),
                        selectedIndex = if (state.engineKey == PlaybackSessionSettings.ENGINE_FLICK_HIRES) 1 else 0,
                        onSelect = onSelectEngine,
                        index = 0,
                        total = 1,
                    )
                }
                SettingsNote(engine.kernelSwitchNote)
            } else {
                SettingsSection(engine.sectionOutputMode) {
                    if (state.isWindowsDesktop) {
                        SettingsSegmentedItem(
                            title = engine.wasapiMode,
                            subtitle = if (state.exclusiveActive) {
                                engine.wasapiExclusiveNote
                            } else {
                                engine.wasapiSharedNote
                            },
                            options = listOf(engine.wasapiSharedOption, engine.wasapiExclusiveOption),
                            selectedIndex = if (state.exclusiveActive) 1 else 0,
                            onSelect = onSelectExclusive,
                            index = 0,
                            total = 1,
                        )
                    } else {
                        SettingsClickItem(
                            title = engine.sharedOutput,
                            subtitle = engine.sharedOutputNote,
                            index = 0,
                            total = 1,
                            trailingContent = { StatusBadge(engine.ready, active = true) },
                        )
                    }
                }
                if (state.isWindowsDesktop) {
                    SettingsNote(engine.exclusiveWarning, emphasis = SettingsNoteEmphasis.WARNING)
                }
            }

            // ---------------- 能力矩阵 ----------------
            if (isAndroid) {
                SettingsSection(engine.sectionCapabilities) {
                    FeatureRow(
                        title = engine.oboeTitle,
                        subtitle = engine.oboeNote,
                        badge = if (state.flickEngineActive) engine.badgeActive else engine.badgeNeedsFlick,
                        active = state.flickEngineActive,
                        index = 0,
                        total = 3,
                    )
                    FeatureRow(
                        title = engine.usbDacTitle,
                        subtitle = engine.usbDacNote,
                        badge = if (state.flickEngineActive) engine.badgeActive else engine.badgeNeedsFlick,
                        active = state.flickEngineActive,
                        index = 1,
                        total = 3,
                    )
                    FeatureRow(
                        title = engine.dsdTitle,
                        subtitle = engine.dsdNoteAndroid,
                        badge = if (state.flickEngineActive) engine.badgeAvailable else engine.badgeNeedsFlick,
                        active = state.flickEngineActive,
                        index = 2,
                        total = 3,
                    )
                }
            } else {
                SettingsSection(engine.sectionCapabilities) {
                    FeatureRow(
                        title = engine.wasapiExclusiveTitle,
                        subtitle = if (state.isWindowsDesktop) {
                            engine.wasapiExclusiveNoteWindows
                        } else {
                            engine.wasapiExclusiveNoteOther
                        },
                        badge = when {
                            !state.isWindowsDesktop -> engine.badgeUnsupported
                            state.exclusiveActive -> engine.badgeActive
                            !state.vipActivated -> engine.badgeNeedsVip
                            else -> engine.badgeOff
                        },
                        active = state.exclusiveActive,
                        index = 0,
                        total = 4,
                    )
                    FeatureRow(
                        title = engine.bitPerfectTitle,
                        subtitle = when {
                            state.exclusiveActive -> engine.bitPerfectNoteActive
                            !state.isWindowsDesktop -> engine.bitPerfectNoteOther
                            else -> engine.bitPerfectNoteInactive
                        },
                        // 徽标只写**当前缺的那一环**：非 Windows 上「需独占」会让人去找一个
                        // 根本不存在的开关；VIP 未激活时先说 VIP，别让用户先去开独占再被拦一次。
                        badge = when {
                            !state.isWindowsDesktop -> engine.badgeUnsupported
                            state.exclusiveActive -> engine.badgeActive
                            !state.vipActivated -> engine.badgeNeedsVip
                            else -> engine.badgeNeedsExclusive
                        },
                        active = state.exclusiveActive,
                        index = 1,
                        total = 4,
                    )
                    FeatureRow(
                        title = engine.dsdTitle,
                        subtitle = engine.dsdNoteDesktop,
                        badge = engine.badgeAvailable,
                        active = true,
                        index = 2,
                        total = 4,
                    )
                    FeatureRow(
                        title = engine.dspTitle,
                        subtitle = engine.dspNote,
                        badge = engine.badgeAvailable,
                        active = true,
                        index = 3,
                        total = 4,
                    )
                }
            }

            SettingsSection(engine.sectionEffects) {
                SettingsClickItem(
                    title = engine.openEffects,
                    subtitle = if (isAndroid) {
                        engine.effectsNoteAndroid
                    } else {
                        engine.effectsNoteDesktop
                    },
                    icon = Icons.Filled.Equalizer,
                    index = 0,
                    total = 1,
                    onClick = onOpenAudioEffect,
                    showChevron = true,
                )
            }

            SettingsSection(engine.sectionCredits) {
                SettingsClickItem(
                    title = "moss-apps / Flick",
                    subtitle = engine.creditsNote,
                    index = 0,
                    total = 1,
                    onClick = onOpenFlickHome,
                )
            }
        }
    }

    CpRouteScaffold(
        title = if (isAndroid) engine.titleAndroid else engine.titleDesktop,
        onBack = onBack,
        content = body,
    )
}

/**
 * 能力矩阵里的一行：不可点击，只陈述「能力 + 当前状态」。
 *
 * @param active 徽标是否按「已生效」着色。这是**着色**而不是事实来源 ——
 *   事实写在 [badge] 文案里，避免出现「颜色看着像开着、其实写着需 VIP」的矛盾。
 */
@Composable
private fun FeatureRow(
    title: String,
    subtitle: String,
    badge: String,
    active: Boolean,
    index: Int,
    total: Int,
) {
    SettingsClickItem(
        title = title,
        subtitle = subtitle,
        index = index,
        total = total,
        trailingContent = { StatusBadge(badge, active) },
    )
}

/** 状态徽标：已生效用容器色强调，未生效/不支持走中性色（不抢视觉但不隐藏）。 */
@Composable
private fun StatusBadge(text: String, active: Boolean) {
    if (active) {
        SettingsBadge(
            text = text,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        SettingsBadge(
            text = text,
            containerColor = cpNestedSurface(),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
