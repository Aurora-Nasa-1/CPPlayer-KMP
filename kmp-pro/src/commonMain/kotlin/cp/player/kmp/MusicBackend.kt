package cp.player.kmp

import cp.player.kmp.api.MusicApiService
import cp.player.kmp.api.MusicApiServiceImpl
import cp.player.kmp.cache.ApiCache
import cp.player.kmp.cache.CacheConfig
import cp.player.kmp.cache.CachedMusicApiService
import cp.player.kmp.cache.InMemoryApiCache
import cp.player.kmp.local.LocalMusicSource
import cp.player.kmp.local.LocalSongMetadata
import cp.player.kmp.monitor.HealthMonitor
import cp.player.kmp.playback.PlaybackController
import cp.player.kmp.playback.PlaybackControllerImpl
import cp.player.kmp.playback.PlaybackEngine
import cp.player.kmp.playback.createPlatformPlayer
import cp.player.kmp.provider.BackendProvider
import cp.player.kmp.provider.ModuleManager
import cp.player.kmp.provider.ProviderCookieStorage
import cp.player.kmp.provider.ProviderManager
import cp.player.kmp.util.PlatformContext
import cp.player.kmp.util.SettingsStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * 4. **本地音乐**：通过 [localMusic] 访问本地音乐列表（当前为占位接口，后续平台不实现可返回空）。
 * 5. **播放控制**：通过 [playback] 访问播放引擎（当前为占位，后续平台注入 actual）。
 * 6. **健康监控**：通过 [health] 访问带 [HealthMonitor.HealthLevel] 三级分类的 API 健康数据。
 * 7. **错误处理**：所有可失败操作返回 [BackendResult]，UI 可穷举 [Success]/[Error]/[Unsupported]。
 *
 * ### 使用约定
 * ```kotlin
 * // 平台 Application.onCreate 中：
 * val backend = MusicBackend.init(context, settings)
 *
 * // UI 观察：
 * val state by backend.stateFlow.collectAsState()
 * when (state) {
 *     is BackendState.NoProvider -> showSetupScreen()
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
    /** 后端状态流（UI 可观察，初始 [BackendState.Uninitialized]）。 */
    private val _stateFlow = MutableStateFlow<BackendState>(BackendState.Uninitialized)
    val stateFlow: StateFlow<BackendState> = _stateFlow.asStateFlow()

    /** 当前状态快照。 */
    val state: BackendState get() = _stateFlow.value

    // ============ 健康监控 ============

    val health: HealthMonitor get() = HealthMonitor

    // ============ 音乐数据访问（云侧，直通原 API） ============

    @Deprecated("请使用统一访问入口 unifiedSource", ReplaceWith("unifiedSource"))
    val musicApi: MusicApiService get() = musicApiImpl

    @Deprecated("请使用统一访问入口 unifiedSource", ReplaceWith("unifiedSource"))
    val cachedApi: CachedMusicApiService get() = cachedMusicApi

    // ============ 本地音乐 ============

    @Deprecated("请使用统一访问入口 unifiedSource", ReplaceWith("unifiedSource"))
    var localMusic: LocalMusicSource = NoopLocalMusicSource
        internal set(value) {
            field = value
            _unifiedSource = cp.player.kmp.music.UnifiedMusicSourceImpl(cachedMusicApi, value)
        }

    // ============ 统一音乐源 (统一 MediaId) ============

    private var _unifiedSource: cp.player.kmp.music.UnifiedMusicSource = cp.player.kmp.music.UnifiedMusicSourceImpl(cachedMusicApi, localMusic)
    
    /**
     * 前端统一数据访问入口。
     * 根据传入的 CPMediaId 自动路由到对应的 Provider 或本地源，并转换数据模型。
     */
    val unifiedSource: cp.player.kmp.music.UnifiedMusicSource get() = _unifiedSource

    // ============ 播放引擎（占位，后续平台注入 actual） ============

    /**
     * 播放引擎。当前为占位（操作无效果）。
     * 后续 Android 平台将注入 ExoPlayer/FlickPlayer 实现。
     */
    var playback: PlaybackEngine = NoopPlaybackEngine
        internal set

    // ============ 播放控制器（前端唯一播放入口） ============

    /** 后端生命周期协程域（[PlaybackController] 内部协程都跑在其上）。 */
    private val backendScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * 前端播放控制唯一入口。惰性创建，绑定 [UnifiedMusicSourceImpl] 取源 +
     * [MusicApiServiceImpl] 抓歌词/scrobble + 平台 [PlatformPlayer] 实际播放。
     *
     * 前端只应 `backend.playbackController.state.collectAsState()` 渲染，
     * 并通过此对象的方法触发播控——禁止直接访问 [playback] / [unifiedSource] 用于播放。
     */
    val playbackController: PlaybackController by lazy {
        val platform = createPlatformPlayer(context)
        PlaybackControllerImpl(
            platform = platform,
            source = unifiedSource,
            api = musicApiImpl,
            cookieProvider = { activeProvider()?.let { p -> providerManager.cookieStorage.getCookie(p.id) } },
            scope = backendScope,
        )
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
    // [cp.player.kmp.music] 中的强类型领域模型，统一返回 [MusicResult]。
    // 首批：推荐歌单 / 日推 / 搜索 / 歌单详情。
    // 见 cp.player.kmp.music.MusicSourceFromApi。

    // ============ 释放 ============

    /** 释放后端单例（主要供测试用）。 */
    fun reset() {
        runCatching { activeProvider()?.stopServer() }
        runCatching { playbackController.release() }
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
         * @param cache 缓存实现，默认进程内 LRU
         * @param cacheConfig 缓存配置
         * @return 初始化后的 [MusicBackend] 单例
         */
        fun init(
            context: PlatformContext,
            settings: SettingsStorage,
            cache: ApiCache = InMemoryApiCache(),
            cacheConfig: CacheConfig = CacheConfig(),
        ): MusicBackend {
            synchronized(COMPA) {
                INSTANCE?.let { return it }
                val cookieStorage = ProviderCookieStorage(settings)
                val providerManager = ProviderManager(settings, cookieStorage)
                val moduleManager = ModuleManager(
                    cp.player.kmp.util.PlatformSupport.modulesDir(context),
                    context,
                )
                moduleManager.init(providerManager)
                val impl = MusicApiServiceImpl(providerManager, cookieStorage)
                val cached = CachedMusicApiService(
                    delegate = impl,
                    cache = cache,
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
                    cache = cache,
                )
                // 初始化完成后计算终态
                backend.stateFromInit()
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

/** 空操作本地音乐源（返回空列表）。 */
object NoopLocalMusicSource : LocalMusicSource {
    override suspend fun scan(directory: String?): List<LocalSongMetadata> = emptyList()
    override fun cached(): List<LocalSongMetadata> = emptyList()
}

/** 空操作播放引擎（所有操作无效果）。 */
object NoopPlaybackEngine : PlaybackEngine {
    override val type get() = cp.player.kmp.playback.EngineType.DESKTOP
    private val _state = MutableStateFlow<cp.player.kmp.playback.PlaybackState>(cp.player.kmp.playback.PlaybackState.Idle)
    override suspend fun play(url: String, metadata: cp.player.kmp.playback.PlaybackMetadata?) {}
    override fun pause() {}
    override fun resume() {}
    override fun stop() {}
    override fun seekTo(positionMs: Long) {}
    override fun stateFlow() = _state.asStateFlow()
    override fun setVolume(volume: Float) {}
}