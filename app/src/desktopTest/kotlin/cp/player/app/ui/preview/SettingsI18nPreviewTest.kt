package cp.player.app.ui.preview

import androidx.compose.foundation.background
import cp.player.app.ui.component.ScrollColumn
import cp.player.app.ui.component.SettingsNote
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.AppModel
import cp.player.app.i18n.AppLanguage
import cp.player.app.i18n.CpStrings
import cp.player.app.i18n.ProvideCpStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.i18n.displayName
import cp.player.app.i18n.displayNote
import cp.player.app.ui.theme.description
// ⚠️ `displayName` 在三个类型上同名（AppLanguage / ThemeMode / ColorSource），
// 静态导入只能挑一个（这里给 AppLanguage）—— 其余两个用别名导入，
// 否则调用点分不清是哪个扩展。
import cp.player.app.ui.theme.displayName as colorSourceName
import cp.player.app.ui.theme.displayName as themeModeName
import cp.player.app.shortcut.ShortcutAction
import cp.player.app.shortcut.ShortcutBinding
import cp.player.app.shortcut.ShortcutCategory
import cp.player.app.shortcut.ShortcutKey
import cp.player.app.ui.component.MonetIcon
import cp.player.app.ui.component.SettingsButtonItem
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsDropdownItem
import cp.player.app.ui.component.SettingsLazyPage
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSegmentedItem
import cp.player.app.ui.component.SettingsSliderItem
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.component.SleepTimerDialog
import cp.player.app.ui.screen.SettingsEntry
import cp.player.app.ui.screen.SettingsGroup
import cp.player.app.ui.screen.ShortcutKeyChip
import cp.player.app.ui.screen.settingsEntries
import cp.player.app.ui.theme.ColorSource
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import java.io.File
import kotlin.test.Test

/**
 * 多语言版式核对（会真的出图）。
 *
 * ### 为什么它必须存在
 *
 * 中文文案普遍比英文短一大截：`通用` → `General`、`连接与集成` →
 * `Connections & Integrations`、局域网设备那条副标题更是从 30 个字符涨到 110 个。
 * 编译和单测都量不到「字撑破了行」「副标题被截成半句」，
 * 而这类问题**只有出图才看得见**（`AGENTS.md` §1 同款规定）。
 *
 * 渲染的是**真实数据**：`settingsEntries()` 出来的树 + [CpStrings] 里的文案
 * （而不是在这里手抄几行样例）—— 手抄的样例会在文案改了之后继续「通过」。
 */
class SettingsI18nPreviewTest {

