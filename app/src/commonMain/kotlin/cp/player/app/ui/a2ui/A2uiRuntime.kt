package cp.player.app.ui.a2ui

import androidx.a2ui.compose.runtime.A2uiMessageParser
import androidx.a2ui.model.processor.processInput
import androidx.a2ui.model.protocol.A2uiClientErrorMessage
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.a2ui.model.protocol.A2uiProtocolConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.material3.a2ui.A2uiSurfaceDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cp.player.app.AppModel
import cp.player.app.i18n.AiStrings
import cp.player.app.i18n.AiStringsZh
import cp.player.app.i18n.cpStrings
import cp.player.app.presentation.navigation.AppNavigator
import cp.player.app.presentation.navigation.AppRoute
import cp.player.app.ui.feedback.CpHaptic
import cp.player.app.ui.feedback.LocalCpHaptics
import cp.player.app.ui.navigation.LocalAppNavigator
import cp.player.app.ui.util.UiEvents
import cp.player.core.ai.AiConfigManager
import cp.player.core.playback.EqBand
import cp.player.core.playback.EqualizerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal val A2UI_PROTOCOL_VERSION: String = A2uiProtocolConstants.PROTOCOL_VERSION
internal const val A2UI_CATALOG_ID =
    "https://a2ui.org/specification/v0_9/catalogs/basic/catalog.json"

private const val EVENT_PLAY_TRACK = "play_track"
private const val EVENT_PLAY_TRACKS = "play_tracks"
private const val EVENT_ENQUEUE_TRACK = "enqueue_track"
private const val EVENT_ENQUEUE_ALL = "enqueue_all"
private const val EVENT_SEARCH_SIMILAR = "search_similar"
private const val EVENT_SET_EQ_PRESET = "set_eq_preset"
private const val EVENT_SEND_PROMPT = "send_prompt"

@Composable
internal expect fun rememberA2uiMessageProcessor(): androidx.a2ui.model.processor.A2uiMessageProcessor

@Composable
fun rememberA2uiRuntime(): A2uiRuntime {
    val processor = rememberA2uiMessageProcessor()
    val strings = cpStrings()
    LaunchedEffect(processor) {
        withContext(Dispatchers.Default) { processor.collectMessages() }
    }
    val runtime = remember(processor) { A2uiRuntime(processor) }
    SideEffect { runtime.strings = strings.ai }
    return runtime
}

/**
 * A2UI 的宿主薄层：只负责 v0.9 JSON 消息收发、Surface 状态和真实动作分发。
 *
 * 协议解析、数据绑定、组件绘制全部由 `ai.littlepress.a2ui` 提供，这里不再维护
 * 任何平行协议模型。
 */
