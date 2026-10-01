package cp.player.app

import cp.player.core.BackendResult
import cp.player.core.BackendState
import cp.player.core.ImportResult
import cp.player.core.MusicBackend
import cp.player.core.api.extractUidFromLoginStatus
import cp.player.core.api.unwrapLoginStatusData
import cp.player.core.control.LocalServerConfig
import cp.player.core.control.LocalServerConfigStore
import cp.player.core.control.LocalServerStatus
import cp.player.core.control.OutputMode
import cp.player.core.monitor.HealthMonitor
import cp.player.core.playback.PlaybackController
import cp.player.core.provider.BackendProvider
import cp.player.core.provider.ProviderCookieStorage
import cp.player.core.util.SettingsStorage
import cp.player.app.repository.AuthRepository
import cp.player.app.repository.MusicRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 应用顶层服务定位器。
 *
 * 唯一依赖的后端类型是 [MusicBackend]。它封装了：
 * - 状态机（[BackendState]）与自动激活
 * - Provider 管理（导入/切换/删除）
 * - 音乐数据访问（直通 + 缓存 + 健康监控）
 * - 本地音乐 / 播放引擎（占位，后续注入）
 *
 * UI 通过 [backendState] 观察瞬态；通过具体方法（如 [importModule]）执行操作，
 * 错误路径通过 [BackendResult]/[ImportResult] 类型安全返回。
 */
object AppModel {

    private val _initialized = MutableStateFlow(false)
    val initialized: StateFlow<Boolean> = _initialized.asStateFlow()

    val backend: MusicBackend get() = MusicBackend.instance

    /** 后端状态流（UI 渲染决策用——NoProvider → Setup，Ready → Main）。 */
    val backendState: StateFlow<BackendState> get() = backend.stateFlow

    /** 当前活跃 Provider 流（顶部标题/登录页等）。 */
    val activeProviderFlow: StateFlow<BackendProvider?> get() = backend.activeProviderFlow

    /** 当前状态快照。 */
    val state: BackendState get() = backend.state

    /** 是否首次运行（无已加载 Provider）。 */
    val isFirstRun: Boolean get() = backend.getAvailableProviders().isEmpty()

    val cookieStorage: ProviderCookieStorage get() = backend.cookieStorage
    /**
     * 全局设置存储。
     *
     * ⚠️ 必须是**单例**。桌面实现构造时把整个 properties 文件读进内存、每次写入**全量回写**，
     * 所以同一个 namespace 上的两个实例会互相覆盖：后写者拿自己的陈旧快照盖掉先写者的改动。
     *
     * 原先写成 `get() = defaultSettingsStorage()` —— 每次访问都新建实例，于是
     * `cp_player_prefs` 上同时有三个写者（这里、`MusicBackend` 注入的那个、
     * `DesktopRenderTuning` 的 lazy 实例），典型症状是
     * 「改完主题 → 去渲染后端页动一下垂直同步 → 主题被回退」。
     *
     * 现在 [cp.player.core.util.defaultSettingsStorage] 自身按 (数据目录, namespace)
     * 返回共享实例，`by lazy` 只是再省掉一次目录探测。
     */
    val settings: SettingsStorage by lazy { cp.player.core.util.defaultSettingsStorage() }

    /** Application-facing repository; new UI code should use this instead of raw API. */
    val musicRepository: MusicRepository get() = MusicRepository(backend.musicApi)

    val authRepository: AuthRepository get() = AuthRepository(backend.musicApi)

    /**
     * Transitional raw API access for operations not migrated yet.
     *
     * ⚠️ 这里拿到的其实是**带缓存的实例** —— `MusicBackend.musicApi` 交出的就是
     * `CachedMusicApiService` 装饰器。"raw" 现在只表示「没经过 repository 封装」，
     * 不再表示「绕过缓存」。
     */
    @Deprecated("Use musicRepository or a feature repository")
    val api: cp.player.core.api.MusicApiService get() = backend.musicApi

    /** 当前活跃 Provider 唯一 ID（无活跃时返回 "default"）。 */
    fun activeProviderId(): String = backend.activeProviderId()

    val health: HealthMonitor get() = backend.health

    /** 播放控制器（前端唯一播放入口；UI 只 collect 其 state）。 */
    val playback: PlaybackController get() = backend.playbackController

    fun markInitialized() { _initialized.value = true }

    fun retryBackendBootstrap() {
        _initialized.value = true
    }

