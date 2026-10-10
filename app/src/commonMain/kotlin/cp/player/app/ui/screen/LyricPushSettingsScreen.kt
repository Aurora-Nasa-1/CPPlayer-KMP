package cp.player.app.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.i18n.CpStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSegmentedItem
import cp.player.app.ui.component.SettingsSliderItem
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.util.popOrNotify
import cp.player.core.lyricpush.LiveUpdateDisplayMode
import cp.player.core.lyricpush.LiveUpdateSecondaryMode
import cp.player.core.lyricpush.LyricContentMode
import cp.player.core.lyricpush.LyricPushConfig
import cp.player.core.lyricpush.LyricSecondaryMode
import cp.player.core.lyricpush.OPlusLyricMode
import cp.player.core.lyricpush.XiaomiSuperIslandConfig
import kotlin.math.roundToInt

/**
 * 「歌词投放」—— CPPlayer 把歌词交给系统 / 第三方歌词应用的**唯一一页**。
 *
 * ### 为什么是一页而不是八页
 * 这八个渠道共用同一份配置对象（[LyricPushConfig]），并且**互相排斥地竞争同一块屏幕
 * 区域**：状态栏只能显示一份歌词，超级岛、实时活动、浮动通知都在抢它。分成八页之后，
 * 用户在上面三页都打开了开关、回到播放界面发现只有一个生效，却没有任何地方能看出
 * 「它们会互相覆盖」。放在一页里，至少「同时开着这么多」这件事是可见的。
 *
 * ### 为什么没有「全部开启」按钮
 * 每个开关都是**向外写数据**（正在听什么、听到哪一句）。提供一键全开等于鼓励用户
 * 在不知道后果的情况下把所有出口都打开，与 `ExternalAccessSettingsScreen`
 * 默认全关的理由一致。
 *
 * ### 桌面端
 * 这些渠道依赖 Android 能力，桌面实现是空转 —— 而且两端**不共用设置存储**，
 * 在桌面打开这里不会影响 Android 端。因此本页在桌面上根本不可达
 * （`SettingsRegistry` 里标了 `androidOnly`），而不是显示一堆永远不生效的开关。
 */
class LyricPushSettingsScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val s = cpStrings()
        val config by AppModel.lyricPushConfigFlow.collectAsState()

        CpRouteScaffold(
            title = s.lyricPush.screenTitle,
            onBack = { navigator.popOrNotify() },
        ) { pageModifier ->
            SettingsPage(pageModifier) {
                SettingsNote(s.lyricPush.note)
                SettingsNote(s.lyricPush.androidOnlyNote)

                channelsSection(s, config)
                colorOsSection(s, config)
                superIslandSection(s, config)
                liveUpdateSection(s, config)
            }
        }
    }
}

// ============ 1. 渠道总开关 ============

@Composable
private fun channelsSection(s: CpStrings, config: LyricPushConfig) {
    SettingsSection(s.lyricPush.sectionChannels) {
        SettingsSwitchItem(
            title = s.lyricPush.lyricon,
            subtitle = s.lyricPush.lyriconNote,
            checked = config.lyriconEnabled,
            onCheckedChange = AppModel::setLyriconEnabled,
            icon = Icons.Filled.Lyrics,
            index = 0,
            total = 7,
        )
        SettingsSecondaryItem(
            mode = config.lyriconSecondary,
            onChange = AppModel::setLyriconSecondary,
            enabled = config.lyriconEnabled,
            index = 1,
        )

        SettingsSwitchItem(
            title = s.lyricPush.superLyric,
            subtitle = s.lyricPush.superLyricNote,
            checked = config.superLyricEnabled,
            onCheckedChange = AppModel::setSuperLyricEnabled,
            icon = Icons.Filled.Bolt,
            index = 2,
            total = 7,
        )
        SettingsSecondaryItem(
            mode = config.superLyricSecondary,
            onChange = AppModel::setSuperLyricSecondary,
            enabled = config.superLyricEnabled,
            index = 3,
        )

        SettingsSwitchItem(
            title = s.lyricPush.lyricGetter,
            subtitle = s.lyricPush.lyricGetterNote,
            checked = config.lyricGetterEnabled,
            onCheckedChange = AppModel::setLyricGetterEnabled,
            icon = Icons.Filled.TextFields,
            index = 4,
            total = 7,
        )

        SettingsSwitchItem(
            title = s.lyricPush.superIsland,
            subtitle = if (config.xiaomiSuperIslandEnabled) s.lyricPush.superIslandUnsupported else s.lyricPush.superIslandNote,
            checked = config.xiaomiSuperIslandEnabled,
            onCheckedChange = AppModel::setXiaomiSuperIslandEnabled,
            icon = Icons.Filled.PhoneAndroid,
            index = 5,
            total = 7,
        )

        SettingsSwitchItem(
            title = s.lyricPush.statusBar,
            subtitle = s.lyricPush.statusBarNote,
            checked = config.statusBarLyricEnabled,
            onCheckedChange = AppModel::setStatusBarLyricEnabled,
            icon = Icons.Filled.Subtitles,
            index = 6,
            total = 9,
        )
        SettingsSecondaryItem(
            mode = config.statusBarSecondary,
            onChange = AppModel::setStatusBarSecondary,
            enabled = config.statusBarLyricEnabled,
            index = 7,
        )
        SettingsSwitchItem(
            title = s.lyricPush.headsUp,
            subtitle = s.lyricPush.headsUpNote,
            checked = config.headsUpLyricEnabled,
            onCheckedChange = AppModel::setHeadsUpLyricEnabled,
            icon = Icons.Filled.NotificationsActive,
            index = 8,
            total = 9,
            enabled = config.statusBarLyricEnabled,
        )

        SettingsSwitchItem(
            title = s.lyricPush.mediaNotification,
            subtitle = s.lyricPush.mediaNotificationNote,
            checked = config.mediaNotificationLyricEnabled,
            onCheckedChange = AppModel::setMediaNotificationLyricEnabled,
            icon = Icons.Filled.Cast,
            index = 9,
            total = 11,
        )
        SettingsSecondaryItem(
            mode = config.mediaNotificationSecondary,
            onChange = AppModel::setMediaNotificationSecondary,
            enabled = config.mediaNotificationLyricEnabled,
            index = 10,
        )
    }
}

