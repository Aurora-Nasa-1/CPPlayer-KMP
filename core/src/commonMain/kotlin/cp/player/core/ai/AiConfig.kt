package cp.player.core.ai

import kotlinx.serialization.Serializable

/**
 * AI 服务支持的供应商类型。
 */
@Serializable
enum class AiProviderType(
    val displayName: String,
    val defaultBaseUri: String,
    val defaultModel: String,
    val presetModels: List<String>,
    val note: String,
) {
    OPENAI(
        displayName = "OpenAI",
        defaultBaseUri = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        presetModels = listOf("gpt-4o-mini", "gpt-4o", "o1-mini", "o3-mini", "gpt-3.5-turbo"),
        note = "标准 OpenAI 兼容接口，支持 GPT-4o 等系列模型",
    ),
    DEEPSEEK(
        displayName = "DeepSeek",
        defaultBaseUri = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
        presetModels = listOf("deepseek-chat", "deepseek-reasoner"),
        note = "深度求索（DeepSeek V3 / R1），高性价比且乐理知识丰富",
    ),
    GEMINI(
        displayName = "Google Gemini",
        defaultBaseUri = "https://generativelanguage.googleapis.com/v1beta/openai",
        defaultModel = "gemini-1.5-flash",
        presetModels = listOf("gemini-1.5-flash", "gemini-1.5-pro", "gemini-2.0-flash-exp"),
        note = "Google Gemini OpenAI 兼容端点，高速响应与长上下文",
    ),
    OLLAMA(
        displayName = "Ollama (本地)",
        defaultBaseUri = "http://localhost:11434/v1",
        defaultModel = "llama3",
        presetModels = listOf("llama3", "llama3.1", "qwen2.5", "mistral", "deepseek-r1"),
        note = "本地离线大语言模型服务，保护隐私且无需外部网络",
    ),
    CUSTOM(
        displayName = "自定义 (Custom)",
        defaultBaseUri = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        presetModels = listOf("gpt-4o-mini", "gpt-4o", "deepseek-chat", "claude-3-5-sonnet"),
        note = "兼容任何 OpenAI 规范的第三方中转或企业私有部署端点",
    );
}

/**
 * AI 与 A2UI 生成式界面全局配置模型。
 */
@Serializable
data class AiConfig(
    /** 是否启用 AI 生成式界面特性 */
    val enabled: Boolean = true,
    /** 当前选中的供应商类型 */
    val provider: AiProviderType = AiProviderType.CUSTOM,
    /** OpenAI 兼容端点基地址 (Base URI) */
    val baseUri: String = AiProviderType.CUSTOM.defaultBaseUri,
    /** 访问密钥 API Key */
    val apiKey: String = "",
    /** 模型标识符 */
    val model: String = AiProviderType.CUSTOM.defaultModel,
    /** 生成随机性 Temperature (0.0 ~ 1.0) */
    val temperature: Float = 0.7f,
    /** 是否启用流式输出 (Server-Sent Events) */
    val streamEnabled: Boolean = true,
    /** 是否启用 M3 Expressive 弹性动效与波形律动 */
    val expressiveMotion: Boolean = true,
    /** 是否启用触觉反馈 */
    val hapticFeedback: Boolean = true,
    /** 无 API Key 或在线失败时，是否允许用当前音源的真实曲目做本地编排 */
    val offlineSimulation: Boolean = true,
) {
    /** API Key 的脱敏显示（如 sk-****abcd） */
    fun maskedApiKey(): String {
        if (apiKey.isBlank()) return ""
        val trimmed = apiKey.trim()
        return if (trimmed.length <= 8) {
            "****"
        } else {
            val prefix = trimmed.take(3)
            val suffix = trimmed.takeLast(4)
            "$prefix****$suffix"
        }
    }

    /** 规范化后的 Base URI（去除末尾斜杠） */
    fun normalizedBaseUri(): String {
        return baseUri.trim().trimEnd('/')
    }
}

/**
 * AI 模型生成测试的执行结果。
 */
@Serializable
data class GenerationTestResult(
    /** 测试是否成功 */
    val success: Boolean,
    /** 模型实际返回的文本或 A2UI 结构内容 */
    val content: String,
    /** 执行测试的模型标识符 */
    val model: String,
    /** 首字返回延迟 (Time to First Token, ms) */
    val firstTokenLatencyMs: Long,
    /** 完整响应总耗时 (ms) */
    val totalLatencyMs: Long,
    /** 失败时的错误详情 */
    val errorMessage: String? = null,
)
