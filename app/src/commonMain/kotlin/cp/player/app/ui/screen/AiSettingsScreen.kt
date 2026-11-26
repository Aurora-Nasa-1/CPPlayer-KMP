package cp.player.app.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import cp.player.app.ui.navigation.RoutePage
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.SettingsButtonItem
import cp.player.app.ui.component.SettingsDropdownItem
import cp.player.app.ui.component.SettingsHeroCard
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsNoteEmphasis
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSliderItem
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.component.SettingsTextInputItem
import cp.player.app.ui.feedback.CpHaptic
import cp.player.app.ui.feedback.LocalCpHaptics
import cp.player.app.ui.navigation.LocalAppNavigator
import cp.player.core.ai.AiConfigManager
import cp.player.core.ai.AiProviderType
import kotlinx.coroutines.launch

/**
 * AI 接入与 A2UI 生成式界面设置。
 *
 * ## 这里只配置「怎么连模型」，不配置「界面长什么样」
 *
 * A2UI 只服务播放器的私人 DJ 台，界面由官方 Basic Catalog 绘制。因此本页没有
 * 主题、配色、卡片样式一类的选项 —— 那些由全局主题决定。页面上剩下的每一项
 * 都直接影响一次真实请求或一次真实动作：
 *
 * | 分组 | 影响 |
 * | --- | --- |
 * | 总开关 | 关闭后私人 DJ 台不再创建 Surface |
 * | 接口与模型 | 请求发往哪里、用哪个模型、失败时回退到什么 |
 * | 生成偏好 | 请求里的 `temperature` |
 * | 行为 | 流式解析、无 Key 时的真实曲目编排、动效与触感 |
 * | 诊断 | `/models` 探测与一次真实生成 |
 *
 * ## 版式
 *
 * 全部走 [SettingsPage] + [SettingsSection] + `Settings*Item`，与其余设置页同宽同距。
 * 不自己拼卡片、不用横向胶囊：本仓库的设置页只有这一套行骨架（见 `SettingsKit` 的 KDoc）。
 * 诊断结果只出现在 [SettingsNote] 一处，按钮副标题保持静态说明 ——
 * 同一句话在行内和页尾各写一遍，读起来像两条不同的状态。
 */
class AiSettingsScreen : RoutePage {

    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.current
        val s = cpStrings()
        val manager = remember { AiConfigManager.instance }
        val config by manager.configFlow.collectAsState()
        val scope = rememberCoroutineScope()
        val haptics = LocalCpHaptics.current

        var isTesting by remember { mutableStateOf(false) }
        var testResult by remember { mutableStateOf<String?>(null) }
        var testIsSuccess by remember { mutableStateOf<Boolean?>(null) }
        var isFetchingModels by remember { mutableStateOf(false) }
        var availableModels by remember(config.provider) {
            mutableStateOf(config.provider.presetModels)
        }
        var modelError by remember { mutableStateOf<String?>(null) }
        var isGeneratingTest by remember { mutableStateOf(false) }
        var generationOutput by remember { mutableStateOf("") }
        var generationNotice by remember { mutableStateOf<String?>(null) }
        var generationError by remember { mutableStateOf<String?>(null) }

        val providerOptions = AiProviderType.entries.map(s.ai::providerName)
        val selectedProviderIndex = AiProviderType.entries
            .indexOfFirst { it == config.provider }
            .coerceAtLeast(0)
        val modelOptions = remember(availableModels, config.model, config.provider) {
            (availableModels + config.model + config.provider.defaultModel)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
        }
        val selectedModelIndex = modelOptions
            .indexOfFirst { it == config.model }
            .takeIf { it >= 0 } ?: 0
        var temperatureDraft by remember(config.temperature) {
            mutableStateOf(config.temperature)
        }

        fun confirmHaptic() {
            if (config.hapticFeedback) haptics.perform(CpHaptic.Confirm)
        }