    // ============ 设置（持久化） ============

    private val KEY_THEME_MODE = "theme_mode"

    /**
     * 旧键：只存「动态取色 开 / 关」。已被 [KEY_COLOR_SOURCE] 取代，
     * 但仍保留读取，用于一次性迁移（见 [colorSource]）。
     */
    private val KEY_DYNAMIC_COLOR_LEGACY = "dynamic_color"
    private val KEY_COLOR_SOURCE = "color_source"
    private val KEY_PURE_BLACK = "pure_black"
    private val KEY_PLAYBACK_QUALITY = "playback_quality"

    private val _themeMode = MutableStateFlow(themeMode())
    val themeModeFlow: StateFlow<cp.player.app.ui.theme.ThemeMode> = _themeMode.asStateFlow()

    private val _colorSource = MutableStateFlow(colorSource())
    val colorSourceFlow: StateFlow<cp.player.app.ui.theme.ColorSource> = _colorSource.asStateFlow()

    /**
     * 当前封面的种子色。null = 未取到（无封面 / 取色中 / 取色失败），
     * 主题会回退到固定种子色 —— 取色失败绝不该影响可用性。
     */
    private val _coverSeed = MutableStateFlow<androidx.compose.ui.graphics.Color?>(null)
    val coverSeedFlow: StateFlow<androidx.compose.ui.graphics.Color?> = _coverSeed.asStateFlow()

    /**
     * 系统壁纸的种子色，用于「跟随封面」但**当前没有曲目封面**时的回退。
     *
     * 与 [coverSeedFlow] 分开而不是合并成一个流，是因为两者的**生命周期完全不同**：
     * 封面每首歌都变，壁纸一个进程只解一次。混在一起会让「壁纸没变」也触发主题重算。
     *
     * Android 恒为 null —— 那边的壁纸色由 Monet 直接给整套方案，不走「种子色」这条路。
     */
    private val _wallpaperSeed = MutableStateFlow<androidx.compose.ui.graphics.Color?>(null)
    val wallpaperSeedFlow: StateFlow<androidx.compose.ui.graphics.Color?> =
        _wallpaperSeed.asStateFlow()

    private val _pureBlack = MutableStateFlow(pureBlack())
    val pureBlackFlow: StateFlow<Boolean> = _pureBlack.asStateFlow()

    fun themeMode(): cp.player.app.ui.theme.ThemeMode =
        runCatching { cp.player.app.ui.theme.ThemeMode.valueOf(settings.getString(KEY_THEME_MODE) ?: "SYSTEM") }
            .getOrDefault(cp.player.app.ui.theme.ThemeMode.SYSTEM)

    fun setThemeMode(mode: cp.player.app.ui.theme.ThemeMode) {
        settings.putString(KEY_THEME_MODE, mode.name)
        _themeMode.value = mode
    }

    /**
     * 取色来源。
     *
     * **默认 FIXED 而不是 PLATFORM**：升级前 `dynamic_color` 默认关（等价于现在的
     * FIXED），若默认改成 PLATFORM，老用户一升级观感就整体变掉。保持「默认不变、
     * 用户显式开启」。
     *
     * 迁移：老键 `dynamic_color == true` ⇒ 认为用户本来就选了「跟随系统」。
     */
    fun colorSource(): cp.player.app.ui.theme.ColorSource {
        settings.getString(KEY_COLOR_SOURCE)?.let { raw ->
            runCatching { cp.player.app.ui.theme.ColorSource.valueOf(raw) }.getOrNull()
                ?.let { return it }
        }
        val legacyDynamic =
            settings.getString(KEY_DYNAMIC_COLOR_LEGACY)?.toBooleanStrictOrNull() ?: false
        return if (legacyDynamic) cp.player.app.ui.theme.ColorSource.PLATFORM
        else cp.player.app.ui.theme.ColorSource.FIXED
    }

    fun setColorSource(source: cp.player.app.ui.theme.ColorSource) {
        settings.putString(KEY_COLOR_SOURCE, source.name)
        _colorSource.value = source
    }

    fun pureBlack(): Boolean = settings.getString(KEY_PURE_BLACK)?.toBooleanStrictOrNull() ?: false
    fun setPureBlack(enabled: Boolean) {
        settings.putString(KEY_PURE_BLACK, enabled.toString())
        _pureBlack.value = enabled
    }

    // ============ 首次使用引导（持久化） ============

    private const val KEY_ONBOARDING_DONE = "onboarding_done"

