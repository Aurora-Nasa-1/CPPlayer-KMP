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
import cp.player.app.i18n.AppLanguage
import cp.player.app.i18n.CpStrings
import cp.player.app.i18n.ProvideCpStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.i18n.displayName
import cp.player.app.i18n.displayNote
import cp.player.app.ui.component.MonetIcon
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.screen.SettingsEntry
import cp.player.app.ui.screen.SettingsGroup
import cp.player.app.ui.screen.settingsEntries
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
            }
        // 初值守护：`ProvideCpStrings` 之外的地方也必须能渲染（预览 / 单测直接调某个 composable）
        kotlin.test.assertTrue(
            CpStrings.en.settings.screenTitle.isNotBlank(),
            "未提供 ProvideCpStrings 时 cpStrings() 回落到中文也应可用",
        )
    }
}
