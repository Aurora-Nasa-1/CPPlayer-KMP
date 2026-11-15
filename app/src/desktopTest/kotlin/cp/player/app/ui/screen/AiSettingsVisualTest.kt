package cp.player.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.AppLanguage
import cp.player.app.i18n.AiStringsZh
import cp.player.app.i18n.ProvideCpStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.SettingsButtonItem
import cp.player.app.ui.component.SettingsDropdownItem
import cp.player.app.ui.component.SettingsHeroCard
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSliderItem
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.component.SettingsTextInputItem
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.ai.AiProviderType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * AI 设置页版式探针。
 *
 * 设置页的判据不是「能编译」，而是**与旁边 10 个设置页同宽同距**：页宽 720dp 上限、
 * 16dp 水平内边距、行内 16/10dp 边距。这里把 `AiSettingsScreen.Content()` 里的
 * 分组结构原样渲染（不进入 Voyager、不读 `AppModel`），在手机与桌面两个宽度出图。
 *
 * 这类问题只有离屏出图能看到：例如把「接口类型」做成横向胶囊后，390dp 下
 * 中文标签会被压成一列竖排。
 */
class AiSettingsVisualTest {

    private fun render(
        width: Int,
        height: Int,
        themeMode: ThemeMode,
        content: @Composable () -> Unit,
    ): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CpTheme(themeMode = themeMode) {
                ProvideCpStrings(AppLanguage.ZH_HANS) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        content()
                    }
                }
            }
        }
        return try {
            scene.render().encodeToData()?.bytes ?: error("PNG encoding failed")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `ai settings page follows the shared settings layout`() {
        val phone = render(390, 844, ThemeMode.DARK) {
            AiSettingsPreview()
        }
        val desktop = render(1100, 900, ThemeMode.LIGHT) {
            AiSettingsPreview()
        }
        write("build/ai-settings-phone.png", phone)
        write("build/ai-settings-desktop.png", desktop)
        assertTrue(phone.size > 20_000, "手机端 AI 设置页不应为空，实际 ${phone.size} bytes")
        assertTrue(desktop.size > 20_000, "桌面端 AI 设置页不应为空，实际 ${desktop.size} bytes")
    }

    @Composable
    private fun AiSettingsPreview() {
        val s = cpStrings()
        val modelOptions = AiProviderType.CUSTOM.presetModels
        SettingsPage(Modifier.fillMaxSize()) {
            SettingsHeroCard(
                title = "gpt-4o-mini",
                subtitle = s.ai.heroSubtitle(
                    provider = s.ai.providerName(AiProviderType.CUSTOM),
                    baseUri = "https://api.openai.com/v1",
                ),
                icon = Icons.Rounded.Tune,
                badgeText = s.ai.providerName(AiProviderType.CUSTOM),
            )
            SettingsNote(s.ai.settingsIntro)

            SettingsSection(s.ai.sectionMaster) {
                SettingsSwitchItem(
                    title = s.ai.masterTitle,
                    subtitle = s.ai.masterNote,
                    checked = true,
                    onCheckedChange = {},
                    index = 0,
                    total = 1,
                )
            }

            SettingsSection(s.ai.sectionConnection) {
                SettingsDropdownItem(
                    title = s.ai.providerTitle,
                    subtitle = s.ai.providerNote(AiProviderType.CUSTOM),
                    options = AiProviderType.entries.map(s.ai::providerName),
                    selectedIndex = AiProviderType.entries.indexOf(AiProviderType.CUSTOM),
                    icon = Icons.Rounded.Link,
                    onSelect = {},
                    index = 0,
                    total = 5,
                )
                SettingsTextInputItem(
                    title = s.ai.baseUriTitle,
                    value = "https://api.openai.com/v1",
                    onCommit = {},
                    validate = { null },
                    subtitle = s.ai.baseUriNote,
                    index = 1,
                    total = 5,
                )
                SettingsTextInputItem(
                    title = s.ai.apiKeyTitle,
                    value = "sk-proj-1234567890abcdef",
                    onCommit = {},
                    validate = { null },
                    subtitle = s.ai.apiKeyNote,
                    obscureText = true,
                    index = 2,
                    total = 5,
                )
                SettingsDropdownItem(
                    title = s.ai.modelTitle,
                    subtitle = "gpt-4o-mini",
                    options = modelOptions,
                    selectedIndex = 0,
                    icon = Icons.Rounded.AutoAwesome,
                    onSelect = {},
                    index = 3,
                    total = 5,
                )
                SettingsButtonItem(
                    text = s.ai.fetchModels,
                    subtitle = s.ai.fetchModelsNote,
                    icon = Icons.Rounded.Download,
                    onClick = {},
                    index = 4,
                    total = 5,
                )
            }

            SettingsSection(s.ai.sectionGeneration) {
                SettingsSliderItem(
                    title = s.ai.temperatureTitle,
                    subtitle = s.ai.temperatureNote,
                    value = 0.7f,
                    onValueChange = {},
                    valueRange = 0f..1f,
                    steps = 9,
                    valueLabel = "70%",
                    index = 0,
                    total = 1,
                )
            }

            SettingsSection(s.ai.sectionBehavior) {
                listOf(
                    s.ai.streamTitle to s.ai.streamNote,
                    s.ai.offlineTitle to s.ai.offlineNote,
                    s.ai.motionTitle to s.ai.motionNote,
                    s.ai.hapticTitle to s.ai.hapticNote,
                ).forEachIndexed { index, (title, note) ->
                    SettingsSwitchItem(
                        title = title,
                        subtitle = note,
                        checked = true,
                        onCheckedChange = {},
                        index = index,
                        total = 4,
                    )
                }
            }

            SettingsSection(s.ai.sectionDiagnostics) {
                SettingsButtonItem(
                    text = s.ai.testConnection,
                    subtitle = s.ai.testConnectionNote,
                    icon = Icons.Rounded.Speed,
                    onClick = {},
                    index = 0,
                    total = 2,
                )
                SettingsButtonItem(
                    text = s.ai.runGenerationTest,
                    subtitle = s.ai.generationTestNote,
                    icon = Icons.Rounded.AutoAwesome,
                    onClick = {},
                    index = 1,
                    total = 2,
                )
            }

            SettingsNote(AiStringsZh.testConnectionSucceeded(128))
        }
    }

    private fun write(path: String, bytes: ByteArray) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }
}