    private val _onboardingDone = MutableStateFlow(
        settings.getString(KEY_ONBOARDING_DONE)?.toBooleanStrictOrNull() ?: false
    )

    /** 是否已完成首次使用引导。[App] 用它决定起始页是引导还是主界面。 */
    val onboardingDoneFlow: StateFlow<Boolean> = _onboardingDone.asStateFlow()

    fun setOnboardingDone(done: Boolean = true) {
        settings.putString(KEY_ONBOARDING_DONE, done.toString())
        _onboardingDone.value = done
    }

    // ============ 音源隔离（持久化） ============

    private const val KEY_ISOLATION_SWITCH_ACCOUNT = "isolation_switch_account"

    private val _isolationSwitchAccount = MutableStateFlow(
        settings.getString(KEY_ISOLATION_SWITCH_ACCOUNT)?.toBooleanStrictOrNull() ?: true
    )

    /**
     * 切换音源时是否**同步切换到该音源自己的账号**（默认开）。
     *
     * Cookie 本来就是按音源分开存的（`cookie_<providerId>`），所以「隔离」只需要在
     * 切换的那一刻把资料重新拉一遍 —— 关掉它则表示切换后保留上一个音源的资料展示。
     */
    val isolationSwitchAccountFlow: StateFlow<Boolean> = _isolationSwitchAccount.asStateFlow()

    fun isolationSwitchAccount(): Boolean = _isolationSwitchAccount.value

    fun setIsolationSwitchAccount(enabled: Boolean) {
        settings.putString(KEY_ISOLATION_SWITCH_ACCOUNT, enabled.toString())
        _isolationSwitchAccount.value = enabled
    }

    private var coverColorTrackingStarted = false

    /**
     * 启动封面取色（幂等）：跟随当前曲目的封面变化更新 [coverSeedFlow]。
     *
     * 成本控制四层，缺一层都会卡（「压缩不卡顿」的落点）：
     * - **门控**：只有取色来源是 [cp.player.app.ui.theme.ColorSource.COVER] 时才解码。
     *   默认来源是 FIXED，若不门控就是「每换一首歌白白解码 + 量化一张封面」。
     * - **触发**：只在封面 URL **变化**时走一遍，并按 URL 命中
     *   [cp.player.app.ui.theme.CoverSeedCache]；同一首歌来回切不重算。
     * - **解码**：Coil 只解 [cp.player.app.ui.theme.CoverSampleSizePx]（112px）的缩略图，
     *   走它自己的内存 / 磁盘缓存，**不额外下载**。
     * - **线程**：解码 + 量化全程在 [Dispatchers.Default]（[modelScope]）上，
     *   绝不占用 `backendScope`（Main / 桌面 EDT）—— 那会直接卡出帧。
     *
     * 与 [colorSourceFlow] `combine` 而不是各自 collect：来源从 FIXED 切到 COVER 时
     * 必须**立刻**按当前曲目补一次取色 —— 此时封面 URL 并没有变化，只监听播放状态收不到事件。
     *
     * 同时负责**预热系统壁纸种子**（[wallpaperSeedFlow]）：那是「跟随封面但没在播放」
     * 的回退色。壁纸要读文件 + 解码（几十到几百毫秒），所以只能在这里的后台协程做，
     * 绝不能放进 `CpTheme` 的 composition —— 那会直接卡住首帧。
     */
    fun startCoverColorTracking() {
        if (coverColorTrackingStarted) return
        coverColorTrackingStarted = true
        modelScope.launch {
            combine(playback.state, colorSourceFlow) { st, source ->
                // 非 COVER 来源一律折算成 null ⇒ 不解码，并顺手清掉上一次的种子色。
                if (source == cp.player.app.ui.theme.ColorSource.COVER) st.currentTrack?.coverUrl
                else null
            }
                .distinctUntilChanged()
                // collectLatest：切歌时**取消**上一次还在跑的下载/解码。
                // 原先用 collect 是串行排队的 —— 快速连切 5 首就要等前 4 次解码跑完，
                // 用户看到的主题色是好几首之前的。dedup 也一并交给 distinctUntilChanged，
                // 不再需要跨 collect 共享的 `lastUrl` 可变状态。
                .collectLatest { url ->
                    if (url.isNullOrBlank()) {
                        _coverSeed.value = null
                        return@collectLatest
                    }
                    _coverSeed.value = resolveCoverSeed(url)
                }
        }
        modelScope.launch {
            // 一个进程只尝试一次：壁纸在运行中不会变，失败（没有壁纸 / 是动态壁纸）
            // 也不该在用户每次切来源时重试一遍解码。
            var attempted = false
            colorSourceFlow.collect { source ->
                if (source != cp.player.app.ui.theme.ColorSource.COVER || attempted) return@collect
                attempted = true
                _wallpaperSeed.value =
                    runCatching { cp.player.app.ui.theme.platformWallpaperSeed() }.getOrNull()
            }
        }
    }