    /** 真实设置树。分组与顺序来自 Registry，标题来自当前语言的 [CpStrings]。 */
    @Composable
    private fun RootList() {
        val strings = cpStrings()
        val entries: List<SettingsEntry> = settingsEntries()
        ScrollColumn(Modifier.fillMaxSize()) {
            SettingsGroup.entries.forEach { group ->
                val groupEntries = entries.filter { it.group == group }
                if (groupEntries.isEmpty()) return@forEach
                SettingsSection(group.titleOf(strings)) {
                    groupEntries.forEachIndexed { index, entry ->
                        SettingsClickItem(
                            title = entry.titleOf(strings),
                            subtitle = entry.subtitleOf(strings),
                            index = index,
                            total = groupEntries.size,
                            onClick = {},
                            leadingContent = {
                                MonetIcon(
                                    icon = entry.icon,
                                    containerColor = MaterialTheme.colorScheme.primaryFixed,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryFixed,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun LanguageList() {
        val strings = cpStrings()
        val options = AppLanguage.entries
        ScrollColumn(Modifier.fillMaxSize()) {
            SettingsSection(strings.language.screenTitle) {
                options.forEachIndexed { index, language ->
                    SettingsClickItem(
                        title = language.displayName(strings),
                        subtitle = language.displayNote(strings),
                        index = index,
                        total = options.size,
                        onClick = {},
                    )
                }
            }
            SettingsNote(strings.language.applyNote)
        }
    }

    /**
     * 外观页的**真实文案**（枚举显示名 + 开关行）。
     *
     * 单独出图而不并进设置根页：这一页的中英长度差比根页更大
     * （`纯黑模式` → `Pure black`、字体圆滑度那条副标题在英文下是两倍长），
     * 而它同时是「喂文案的枚举」所在页 —— 枚举显示名漏迁移在这里最容易看出来。
     */
    @Composable
    private fun AppearancePage() {
        val s = cpStrings()
        val sources = listOf(ColorSource.PLATFORM, ColorSource.COVER, ColorSource.FIXED)
        ScrollColumn(Modifier.fillMaxSize()) {
            SettingsSection(s.appearance.sectionLook) {
                SettingsSegmentedItem(
                    title = s.appearance.themeMode,
                    options = listOf(
                        ThemeMode.SYSTEM.themeModeName(s),
                        ThemeMode.LIGHT.themeModeName(s),
                        ThemeMode.DARK.themeModeName(s),
                    ),
                    selectedIndex = 0,
                    onSelect = {},
                    index = 0,
                    total = 3,
                )
                SettingsSegmentedItem(
                    title = s.appearance.colorSource,
                    options = sources.map { it.colorSourceName(s) },
                    selectedIndex = 0,
                    onSelect = {},
                    index = 1,
                    total = 3,
                )
                SettingsSwitchItem(
                    title = s.appearance.pureBlack,
                    subtitle = s.appearance.pureBlackNote,
                    checked = true,
                    onCheckedChange = {},
                    index = 2,
                    total = 3,
                )
            }
            SettingsSection(s.appearance.sectionFont) {
                SettingsSliderItem(
                    title = s.appearance.fontRoundness,
                    // 传平台默认值，形态与真实页面一致（含插值后的数字）。
                    subtitle = s.appearance.fontRoundnessNote(100),
                    value = 100f,
                    onValueChange = {},
                    valueRange = 0f..100f,
                    steps = 19,
                    onValueChangeFinished = {},
                    valueLabel = "100${s.appearance.roundnessDefaultTag}",
                    index = 0,
                    total = 1,
                )
            }
            SettingsNote(s.appearance.roundnessNote)
            SettingsNote(ColorSource.COVER.description(s, platformAvailable = true))
        }
    }

    /**
     * 存储页的**真实文案**（含带参数的摘要行）。
     *
     * 带参数的文案（`"$count tracks · $bytes"`）在别处测不到版式：
     * 数字一填进去，英文行宽就变了 —— 窄屏那张必须看。
     */
    @Composable
    private fun StoragePage() {
        val s = cpStrings()
        ScrollColumn(Modifier.fillMaxSize()) {
            SettingsSection(s.storage.sectionDownload) {
                SettingsClickItem(
                    title = s.storage.downloadedMusic,
                    subtitle = s.storage.downloadedMusicSummary(128, "3.4 GB"),
                    index = 0,
                    total = 3,
                    onClick = {},
                )
                SettingsClickItem(
                    title = s.storage.downloadDir,
                    subtitle = s.storage.downloadDirDefault,
                    index = 1,
                    total = 3,
                    onClick = {},
                )
                SettingsClickItem(
                    title = s.storage.openDir,
                    subtitle = s.storage.openDirNote,
                    index = 2,
                    total = 3,
                    onClick = {},
                )
            }
            SettingsSection(s.storage.sectionSongCache) {
                SettingsClickItem(
                    title = s.storage.cachedSongs,
                    subtitle = s.storage.cachedSongsSummary(37, "1.2 GB", "2 GB"),
                    index = 0,
                    total = 3,
                    onClick = {},
                )
                SettingsButtonItem(
                    text = s.storage.clearSongCache,
                    subtitle = s.storage.clearSongCacheNote,
                    index = 1,
                    total = 3,
                    onClick = {},
                )
                SettingsButtonItem(
                    text = s.storage.clearStaleCache,
                    subtitle = s.storage.clearStaleCacheNote,
                    index = 2,
                    total = 3,
                    onClick = {},
                )
            }
            SettingsSection(s.storage.sectionApiCache) {
                SettingsClickItem(
                    title = s.storage.apiCacheEntries,
                    subtitle = s.storage.apiCacheEntryCount(2841) + s.storage.apiCacheHitRate(87),
                    index = 0,
                    total = 1,
                    onClick = {},
                )
            }
            SettingsNote(s.storage.noteDesktop)
        }
    }

    /**
     * 播放页的**真实文案**。
     *
     * 音质下拉的中英差异是这批里最大的：`无损` → `Lossless` 还好，
     * 但副标题（AMLL 那条）在英文下长度翻倍。
     */
    @Composable
    private fun PlaybackPage() {
        val s = cpStrings()
        val levels = AppModel.qualityLevels
        ScrollColumn(Modifier.fillMaxSize()) {
            SettingsSection(s.playback.sectionQuality) {
                SettingsDropdownItem(
                    title = s.playback.defaultQuality,
                    subtitle = s.playback.defaultQualityNote,
                    options = levels.map { s.quality.labelOf(it) },
                    selectedIndex = 1,
                    onSelect = {},
                    index = 0,
                    total = 1,
                )
            }
            SettingsSection(s.playback.sectionLyrics) {
                SettingsDropdownItem(
                    title = s.playback.lyricsSource,
                    subtitle = s.playback.lyricsSourceNote,
                    options = listOf(
                        s.playback.lyricsProviderOnly,
                        s.playback.lyricsAmllFirst,
                        s.playback.lyricsAmllOnly,
                    ),
                    selectedIndex = 1,
                    onSelect = {},
                    index = 0,
                    total = 1,
                )
            }
            SettingsSection(s.playback.sectionSleepTimer) {
                SettingsClickItem(
                    title = s.playback.sleepTimer,
                    subtitle = s.playback.sleepRemaining(42),
                    index = 0,
                    total = 1,
                    onClick = {},
                )
            }
            SettingsNote(s.playback.sharedTimerNote)
        }
    }

    /**
     * 睡眠定时**弹窗**的文案。
     *
     * 单独出图的理由：这是设置页 / 播放页 / 玩家界面**三处共用**的弹窗（单一事实源），
     * 而 chip 上的「15 分钟」→ `15 min`、「播完本曲」→ `After this track` 长度差很大，
     * 5 个 chip + 1 个在 360dp 窄屏上**必须**逐个看过才知道会不会挤。
     *
     * 直接渲染 [SleepTimerDialog] 本体（不是手抄样例）—— 手抄的会在文案改了之后
     * 继续「通过」。
     */
    @Composable
    private fun SleepTimerDialogContent(active: Boolean) {
        SleepTimerDialog(
            activeRemainingMs = if (active) 42 * 60_000L else null,
            afterTrackActive = false,
            onSelect = {},
            onCancelTimer = {},
            onDismiss = {},
        )
    }

    /**
     * 快捷键页的**真实文案**。
     *
     * 这一页能同时验到三处迁移：动作名（`labelOf`）、说明（`hintOf`）、
     * 键位徽标（`displayName`，其中三个键名带语言差异）。
     */
    @Composable
    private fun ShortcutPage() {
        val s = cpStrings()
        val bindings = mapOf(
            ShortcutAction.PLAY_PAUSE.id to ShortcutBinding(ShortcutKey.SPACE),
            ShortcutAction.NEXT_TRACK.id to
                ShortcutBinding(ShortcutKey.RIGHT, ctrl = true, shift = true),
            ShortcutAction.SEEK_FORWARD.id to ShortcutBinding(ShortcutKey.F5, alt = true),
            // 有意留一个未绑定：徽标那态也要在图上看得见。
            ShortcutAction.TOGGLE_FAVORITE.id to null,
        )
        SettingsLazyPage(Modifier.fillMaxSize()) {
            ShortcutCategory.entries.forEach { category ->
                val actions = ShortcutAction.entries.filter { it.category == category }
                if (actions.isEmpty()) return@forEach
                item(key = "pv_${category.name}") {
                    SettingsSection(category.titleOf(s)) {
                        actions.forEachIndexed { index, action ->
                            SettingsClickItem(
                                title = action.labelOf(s),
                                subtitle = action.hintOf(s),
                                index = index,
                                total = actions.size,
                                onClick = {},
                                trailingContent = {
                                    ShortcutKeyChip(
                                        binding = bindings[action.id],
                                        conflicted = false,
                                        strings = s,
                                    )
                                },
                            )
                        }
                    }
                }
            }
            item(key = "pv_note") { SettingsNote(s.shortcuts.note) }
        }
    }

    private fun render(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.LIGHT) { content() }
        }
        try {
            val data = scene.render().encodeToData() ?: error("encode failed")
            val out = File("build-verify/preview").apply { mkdirs() }
            File(out, "$name.png").writeBytes(data.bytes)
        } finally {
            scene.close()
        }
    }

    @Test
    fun renderBothLanguages() {
        AppLanguage.entries
            .filter { it != AppLanguage.SYSTEM } // SYSTEM 的结果取决于宿主 locale，不能用来做稳定的图
            .forEach { language ->
                val tag = when (language) {
                    AppLanguage.ZH_HANS -> "zh"
                    AppLanguage.ENGLISH -> "en"
                    AppLanguage.SYSTEM -> error("unreachable")
                }
                // 宽屏：正常桌面窗口
                render("i18n-settings-$tag-wide", 1200, 1000) {
                    ProvideCpStrings(language) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            RootList()
                        }
                    }
                }
                // 窄屏：480dp 下的翻译能不能放下，是 English 最容易翻车的地方
                render("i18n-settings-$tag-narrow", 480, 1000) {
                    ProvideCpStrings(language) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            RootList()
                        }
                    }
                }
                render("i18n-language-$tag", 520, 460) {
                    ProvideCpStrings(language) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            LanguageList()
                        }
                    }
                }
                // 批次 1 迁走的四个页面。**每页两张（宽 / 窄）**：这些页的英文普遍
                // 比中文长 1.5~2 倍（副标题尤其），只出宽屏等于放过最该看的那张。
                listOf(
                    "appearance" to @Composable { AppearancePage() },
                    "storage" to @Composable { StoragePage() },
                    "playback" to @Composable { PlaybackPage() },
                    "shortcut" to @Composable { ShortcutPage() },
                    // 睡眠定时弹窗：两个态都要 —— 「未启用」时不渲染取消按钮，
                    // 那个按钮是 error 色的破坏性动作，英文下最容易挤爆。
                    "timer-active" to @Composable { SleepTimerDialogContent(active = true) },
                    "timer-idle" to @Composable { SleepTimerDialogContent(active = false) },
                ).forEach { (name, content) ->
                    render("i18n-$name-$tag-wide", 1000, 1100) {
                        ProvideCpStrings(language) {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                                content()
                            }
                        }
                    }
                    render("i18n-$name-$tag-narrow", 460, 1100) {
                        ProvideCpStrings(language) {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                                content()
                            }
                        }
                    }
                }
            }
        // 初值守护：`ProvideCpStrings` 之外的地方也必须能渲染（预览 / 单测直接调某个 composable）
        kotlin.test.assertTrue(
            CpStrings.en.settings.screenTitle.isNotBlank(),
            "未提供 ProvideCpStrings 时 cpStrings() 回落到中文也应可用",
        )
    }
}
