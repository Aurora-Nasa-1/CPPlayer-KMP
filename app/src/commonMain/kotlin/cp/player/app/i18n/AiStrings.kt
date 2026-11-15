package cp.player.app.i18n

import cp.player.core.ai.AiProviderType

/**
 * AI 接入与 A2UI 生成式界面的文案。
 *
 * 单独成组的原因：这批文案同时被三处消费 —— 设置页、播放器「私人 DJ 台」弹层、
 * 以及运行期的生成状态（`A2uiRuntime` 在组合外写状态行）。三者必须用同一套说法，
 * 否则「私人 DJ 台」在入口、标题和空态里会出现三种叫法。
 */
interface AiStrings {
    // —— 设置入口 ——
    val settingsTitle: String
    val settingsSubtitle: String

    // —— 设置页总览 ——
    val settingsIntro: String
    val heroNoKey: String
    val heroDisabled: String
    fun heroSubtitle(provider: String, baseUri: String): String
    val sectionMaster: String
    val masterTitle: String
    val masterNote: String

    // —— 接口与模型 ——
    val sectionConnection: String
    val providerTitle: String
    fun providerName(provider: AiProviderType): String
    fun providerNote(provider: AiProviderType): String
    val baseUriTitle: String
    val baseUriNote: String
    val baseUriRequired: String
    val apiKeyTitle: String
    val apiKeyNote: String
    val modelTitle: String
    val fetchModels: String
    val fetchingModels: String
    val fetchModelsNote: String
    val fetchModelsFailed: String

    // —— 生成偏好与行为 ——
    val sectionGeneration: String
    val temperatureTitle: String
    val temperatureNote: String
    val sectionBehavior: String
    val streamTitle: String
    val streamNote: String
    val offlineTitle: String
    val offlineNote: String
    val motionTitle: String
    val motionNote: String
    val hapticTitle: String
    val hapticNote: String

    // —— 诊断 ——
    val sectionDiagnostics: String
    val testConnection: String
    val testingConnection: String
    val testConnectionNote: String
    fun testConnectionSucceeded(latencyMs: Long): String
    val testConnectionFailed: String
    val runGenerationTest: String
    val runningGenerationTest: String
    val generationTestNote: String
    fun generationTestSummary(model: String, firstTokenMs: Long, totalMs: Long): String
    val generationTestFailed: String

    // —— 私人 DJ 台 ——
    val djDeskTitle: String
    val djDeskEntryNote: String
    val djNowPlayingFallback: String
    val djPlayNow: String
    val djPlayNext: String
    val djFindSimilar: String
    val djAskAi: String
    fun djAskAiPrompt(title: String, artist: String): String
    fun djFindSimilarPrompt(title: String, artist: String): String
    val djAiSectionTitle: String
    val djSoundTitle: String
    val djEqVocal: String
    val djEqNight: String
    val djEqFlat: String
    val djQueueTitle: String
    val djPlay: String
    val djEnqueue: String
    val djEmptyNoTrack: String
    val djEmptyDisabled: String
    val djGenerating: String

    // —— 本地编排 Surface ——
    fun djSynthTitle(prompt: String): String
    val djSynthSearchedIntro: String
    val djSynthContextIntro: String
    val djSynthNowTitle: String
    val djSynthRecommendedTitle: String
    val djSynthEmptyTracks: String
    val djSynthSearchAction: String

    // —— 运行状态 ——
    val statusPromptRequired: String
    val statusDisabled: String
    val statusPreparing: String
    val statusNoKeyOfflineDisabled: String
    val statusLocalSearching: String
    fun statusConnecting(provider: String): String
    val statusReceiving: String
    val statusOnlineFallback: String
    val statusNoSurfaceFallback: String
    val statusNoSurface: String
    fun statusReceived(count: Int): String
    val statusFailed: String
    val statusParseFailed: String