    /** 取封面种子色：先查缓存，未命中才解码 + 量化。任何异常都退化为 null。 */
    private suspend fun resolveCoverSeed(url: String): androidx.compose.ui.graphics.Color? {
        cp.player.app.ui.theme.CoverSeedCache.get(url)?.let { return it }
        val bitmap = runCatching {
            cp.player.app.ui.theme.loadCoverBitmap(
                url,
                cp.player.app.ui.theme.CoverSampleSizePx,
            )
        }.getOrNull()
        // `loadCoverBitmap` 内部的 runCatching 会把 CancellationException 也吞掉
        // （Coil 被取消时返回 null 而不是抛出）。若本次已被 collectLatest 取消，
        // 不主动停就会把「上一首的 null」写回 _coverSeed，覆盖掉新曲目的种子色。
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (bitmap == null) return null
        val seed = runCatching { cp.player.app.ui.theme.extractSeedColor(bitmap) }.getOrNull()
            ?: return null
        cp.player.app.ui.theme.CoverSeedCache.put(url, seed)
        return seed
    }

    // ============ 播放音质（持久化） ============

    /** 可选在线音质等级（level → 展示名）。 */
    val qualityOptions: List<Pair<String, String>> = listOf(
        "standard" to "标准",
        "exhigh" to "极高",
        "lossless" to "无损",
        "hires" to "Hi-Res",
    )

    private val _playbackQuality = MutableStateFlow(playbackQuality())
    val playbackQualityFlow: StateFlow<String> = _playbackQuality.asStateFlow()

    fun playbackQuality(): String = settings.getString(KEY_PLAYBACK_QUALITY) ?: "exhigh"

    /** 设置在线播放音质并立即同步到播放控制器（作用于后续加载的曲目）。 */
    fun setPlaybackQuality(level: String) {
        settings.putString(KEY_PLAYBACK_QUALITY, level)
        _playbackQuality.value = level
        runCatching { playback.setQuality(level) }
    }

    /** 启动时把持久化音质同步给播放控制器。 */
    fun syncPlaybackQuality() {
        runCatching { playback.setQuality(playbackQuality()) }
    }

    // ============ 本地服务器输出 + 外部推送（持久化） ============

    private val _localServerConfig = MutableStateFlow(LocalServerConfigStore.read(settings))

    /** 输出配置流（设置页开关 / 端口 / 接收端地址绑定）。 */
    val localServerConfigFlow: StateFlow<LocalServerConfig> = _localServerConfig.asStateFlow()

    /** 流输出服务状态（监听地址 / 启动错误），由后端转发。 */
    val localServerStatus: StateFlow<LocalServerStatus> get() = backend.localServerStatus

    /** 最近一次推送结果（UI 展示成败）。 */
    val lastPushResult: StateFlow<cp.player.core.control.PushResult?> get() = backend.lastPushResult

    /** 当前配置快照。 */
    fun localServerConfig(): LocalServerConfig = _localServerConfig.value

    /**
     * 启用/停用本地服务器输出。
     *
     * 首次启用时自动生成访问令牌——一旦监听在 `0.0.0.0`，没有令牌等于把
     * 本机音频流暴露给同网段任何人。
     */
    fun setLocalServerEnabled(enabled: Boolean) {
        val current = _localServerConfig.value
        val token = if (enabled && current.accessToken.isBlank()) {
            LocalServerConfigStore.ensureToken(settings)
        } else {
            current.accessToken
        }
        updateLocalServer(current.copy(enabled = enabled, accessToken = token))
    }

    /** 设置音频输出目标：本机声卡 / 只做服务器（本机静音）。 */
    fun setOutputMode(mode: OutputMode) {
        updateLocalServer(_localServerConfig.value.copy(outputMode = mode))
    }