class A2uiRuntime internal constructor(
    val processor: androidx.a2ui.model.processor.A2uiMessageProcessor,
) {
    private val parser = A2uiMessageParser()
    private var pendingJson = ""

    /**
     * 当前语言的文案。由 [rememberA2uiRuntime] 在每次组合后同步，
     * 这样 `generate` 与解析失败的兜底文案都能跟随应用内语言切换。
     */
    internal var strings: AiStrings = AiStringsZh

    var isGenerating by mutableStateOf(false)
        private set

    var statusLine by mutableStateOf("")
        private set

    var errorText by mutableStateOf<String?>(null)
        private set

    var receivedMessages by mutableIntStateOf(0)
        private set

    suspend fun generate(prompt: String): Result<Unit> = runCatching {
        if (isGenerating) return@runCatching
        val query = prompt.trim()
        require(query.isNotEmpty()) { strings.statusPromptRequired }

        isGenerating = true
        errorText = null
        statusLine = strings.statusPreparing
        pendingJson = ""
        receivedMessages = 0
        deleteAllSurfaces()

        try {
            val config = AiConfigManager.instance.currentConfig
            require(config.enabled) { strings.statusDisabled }
            val context = A2uiAgentContext.fromCurrentPlayer(query)

            if (config.apiKey.isBlank()) {
                require(config.offlineSimulation) { strings.statusNoKeyOfflineDisabled }
                statusLine = strings.statusLocalSearching
                feed(A2uiAgent.localSurface(context, strings).joinToString("\n"))
            } else {
                statusLine = strings.statusConnecting(config.provider.displayName)
                val result = AiConfigManager.instance.streamChat(
                    prompt = query,
                    systemPrompt = A2uiAgent.systemPrompt(context, strings),
                    onChunk = { chunk ->
                        statusLine = strings.statusReceiving
                        feed(chunk)
                    },
                    config = config,
                )
                result.exceptionOrNull()?.let { failure ->
                    if (!config.offlineSimulation) throw failure
                    statusLine = strings.statusOnlineFallback
                    deleteAllSurfaces()
                    feed(A2uiAgent.localSurface(context, strings).joinToString("\n"))
                }
                flush()
            }

            val appeared = withTimeoutOrNull(2_000) {
                while (processor.activeSurfaces.value.isEmpty()) delay(20)
                true
            } ?: false

            if (!appeared) {
                val config = AiConfigManager.instance.currentConfig
                if (config.offlineSimulation) {
                    statusLine = strings.statusNoSurfaceFallback
                    feed(A2uiAgent.localSurface(context, strings).joinToString("\n"))
                    withTimeoutOrNull(2_000) {
                        while (processor.activeSurfaces.value.isEmpty()) delay(20)
                        true
                    }
                }
            }
            check(processor.activeSurfaces.value.isNotEmpty()) {
                strings.statusNoSurface
            }
            statusLine = strings.statusReceived(receivedMessages)
        } finally {
            isGenerating = false
        }
    }.onFailure { failure ->
        errorText = failure.message ?: strings.statusFailed
        statusLine = strings.statusFailed
        isGenerating = false
    }

    fun submit(json: String) {
        val text = json.trim()
        if (text.isEmpty()) return
        runCatching {
            processor.processInput(parser, text)
            if (isRenderableMessage(text)) receivedMessages += 1
            errorText = null
        }.onFailure { failure ->
            errorText = failure.message ?: strings.statusParseFailed
        }
    }

    /**
     * `deleteSurface` 是宿主自己的清理消息，不该算进「已接收的模型消息」。
     * 计数只用于状态行展示，因此按顶层键判断足够，不必再解析一遍。
     */
    private fun isRenderableMessage(json: String): Boolean =
        !json.contains("\"deleteSurface\"")

    fun feed(chunk: String) {
        if (chunk.isEmpty()) return
        pendingJson += chunk
        val batch = A2uiJsonFramer.drain(pendingJson)
        pendingJson = batch.remainder
        batch.objects.forEach(::submit)
    }

    fun flush() {
        if (pendingJson.isBlank()) return
        val remaining = pendingJson.trim()
        pendingJson = ""
        if (remaining.startsWith("{") && remaining.endsWith("}")) submit(remaining)
    }

    fun deleteAllSurfaces() {
        processor.activeSurfaces.value
            .map { it.id }
            .forEach { surfaceId ->
                submit(
                    buildJsonObject {
                        put("version", A2UI_PROTOCOL_VERSION)
                        putJsonObject("deleteSurface") {
                            put("surfaceId", surfaceId)
                        }
                    }.toString(),
                )
            }
    }

    internal fun reportError(message: String) {
        errorText = message
    }
}

@Composable
fun A2uiSurfaceHost(
    runtime: A2uiRuntime,
    actionHandler: A2uiActionHandler,
    modifier: Modifier = Modifier,
    expressiveMotion: Boolean = true,
    emptyContent: @Composable () -> Unit = {},
) {
    val surfaces by runtime.processor.activeSurfaces.collectAsState()
    val strings by rememberUpdatedState(cpStrings())

    LaunchedEffect(runtime.processor, actionHandler) {
        runtime.processor.outboundEvents.collect { message ->
            when (message) {
                is A2uiClientEventMessage -> actionHandler.handle(message, strings.ai)
                is A2uiClientErrorMessage -> runtime.reportError(message.message)
            }
        }
    }

    if (surfaces.isEmpty()) {
        emptyContent()
        return
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        surfaces.forEach { surface ->
            key(surface.id) {
                A2uiSurface(
                    surfaceModel = surface,
                    modifier = Modifier.fillMaxWidth(),
                    transitionSpec = if (expressiveMotion) {
                        A2uiSurfaceDefaults.transitionSpec
                    } else {
                        null
                    },
                )
            }
        }
    }
}

