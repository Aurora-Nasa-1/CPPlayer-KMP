package cp.player.app.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Waves
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.SettingsButtonItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSegmentedItem
import cp.player.app.ui.component.SettingsSliderItem
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.component.settingsSearchTarget
import cp.player.app.ui.feedback.CpHaptic
import cp.player.app.ui.feedback.LocalCpHaptics
import cp.player.app.ui.util.popOrNotify
import kotlin.math.roundToInt

/**
 * 手机播放界面与布局微调设置屏幕（Voyager 路由页面）。
 *
 * 接入全平台统一的 SettingsRegistry 设置树与搜索定位机制。
 */
class MobilePlayerSettingsScreen(
    private val targetSettingId: String? = null,
) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val haptics = LocalCpHaptics.current
        val config by AppModel.mobilePlayerCustomConfigFlow.collectAsState()

        var draggingCoverScale by remember { mutableFloatStateOf(-1f) }
        val displayedCoverScale = if (draggingCoverScale >= 0f) draggingCoverScale else config.coverScale

        var draggingCoverCorner by remember { mutableIntStateOf(-1) }
        val displayedCoverCorner = if (draggingCoverCorner >= 0) draggingCoverCorner else config.coverCornerDp

        var draggingLyricsSize by remember { mutableIntStateOf(-1) }
        val displayedLyricsSize = if (draggingLyricsSize >= 0) draggingLyricsSize else config.lyricsFontSizeSp

        CpRouteScaffold(
            title = "手机播放界面与布局",
            onBack = { navigator.popOrNotify() },
        ) { pageModifier ->
            SettingsPage(pageModifier) {
                // 1. 布局模式
                SettingsSection("布局模式") {
                    SettingsSwitchItem(
                        title = "全新单页播放布局",
                        subtitle = if (config.enabled) "已启用 · 封面、双行歌词预览与走带整合一屏" else "已停用 · 退回经典三页横滑播放页",
                        icon = Icons.Filled.PhoneAndroid,
                        checked = config.enabled,
                        onCheckedChange = {
                            haptics.perform(CpHaptic.Confirm)
                            AppModel.setModernMobilePlayerEnabled(it)
                        },
                        index = 0,
                        total = 1,
                        modifier = Modifier.settingsSearchTarget("mobile_player_enabled", targetSettingId),
                    )
                }

                // 2. 封面视觉与空间光影
                SettingsSection("封面视觉与空间光影") {
                    SettingsSliderItem(
                        title = "封面占屏宽比例",
                        subtitle = "调节专辑封面在竖屏播放界面中的相对尺寸",
                        value = displayedCoverScale,
                        onValueChange = { draggingCoverScale = it },
                        valueRange = 0.55f..0.92f,
                        onValueChangeFinished = {
                            AppModel.updateMobilePlayerCustomConfig { c -> c.copy(coverScale = displayedCoverScale) }
                            draggingCoverScale = -1f
                        },
                        valueLabel = "${(displayedCoverScale * 100).roundToInt()}%",
                        index = 0,
                        total = 4,
                        modifier = Modifier.settingsSearchTarget("mobile_player_cover_scale", targetSettingId),
                    )
                    SettingsSliderItem(
                        title = "封面圆角半径",
                        subtitle = "控制专辑封面四周平滑曲率与卡片视觉柔和度",
                        value = displayedCoverCorner.toFloat(),
                        onValueChange = { draggingCoverCorner = it.roundToInt() },
                        valueRange = 8f..38f,
                        steps = 14,
                        onValueChangeFinished = {
                            AppModel.updateMobilePlayerCustomConfig { c -> c.copy(coverCornerDp = displayedCoverCorner) }
                            draggingCoverCorner = -1
                        },
                        valueLabel = "${displayedCoverCorner} dp",
                        index = 1,
                        total = 4,
                        modifier = Modifier.settingsSearchTarget("mobile_player_cover_corner", targetSettingId),
                    )
                    val elevationOptions = listOf("无阴影", "柔和 8dp", "景深 16dp", "沉浸 28dp")
                    val elevationValues = listOf(0, 8, 16, 28)
                    val selectedElevationIndex = elevationValues.indexOf(config.coverElevationDp).coerceAtLeast(0)
                    SettingsSegmentedItem(
                        title = "空间立体阴影",
                        subtitle = "为封面提供浮动层叠阴影效果",
                        options = elevationOptions,
                        selectedIndex = selectedElevationIndex,
                        onSelect = { idx ->
                            haptics.perform(CpHaptic.Tick)
                            AppModel.updateMobilePlayerCustomConfig { c -> c.copy(coverElevationDp = elevationValues[idx]) }
                        },
                        index = 2,
                        total = 4,
                        modifier = Modifier.settingsSearchTarget("mobile_player_cover_elevation", targetSettingId),
                    )
                    SettingsSwitchItem(
                        title = "封面弥散环境光晕",
                        subtitle = "在专辑封面底层投射基于动态色彩的流光柔和光晕",
                        checked = config.ambientGlow,
                        onCheckedChange = {
                            haptics.perform(CpHaptic.Confirm)
                            AppModel.updateMobilePlayerCustomConfig { c -> c.copy(ambientGlow = it) }
                        },
                        index = 3,
                        total = 4,
                        modifier = Modifier.settingsSearchTarget("mobile_player_ambient_glow", targetSettingId),
                    )
                }

                // 3. 手势与走带控制
                SettingsSection("手势与走带控制") {
                    SettingsSwitchItem(
                        title = "封面左右滑动切歌手势",
                        subtitle = "在专辑封面上左右横向滑动手势触发上一首 / 下一首",
                        checked = config.coverSwipeGesture,
                        onCheckedChange = {
                            haptics.perform(CpHaptic.Confirm)
                            AppModel.updateMobilePlayerCustomConfig { c -> c.copy(coverSwipeGesture = it) }
                        },
                        index = 0,
                        total = 3,
                        modifier = Modifier.settingsSearchTarget("mobile_player_cover_swipe", targetSettingId),
                    )
                    SettingsSwitchItem(
                        title = "M3 Expressive 律动波形进度条",
                        subtitle = "采用 Material 3 Expressive 律动动态波形进度条（关闭则为平直进度条）",
                        icon = Icons.Filled.Waves,
                        checked = config.useWavySeekBar,
                        onCheckedChange = {
                            haptics.perform(CpHaptic.Confirm)
                            AppModel.updateMobilePlayerCustomConfig { c -> c.copy(useWavySeekBar = it) }
                        },
                        index = 1,
                        total = 3,
                        modifier = Modifier.settingsSearchTarget("mobile_player_wavy_seek_bar", targetSettingId),
                    )
                    val controlsOptions = listOf("紧凑 85%", "标准 100%", "醒目 115%")
                    val controlsValues = listOf(0.85f, 1.0f, 1.15f)
                    val selectedControlsIndex = controlsValues.indexOfFirst { kotlin.math.abs(it - config.controlsScale) < 0.05f }.coerceAtLeast(1)
                    SettingsSegmentedItem(
                        title = "走带按键尺寸",
                        subtitle = "调节播放/暂停、切歌与模式切换按键的缩放比例",
                        options = controlsOptions,
                        selectedIndex = selectedControlsIndex,
                        onSelect = { idx ->
                            haptics.perform(CpHaptic.Tick)
                            AppModel.updateMobilePlayerCustomConfig { c -> c.copy(controlsScale = controlsValues[idx]) }
                        },
                        index = 2,
                        total = 3,
                        modifier = Modifier.settingsSearchTarget("mobile_player_controls_scale", targetSettingId),
                    )
                }

                // 4. 夹层歌词与卡拉OK
                SettingsSection("夹层歌词与卡拉OK") {
                    SettingsSliderItem(
                        title = "夹层歌词正文字号",
                        subtitle = "调整单页模式下中间双行歌词预览的字体大小",
                        value = displayedLyricsSize.toFloat(),
                        onValueChange = { draggingLyricsSize = it.roundToInt() },
                        valueRange = 14f..22f,
                        steps = 7,
                        onValueChangeFinished = {
                            AppModel.updateMobilePlayerCustomConfig { c -> c.copy(lyricsFontSizeSp = displayedLyricsSize) }
                            draggingLyricsSize = -1
                        },
                        valueLabel = "${displayedLyricsSize} sp",
                        index = 0,
                        total = 4,
                        modifier = Modifier.settingsSearchTarget("mobile_player_lyrics_size", targetSettingId),
                    )
                    val alignOptions = listOf("居中对齐", "靠左对齐")
                    val selectedAlignIndex = if (config.lyricsAlignStart) 1 else 0
                    SettingsSegmentedItem(
                        title = "歌词对齐方式",
                        subtitle = "选择夹层预览歌词的文本排版方向",
                        options = alignOptions,
                        selectedIndex = selectedAlignIndex,
                        onSelect = { idx ->
                            haptics.perform(CpHaptic.Tick)
                            AppModel.updateMobilePlayerCustomConfig { c -> c.copy(lyricsAlignStart = idx == 1) }
                        },
                        index = 1,
                        total = 4,
                        modifier = Modifier.settingsSearchTarget("mobile_player_lyrics_align", targetSettingId),
                    )
                    SettingsSwitchItem(
                        title = "逐字卡拉OK动效",
                        subtitle = "音源包含逐字时值时，以精准平滑的高亮推进动画展现",
                        checked = config.karaokeEnabled,
                        onCheckedChange = {
                            haptics.perform(CpHaptic.Confirm)
                            AppModel.updateMobilePlayerCustomConfig { c -> c.copy(karaokeEnabled = it) }
                        },
                        index = 2,
                        total = 4,
                        modifier = Modifier.settingsSearchTarget("mobile_player_karaoke", targetSettingId),
                    )
                    SettingsSwitchItem(
                        title = "双语歌词翻译展示",
                        subtitle = "在歌曲拥有翻译歌词时，在正文下方同步显示次行译文",
                        checked = config.showTranslation,
                        onCheckedChange = {
                            haptics.perform(CpHaptic.Confirm)
                            AppModel.updateMobilePlayerCustomConfig { c -> c.copy(showTranslation = it) }
                        },
                        index = 3,
                        total = 4,
                        modifier = Modifier.settingsSearchTarget("mobile_player_translation", targetSettingId),
                    )
                }

                // 5. 交互说明与配置维护
                SettingsSection("交互说明与重置") {
                    SettingsButtonItem(
                        text = "恢复默认播放器布局配置",
                        subtitle = "重置封面比例、圆角、歌词字号及走带尺寸为出厂推荐值",
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

                SettingsNote(
                    "提示：在单页播放界面中轻触中间的双行歌词区域，可即时展开类似 Apple Music 的沉浸式全屏歌词视图；底部走带控制栏右侧常驻独立评论入口，随时查看互动评论。"
                )
            }
        }
    }
}