/**
 * 副行内容选择。
 *
 * 抽成一个共用控件而不是在每个渠道下各写一遍三段式：一共有 **5 个渠道**要选副行，
 * 各写一遍意味着「不显示 / 翻译 / 注音」这三个词有 5 份调用点，改词要改 5 处；
 * 更麻烦的是下标映射（枚举 → 第几段）会散落 5 份，加一档就要全改。
 */
@Composable
private fun SettingsSecondaryItem(
    mode: LyricSecondaryMode,
    onChange: (LyricSecondaryMode) -> Unit,
    enabled: Boolean,
    index: Int,
) {
    val s = cpStrings()
    SettingsSegmentedItem(
        title = s.lyricPush.sectionSecondary,
        options = listOf(
            s.lyricPush.secondaryOff,
            s.lyricPush.secondaryTranslation,
            s.lyricPush.secondaryPronunciation,
        ),
        selectedIndex = mode.ordinal,
        onSelect = { onChange(LyricSecondaryMode.entries[it]) },
        index = index,
        total = 1,
        enabled = enabled,
    )
}

// ============ 2. ColorOS 锁屏岛 ============

@Composable
private fun colorOsSection(s: CpStrings, config: LyricPushConfig) {
    SettingsSection(s.lyricPush.sectionColorOs) {
        SettingsSwitchItem(
            title = s.lyricPush.colorOs,
            subtitle = s.lyricPush.colorOsNote,
            checked = config.colorOsEnabled,
            onCheckedChange = AppModel::setColorOsEnabled,
            icon = Icons.Filled.Subtitles,
            index = 0,
            total = 2,
        )
        SettingsSegmentedItem(
            title = s.lyricPush.colorOsMode,
            subtitle = if (config.colorOsMode == OPlusLyricMode.MODULE) {
                s.lyricPush.colorOsModeModuleNote
            } else {
                s.lyricPush.colorOsModeSystemNote
            },
            options = listOf(s.lyricPush.colorOsModeSystem, s.lyricPush.colorOsModeModule),
            selectedIndex = if (config.colorOsMode == OPlusLyricMode.MODULE) 1 else 0,
            onSelect = {
                AppModel.setColorOsMode(if (it == 1) OPlusLyricMode.MODULE else OPlusLyricMode.SYSTEM)
            },
            index = 1,
            total = 2,
            enabled = config.colorOsEnabled,
        )
    }
}

// ============ 3. 超级岛外观 ============