class A2uiActionHandler internal constructor(
    private val navigator: AppNavigator,
    private val onPrompt: (String) -> Unit,
    private val performHaptic: () -> Unit,
) {
    suspend fun handle(message: A2uiClientEventMessage, strings: AiStrings) {
        val context = message.context
        when (message.type) {
            EVENT_PLAY_TRACK -> {
                val mediaIds = context.mediaIds("mediaId", "mediaIds", "tracks")
                if (mediaIds.isNotEmpty()) {
                    AppModel.playback.playQueue(mediaIds.map(AppModel::toMediaId))
                    performHaptic()
                    UiEvents.notify(strings.actionPlayStarted)
                }
            }

            EVENT_PLAY_TRACKS -> {
                val mediaIds = context.mediaIds("mediaIds", "tracks")
                if (mediaIds.isNotEmpty()) {
                    AppModel.playback.playQueue(mediaIds.map(AppModel::toMediaId))
                    performHaptic()
                    UiEvents.notify(strings.actionPlayStartedCount(mediaIds.size))
                }
            }

            EVENT_ENQUEUE_TRACK -> {
                val mediaIds = context.mediaIds("mediaId", "mediaIds")
                mediaIds.firstOrNull()?.let {
                    AppModel.playback.addToQueue(AppModel.toMediaId(it))
                    performHaptic()
                    UiEvents.notify(strings.actionEnqueued)
                }
            }

            EVENT_ENQUEUE_ALL -> {
                val mediaIds = context.mediaIds("mediaIds", "tracks")
                if (mediaIds.isNotEmpty()) {
                    mediaIds.forEach { AppModel.playback.addToQueue(AppModel.toMediaId(it)) }
                    performHaptic()
                    UiEvents.notify(strings.actionEnqueuedCount(mediaIds.size))
                }
            }

            EVENT_SEARCH_SIMILAR -> {
                val query = context.textValue("query", "q")
                    ?: AppModel.playback.state.value.currentTrack?.let { "${it.name} ${it.artist}" }
                    ?: return
                performHaptic()
                navigator.push(AppRoute.Search(query))
            }

            EVENT_SET_EQ_PRESET -> {
                val preset = context.textValue("preset", "name") ?: return
                if (applyEqPreset(preset)) {
                    performHaptic()
                    UiEvents.notify(strings.actionEqApplied(preset))
                }
            }

            EVENT_SEND_PROMPT -> {
                val prompt = context.textValue("prompt", "query") ?: return
                performHaptic()
                onPrompt(prompt)
            }
        }
    }

    private fun applyEqPreset(rawPreset: String): Boolean {
        val gains = eqPresetGains(rawPreset) ?: return false
        val current = AppModel.audioEffect()
        val bands = EqualizerConfig.DEFAULT_FREQUENCIES_HZ
            .zip(gains) { frequency, gain -> EqBand(gainDb = gain, frequencyHz = frequency) }
        AppModel.setAudioEffect(
            current.copy(
                enabled = true,
                equalizer = current.equalizer.copy(enabled = true, bands = bands),
            ),
        )
        return true
    }
}

/**
 * 把 A2UI 按钮传来的声场名映射到 5 段增益。
 *
 * 模型只能按系统提示词里的固定取值发事件，但不同模型仍可能给出同义词或中文名，
 * 因此这里同时接受英文规范名与常见中文说法。无法识别时返回 `null` —— 调用方
 * 必须保持当前声场不动，而不是把 EQ 静默重置成平直。
 */
internal fun eqPresetGains(rawPreset: String): List<Float>? {
    val preset = rawPreset.trim().lowercase()
    return when {
        preset in setOf("flat", "neutral", "平直", "原声") -> listOf(0f, 0f, 0f, 0f, 0f)
        preset in setOf("vocal", "voice", "人声") -> listOf(-2f, -1f, 3f, 2f, 0f)
        preset in setOf("bass", "低频", "重低音") -> listOf(6f, 4f, 1f, 0f, -1f)
        preset in setOf("night", "lofi", "夜间", "雨夜") -> listOf(3f, 2f, 0f, -1f, -2f)
        preset in setOf("bright", "air", "明亮", "通透") -> listOf(-1f, 0f, 1f, 3f, 4f)
        else -> null
    }
}

@Composable
fun rememberA2uiActionHandler(onPrompt: (String) -> Unit): A2uiActionHandler {
    val navigator = LocalAppNavigator.current
    val haptics = LocalCpHaptics.current
    val currentOnPrompt by rememberUpdatedState(onPrompt)
    return remember(navigator, haptics) {
        A2uiActionHandler(
            navigator = navigator,
            onPrompt = { currentOnPrompt(it) },
            performHaptic = {
                if (AiConfigManager.instance.currentConfig.hapticFeedback) {
                    haptics.perform(CpHaptic.Confirm)
                }
            },
        )
    }
}

internal data class A2uiJsonBatch(
    val objects: List<String>,
    val remainder: String,
)

internal object A2uiJsonFramer {
    fun drain(input: String): A2uiJsonBatch {
        val objects = mutableListOf<String>()
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false

        for (index in input.indices) {
            val char = input[index]
            if (start < 0) {
                if (char == '{') {
                    start = index
                    depth = 1
                }
                continue
            }

            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }

            when (char) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        objects += input.substring(start, index + 1)
                        start = -1
                    }
                }
            }
        }

        return A2uiJsonBatch(
            objects = objects,
            remainder = if (start >= 0) input.substring(start) else "",
        )
    }
}

internal fun Map<String, Any?>.textValue(vararg keys: String): String? {
    for (key in keys) {
        when (val value = this[key]) {
            is String -> if (value.isNotBlank()) return value.trim()
            is Number, is Boolean -> return value.toString()
        }
    }
    return null
}

internal fun Map<String, Any?>.mediaIds(vararg keys: String): List<String> =
    keys.flatMap { key -> this[key].asStringList() }
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()

private fun Any?.asStringList(): List<String> = when (this) {
    null -> emptyList()
    is String -> listOf(this)
    is Number, is Boolean -> listOf(toString())
    is List<*> -> flatMap { it.asStringList() }
    is Map<*, *> -> {
        val nested = listOf("mediaId", "media_id", "id", "trackId")
            .firstNotNullOfOrNull { key ->
                when (val value = this[key]) {
                    is String -> value
                    is Number -> value.toString()
                    else -> null
                }
            }
        nested?.let(::listOf).orEmpty()
    }
    else -> emptyList()
}
