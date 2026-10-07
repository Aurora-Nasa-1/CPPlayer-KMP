package cp.player.core

import cp.player.core.api.AmllTtmlClient
import cp.player.core.api.LyricsSourceMode
import cp.player.core.api.MusicApiService
import cp.player.core.api.MusicApiServiceImpl
import cp.player.core.cache.ApiCache
import cp.player.core.cache.CacheConfig
import cp.player.core.cache.CacheStats
import cp.player.core.cache.CachedMusicApiService
import cp.player.core.cache.InMemoryApiCache
import cp.player.core.control.ExternalPusher
import cp.player.core.control.LocalServer
import cp.player.core.control.LocalServerConfig
import cp.player.core.control.LocalServerConfigStore
import cp.player.core.control.LocalServerStatus
import cp.player.core.control.PushResult
import cp.player.core.control.PushTrack
import cp.player.core.control.StreamTarget
import cp.player.core.control.createExternalPusher
import cp.player.core.control.createLocalServer
import cp.player.core.control.resolveAdvertisedHost
import cp.player.core.download.MediaDownloadManager
import cp.player.core.download.MediaDownloadManagerImpl
import cp.player.core.integration.IntegrationDescriptorWriter
import cp.player.core.integration.IntegrationPlaybackControl
import cp.player.core.integration.IntegrationProviderInfo
import cp.player.core.integration.IntegrationService
import cp.player.core.integration.createIntegrationDescriptorWriter
import cp.player.core.integration.createIntegrationRoutes
import cp.player.core.local.LocalMediaSource
import cp.player.core.local.ScanProgress
import cp.player.core.local.createLocalMediaSource
import cp.player.core.media.LocalMediaItem
import cp.player.core.monitor.HealthMonitor
import cp.player.core.playback.PlaybackController
import cp.player.core.playback.PlaybackControllerImpl
import cp.player.core.playback.SilentOutputPlayer
import cp.player.core.playback.StreamLocalizer
import cp.player.core.playback.createPlatformPlayer
import cp.player.core.playback.createStreamLocalizer
import cp.player.core.provider.BackendProvider
import cp.player.core.provider.ModuleManager
import cp.player.core.provider.ProviderCookieStorage
import cp.player.core.provider.ProviderManager
import cp.player.core.util.PlatformContext
import cp.player.core.util.SettingsStorage
import cp.player.core.util.defaultSettingsStorage
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/** AMLL TTML 磁盘缓存的 SettingsStorage namespace（与主设置 / cookie 隔离）。 */
private const val AMLL_CACHE_NAMESPACE = "amll_ttml_cache"

/**
 * CPPlayer 后端统一入口（KMP 版）。
 *
 * **前端唯一依赖的后端类型。** 所有音乐数据访问、Provider 管理、播放控制
 * 都应通过此对象进行，禁止直接触碰 [ProviderManager] / [ModuleManager] 等内部组件。
 *
 * ### 职责
 * 1. **生命周期 + 状态机**：通过 [stateFlow] 暴露 [BackendState]，
 *    自动管理初始化、Provider 激活、错误恢复。
 * 2. **Provider 管理**：导入 / 切换 / 删除音源模块，导入时自动激活首个 Provider。
 * 3. **音乐数据访问**：通过 [musicApi] / [cachedMusicApi] 提供云音乐 API（原 [MusicApiService]），
 *    后续将逐步迁移到更高层的 [MusicSource]（待实现，见 below）。
 * 4. **本地媒体**：通过 [localMedia] 访问平台本地媒体源（扫描 / 导入 / 下载登记），
 *    并经由 [unifiedSource] 参与统一 MediaId 路由（[localMusic] 为兼容别名）。
 * 5. **播放控制**：通过 [playbackController] 访问；实际播放由平台 [createPlatformPlayer] 提供。
 * 6. **健康监控**：通过 [health] 访问带 [HealthMonitor.HealthLevel] 三级分类的 API 健康数据。
 * 7. **错误处理**：所有可失败操作返回 [BackendResult]，UI 可穷举 [Success]/[Error]/[Unsupported]。
 * 8. **下载管理**：通过 [downloadManager] 提供媒体下载（入队 / 暂停 / 断点续传 / 重试），
 *    下载完成的产物自动登记到 [localMedia]（source = DOWNLOADED）。
 *
 * ### 使用约定
 * ```kotlin
 * // 平台 Application.onCreate 中：
 * val backend = MusicBackend.init(context, settings)
 *
 * // UI 观察：
 * val state by backend.stateFlow.collectAsState()
 * when (state) {
 *     is BackendState.NoProvider -> showOnboardingScreen()
 *     is BackendState.Ready -> showMainScreen()
 *     ...
 * }
 *
 * // 导入模块后自动激活：
 * val result = backend.importModule(zipPath)
 * when (result) {
 *     is ImportResult.Activated -> showToast("已激活 ${result.provider.name}")
 *     is ImportResult.Loaded -> showToast("已导入 ${result.provider.name}（当前仍使用 ${backend.activeProvider()?.name}）")
 *     is ImportResult.Failed -> showError(result.message)
 * }
 * ```
 *
 * @see BackendState 状态机定义
 * @see BackendResult 类型安全操作结果
 * @see ImportResult 导入结果（含自动激活语义）
 */