    // —— 动作反馈 ——
    val actionPlayStarted: String
    fun actionPlayStartedCount(count: Int): String
    val actionEnqueued: String
    fun actionEnqueuedCount(count: Int): String
    fun actionEqApplied(preset: String): String

    /** 供 A2UI 系统提示词声明「界面文案该用哪种语言、哪些措辞不要出现」。 */
    val interfaceLanguageDirective: String
}

object AiStringsZh : AiStrings {
    override val settingsTitle = "AI 与 A2UI"
    override val settingsSubtitle = "模型接入 · 私人 DJ 台与真实播放动作"

    override val settingsIntro =
        "A2UI 只服务播放器的私人 DJ 台：界面由官方 Basic Catalog 解析绘制，按钮直接接入真实播放、队列与声场。" +
            "下面的接口预设只负责填入基地址与默认模型，之后仍可手动改写。"
    override val heroNoKey = "等待填写 API Key"
    override val heroDisabled = "生成式界面已关闭"
    override fun heroSubtitle(provider: String, baseUri: String) = "$provider · $baseUri"
    override val sectionMaster = "总开关"
    override val masterTitle = "启用 AI 生成式界面"
    override val masterNote = "关闭后私人 DJ 台不再生成界面"

    override val sectionConnection = "接口与模型"
    override val providerTitle = "接口预设"
    override fun providerName(provider: AiProviderType) = when (provider) {
        AiProviderType.OPENAI -> "OpenAI"
        AiProviderType.DEEPSEEK -> "DeepSeek"
        AiProviderType.GEMINI -> "Gemini"
        AiProviderType.OLLAMA -> "Ollama"
        AiProviderType.CUSTOM -> "自定义端点"
    }
    override fun providerNote(provider: AiProviderType) = when (provider) {
        AiProviderType.OPENAI -> "OpenAI 官方端点，兼容 GPT 系列"
        AiProviderType.DEEPSEEK -> "DeepSeek 官方端点，中文与乐理表现稳定"
        AiProviderType.GEMINI -> "Google Gemini 的 OpenAI 兼容端点，长上下文"
        AiProviderType.OLLAMA -> "本机运行的离线模型，不需要外部网络"
        AiProviderType.CUSTOM -> "任意兼容 OpenAI 规范的第三方或私有端点"
    }
    override val baseUriTitle = "基地址"
    override val baseUriNote = "OpenAI 兼容端点，例如 https://api.openai.com/v1"
    override val baseUriRequired = "请输入接口基地址"
    override val apiKeyTitle = "API Key"
    override val apiKeyNote = "只保存在本机私有存储，不会上传"
    override val modelTitle = "模型"
    override val fetchModels = "获取模型列表"
    override val fetchingModels = "获取模型中"
    override val fetchModelsNote = "从当前端点读取可用模型，失败时回退到内置推荐"
    override val fetchModelsFailed = "获取模型列表失败"

    override val sectionGeneration = "生成偏好"
    override val temperatureTitle = "Temperature"
    override val temperatureNote = "数值越高，编排越自由；越低越贴近固定结构"
    override val sectionBehavior = "行为"
    override val streamTitle = "流式输出"
    override val streamNote = "边接收边解析 A2UI 消息，首屏更快"
    override val offlineTitle = "无 Key 时用真实曲目编排"
    override val offlineNote = "未配置密钥或在线失败时，只从当前音源的真实结果编排，不生成假曲目"
    override val motionTitle = "界面动效"
    override val motionNote = "关闭后不再播放 A2UI 结构切换动画"
    override val hapticTitle = "触觉反馈"
    override val hapticNote = "播放、入队与声场动作的确认反馈（仅支持的设备）"