    /** 设置流输出绑定地址（[LocalServerConfig.BIND_LOOPBACK] 或 [LocalServerConfig.BIND_ALL]）。 */
    fun setLocalServerBind(address: String) {
        if (address !in LocalServerConfig.BIND_OPTIONS) return
        updateLocalServer(_localServerConfig.value.copy(bindAddress = address))
    }

    /** 设置流输出端口（非法值回退默认）。 */
    fun setLocalServerStreamPort(port: Int) {
        updateLocalServer(
            _localServerConfig.value.copy(
                streamPort = LocalServerConfig.normalizePort(port, LocalServerConfig.DEFAULT_STREAM_PORT),
            )
        )
    }

    /** 设置接收端基地址（如 `http://127.0.0.1:8420`）。 */
    fun setReceiverBaseUrl(url: String) {
        updateLocalServer(_localServerConfig.value.copy(receiverBaseUrl = url.trim()))
    }

    /** 曲目变化时是否自动推送到接收端。 */
    fun setPushEnabled(enabled: Boolean) {
        updateLocalServer(_localServerConfig.value.copy(pushEnabled = enabled))
    }

    /** 是否开放媒体面（`/stream`）。默认开，关掉后接收端拉流会得到 403。 */
    fun setExposeStream(enabled: Boolean) {
        updateLocalServer(_localServerConfig.value.copy(exposeStream = enabled))
    }

    /**
     * 是否开放数据面（`/api/v1/...`）。**默认关**，因为它扩大了攻击面。
     *
     * 打开时顺带**确保令牌存在**：非回环绑定下没有令牌 = 数据面全部 401，
     * 而用户看到的只是「明明打开了却连不上」，很难联想到要去重新生成令牌。
     * 与 [setLocalServerEnabled] 同一处理。
     */
    fun setExposeDataApi(enabled: Boolean) {
        val current = _localServerConfig.value
        val token = if (enabled && current.accessToken.isBlank()) {
            LocalServerConfigStore.ensureToken(settings)
        } else {
            current.accessToken
        }
        updateLocalServer(current.copy(exposeDataApi = enabled, accessToken = token))
    }

    /**
     * 是否允许远程播控写操作（`/api/v1/playback/...`）。**默认关**。
     *
     * ⚠️ 端点本身尚未实现（Phase 3）。开关先落配置，是为了让「用户可以预先决定
     * 要不要开放播控」这件事与实现解耦；在端点落地前打开它**不会**有任何效果。
     */
    fun setAllowRemoteControl(enabled: Boolean) {
        updateLocalServer(_localServerConfig.value.copy(allowRemoteControl = enabled))
    }

    /** 重新生成访问令牌，返回新值。 */
    fun regenerateLocalServerToken(): String {
        val token = LocalServerConfigStore.regenerateToken(settings)
        updateLocalServer(_localServerConfig.value.copy(accessToken = token))
        return token
    }

    /**
     * 按持久化配置恢复（应用启动时调用，幂等）。
     *
     * 关闭状态不做任何事，因此不会占用端口。
     */
    fun restoreLocalServer() {
        val config = _localServerConfig.value
        if (config.enabled) runCatching { backend.applyOutputConfig(config) }
    }

    // ---- 推送动作（供设置页手动触发） ----

    /** 探测接收端是否在线。 */
    fun probeReceiver(onResult: (cp.player.core.control.PushResult) -> Unit) {
        modelScope.launch { onResult(backend.probeReceiver()) }
    }

    /** 手动推送当前曲目。 */
    fun pushCurrentTrack(onResult: (cp.player.core.control.PushResult) -> Unit) {
        modelScope.launch { onResult(backend.pushCurrentTrack()) }
    }

    /** 手动推送当前队列。 */
    fun pushQueueToReceiver(onResult: (cp.player.core.control.PushResult) -> Unit) {
        modelScope.launch { onResult(backend.pushQueue()) }
    }

    /** 写盘 + 更新流 + 应用到后端。 */
    private fun updateLocalServer(config: LocalServerConfig) {
        LocalServerConfigStore.write(settings, config)
        _localServerConfig.value = config
        runCatching { backend.applyOutputConfig(config) }
    }

    // ============ 下载与本地媒体（转发后端门面） ============

    /** 媒体下载管理门面（前端唯一下载入口）。 */
    val downloads get() = backend.downloadManager

    /** 本地媒体源（扫描 / 导入 / 下载产物登记）。 */
    val localMedia get() = backend.localMedia

    // ============ 下载目录（持久化，key 与 DownloadConfig 保持一致） ============

