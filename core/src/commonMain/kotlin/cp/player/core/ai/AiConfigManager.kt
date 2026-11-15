package cp.player.core.ai

import cp.player.core.util.SettingsStorage
import cp.player.core.util.createHttpClient
import cp.player.core.util.defaultSettingsStorage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.TimeSource

/**
 * AI 服务与 A2UI 生成式界面的统一配置与通信管理器。
 */
class AiConfigManager(
    private val storage: SettingsStorage = defaultSettingsStorage(SETTINGS_NAMESPACE),
    private val httpClient: HttpClient = createHttpClient(),
) {
    companion object {
        const val SETTINGS_NAMESPACE = "cp_ai_settings"

        private const val KEY_ENABLED = "ai_enabled"
        private const val KEY_PROVIDER = "ai_provider"
        private const val KEY_BASE_URI = "ai_base_uri"
        private const val KEY_API_KEY = "ai_api_key"
        private const val KEY_MODEL = "ai_model"
        private const val KEY_TEMPERATURE = "ai_temperature"
        private const val KEY_STREAM = "ai_stream_enabled"
        private const val KEY_EXPRESSIVE_MOTION = "ai_expressive_motion"
        private const val KEY_HAPTIC = "ai_haptic_feedback"
        private const val KEY_OFFLINE_SIMULATION = "ai_offline_simulation"

        val instance: AiConfigManager by lazy { AiConfigManager() }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val _configFlow = MutableStateFlow(loadConfig())
    val configFlow: StateFlow<AiConfig> = _configFlow.asStateFlow()

    val currentConfig: AiConfig get() = _configFlow.value

    /**
     * 从本地持久化存储加载配置。
     */
    private fun loadConfig(): AiConfig {
        val providerStr = storage.getString(KEY_PROVIDER)
        val provider = runCatching {
            if (providerStr != null) AiProviderType.valueOf(providerStr) else AiProviderType.CUSTOM
        }.getOrDefault(AiProviderType.CUSTOM)

        fun getBool(key: String, def: Boolean): Boolean =
            storage.getString(key)?.toBooleanStrictOrNull() ?: def

        return AiConfig(
            enabled = getBool(KEY_ENABLED, true),
            provider = provider,
            baseUri = storage.getString(KEY_BASE_URI) ?: provider.defaultBaseUri,
            apiKey = storage.getString(KEY_API_KEY).orEmpty(),
            model = storage.getString(KEY_MODEL) ?: provider.defaultModel,
            temperature = storage.getString(KEY_TEMPERATURE)?.toFloatOrNull() ?: 0.7f,
            streamEnabled = getBool(KEY_STREAM, true),
            expressiveMotion = getBool(KEY_EXPRESSIVE_MOTION, true),
            hapticFeedback = getBool(KEY_HAPTIC, true),
            offlineSimulation = getBool(KEY_OFFLINE_SIMULATION, true),
        )
    }

    /**
     * 更新全局配置并持久化保存。
     */
    fun updateConfig(config: AiConfig) {
        storage.putString(KEY_ENABLED, config.enabled.toString())
        storage.putString(KEY_PROVIDER, config.provider.name)
        storage.putString(KEY_BASE_URI, config.baseUri)
        storage.putString(KEY_API_KEY, config.apiKey)
        storage.putString(KEY_MODEL, config.model)
        storage.putString(KEY_TEMPERATURE, config.temperature.toString())
        storage.putString(KEY_STREAM, config.streamEnabled.toString())
        storage.putString(KEY_EXPRESSIVE_MOTION, config.expressiveMotion.toString())
        storage.putString(KEY_HAPTIC, config.hapticFeedback.toString())
        storage.putString(KEY_OFFLINE_SIMULATION, config.offlineSimulation.toString())
        _configFlow.value = config
    }

    /**
     * 切换供应商预设配置。
     */
    fun selectProviderPreset(provider: AiProviderType) {
        val current = _configFlow.value
        val updated = current.copy(
            provider = provider,
            baseUri = provider.defaultBaseUri,
            model = provider.defaultModel,
        )
        updateConfig(updated)
    }

    /**
     * 测试与当前 Base URI 及 API Key 的连通性。
     *
     * @return 成功返回网络延迟 (ms)，失败返回对应异常。
     */
    suspend fun testConnection(config: AiConfig = currentConfig): Result<Long> = runCatching {
        val timeSource = TimeSource.Monotonic
        val base = config.normalizedBaseUri()
        val mark = timeSource.markNow()

        // 优先探测 /models 端点（标准 OpenAI 规范）
        val modelsUrl = "$base/models"
        val response = runCatching {
            httpClient.get(modelsUrl) {
                if (config.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${config.apiKey.trim()}")
                }
                timeout {
                    requestTimeoutMillis = 8000
                    connectTimeoutMillis = 5000
                }
            }
        }.getOrNull()

        if (response != null && (response.status.isSuccess() || response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden)) {
            val latency = mark.elapsedNow().inWholeMilliseconds
            if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
                throw IllegalStateException("认证失败 (HTTP ${response.status.value}): 请检查 API Key 是否正确")
            }
            return@runCatching latency
        }

        // 备用探测：发送最简 chat completion 探针
        val chatUrl = "$base/chat/completions"
        val probeBody = buildJsonObject {
            put("model", config.model.trim())
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "ping")
                })
            })
            put("max_tokens", 1)
        }.toString()

        val chatResponse = httpClient.post(chatUrl) {
            if (config.apiKey.isNotBlank()) {
                header("Authorization", "Bearer ${config.apiKey.trim()}")
            }
            contentType(ContentType.Application.Json)
            setBody(probeBody)
            timeout {
                requestTimeoutMillis = 10000
                connectTimeoutMillis = 6000
            }
        }

        val latency = mark.elapsedNow().inWholeMilliseconds
        if (chatResponse.status.isSuccess()) {
            latency
        } else if (chatResponse.status == HttpStatusCode.Unauthorized || chatResponse.status == HttpStatusCode.Forbidden) {
            throw IllegalStateException("认证失败 (HTTP ${chatResponse.status.value}): API Key 无效或配额超限")
        } else {
            val body = runCatching { chatResponse.bodyAsText().take(120) }.getOrDefault("")
            throw IllegalStateException("端点响应异常 (HTTP ${chatResponse.status.value}): $body")
        }
    }

    /**
     * 获取当前服务商支持或开放的可用模型标识符列表。
     *
     * 优先尝试调用 `/models` 或 Ollama 的 `/api/tags`；若失败或端点不支持，
     * 自动回退并返回当前供应商预置的常用推荐模型列表，确保界面随时可用。
     */
    suspend fun fetchAvailableModels(config: AiConfig = currentConfig): Result<List<String>> = runCatching {
        val base = config.normalizedBaseUri()
        val fetchedModels = mutableListOf<String>()

        // 1. 尝试标准 OpenAI /models 端点
        runCatching {
            val modelsUrl = "$base/models"
            val response = httpClient.get(modelsUrl) {
                if (config.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${config.apiKey.trim()}")
                }
                timeout {
                    requestTimeoutMillis = 10000
                    connectTimeoutMillis = 5000
                }
            }
            if (response.status.isSuccess()) {
                val text = response.bodyAsText()
                val parsed = json.parseToJsonElement(text).jsonObject
                val dataArray = parsed["data"]?.jsonArray
                if (dataArray != null) {
                    for (item in dataArray) {
                        val obj = item.jsonObject
                        val id = obj["id"]?.jsonPrimitive?.contentOrNull
                            ?: obj["name"]?.jsonPrimitive?.contentOrNull
                        if (!id.isNullOrBlank()) {
                            fetchedModels.add(id.trim())
                        }
                    }
                }
            }
        }

        // 2. 如果是 Ollama 且常规获取为空，尝试专用 /api/tags
        if (fetchedModels.isEmpty() && config.provider == AiProviderType.OLLAMA) {
            runCatching {
                val host = if (base.endsWith("/v1")) base.removeSuffix("/v1") else base
                val tagsUrl = "$host/api/tags"
                val response = httpClient.get(tagsUrl) {
                    timeout {
                        requestTimeoutMillis = 6000
                        connectTimeoutMillis = 4000
                    }
                }
                if (response.status.isSuccess()) {
                    val parsed = json.parseToJsonElement(response.bodyAsText()).jsonObject
                    val modelsArray = parsed["models"]?.jsonArray
                    if (modelsArray != null) {
                        for (item in modelsArray) {
                            val name = item.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                            if (!name.isNullOrBlank()) {
                                fetchedModels.add(name.trim())
                            }
                        }
                    }
                }
            }
        }

        // 3. 去重与模型排序
        val distinctList = fetchedModels.distinct()
        if (distinctList.isNotEmpty()) {
            return@runCatching distinctList
        }

        // 4. 若在线获取未果或无返回，回退到当前服务商的内置预设模型列表
        config.provider.presetModels
    }

    /**
     * 发起 OpenAI 兼容格式的流式对话请求。
     *
     * @param prompt 用户或上下文提示词
     * @param systemPrompt 可选系统指令
     * @param onChunk 实时增量文本回调
     * @return 最终聚合的完整响应内容
     */
    suspend fun streamChat(
        prompt: String,
        systemPrompt: String? = null,
        onChunk: (String) -> Unit,
        config: AiConfig = currentConfig,
    ): Result<String> = runCatching {
        require(config.apiKey.isNotBlank()) { "请先在设置中填写 API Key" }
        val base = config.normalizedBaseUri()
        val chatUrl = "$base/chat/completions"

        val requestPayload = buildJsonObject {
            put("model", config.model.trim())
            put("stream", config.streamEnabled)
            put("temperature", config.temperature)
            put("messages", buildJsonArray {
                if (!systemPrompt.isNullOrBlank()) {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                }
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }.toString()

        val fullResponse = StringBuilder()

        if (!config.streamEnabled) {
            val response = httpClient.post(chatUrl) {
                if (config.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${config.apiKey.trim()}")
                }
                contentType(ContentType.Application.Json)
                setBody(requestPayload)
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("HTTP ${response.status.value}: ${response.bodyAsText().take(200)}")
            }
            val text = response.bodyAsText()
            val parsed = json.parseToJsonElement(text).jsonObject
            val content = parsed["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
            onChunk(content)
            return@runCatching content
        }

        val response = httpClient.post(chatUrl) {
            if (config.apiKey.isNotBlank()) {
                header("Authorization", "Bearer ${config.apiKey.trim()}")
            }
            contentType(ContentType.Application.Json)
            setBody(requestPayload)
            timeout {
                requestTimeoutMillis = 60000
                socketTimeoutMillis = 30000
            }
        }

        if (!response.status.isSuccess()) {
            throw IllegalStateException("HTTP ${response.status.value}: ${response.bodyAsText().take(200)}")
        }

        val channel = response.bodyAsChannel()
        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            val trimmed = line.trim()
            if (trimmed.startsWith("data:")) {
                val dataPayload = trimmed.removePrefix("data:").trim()
                if (dataPayload == "[DONE]") break
                if (dataPayload.isNotEmpty()) {
                    runCatching {
                        val element = json.parseToJsonElement(dataPayload).jsonObject
                        val delta = element["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                            ?.get("delta")?.jsonObject
                            ?.get("content")?.jsonPrimitive?.contentOrNull
                        if (!delta.isNullOrEmpty()) {
                            fullResponse.append(delta)
                            onChunk(delta)
                        }
                    }
                }
            }
        }

        fullResponse.toString()
    }

    /**
     * 发起真实的 AI 文本与乐理生成能力测试。
     *
     * 测量首字响应延迟（TTFT）与总执行延迟。无 Key 时直接失败，不生成占位内容。
     *
     * @param prompt 测试提示词
     * @param config 目标配置
     * @param onChunk 增量内容回调
     * @return 生成测试综合结果
     */
    suspend fun runGenerationTest(
        prompt: String = "请用严谨专业且生动的一句话，介绍适合夜间沉浸的音乐风格及其声场均衡特征。",
        config: AiConfig = currentConfig,
        onChunk: (String) -> Unit = {},
    ): Result<GenerationTestResult> = runCatching {
        val timeSource = TimeSource.Monotonic
        val startTime = timeSource.markNow()
        var firstTokenTime: Long? = null

        // 在线真实请求测试
        val fullContent = StringBuilder()
        val chatResult = streamChat(
            prompt = prompt,
            systemPrompt = "你是一名资深的音乐声学工程师与乐理评论家，回答严谨精准，不包含表情符号。",
            onChunk = { chunk ->
                if (firstTokenTime == null && chunk.isNotBlank()) {
                    firstTokenTime = startTime.elapsedNow().inWholeMilliseconds
                }
                fullContent.append(chunk)
                onChunk(chunk)
            }
        )

        val totalTime = startTime.elapsedNow().inWholeMilliseconds
        if (chatResult.isSuccess) {
            GenerationTestResult(
                success = true,
                content = fullContent.toString().ifEmpty { chatResult.getOrDefault("") },
                model = config.model,
                firstTokenLatencyMs = firstTokenTime ?: totalTime,
                totalLatencyMs = totalTime,
            )
        } else {
            val ex = chatResult.exceptionOrNull()
            throw (ex ?: IllegalStateException("生成测试未产生有效响应"))
        }
    }
}