class MusicBackend private constructor(
    private val context: PlatformContext,
    private val settings: SettingsStorage,
    private val providerManager: ProviderManager,
    private val moduleManager: ModuleManager,
    private val musicApiImpl: MusicApiServiceImpl,
    private val cachedMusicApi: CachedMusicApiService,
    private val cache: ApiCache,
) {

    /** 模块内可见：供 [MusicApiServiceFactory] 兼容层转发。 */
    internal val providerManagerInternal get() = providerManager
    internal val moduleManagerInternal get() = moduleManager
    internal val apiImplInternal get() = musicApiImpl
    internal val cachedApiInternal get() = cachedMusicApi

    /**
     * Cookie 存储（按 Provider 隔离账号）。前端登录/登出时直接使用。
     */
    val cookieStorage: ProviderCookieStorage get() = providerManager.cookieStorage

    /**
     * 平台上下文（Android: `Context`；Desktop: 占位对象）。
     *
     * 刻意暴露给应用层：需要在应用数据目录下落盘的模块（听歌记录、
     * 将来的设备同步日志）必须能拿到它去解析目录，否则每个模块都要自己
     * 想办法搞一个 Context —— 那才是「同一个东西三种获取方式」的来源。
     *
     * 只读：上下文在 [Companion.init] 时固定，换掉它等于整个后端失效。
     */
    val platformContext: PlatformContext get() = context
    /** 后端状态流（UI 可观察，初始 [BackendState.Uninitialized]）。 */
    private val _stateFlow = MutableStateFlow<BackendState>(BackendState.Uninitialized)
    val stateFlow: StateFlow<BackendState> = _stateFlow.asStateFlow()

    /** 当前状态快照。 */
    val state: BackendState get() = _stateFlow.value

    // ============ 健康监控 ============

    val health: HealthMonitor get() = HealthMonitor

    // ============ 音乐数据访问（云侧） ============

    /**
     * 带读透缓存的云音乐 API。
     *
     * 交出去的是 [CachedMusicApiService]（装饰器），**裸实现不外泄** ——
     * 否则调用方绕过装饰器，缓存层形同不存在（历史 bug，见 ARCHITECTURE §5）。
     */
    @Deprecated("请使用统一访问入口 unifiedSource", ReplaceWith("unifiedSource"))
    val musicApi: MusicApiService get() = cachedMusicApi

    @Deprecated("请使用统一访问入口 unifiedSource", ReplaceWith("unifiedSource"))
    val cachedApi: CachedMusicApiService get() = cachedMusicApi

    // ============ 本地音乐 ============

    @Deprecated("请使用统一访问入口 unifiedSource", ReplaceWith("unifiedSource"))
    var localMusic: LocalMediaSource
        get() = _localMusic
        internal set(value) {
            attachLocalMedia(value)
        }

    private var _localMusic: LocalMediaSource = NoopLocalMusicSource

    /**
     * 本地媒体源一次性装配：赋值 [NoopLocalMusicSource] 之外的真实实现，
     * 并重建 [unifiedSource] 纳入本地源。
     *
     * 守卫：仅允许从 [NoopLocalMusicSource] 迁移一次；二次赋值抛异常，
     * 防止重建 [unifiedSource] 导致 playbackController/download 持有旧实例。
     */
    private fun attachLocalMedia(source: LocalMediaSource) {
        check(_localMusic is NoopLocalMusicSource) { "本地媒体源已装配，不允许二次赋值" }
        _localMusic = source
        _unifiedSource = unifiedSourceFor(source)
    }

    private fun unifiedSourceFor(source: LocalMediaSource) =
        cp.player.core.music.UnifiedMusicSourceImpl(
            musicApiService = cachedMusicApi,
            localMusicSource = source,
            activeProviderId = { providerManager.getCurrentProviderId() },
        )

    // ============ 统一音乐源 (统一 MediaId) ============

    private var _unifiedSource: cp.player.core.music.UnifiedMusicSource = unifiedSourceFor(NoopLocalMusicSource)
    
    /**
     * 前端统一数据访问入口。
     * 根据传入的 CPMediaId 自动路由到对应的 Provider 或本地源，并转换数据模型。
     */
    val unifiedSource: cp.player.core.music.UnifiedMusicSource get() = _unifiedSource

    // ============ 本地媒体源（平台 actual，惰性装配） ============

    private val localMediaLazy = lazy {
        createLocalMediaSource(context).also { attachLocalMedia(it) }
    }

    /**
     * 平台本地媒体源（真实实现，由 [createLocalMediaSource] 装配）。
     *
     * 首次访问时构造并同步赋给 [localMusic]（其 setter 会重建 [unifiedSource]，
     * 保证统一路由包含本地源）。惰性委托默认 SYNCHRONIZED，多线程安全、单例语义。
     *
     * [init] 完成时已显式触发装配（见 [bootstrapLocalStack]），前端可直接使用。
     */
    val localMedia: LocalMediaSource by localMediaLazy

    // ============ 下载管理（前端唯一下载入口） ============

    private val downloadManagerLazy = lazy {
        // 先确保 localMedia 已装配：其 setter 会重建 unifiedSource，
        // 保证下载侧持有的 source 与最终统一路由一致（顺序不可颠倒）。
        localMedia
        MediaDownloadManagerImpl(
            source = unifiedSource,
            settings = settings,
            context = context,
            onCompleted = { _, item -> localMedia.addExternalItems(listOf(item)) },
        ).also { manager ->
            // 引擎内部自建 IO 协程域；此处仅在 backendScope 上触发一次持久化任务恢复
            backendScope.launch(Dispatchers.IO) { manager.start() }
        }
    }

    /**
     * 媒体下载管理门面。惰性创建：绑定 [unifiedSource] 取链 + [localMedia] 登记产物。
     *
     * 首次访问时创建并在 [backendScope] 上异步恢复持久化任务（[MediaDownloadManager.start]）。
     * [init] 完成时已显式触发装配（见 [bootstrapLocalStack]），前端可直接观察 [MediaDownloadManager.tasksFlow]。
     */
    val downloadManager: MediaDownloadManager by downloadManagerLazy

    /**
     * 本地媒体 + 下载管理的启动装配（init 末尾调用一次）。
     *
     * 顺序固定：先 [localMedia]（重建 unifiedSource 纳入本地源），
     * 后 [downloadManager]（绑定含本地源的 unifiedSource 并异步恢复下载任务）。
     */
    private fun bootstrapLocalStack() {
        localMedia
        downloadManager
    }

    // ============ 缓存管理（存储管理页） ============

    /**
     * 无损档位的落盘缓存句柄（桌面为真实下载，安卓为空实现）。
     *
     * **由 [MusicBackend] 持有、注入播放控制器**，而不是在 [playbackController] 的
     * lazy 块里现建：管理页要按**同一个实例**读占用、删条目；各建一个的话，
     * 管理页删的与播放器用的会是两份互不知情的缓存。
     *
     * 同样走 lazy：桌面实现构造期会创建 `~/.cpplayer/stream-cache/`，
     * 不在 [init] 里提前落地 —— 测试与「从没放过歌」的会话不该平白多一个目录。
     */
    private val streamLocalizerLazy = lazy { createStreamLocalizer() }

    /**
     * AMLL TTML 歌词客户端（惰性创建）。
     *
     * ⚠️ 必须是本类持有的字段，不能内联进 [playbackController] 的构造参数 ——
     * 内联时 [reset] 拿不到引用，它的 Ktor 连接池 / 线程每次重建都泄漏一套。
     * 与 K11 同一类：清理要先被引用到，才谈得上「接入 reset」。
     */
    private val amllClientLazy = lazy {
        AmllTtmlClient(diskCache = defaultSettingsStorage(AMLL_CACHE_NAMESPACE))
    }

    /** 歌曲缓存（无损流落盘）的管理句柄：占用概览 + 条目列表 + 删除 / 清理。 */
    val songCache: StreamLocalizer by streamLocalizerLazy

    /** 接口缓存的可观测计数（命中 / 未命中 / 降级返回旧缓存 …），管理页据此显示命中率。 */
    val apiCacheStats: StateFlow<CacheStats> get() = cachedMusicApi.stats

    /** 当前接口缓存条目数。 */
    fun apiCacheSize(): Int = cache.size()

    /** 清空接口缓存，返回删除的条目数。 */
    fun clearApiCache(): Int {
        val removed = cache.size()
        if (removed > 0) cache.clear()
        return removed
    }

    // ============ 播放控制器（前端唯一播放入口） ============

    /** 后端生命周期协程域（[PlaybackController] 内部协程都跑在其上）。 */
    private val backendScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * 前端播放控制唯一入口。惰性创建，绑定 [UnifiedMusicSourceImpl] 取源 +
     * [MusicApiServiceImpl] 抓歌词/scrobble + 平台 [PlatformPlayer] 实际播放。
     *
     * 前端只应 `backend.playbackController.state.collectAsState()` 渲染，
     * 并通过此对象的方法触发播控——禁止直接访问 [unifiedSource] 用于播放。
     */
    val playbackController: PlaybackController by lazy {
        val platform = SilentOutputPlayer(
            delegate = createPlatformPlayer(context),
            muted = activeOutputConfig.silentLocalOutput,
        ).also { outputGate = it }
        PlaybackControllerImpl(
            platform = platform,
            source = unifiedSource,
            api = musicApiImpl,
            cookieProvider = { activeProvider()?.let { p -> providerManager.cookieStorage.getCookie(p.id) } },
            scope = backendScope,
            // 无损档位「边播边落盘」：桌面引擎无法定位 FLAC over HTTP，安卓是空实现。
            // 见 StreamLocalizer 的实测矩阵。实例由本类持有（见 songCache），管理页共用同一个。
            streamLocalizer = streamLocalizerLazy.value,
            // AMLL TTML 歌词（官方词库 API）：磁盘缓存走独立 namespace，与主设置隔离。
            // 实例由本类持有（见 amllClientLazy），[reset] 才能关掉它的连接池。
            amllClient = amllClientLazy.value,
            lyricsSourceMode = {
                LyricsSourceMode.fromKey(settings.getString(LyricsSourceMode.SETTINGS_KEY))
            },
            // 播放模式（随机 / 循环）持久化：启动恢复、变更落盘。
            playbackModeSettings = settings,
            // 歌词源插件（Lyrico Plugin API）：作为 AMLL 与音源之后的最后兜底。
            // 只有用户显式启用插件后才会联网，未启用用户行为不变。
            lyricsPluginService = lyricsPlugins,
        )
    }

    /**
     * 歌词源插件服务（前端「设置 → 歌词源插件」管理页使用）。
     *
     * 惰性创建；插件目录落在 [PlatformSupport.dataDir] 下的 `lyrics-plugins/`。
     */
    val lyricsPlugins: cp.player.core.lyricsplugin.LyricsPluginService by lazy {
        cp.player.core.lyricsplugin.createLyricsPluginService(context)
    }

    // ============ 对外集成契约（数据面） ============

    /**
     * 控制线程派发器。
     *
     * 从 [backendScope] **派生**而不是硬编码 [Dispatchers.Main]：队列与播放状态在
     * backendScope 上串行读写，写操作必须回到同一个线程，否则会与 UI 抢状态
     * （症状是偶发跳歌 / 队列错乱且难以复现）。硬编码则让这条规则在
     * 「core 的 desktopTest 没有 coroutines-swing」时不可测。
     */
    private val controlDispatcher: CoroutineDispatcher by lazy {
        backendScope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
            ?: Dispatchers.Main
    }

    /**
     * 对外播控的**收窄**适配。
     *
     * 刻意只映射四个动作：`PlaybackController` 的其它方法（seek / 队列增删改 /
     * `clearQueue`）都不外露 —— 尤其没有非破坏性的 `stop`，映射 `clearQueue`
     * 等于一次远程调用静默销毁用户队列，见 `PlaybackAction.DELIBERATELY_ABSENT`。
     */
    private val integrationPlaybackControl: IntegrationPlaybackControl =
        object : IntegrationPlaybackControl {
            override suspend fun play() = playbackController.resume()
            override suspend fun pause() = playbackController.pause()
            override suspend fun next() = playbackController.skipNext()
            override suspend fun previous() = playbackController.skipPrevious()
        }

    /**
     * 对外契约的用例层（组合根：`provider.*` → `integration` 的中性类型映射在这里）。
     *
     * 惰性创建：只有真的要起服务（`applyOutputConfig` 且 `enabled`）时才构建，
     * 避免为了「也许有人会来连」就把播放控制器先实例化出来。
     */
    private val integrationService: IntegrationService by lazy {
        IntegrationService(
            source = { unifiedSource },
            playbackState = { playbackController.state },
            playbackControl = { integrationPlaybackControl },
            controlDispatcher = controlDispatcher,
            availableProviders = {
                providersFlow.value.map {
                    IntegrationProviderInfo(
                        id = it.id,
                        name = it.name,
                        version = it.version,
                        type = it.type.name,
                    )
                }
            },
            activeProviderId = { activeProvider()?.id },
            loggedIn = {
                activeProvider()?.let { p -> !cookieStorage.getCookie(p.id).isNullOrBlank() } ?: false
            },
            config = { activeOutputConfig },
        )
    }

    /** 端点描述符写入器（Android 是**有理由的**空实现，不是「以后再说」）。 */
    private val integrationDescriptorWriter: IntegrationDescriptorWriter by lazy {
        createIntegrationDescriptorWriter()
    }

    // ============ 本地服务器输出 + 外部推送 ============

    /** 最近一次应用的输出配置（推送客户端按需读取）。 */
    @Volatile
    private var activeOutputConfig: LocalServerConfig = LocalServerConfig()

    /**
     * 音频输出静音门（[cp.player.core.playback.SilentOutputPlayer]）。
     *
     * 在 [playbackController] 首次创建时装配；此后 [applyOutputConfig]
     * 通过它切换「本机是否出声」，无需重建控制器、不丢队列。
     */
    @Volatile
    private var outputGate: SilentOutputPlayer? = null

    /** 当前输出配置快照。 */
    val outputConfig: LocalServerConfig get() = activeOutputConfig

    @Volatile
    private var localServer: LocalServer? = null

    private val _localServerStatus = MutableStateFlow(LocalServerStatus())

    /** 流输出服务状态（UI 展示监听地址与错误）。 */
    val localServerStatus: StateFlow<LocalServerStatus> = _localServerStatus.asStateFlow()

    private val _lastPushResult = MutableStateFlow<PushResult?>(null)

    /** 最近一次推送结果（UI 展示成败）。 */
    val lastPushResult: StateFlow<PushResult?> = _lastPushResult.asStateFlow()

    private var localServerStatusJob: Job? = null
    private var autoPushJob: Job? = null

    /** 接收端推送客户端。地址每次请求时从 [activeOutputConfig] 读取，配置变更无需重建。 */
    private val pusher: ExternalPusher by lazy { createExternalPusher { activeOutputConfig } }

    /**
     * 应用输出配置。
     *
     * **只在流输出相关字段变化时重建服务**（监听地址在构造期固定，无法热更新）。
     * 接收端地址、自动推送开关这类纯推送侧改动只影响推送行为，
     * 因此编辑接收端 URL 不会反复重启监听端口。
     */
    fun applyOutputConfig(config: LocalServerConfig) {
        val previous = activeOutputConfig
        activeOutputConfig = config

        // 静默输出：本机不出声，音频只从接收端出。可运行时切换，不影响队列。
        outputGate?.setMuted(config.silentLocalOutput)

        val streamChanged = localServer == null ||
            previous.enabled != config.enabled ||
            previous.bindAddress != config.bindAddress ||
            previous.streamPort != config.streamPort ||
            previous.accessToken != config.accessToken

        if (streamChanged) {
            localServerStatusJob?.cancel()
            localServerStatusJob = null
            localServer?.stop()
            localServer = null

            if (config.enabled) {
                // 数据面**始终挂载**、由闸门按请求判定：exposeDataApi 是路由级开关，
                // 改它不该重启端口 —— 所以这里不能按开关决定是否挂路由。
                val server = createLocalServer(
                    config = config,
                    integration = createIntegrationRoutes(integrationService),
                    activeConfig = { activeOutputConfig },
                    resolveStreamUrl = ::resolveStreamTarget,
                )
                localServer = server
                localServerStatusJob = backendScope.launch {
                    server.status.collect { status ->
                        _localServerStatus.value = status
                        // 描述符只在**确认在跑之后**才写：start() 把绑定失败吞进
                        // status.error 而不抛异常，所以「调用过 start()」≠「服务可用」。
                        if (status.running) publishIntegrationDescriptor()
                    }
                }
                server.start()
            } else {
                integrationDescriptorWriter.clear()
                _localServerStatus.value = LocalServerStatus(
                    running = false,
                    bindAddress = config.bindAddress,
                    streamPort = config.streamPort,
                )
            }
        }

        if (config.enabled && config.pushEnabled) startAutoPush() else stopAutoPush()
    }

    /**
     * 手动推送当前曲目（`POST /api/v1/play-url`）。
     * 单曲推送会替换接收端队列，适合"随点随放"。
     */
    suspend fun pushCurrentTrack(): PushResult {
        val track = playbackController.state.value.currentTrack
            ?: return PushResult.Failed("当前没有播放中的曲目")
        return pushTrack(track).also { _lastPushResult.value = it }
    }

    /** 手动推送当前队列（`POST /api/v1/queue`），由接收端负责顺序播放。 */
    suspend fun pushQueue(): PushResult {
        val queue = playbackController.state.value.queue
        if (queue.isEmpty()) return PushResult.Failed("队列为空")
        val tracks = queue.mapNotNull { item ->
            item.mediaId.takeIf { it.isNotBlank() }?.let { mediaId ->
                PushTrack(
                    url = activeOutputConfig.streamUrlFor(mediaId, advertisedHost()),
                    title = item.title,
                    artist = item.artist,
                    durationMs = item.durationMs.takeIf { it > 0 },
                )
            }
        }
        if (tracks.isEmpty()) return PushResult.Failed("队列中没有可推送的曲目")
        val index = playbackController.state.value.currentIndex.coerceIn(0, tracks.lastIndex)
        return pusher.pushQueue(tracks, autoplay = true, startIndex = index)
            .also { _lastPushResult.value = it }
    }

    /** 传输控制转发（play / pause / stop / next / previous）。 */
    suspend fun pushTransport(action: String): PushResult =
        pusher.transport(action).also { _lastPushResult.value = it }

    /** 探测接收端是否在线（`GET /api/health`）。 */
    suspend fun probeReceiver(): PushResult =
        pusher.health().also { _lastPushResult.value = it }

    /** 清空接收端队列。 */
    suspend fun clearReceiverQueue(): PushResult =
        pusher.clearQueue().also { _lastPushResult.value = it }

    // ---- 内部 ----

    /** 广播地址：绑定 0.0.0.0 时不能把 0.0.0.0 当目标地址下发。 */
    private fun advertisedHost(): String = resolveAdvertisedHost(activeOutputConfig.bindAddress)

    /**
     * 写出端点描述符（`~/.cpplayer/integration.json`），让集成方不必手抄地址与令牌。
     *
     * 只在**服务确认在跑之后**调用 —— 否则会留下一个指向不存在服务的描述符，
     * 而集成方只能靠 `pid` 判断进程是否还活着，看到的是一个**活着但没监听**的进程，
     * 比「没有描述符」更难排查。
     */
    private fun publishIntegrationDescriptor() {
        val cfg = activeOutputConfig
        // baseUrl 不带路径：集成方要能直接拼出 /api/v1/...，不做字符串截断
        integrationDescriptorWriter.publish(cfg.baseUrl(advertisedHost()), cfg.accessToken)
    }

    private suspend fun pushTrack(track: cp.player.core.music.TrackSummary): PushResult =
        pusher.playUrl(
            PushTrack(
                url = activeOutputConfig.streamUrlFor(track.id, advertisedHost()),
                title = track.name,
                artist = track.artist,
                album = track.album,
                artworkUrl = track.coverUrl,
                durationMs = track.durationMs.takeIf { it > 0 },
            )
        )

    /**
     * 曲目变化时自动推送，并同步传输状态。
     *
     * 只在 [LocalServerConfig.enabled] 且 [LocalServerConfig.pushEnabled] 时生效。
     * 推送失败不影响本机播放（[ExternalPusher] 不抛异常）。
     */
    private fun startAutoPush() {
        if (autoPushJob?.isActive == true) return
        autoPushJob = backendScope.launch {
            var lastPushedId: String? = null
            var lastPlaying: Boolean? = null
            playbackController.state.collect { st ->
                if (!activeOutputConfig.enabled || !activeOutputConfig.pushEnabled) return@collect
                val track = st.currentTrack
                if (track != null && st.isPlaying && track.id != lastPushedId) {
                    lastPushedId = track.id
                    _lastPushResult.value = pushTrack(track)
                }
                // 只在状态真正翻转时下发，避免每帧重复请求
                val playing = st.isPlaying
                if (lastPlaying != null && lastPlaying != playing) {
                    pusher.transport(if (playing) "play" else "pause")
                }
                lastPlaying = playing
            }
        }
    }

    private fun stopAutoPush() {
        autoPushJob?.cancel()
        autoPushJob = null
    }

    /**
     * 流输出服务的目标解析：mediaId → 上游可播放地址。
     *
     * mediaId 为空时取当前曲目。音质沿用播放控制器当前等级，
     * cookie 由服务端注入并**只保留在本机**，不下发给接收端。
     */
    private suspend fun resolveStreamTarget(mediaId: String?): StreamTarget? {
        val targetId = mediaId?.takeIf { it.isNotBlank() }
            ?: playbackController.state.value.currentTrack?.id
            ?: return null
        val level = playbackController.state.value.qualityLevel.ifBlank { "exhigh" }
        val songUrl = (unifiedSource.getSongUrl(targetId, level) as? BackendResult.Success)?.data
            ?: return null
        if (songUrl.url.isBlank()) return null
        val cookie = songUrl.cookie?.takeIf { it.isNotBlank() }
            ?: activeProvider()?.let { providerManager.cookieStorage.getCookie(it.id) }
        return StreamTarget(url = songUrl.url, cookie = cookie)
    }

    // ============ Provider 管理（带状态机错误处理与自动激活） ============

    /** 已加载 Provider 列表（响应式，供 UI 列表页展示）。 */
    val providersFlow: StateFlow<List<BackendProvider>> = moduleManager.providersFlow

    /** 当前活跃 Provider 流（响应式，UI 顶部标题/登录页等用）。 */
    val activeProviderFlow: StateFlow<BackendProvider?> = providerManager.currentProviderFlow

    /** 当前活跃 Provider（便捷快照；变更请使用 [stateFlow]）。 */
    fun activeProvider(): BackendProvider? = state.activeProvider

    /** Provider 名称（UI 标题用，无活跃时返回占位串）。 */
    fun activeProviderName(): String = activeProvider()?.name ?: "未选择音源"

    /** Provider 唯一 ID（设置持久化用，无活跃时返回 "default"）。 */
    fun activeProviderId(): String = activeProvider()?.id ?: "default"

    /**
     * 导入 zip 模块包并**自动激活**（此前无活跃 Provider 时）。
     *
     * 失败时 [lastLoadError] 会被设置；成功时 [stateFlow] 迁移到 [BackendState.Ready]
     * （此前为 [BackendState.NoProvider] 的情况）或保持 [BackendState.Ready] 不变。
     *
     * @param zipPath zip 文件绝对路径
     * @return 导入结果（[ImportResult.Activated] / [ImportResult.Loaded] / [ImportResult.Failed]）
     */
    fun importModule(zipPath: String): ImportResult {
        val ok = moduleManager.importModule(zipPath)
        if (!ok) return ImportResult.Failed(moduleManager.lastLoadError ?: "导入失败")
        val provider = moduleManager.getAvailableProviders().lastOrNull()
            ?: return ImportResult.Failed("导入成功但未找到 Provider（异常）")
        if (activeProvider() == null || !activeProvider()!!.isReady()) {
            // 无活跃 Provider 或当前活跃不可用：自动激活（修复旧版"导入后仍显示未加载"的 bug）
            val result = switchProviderInternal(provider, save = true)
            return when (result) {
                is BackendResult.Success -> ImportResult.Activated(provider)
                is BackendResult.Error -> ImportResult.Failed(result.message)
                is BackendResult.Unsupported -> ImportResult.Failed(result.message)
            }
        }
        return ImportResult.Loaded(provider)
    }

    /**
     * 用 zip 模块包**更新**已安装模块（包内 manifest.id 必须与 [targetId] 一致）。
     *
     * 与 [importModule] 的差异：只接受 id 匹配的包，且更新的是**活跃** Provider 时
     * 会自动重新激活新实例（旧引用已被替换，不重开的话播放会继续打到旧对象上）。
     */
    fun updateModule(zipPath: String, targetId: String): ImportResult {
        val ok = moduleManager.updateModule(zipPath, targetId)
        if (!ok) return ImportResult.Failed(moduleManager.lastLoadError ?: "更新失败")
        val provider = moduleManager.getProvider(targetId)
            ?: return ImportResult.Failed("更新成功但未找到 Provider（异常）")
        val active = activeProvider()
        return if (active == null || active.id == targetId || !active.isReady()) {
            when (val result = switchProviderInternal(provider, save = true)) {
                is BackendResult.Success -> ImportResult.Activated(provider)
                is BackendResult.Error -> ImportResult.Failed(result.message)
                is BackendResult.Unsupported -> ImportResult.Failed(result.message)
            }
        } else {
            ImportResult.Loaded(provider)
        }
    }

    /**
     * 把已安装模块目录打包为 zip 写入 [zipPath]（导出）。失败时错误信息取自
     * [ModuleManager.lastExportError]。
     */
    fun exportModule(providerId: String, zipPath: String): BackendResult<Unit> =
        if (moduleManager.exportModule(providerId, zipPath)) BackendResult.Success(Unit)
        else BackendResult.Error(moduleManager.lastExportError ?: "导出失败: $providerId")

    /**
     * 切换活跃 Provider。
     *
     * @param provider 目标 Provider
     * @return [BackendResult.Success]/[Error]
     */
    fun switchProvider(provider: BackendProvider): BackendResult<Unit> {
        if (!provider.isReady()) return BackendResult.Error("Provider 未就绪，无法切换")
        return switchProviderInternal(provider, save = true)
    }

    /** 按 ID 切换活跃 Provider。 */
    fun switchProviderById(providerId: String): BackendResult<Unit> {
        val provider = moduleManager.getProvider(providerId)
            ?: return BackendResult.Error("未找到 Provider: $providerId")
        return switchProvider(provider)
    }

    /**
     * 删除 Provider 模块。
     * 若删除的是当前活跃 Provider，会尝试切换到剩余的第一个；
     * 若删除后无 Provider，状态迁移到 [BackendState.NoProvider]。
     */
    fun deleteModule(providerId: String): BackendResult<Unit> {
        val deletingActive = providerId == activeProviderId()
        val ok = moduleManager.deleteModule(providerId)
        if (!ok) return BackendResult.Error("删除模块失败: $providerId")
        val remaining = moduleManager.getAvailableProviders()
        if (deletingActive || remaining.isEmpty()) {
            if (remaining.isEmpty()) {
                _stateFlow.value = BackendState.NoProvider
            } else {
                switchProviderInternal(remaining.first(), save = true)
            }
        }
        return BackendResult.Success(Unit)
    }

    /** 所有已加载 Provider 的快照。 */
    fun getAvailableProviders(): List<BackendProvider> = moduleManager.getAvailableProviders()

    /** 最近一次加载/导入错误（用于详细诊断）。 */
    val lastLoadError: String? get() = moduleManager.lastLoadError

    // ============ 音乐数据访问（高层封装，逐步从 [MusicApiService] 迁移） ============
    //
    // TODO（增量迁移）：在此处逐步增加 `fetch*` 方法，
    // 将 [MusicApiService] 的 JsonElement 返回解析为
    // [cp.player.core.music] 中的强类型领域模型，统一返回 [MusicResult]。
    // 首批：推荐歌单 / 日推 / 搜索 / 歌单详情。
    // 见 cp.player.core.music.MusicSourceFromApi。

    // ============ 释放 ============

    /** 释放后端单例（主要供测试用）。 */
    fun reset() {
        runCatching { stopAutoPush() }
        runCatching { localServerStatusJob?.cancel() }
        runCatching { localServer?.stop() }
        localServer = null
        runCatching { activeProvider()?.stopServer() }
        // 释放各 Provider 持有的资源（HttpProvider / BinaryProvider 各自持有一个 Ktor 客户端）。
        // Provider 重载或后端重建时不关就是连接池 / 线程泄漏；BackendProvider.close() 默认空实现，
        // 无状态 Provider 自动免疫。
        runCatching {
            moduleManager.getAvailableProviders().forEach { p -> runCatching { p.close() } }
        }
        // ⚠️ Android 侧注意（CODE_REVIEW K10）：这会经 PlatformPlayer.release() 释放
        // SharedMedia3Player 的**单例** ExoPlayer —— 若 MediaSessionService 仍存活，
        // 会话将脱钩、通知恒 IDLE。当前 reset() 只在测试路径触发；生产代码不要在
        // 服务存活时调用本方法（桌面无单例问题，不受此限）。
        runCatching { playbackController.release() }
        // AMLL 歌词客户端同样持有 Ktor 客户端。仅当真装配过时才关 —— 否则 reset 会反向触发
        // 它的惰性创建（平白多一个请求 + 多一个连接池）。
        if (amllClientLazy.isInitialized()) runCatching { amllClientLazy.value.close() }
        // 仅在已装配时关闭下载引擎（避免 reset 反向触发惰性初始化）
        if (downloadManagerLazy.isInitialized()) {
            runCatching { downloadManager.shutdown() }
        }
        backendScope.cancel()
        _stateFlow.value = BackendState.Uninitialized
        synchronized(COMPA) {
            if (INSTANCE === this) INSTANCE = null
        }
    }

    // ============ 内部 ============

    private fun switchProviderInternal(provider: BackendProvider, save: Boolean): BackendResult<Unit> {
        return try {
            val ok = providerManager.switchProvider(provider, context, save = save)
            if (!ok) {
                val msg = extractProviderError(provider) ?: "Provider 切换失败"
                _stateFlow.value = BackendState.Error(msg)
                return BackendResult.Error(msg)
            }
            if (provider.isReady()) {
                _stateFlow.value = BackendState.Ready(provider)
                BackendResult.Success(Unit)
            } else {
                val msg = extractProviderError(provider) ?: "Provider 服务启动失败"
                _stateFlow.value = BackendState.Error(msg)
                BackendResult.Error(msg)
            }
        } catch (e: Throwable) {
            _stateFlow.value = BackendState.Error("切换 Provider 失败: ${e.message}")
            BackendResult.Error("切换 Provider 失败: ${e.message}", cause = e)
        }
    }

    private fun updateReadyState(provider: BackendProvider) {
        if (provider.isReady()) _stateFlow.value = BackendState.Ready(provider)
        else _stateFlow.value = BackendState.Error(extractProviderError(provider) ?: "Provider 未就绪")
    }

    /**
     * 尝试从 Provider 提取加载/启动失败的错误描述。
     * JNI Provider 有 [JniProvider.getLoadError]；其他类型返回 null。
     */
    private fun extractProviderError(provider: BackendProvider): String? {
        // 反射避免 commonMain 直接依赖 androidMain 的 JniProvider
        return runCatching {
            val m = provider.javaClass.getMethod("getLoadError")
            m.invoke(provider) as? String
        }.getOrNull()
    }

    // ============ 伴生（工厂） ============

    companion object COMPA {
        @Volatile private var INSTANCE: MusicBackend? = null

        /**
         * 初始化后端。
         *
         * 来源：平台 Application.onCreate / JVM main：
         * - Android：`context = toPlatformContext(this)`、`settings = defaultSettingsStorage()`
         * - Desktop：`context = PlatformContext()`、`settings = defaultSettingsStorage()`
         *
         * @param context 平台上下文
         * @param settings 设置存储（cookie / 最近 Provider ID 持久化）
         * @param cache 缓存实现；null 时按 [CacheConfig.maxEntries] 建进程内 LRU
         *   （曾经在这里写死 `InMemoryApiCache()` 默认值，`CacheConfig.maxEntries` 就成了死参数）
         * @param cacheConfig 缓存配置
         * @return 初始化后的 [MusicBackend] 单例
         */
        fun init(
            context: PlatformContext,
            settings: SettingsStorage,
            cache: ApiCache? = null,
            cacheConfig: CacheConfig = CacheConfig(),
        ): MusicBackend {
            synchronized(COMPA) {
                INSTANCE?.let { return it }
                val effectiveCache = cache ?: InMemoryApiCache(cacheConfig.maxEntries)
                val cookieStorage = ProviderCookieStorage(settings)
                val providerManager = ProviderManager(settings, cookieStorage)
                val moduleManager = ModuleManager(
                    cp.player.core.util.PlatformSupport.modulesDir(context),
                    context,
                )
                moduleManager.init(providerManager)
                val impl = MusicApiServiceImpl(providerManager, cookieStorage)
                val cached = CachedMusicApiService(
                    delegate = impl,
                    cache = effectiveCache,
                    providerManager = providerManager,
                    allProviders = { moduleManager.getAvailableProviders() },
                    config = cacheConfig,
                )
                val backend = MusicBackend(
                    context = context,
                    settings = settings,
                    providerManager = providerManager,
                    moduleManager = moduleManager,
                    musicApiImpl = impl,
                    cachedMusicApi = cached,
                    cache = effectiveCache,
                )
                // 初始化完成后计算终态
                backend.stateFromInit()
                // 读取持久化输出配置：playbackController 首次创建时据此决定是否静默
                backend.activeOutputConfig = LocalServerConfigStore.read(settings)
                // 装配本地媒体源与下载管理器（恢复持久化下载任务）
                backend.bootstrapLocalStack()
                INSTANCE = backend
                return backend
            }
        }

        /** 当前实例（init 后可取）。 */
        val instance: MusicBackend get() = INSTANCE ?: error("MusicBackend.init() not called")
    }

    private fun stateFromInit() {
        val current = providerManager.currentProvider
        _stateFlow.value = when {
            // current 已设置且就绪 → Ready
            current != null && current.isReady() -> BackendState.Ready(current)
            // current 已设置但未就绪（如 JNI startServer 崩溃）→ Error（附具体原因）
            current != null && !current.isReady() ->
                BackendState.Error(extractProviderError(current) ?: "Provider 服务启动失败")
            // 有模块但未激活（init 中所有 provider 都 isReady=false，switchProvider 拒绝）
            moduleManager.getAvailableProviders().isNotEmpty() -> {
                val first = moduleManager.getAvailableProviders().first()
                BackendState.Error(extractProviderError(first) ?: "所有 Provider 都未就绪")
            }
            else -> BackendState.NoProvider
        }
    }
}

// ============ 占位实现 ============

/** 空操作本地媒体源（返回空列表 / 空流）。 */
object NoopLocalMusicSource : LocalMediaSource {
    private val emptyItems = MutableStateFlow<List<LocalMediaItem>>(emptyList())
    private val emptyScanning = MutableStateFlow(false)

    override suspend fun scan(): Flow<ScanProgress> = emptyFlow()
    override suspend fun importFolder(uri: String): Int = 0
    override fun removeItem(item: LocalMediaItem) {}
    override fun items(): StateFlow<List<LocalMediaItem>> = emptyItems
    override val isScanningFlow: StateFlow<Boolean> = emptyScanning
    override fun addExternalItems(items: List<LocalMediaItem>) {}
}