    override val sectionDiagnostics = "诊断"
    override val testConnection = "测试连接"
    override val testingConnection = "测试中"
    override val testConnectionNote = "检查认证、配额与端点延迟"
    override fun testConnectionSucceeded(latencyMs: Long) = "连接成功 · ${latencyMs}ms"
    override val testConnectionFailed = "连接失败"
    override val runGenerationTest = "生成测试"
    override val runningGenerationTest = "发送中"
    override val generationTestNote = "检查模型是否能按 A2UI 结构返回内容"
    override fun generationTestSummary(model: String, firstTokenMs: Long, totalMs: Long) =
        "$model · 首字 ${firstTokenMs}ms · 总计 ${totalMs}ms"
    override val generationTestFailed = "生成测试失败"

    override val djDeskTitle = "私人 DJ 台"
    override val djDeskEntryNote = "A2UI 实时编排 · 歌曲解读 · 队列与声场"
    override val djNowPlayingFallback = "正在播放"
    override val djPlayNow = "立即播放"
    override val djPlayNext = "下一首播放"
    override val djFindSimilar = "找相似"
    override val djAskAi = "让 AI 解读这首歌"
    override fun djAskAiPrompt(title: String, artist: String) =
        "请从编曲、声场、情绪和适合的聆听场景出发，解读《$title》（$artist）。不要编造曲目数据。"
    override fun djFindSimilarPrompt(title: String, artist: String) =
        "请围绕《$title》（$artist）的听感，从当前音源里找几首真正相似、可以直接播放的曲目。不要编造曲目。"
    override val djAiSectionTitle = "AI 解读与编排"
    override val djSoundTitle = "快速声场"
    override val djEqVocal = "人声"
    override val djEqNight = "夜间"
    override val djEqFlat = "平直"
    override val djQueueTitle = "队列接下来"
    override val djPlay = "播放"
    override val djEnqueue = "加入队列"
    override val djEmptyNoTrack = "暂无播放曲目。先播放一首歌，再打开私人 DJ 台。"
    override val djEmptyDisabled = "AI 生成式界面已在设置中关闭。"
    override val djGenerating = "正在生成"

    override fun djSynthTitle(prompt: String) = "为「$prompt」编排"
    override val djSynthSearchedIntro = "以下曲目来自当前音源的真实搜索结果，可直接播放或加入队列。"
    override val djSynthContextIntro =
        "已读取当前播放上下文；没有检索到新的真实曲目时只提供搜索入口，不会生成假曲目。"
    override val djSynthNowTitle = "当前播放"
    override val djSynthRecommendedTitle = "推荐曲目"
    override val djSynthEmptyTracks = "当前音源没有返回可播放曲目。可以继续搜索，或换一个音源后再试。"
    override val djSynthSearchAction = "用这个描述去搜索"

    override val statusPromptRequired = "请输入听歌场景"
    override val statusDisabled = "AI 生成式界面已在设置中关闭"
    override val statusPreparing = "正在准备 A2UI 上下文"
    override val statusNoKeyOfflineDisabled = "未配置 API Key，且无 Key 编排已关闭"
    override val statusLocalSearching = "未配置 API Key，正在从真实音乐库检索并编排"
    override fun statusConnecting(provider: String) = "正在连接 $provider"
    override val statusReceiving = "正在接收 A2UI v0.9 消息"
    override val statusOnlineFallback = "在线推理失败，已回退到真实曲目本地编排"
    override val statusNoSurfaceFallback = "模型未返回可渲染界面，已改用真实曲目本地编排"
    override val statusNoSurface = "没有收到可渲染的 A2UI v0.9 消息"
    override fun statusReceived(count: Int) = "已接收 $count 条 A2UI 消息"
    override val statusFailed = "生成失败"
    override val statusParseFailed = "无法解析 A2UI 消息"

    override val actionPlayStarted = "开始播放所选曲目"
    override fun actionPlayStartedCount(count: Int) = "已开始播放 $count 首曲目"
    override val actionEnqueued = "已加入播放队列"
    override fun actionEnqueuedCount(count: Int) = "已加入 $count 首曲目"
    override fun actionEqApplied(preset: String) = "已应用 $preset 声场"