    /** SettingsStorage key：自定义下载根目录（与 [cp.player.core.download.DownloadConfig.KEY_DOWNLOAD_ROOT_DIR] 同值）。 */
    val KEY_DOWNLOAD_DIR: String = cp.player.core.download.DownloadConfig.KEY_DOWNLOAD_ROOT_DIR

    /** 下载管理器的配置实例（优先经 DownloadConfig 读写；装配异常时为 null）。 */
    private val downloadConfig: cp.player.core.download.DownloadConfig?
        get() = runCatching { downloads as? cp.player.core.download.MediaDownloadManagerImpl }
            .getOrNull()?.config

    private val _downloadDir = MutableStateFlow(downloadDir())
    val downloadDirFlow: StateFlow<String> = _downloadDir.asStateFlow()

    /** 当前下载目录（自定义优先，缺省平台默认下载目录）。 */
    fun downloadDir(): String =
        settings.getString(KEY_DOWNLOAD_DIR)?.takeIf { it.isNotBlank() }
            ?: downloadConfig?.rootDir
            ?: ""

    /** 更新下载根目录（经 DownloadConfig 写入，仅对后续下载生效）。 */
    fun setDownloadDir(path: String) {
        val cfg = downloadConfig
        if (cfg != null) cfg.setRootDir(path.takeIf { it.isNotBlank() })
        else settings.putString(KEY_DOWNLOAD_DIR, path)
        _downloadDir.value = downloadDir()
    }

    // ============ 下载封装（mediaId 解析 + 入队 + 提示） ============

    /** 把裸 id 解析为完整 mediaId（已含 scheme 时原样返回）。 */
    private fun resolveDownloadMediaId(id: String): String =
        if (id.contains("://")) id else "${activeProviderId()}://song/$id"

    /** 该 id（裸 id 或完整 mediaId）对应媒体是否已下载完成。 */
    fun isDownloaded(mediaId: String): Boolean =
        runCatching { downloads.isDownloaded(resolveDownloadMediaId(mediaId)) }.getOrDefault(false)

    /** 取消下载任务。 */
    fun cancelDownload(id: String) {
        runCatching { downloads.cancel(id) }
    }