        CpRouteScaffold(
            title = s.ai.settingsTitle,
            onBack = { navigator.pop() },
        ) { pageModifier ->
            SettingsPage(pageModifier) {
                SettingsHeroCard(
                    title = when {
                        !config.enabled -> s.ai.heroDisabled
                        config.apiKey.isBlank() -> s.ai.heroNoKey
                        else -> config.model
                    },
                    subtitle = if (config.enabled) {
                        s.ai.heroSubtitle(
                            provider = s.ai.providerName(config.provider),
                            baseUri = config.baseUri,
                        )
                    } else {
                        s.ai.masterNote
                    },
                    icon = Icons.Rounded.Tune,
                    badgeText = if (config.enabled && config.apiKey.isNotBlank()) {
                        s.ai.providerName(config.provider)
                    } else {
                        null
                    },
                )
                SettingsNote(s.ai.settingsIntro)

                SettingsSection(s.ai.sectionMaster) {
                    SettingsSwitchItem(
                        title = s.ai.masterTitle,
                        subtitle = s.ai.masterNote,
                        checked = config.enabled,
                        onCheckedChange = { manager.updateConfig(config.copy(enabled = it)) },
                        index = 0,
                        total = 1,
                    )
                }

                if (!config.enabled) return@SettingsPage

                SettingsSection(s.ai.sectionConnection) {
                    val modelRowTotal = 5
                    SettingsDropdownItem(
                        title = s.ai.providerTitle,
                        subtitle = s.ai.providerNote(config.provider),
                        options = providerOptions,
                        selectedIndex = selectedProviderIndex,
                        icon = Icons.Rounded.Link,
                        onSelect = { index ->
                            confirmHaptic()
                            val preset = AiProviderType.entries[index]
                            manager.selectProviderPreset(preset)
                            availableModels = preset.presetModels
                            modelError = null
                            testResult = null
                            testIsSuccess = null
                            generationOutput = ""
                            generationNotice = null
                            generationError = null
                        },
                        index = 0,
                        total = modelRowTotal,
                    )
                    SettingsTextInputItem(
                        title = s.ai.baseUriTitle,
                        value = config.baseUri,
                        onCommit = { value ->
                            manager.updateConfig(config.copy(baseUri = value.trim()))
                        },
                        validate = { value ->
                            if (value.isBlank()) s.ai.baseUriRequired else null
                        },
                        subtitle = s.ai.baseUriNote,
                        index = 1,
                        total = modelRowTotal,
                    )
                    SettingsTextInputItem(
                        title = s.ai.apiKeyTitle,
                        value = config.apiKey,
                        onCommit = { value ->
                            manager.updateConfig(config.copy(apiKey = value.trim()))
                        },
                        validate = { null },
                        subtitle = s.ai.apiKeyNote,
                        obscureText = true,
                        index = 2,
                        total = modelRowTotal,
                    )
                    SettingsDropdownItem(
                        title = s.ai.modelTitle,
                        subtitle = config.model,
                        options = modelOptions,
                        selectedIndex = selectedModelIndex,
                        icon = Icons.Rounded.AutoAwesome,
                        onSelect = { index ->
                            confirmHaptic()
                            manager.updateConfig(config.copy(model = modelOptions[index]))
                        },
                        index = 3,
                        total = modelRowTotal,
                    )
                    SettingsButtonItem(
                        text = if (isFetchingModels) s.ai.fetchingModels else s.ai.fetchModels,
                        subtitle = s.ai.fetchModelsNote,
                        icon = Icons.Rounded.Download,
                        enabled = !isFetchingModels,
                        index = 4,
                        total = modelRowTotal,
                        onClick = {
                            isFetchingModels = true
                            modelError = null
                            confirmHaptic()
                            scope.launch {
                                val result = manager.fetchAvailableModels()
                                isFetchingModels = false
                                result.fold(
                                    onSuccess = { models ->
                                        availableModels = models
                                    },
                                    onFailure = { failure ->
                                        modelError = failure.message ?: s.ai.fetchModelsFailed
                                    },
                                )
                            }
                        },
                    )
                }

                SettingsSection(s.ai.sectionGeneration) {
                    SettingsSliderItem(
                        title = s.ai.temperatureTitle,
                        subtitle = s.ai.temperatureNote,
                        value = temperatureDraft,
                        onValueChange = { value -> temperatureDraft = value },
                        onValueChangeFinished = {
                            manager.updateConfig(config.copy(temperature = temperatureDraft))
                        },
                        valueRange = 0f..1f,
                        steps = 9,
                        valueLabel = "${(temperatureDraft * 100).toInt()}%",
                        index = 0,
                        total = 1,
                    )
                }

                SettingsSection(s.ai.sectionBehavior) {
                    SettingsSwitchItem(
                        title = s.ai.streamTitle,
                        subtitle = s.ai.streamNote,
                        checked = config.streamEnabled,
                        onCheckedChange = {
                            manager.updateConfig(config.copy(streamEnabled = it))
                        },
                        index = 0,
                        total = 4,
                    )
                    SettingsSwitchItem(
                        title = s.ai.offlineTitle,
                        subtitle = s.ai.offlineNote,
                        checked = config.offlineSimulation,
                        onCheckedChange = {
                            manager.updateConfig(config.copy(offlineSimulation = it))
                        },
                        index = 1,
                        total = 4,
                    )
                    SettingsSwitchItem(
                        title = s.ai.motionTitle,
                        subtitle = s.ai.motionNote,
                        checked = config.expressiveMotion,
                        onCheckedChange = {
                            manager.updateConfig(config.copy(expressiveMotion = it))
                        },
                        index = 2,
                        total = 4,
                    )
                    SettingsSwitchItem(
                        title = s.ai.hapticTitle,
                        subtitle = s.ai.hapticNote,
                        checked = config.hapticFeedback,
                        onCheckedChange = {
                            manager.updateConfig(config.copy(hapticFeedback = it))
                        },
                        index = 3,
                        total = 4,
                    )
                }

                SettingsSection(s.ai.sectionDiagnostics) {
                    SettingsButtonItem(
                        text = if (isTesting) s.ai.testingConnection else s.ai.testConnection,
                        subtitle = s.ai.testConnectionNote,
                        icon = Icons.Rounded.Speed,
                        enabled = !isTesting,
                        index = 0,
                        total = 2,
                        onClick = {
                            isTesting = true
                            testResult = null
                            testIsSuccess = null
                            confirmHaptic()
                            scope.launch {
                                val result = manager.testConnection()
                                isTesting = false
                                result.fold(
                                    onSuccess = { latency ->
                                        testIsSuccess = true
                                        testResult = s.ai.testConnectionSucceeded(latency)
                                    },
                                    onFailure = { failure ->
                                        testIsSuccess = false
                                        testResult = failure.message ?: s.ai.testConnectionFailed
                                    },
                                )
                            }
                        },
                    )
                    SettingsButtonItem(
                        text = if (isGeneratingTest) {
                            s.ai.runningGenerationTest
                        } else {
                            s.ai.runGenerationTest
                        },
                        subtitle = s.ai.generationTestNote,
                        icon = Icons.Rounded.AutoAwesome,
                        enabled = !isGeneratingTest,
                        index = 1,
                        total = 2,
                        onClick = {
                            isGeneratingTest = true
                            generationOutput = ""
                            generationNotice = null
                            generationError = null
                            confirmHaptic()
                            scope.launch {
                                val result = manager.runGenerationTest(
                                    onChunk = { chunk -> generationOutput += chunk },
                                )
                                isGeneratingTest = false
                                result.fold(
                                    onSuccess = { test ->
                                        generationOutput = test.content
                                        generationNotice = s.ai.generationTestSummary(
                                            model = test.model,
                                            firstTokenMs = test.firstTokenLatencyMs,
                                            totalMs = test.totalLatencyMs,
                                        )
                                    },
                                    onFailure = { failure ->
                                        generationError = failure.message ?: s.ai.generationTestFailed
                                    },
                                )
                            }
                        },
                    )
                }

                // 诊断结果只在这里出现一次：按钮副标题保持静态说明。
                testResult?.let { message ->
                    SettingsNote(
                        text = message,
                        emphasis = if (testIsSuccess == true) {
                            SettingsNoteEmphasis.INFO
                        } else {
                            SettingsNoteEmphasis.ERROR
                        },
                    )
                }

                modelError?.let { message ->
                    SettingsNote(
                        text = message,
                        emphasis = SettingsNoteEmphasis.ERROR,
                    )
                }

                generationError?.let { message ->
                    SettingsNote(
                        text = message,
                        emphasis = SettingsNoteEmphasis.ERROR,
                    )
                }

                generationNotice?.let { notice ->
                    SettingsNote(notice)
                }

                if (generationOutput.isNotBlank()) {
                    SettingsNote(generationOutput)
                }
            }
        }
    }
}