    override val interfaceLanguageDirective =
        "界面文案必须使用简体中文，简洁、可扫读；不要写“模拟”“假数据”之类的占位内容。"
}

object AiStringsEn : AiStrings {
    override val settingsTitle = "AI & A2UI"
    override val settingsSubtitle = "Model access · private DJ desk with real playback actions"

    override val settingsIntro =
        "A2UI powers the player's private DJ desk only. The official Basic Catalog renders the surface, " +
            "and its buttons drive real playback, queue and sound-field actions. " +
            "Presets below only fill in a base URI and default model; both stay editable."
    override val heroNoKey = "Waiting for an API key"
    override val heroDisabled = "Generated interfaces are off"
    override fun heroSubtitle(provider: String, baseUri: String) = "$provider · $baseUri"
    override val sectionMaster = "Master switch"
    override val masterTitle = "Enable AI-generated interfaces"
    override val masterNote = "When off, the private DJ desk stops generating surfaces"

    override val sectionConnection = "Endpoint & model"
    override val providerTitle = "Endpoint preset"
    override fun providerName(provider: AiProviderType) = when (provider) {
        AiProviderType.OPENAI -> "OpenAI"
        AiProviderType.DEEPSEEK -> "DeepSeek"
        AiProviderType.GEMINI -> "Gemini"
        AiProviderType.OLLAMA -> "Ollama"
        AiProviderType.CUSTOM -> "Custom endpoint"
    }
    override fun providerNote(provider: AiProviderType) = when (provider) {
        AiProviderType.OPENAI -> "Official OpenAI endpoint, GPT model family"
        AiProviderType.DEEPSEEK -> "Official DeepSeek endpoint, solid Chinese and music knowledge"
        AiProviderType.GEMINI -> "Google Gemini's OpenAI-compatible endpoint, long context"
        AiProviderType.OLLAMA -> "Models running locally; no external network needed"
        AiProviderType.CUSTOM -> "Any third-party or private endpoint that follows the OpenAI spec"
    }
    override val baseUriTitle = "Base URI"
    override val baseUriNote = "OpenAI-compatible endpoint, e.g. https://api.openai.com/v1"
    override val baseUriRequired = "Enter the endpoint base URI"
    override val apiKeyTitle = "API key"
    override val apiKeyNote = "Stored only in this device's private storage"
    override val modelTitle = "Model"
    override val fetchModels = "Fetch model list"
    override val fetchingModels = "Fetching models"
    override val fetchModelsNote = "Read models from the endpoint; falls back to built-in suggestions"
    override val fetchModelsFailed = "Could not fetch the model list"

    override val sectionGeneration = "Generation"
    override val temperatureTitle = "Temperature"
    override val temperatureNote = "Higher values allow freer layouts; lower values stay closer to a fixed structure"
    override val sectionBehavior = "Behavior"
    override val streamTitle = "Streaming"
    override val streamNote = "Parse A2UI messages as they arrive for a faster first frame"
    override val offlineTitle = "Arrange real tracks without a key"
    override val offlineNote =
        "Without a key, or when the online request fails, arrange only real results from the active provider"
    override val motionTitle = "Surface motion"
    override val motionNote = "Turn off to disable A2UI structure transitions"
    override val hapticTitle = "Haptic feedback"
    override val hapticNote = "Confirmation feedback for playback, queue and sound-field actions where supported"

    override val sectionDiagnostics = "Diagnostics"
    override val testConnection = "Test connection"
    override val testingConnection = "Testing"
    override val testConnectionNote = "Check authentication, quota and endpoint latency"
    override fun testConnectionSucceeded(latencyMs: Long) = "Connected · ${latencyMs}ms"
    override val testConnectionFailed = "Connection failed"
    override val runGenerationTest = "Test generation"
    override val runningGenerationTest = "Sending"
    override val generationTestNote = "Check whether the model can answer in the A2UI structure"
    override fun generationTestSummary(model: String, firstTokenMs: Long, totalMs: Long) =
        "$model · first token ${firstTokenMs}ms · total ${totalMs}ms"
    override val generationTestFailed = "Generation test failed"