    /** 下载单首歌曲（解析 mediaId/title/artist/coverUrl，AUDIO 入队）并提示「已加入下载」。 */
    fun downloadTrack(track: cp.player.core.music.TrackSummary, level: String = playbackQuality()) {
        modelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = runCatching {
                downloads.enqueue(
                    mediaId = resolveDownloadMediaId(track.id),
                    title = track.name,
                    artist = track.artist,
                    coverUrl = track.coverUrl,
                    mediaType = cp.player.core.media.MediaType.AUDIO,
                    level = level,
                )
            }.isSuccess
            cp.player.app.platform.sendPlatformToast(
                if (ok) "已加入下载" else "加入下载失败"
            )
        }
    }

    /** 批量入队下载（歌单「全部下载」用），完成后 toast 报告入队数量。 */
    fun downloadTracks(tracks: List<cp.player.core.music.TrackSummary>, level: String = playbackQuality()) {
        if (tracks.isEmpty()) {
            cp.player.app.platform.sendPlatformToast("没有可下载的歌曲")
            return
        }
        modelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var count = 0
            tracks.forEach { track ->
                val ok = runCatching {
                    downloads.enqueue(
                        mediaId = resolveDownloadMediaId(track.id),
                        title = track.name,
                        artist = track.artist,
                        coverUrl = track.coverUrl,
                        mediaType = cp.player.core.media.MediaType.AUDIO,
                        level = level,
                    )
                }.isSuccess
                if (ok) count++
            }
            cp.player.app.platform.sendPlatformToast("已加入 $count 首下载")
            cp.player.app.ui.util.UiEvents.notify("已加入 $count 首下载")
        }
    }

    // ============ 当前用户资料 ============

    data class UserProfile(
        val uid: Long,
        val nickname: String,
        val avatarUrl: String,
    )

    private val _userProfile = MutableStateFlow<UserProfile?>(null)
    val userProfileFlow: StateFlow<UserProfile?> = _userProfile.asStateFlow()

    private var profileRefreshJob: kotlinx.coroutines.Job? = null

    /** AppModel 内部协程域（资料/收藏刷新等后台任务）。 */
    private val modelScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )

    /**
     * 拉取当前登录用户资料（uid/昵称/头像），并顺带刷新收藏列表。
     * 未登录时清空资料。可在 App 启动、登录成功、登出后调用。
     */
    fun refreshUserProfile() {
        profileRefreshJob?.cancel()
        profileRefreshJob = modelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            refreshUserProfileAwait()
        }
    }

    /**
     * [refreshUserProfile] 的可挂起版本：登录/登出流程需要**拿到这次拉取的结果**
     * （多账号管理要把 uid/昵称/头像连同 cookie 一起存起来），所以把「取资料」拆出来。
     *
     * 返回 null 表示当前音源没有登录态（或拉取失败）。
     */
    suspend fun refreshUserProfileAwait(): UserProfile? {
        val profile = runCatching {
            val status = api.getLoginStatus()
            val root = status as? kotlinx.serialization.json.JsonObject ?: return@runCatching null
            val uid = extractUidFromLoginStatus(root) ?: return@runCatching null
            val data = unwrapLoginStatusData(root) ?: return@runCatching null
            val prof = (data["profile"] as? kotlinx.serialization.json.JsonObject)
                ?: (data["account"] as? kotlinx.serialization.json.JsonObject)
            UserProfile(
                uid = uid,
                nickname = (prof?.get("nickname") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "",
                avatarUrl = (prof?.get("avatarUrl") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "",
            )
        }.getOrNull()
        _userProfile.value = profile
        // 收藏列表与账号绑定，资料刷新后同步刷新
        runCatching { playback.refreshFavorites() }
        return profile
    }

    /** 清空当前用户资料（登出后调用）。 */
    fun clearUserProfile() {
        _userProfile.value = null
        modelScope.launch { runCatching { playback.refreshFavorites() } }
    }

    // ============ 最近播放（持久化） ============

    private val KEY_RECENT_TRACKS = "recent_tracks"
    private val RECENT_LIMIT = 30

    private val _recentTracks = MutableStateFlow(loadRecentTracks())
    val recentTracksFlow: StateFlow<List<cp.player.core.music.TrackSummary>> = _recentTracks.asStateFlow()

    private var historyRecorderStarted = false

    /** 启动播放历史记录（幂等）：监听当前曲目变化，去重后前移并持久化。 */
    fun startHistoryRecorder() {
        if (historyRecorderStarted) return
        historyRecorderStarted = true
        modelScope.launch {
            var lastRecordedId: String? = null
            playback.state.collect { st ->
                val track = st.currentTrack
                if (track != null && st.isPlaying && track.id != lastRecordedId) {
                    lastRecordedId = track.id
                    recordRecentTrack(track)
                }
            }
        }
    }

    private fun recordRecentTrack(track: cp.player.core.music.TrackSummary) {
        // update 为原子 CAS，避免与 enrich 回写的读改写窗口互覆
        var recorded: List<cp.player.core.music.TrackSummary>? = null
        _recentTracks.update { list ->
            ((listOf(track) + list.filter { it.id != track.id }).take(RECENT_LIMIT)).also {
                recorded = it
            }
        }
        recorded?.let { saveRecentTracks(it) }
    }

    private fun saveRecentTracks(tracks: List<cp.player.core.music.TrackSummary>) {
        runCatching {
            val array = kotlinx.serialization.json.buildJsonArray {
                tracks.forEach { t ->
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("id", kotlinx.serialization.json.JsonPrimitive(t.id))
                        put("name", kotlinx.serialization.json.JsonPrimitive(t.name))
                        put("artist", kotlinx.serialization.json.JsonPrimitive(t.artist))
                        put("album", kotlinx.serialization.json.JsonPrimitive(t.album ?: ""))
                        put("coverUrl", kotlinx.serialization.json.JsonPrimitive(t.coverUrl ?: ""))
                        put("durationMs", kotlinx.serialization.json.JsonPrimitive(t.durationMs))
                    })
                }
            }
            settings.putString(KEY_RECENT_TRACKS, array.toString())
        }
    }

    private fun loadRecentTracks(): List<cp.player.core.music.TrackSummary> {
        return runCatching {
            val raw = settings.getString(KEY_RECENT_TRACKS) ?: return emptyList()
            val array = kotlinx.serialization.json.Json.parseToJsonElement(raw)
                as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            array.mapNotNull { el ->
                val obj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                fun str(key: String) =
                    (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                val id = str("id")
                if (id.isBlank()) return@mapNotNull null
                cp.player.core.music.TrackSummary(
                    id = id,
                    name = str("name"),
                    artist = str("artist"),
                    album = str("album").ifBlank { null },
                    coverUrl = str("coverUrl").ifBlank { null },
                    durationMs = (obj["durationMs"] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.let { runCatching { it.content.toLong() }.getOrNull() } ?: 0L,
                )
            }
        }.getOrDefault(emptyList())
    }

    // ============ 最近播放数据补齐（修复历史数据缺失字段） ============

    private var recentEnrichStarted = false

    /**
     * 启动最近播放数据补齐（幂等）：对缺失封面等字段的历史条目，
     * 按当前 provider 批量拉取歌曲详情回填并重新持久化。
     */
    fun startRecentTracksEnrich() {
        if (recentEnrichStarted) return
        recentEnrichStarted = true
        modelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val current = _recentTracks.value
                if (current.isEmpty()) return@runCatching
                val providerId = activeProviderId()
                // 每条记录对应的可查询 mediaId（旧数据可能只存了裸 id）
                val withMediaIds = current.map { t ->
                    t to if (t.id.contains("://")) t.id else "$providerId://song/${t.id}"
                }
                val toFetch = withMediaIds.filter { (t, mid) ->
                    t.coverUrl.isNullOrBlank() && !mid.startsWith("local://")
                }
                if (toFetch.isEmpty()) return@runCatching
                val result = backend.unifiedSource.getTrackDetails(toFetch.map { it.second })
                val details = (result as? cp.player.core.BackendResult.Success)?.data
                    ?: return@runCatching
                if (details.isEmpty()) return@runCatching
                // mediaId → 详情；同时按裸 id 建索引（新记录条目可能已存完整 mediaId）
                val byMediaId = details.associateBy { it.id }
                val byBareId = details.associateBy {
                    it.id.substringAfterLast("/", it.id)
                }
                // 以「最新」列表为基线按 id 合并，仅回填缺失字段，
                // 保留挂起期间 historyRecorder 新写入的条目与顺序（避免旧快照整体覆盖）
                var mergedList: List<cp.player.core.music.TrackSummary>? = null
                var changed = false
                _recentTracks.update { latest ->
                    val merged = latest.map { t ->
                        val mediaId = if (t.id.contains("://")) t.id else "$providerId://song/${t.id}"
                        val fresh = byMediaId[mediaId] ?: byBareId[t.id]
                        ?: return@map t
                        if (fresh.coverUrl.isNullOrBlank()) return@map t
                        // 仅回填缺失字段（幂等：字段已有时保持原值）
                        t.copy(
                            coverUrl = t.coverUrl?.takeIf { it.isNotBlank() } ?: fresh.coverUrl,
                            album = t.album ?: fresh.album,
                            artist = t.artist.ifBlank { fresh.artist },
                            durationMs = t.durationMs.takeIf { it > 0 } ?: fresh.durationMs,
                        ).also { if (it != t) changed = true }
                    }
                    mergedList = merged
                    merged
                }
                if (changed) {
                    mergedList?.let { saveRecentTracks(it) }
                }
            }
        }
    }

    // ============ Provider 管理（封装 [MusicBackend] 并返回类型安全结果） ============

    fun availableProviders(): List<BackendProvider> = backend.getAvailableProviders()

    fun activeProvider(): BackendProvider? = backend.activeProvider()

    fun switchProvider(provider: BackendProvider): BackendResult<Unit> = backend.switchProvider(provider)

    /** 切换 Provider，返回是否成功（便捷版，错误信息存入 [lastSwitchError]）。 */
    var lastSwitchError: String? = null
        private set

    fun switchOrReport(provider: BackendProvider): Boolean {
        val result = backend.switchProvider(provider)
        lastSwitchError = (result as? BackendResult.Error)?.message
            ?: (result as? BackendResult.Unsupported)?.message
        // 音源隔离：cookie 是按音源存的，切过去之后要按**新音源的 cookie** 重新拉资料，
        // 否则界面会继续显示上一个音源的账号（用户看到的是「切了源但账号没换」）。
        if (result.isSuccess && isolationSwitchAccount()) {
            refreshUserProfile()
        }
        return result.isSuccess
    }

    /** 导入模块，自动激活（此前无活跃时），返回 [ImportResult]。 */
    fun importModule(zipPath: String): ImportResult = backend.importModule(zipPath)

    /** 删除模块，返回 [BackendResult]。 */
    fun deleteProvider(id: String): BackendResult<Unit> = backend.deleteModule(id)

    val lastLoadError: String? get() = backend.lastLoadError
}