@Composable
private fun superIslandSection(s: CpStrings, config: LyricPushConfig) {
    val island = config.xiaomiSuperIsland
    val enabled = config.xiaomiSuperIslandEnabled
    SettingsSection(s.lyricPush.sectionSuperIsland) {
        SettingsSegmentedItem(
            title = s.lyricPush.islandContent,
            options = listOf(
                s.lyricPush.contentOriginal,
                s.lyricPush.contentTranslation,
                s.lyricPush.contentPronunciation,
            ),
            selectedIndex = island.content.ordinal,
            onSelect = { AppModel.setXiaomiSuperIsland(island.copy(content = LyricContentMode.entries[it])) },
            index = 0,
            total = 10,
            enabled = enabled,
        )
        SettingsSegmentedItem(
            title = s.lyricPush.islandLayout,
            options = listOf(s.lyricPush.islandLayoutStandard, s.lyricPush.islandLayoutFull),
            selectedIndex = if (island.lyricMode == XiaomiSuperIslandConfig.LyricMode.FULL) 1 else 0,
            onSelect = {
                AppModel.setXiaomiSuperIsland(
                    island.copy(
                        lyricMode = if (it == 1) {
                            XiaomiSuperIslandConfig.LyricMode.FULL
                        } else {
                            XiaomiSuperIslandConfig.LyricMode.STANDARD
                        },
                    ),
                )
            },
            index = 1,
            total = 10,
            enabled = enabled,
        )
        SettingsSwitchItem(
            title = s.lyricPush.islandShowCover,
            subtitle = s.lyricPush.islandShowCoverNote,
            checked = island.fullLyricShowLeftCover,
            onCheckedChange = { AppModel.setXiaomiSuperIsland(island.copy(fullLyricShowLeftCover = it)) },
            index = 2,
            total = 10,
            enabled = enabled && island.lyricMode == XiaomiSuperIslandConfig.LyricMode.FULL,
        )
        SettingsSwitchItem(
            title = s.lyricPush.islandScrolling,
            subtitle = s.lyricPush.islandScrollingNote,
            checked = island.scrollingEnabled,
            onCheckedChange = { AppModel.setXiaomiSuperIsland(island.copy(scrollingEnabled = it)) },
            index = 3,
            total = 10,
            enabled = enabled,
        )
        IslandCharSlider(
            title = s.lyricPush.islandRightChars,
            value = island.rightTextChars,
            range = XiaomiSuperIslandConfig.RIGHT_CHARS_RANGE,
            enabled = enabled,
            index = 4,
            total = 10,
        ) { AppModel.setXiaomiSuperIsland(island.copy(rightTextChars = it)) }
        IslandCharSlider(
            title = s.lyricPush.islandLeftCoverChars,
            value = island.leftWithCoverTextChars,
            range = XiaomiSuperIslandConfig.LEFT_WITH_COVER_RANGE,
            enabled = enabled,
            index = 5,
            total = 10,
        ) { AppModel.setXiaomiSuperIsland(island.copy(leftWithCoverTextChars = it)) }
        IslandCharSlider(
            title = s.lyricPush.islandLeftChars,
            value = island.leftWithoutCoverTextChars,
            range = XiaomiSuperIslandConfig.LEFT_WITHOUT_COVER_RANGE,
            enabled = enabled,
            index = 6,
            total = 10,
        ) { AppModel.setXiaomiSuperIsland(island.copy(leftWithoutCoverTextChars = it)) }
        SettingsSwitchItem(
            title = s.lyricPush.islandTextColor,
            subtitle = s.lyricPush.islandTextColorNote,
            checked = island.textColorEnabled,
            onCheckedChange = { AppModel.setXiaomiSuperIsland(island.copy(textColorEnabled = it)) },
            index = 7,
            total = 10,
            enabled = enabled,
        )
        SettingsSegmentedItem(
            title = s.lyricPush.islandColorSource,
            options = listOf(s.lyricPush.islandColorSourceAlbum, s.lyricPush.islandColorSourceCustom),
            selectedIndex = if (island.colorSource == XiaomiSuperIslandConfig.IslandColorSource.CUSTOM) 1 else 0,
            onSelect = {
                AppModel.setXiaomiSuperIsland(
                    island.copy(
                        colorSource = if (it == 1) {
                            XiaomiSuperIslandConfig.IslandColorSource.CUSTOM
                        } else {
                            XiaomiSuperIslandConfig.IslandColorSource.ALBUM
                        },
                    ),
                )
            },
            index = 8,
            total = 10,
            enabled = enabled && island.textColorEnabled,
        )
        SettingsSwitchItem(
            title = s.lyricPush.islandProgressColor,
            subtitle = s.lyricPush.islandProgressColorNote,
            checked = island.progressColorEnabled,
            onCheckedChange = { AppModel.setXiaomiSuperIsland(island.copy(progressColorEnabled = it)) },
            index = 9,
            total = 10,
            enabled = enabled,
        )
        SettingsSegmentedItem(
            title = s.lyricPush.islandDismissDelay,
            options = XiaomiSuperIslandConfig.DISMISS_DELAYS_MS.sorted().map { delayMs ->
                if (delayMs == 0) s.lyricPush.islandDismissImmediately else s.lyricPush.islandDismissAfter(delayMs / 1000)
            },
            selectedIndex = XiaomiSuperIslandConfig.DISMISS_DELAYS_MS
                .sorted()
                .indexOf(island.dismissDelayMs)
                .coerceAtLeast(0),
            onSelect = { AppModel.setXiaomiSuperIsland(island.copy(dismissDelayMs = DISMISS_ORDER[it])) },
            index = 10,
            total = 10,
            enabled = enabled,
        )
    }
}