    override val djDeskTitle = "Private DJ desk"
    override val djDeskEntryNote = "Live A2UI arrangement · track notes · queue and sound field"
    override val djNowPlayingFallback = "Now playing"
    override val djPlayNow = "Play now"
    override val djPlayNext = "Play next"
    override val djFindSimilar = "Find similar"
    override val djAskAi = "Ask AI about this track"
    override fun djAskAiPrompt(title: String, artist: String) =
        "Describe \"$title\" by $artist in terms of arrangement, sound stage, mood and listening context. " +
            "Do not invent track data."
    override fun djFindSimilarPrompt(title: String, artist: String) =
        "Find a few genuinely similar, playable tracks from the active provider for \"$title\" by $artist. " +
            "Do not invent tracks."
    override val djAiSectionTitle = "AI notes & arrangement"
    override val djSoundTitle = "Quick sound field"
    override val djEqVocal = "Vocal"
    override val djEqNight = "Night"
    override val djEqFlat = "Flat"
    override val djQueueTitle = "Up next"
    override val djPlay = "Play"
    override val djEnqueue = "Add to queue"
    override val djEmptyNoTrack = "Nothing is playing. Start a track before opening the private DJ desk."
    override val djEmptyDisabled = "AI-generated interfaces are disabled in Settings."
    override val djGenerating = "Generating"

    override fun djSynthTitle(prompt: String) = "Arranged for \"$prompt\""
    override val djSynthSearchedIntro =
        "These tracks come from real search results on the active provider. Play them or add them to the queue."
    override val djSynthContextIntro =
        "The current playback context was read. When no new real tracks are found, only a search entry is offered."
    override val djSynthNowTitle = "Now playing"
    override val djSynthRecommendedTitle = "Recommended tracks"
    override val djSynthEmptyTracks =
        "The active provider returned no playable tracks. Search again or switch providers and retry."
    override val djSynthSearchAction = "Search with this description"

    override val statusPromptRequired = "Describe the listening scenario"
    override val statusDisabled = "AI-generated interfaces are disabled in Settings"
    override val statusPreparing = "Preparing the A2UI context"
    override val statusNoKeyOfflineDisabled = "No API key configured, and local arranging is turned off"
    override val statusLocalSearching = "No API key; arranging real results from the music library"
    override fun statusConnecting(provider: String) = "Connecting to $provider"
    override val statusReceiving = "Receiving A2UI v0.9 messages"
    override val statusOnlineFallback = "Online request failed; fell back to real local tracks"
    override val statusNoSurfaceFallback = "The model returned no renderable surface; using real local tracks"
    override val statusNoSurface = "No renderable A2UI v0.9 messages were received"
    override fun statusReceived(count: Int) =
        if (count == 1) "Received 1 A2UI message" else "Received $count A2UI messages"
    override val statusFailed = "Generation failed"
    override val statusParseFailed = "Could not parse the A2UI message"

    override val actionPlayStarted = "Playing the selected track"
    override fun actionPlayStartedCount(count: Int) =
        if (count == 1) "Playing 1 track" else "Playing $count tracks"
    override val actionEnqueued = "Added to the queue"
    override fun actionEnqueuedCount(count: Int) =
        if (count == 1) "Added 1 track to the queue" else "Added $count tracks to the queue"
    override fun actionEqApplied(preset: String) = "Applied the $preset sound field"

    override val interfaceLanguageDirective =
        "Write every user-visible string in English. Keep it concise and scannable, " +
            "and never use placeholder wording such as \"mock\" or \"fake data\"."
}