/**
 * 岛内两列的字数滑杆。
 *
 * ### 为什么需要本地状态
 * [SettingsSliderItem] 只给 `onValueChange`（拖动中，连续触发）与
 * `onValueChangeFinished`（松手）两个口，**没有独立的值槽**。若在 `onValueChange` 里
 * 直接落盘，拖一次会写几十次 `SettingsStorage` 并触发几十次 `applyConfig` ——
 * 每次 `applyConfig` 都会重发歌词。所以拖动期间只更新本地状态，松手才提交。
 *
 * `remember(value)` 的 key 用**已提交的值**：外部（换歌 / 恢复配置）改了值之后，
 * 本地状态会跟着重置，不会出现「滑杆停在旧位置、实际值是新的」。
 */
@Composable
private fun IslandCharSlider(
    title: String,
    value: Int,
    range: IntRange,
    enabled: Boolean,
    index: Int,
    total: Int,
    onChange: (Int) -> Unit,
) {
    val s = cpStrings()
    var local by remember(value) { mutableStateOf(value.toFloat()) }
    SettingsSliderItem(
        title = title,
        value = local,
        onValueChange = { local = it },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        onValueChangeFinished = { onChange(local.roundToInt()) },
        // steps = 中间刻度数；两端各由 valueRange 提供，所以要减 1。
        steps = (range.last - range.first - 1).coerceAtLeast(0),
        subtitle = s.lyricPush.sectionSuperIsland,
        valueLabel = s.lyricPush.islandCharsValue(local.roundToInt()),
        index = index,
        total = total,
        enabled = enabled,
    )
}

/** 与 [XiaomiSuperIslandConfig.DISMISS_DELAYS_MS] 同序（升序），供下标映射。 */
private val DISMISS_ORDER: List<Int> = XiaomiSuperIslandConfig.DISMISS_DELAYS_MS.sorted()

// ============ 4. 实时活动 ============

@Composable
private fun liveUpdateSection(s: CpStrings, config: LyricPushConfig) {
    val enabled = config.liveUpdateEnabled
    SettingsSection(s.lyricPush.sectionLiveUpdate) {
        SettingsSwitchItem(
            title = s.lyricPush.liveUpdate,
            subtitle = s.lyricPush.liveUpdateNote,
            checked = enabled,
            onCheckedChange = AppModel::setLiveUpdateEnabled,
            icon = Icons.Filled.NotificationsActive,
            index = 0,
            total = 3,
        )
        SettingsSegmentedItem(
            title = s.lyricPush.liveUpdateContent,
            options = listOf(
                s.lyricPush.contentOriginal,
                s.lyricPush.contentTranslation,
                s.lyricPush.contentPronunciation,
            ),
            selectedIndex = config.liveUpdateContent.ordinal,
            onSelect = { AppModel.setLiveUpdateContent(LyricContentMode.entries[it]) },
            index = 1,
            total = 3,
            enabled = enabled,
        )
        SettingsSegmentedItem(
            title = s.lyricPush.liveUpdateDisplay,
            options = listOf(s.lyricPush.liveUpdateDisplayWord, s.lyricPush.liveUpdateDisplayFull),
            selectedIndex = if (config.liveUpdateDisplay == LiveUpdateDisplayMode.FULL_LINE) 1 else 0,
            onSelect = {
                AppModel.setLiveUpdateDisplay(
                    if (it == 1) LiveUpdateDisplayMode.FULL_LINE else LiveUpdateDisplayMode.WORD_WINDOW,
                )
            },
            index = 2,
            total = 3,
            enabled = enabled,
        )
        SettingsSegmentedItem(
            title = s.lyricPush.liveUpdateSecondary,
            options = listOf(
                s.lyricPush.liveUpdateSecondarySong,
                s.lyricPush.secondaryTranslation,
                s.lyricPush.secondaryPronunciation,
            ),
            selectedIndex = when (config.liveUpdateSecondary) {
                LiveUpdateSecondaryMode.SONG -> 0
                LiveUpdateSecondaryMode.TRANSLATION -> 1
                LiveUpdateSecondaryMode.PRONUNCIATION -> 2
            },
            onSelect = {
                AppModel.setLiveUpdateSecondary(
                    when (it) {
                        1 -> LiveUpdateSecondaryMode.TRANSLATION
                        2 -> LiveUpdateSecondaryMode.PRONUNCIATION
                        else -> LiveUpdateSecondaryMode.SONG
                    },
                )
            },
            index = 3,
            total = 4,
            enabled = enabled,
        )
    }
}
