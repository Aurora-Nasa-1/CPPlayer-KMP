package cp.player.app

import androidx.compose.ui.input.key.KeyEvent
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
import cp.player.core.playback.PlaybackSessionSettings
import cp.player.core.provider.BackendProvider
import cp.player.core.provider.ProviderCookieStorage
import cp.player.core.util.SettingsStorage
import cp.player.app.repository.AuthRepository
import cp.player.app.repository.MusicRepository
import cp.player.app.repository.SocialRepository
import cp.player.app.shortcut.ShortcutAction
import cp.player.app.shortcut.ShortcutBinding
import cp.player.app.shortcut.UNBOUND_SHORTCUT_MARKER
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
 * 诊断页只读视图类型（转发 [HealthMonitor] 的嵌套类型）。
 *
 * UI 一律 import 这两个别名，不直接 import `core.monitor.*`——
 * 边界规则见 docs/dev/ARCHITECTURE.md §3.2。
 */
typealias HealthLevel = HealthMonitor.HealthLevel
typealias ApiCallRecord = HealthMonitor.ApiCallRecord

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

    /**
     * 「音源代际」：每次**用户主动**切换音源成功后 +1。
     *
     * 为什么不用 [activeProviderFlow] 当刷新信号：应用启动时后端会自动恢复上次的
     * Provider（null → 实例），它也会发一次 —— 按它刷新会让每个页面的 ScreenModel
     * 在启动时白拉一遍数据。代际计数只由真正的用户动作（[switchOrReport] /
     * 导入自动激活 / 更新自动激活）驱动，`drop(1)` 之后收到的必然是「用户切了源」，
     * 页面据此作废自己的按音源缓存并重新拉取。
     */
    private val _sourceGeneration = MutableStateFlow(0)
    val sourceGeneration: StateFlow<Int> = _sourceGeneration.asStateFlow()

    private fun bumpSourceGeneration() { _sourceGeneration.value += 1 }

    /**
     * 「账号代际」：当前登录账号（uid）**实际发生变化**时 +1 —— 覆盖登录 / 登出 / 多账号切换。
     *
     * 与 [sourceGeneration] 是同一种信号，只是驱动源不同：那个管「换了音源」，
     * 这个管「换了人」。绑定账号的数据（首页日推、侧栏「我的歌单」、收藏夹 …）要
     * 同时订阅两者 —— 只订 [sourceGeneration] 的话，同一个音源下换个账号，
     * 这些数据会继续显示上一个账号的内容。
     *
     * ⚠️ **启动时的那次资料恢复（null → uid）不算变化**。它是进程启动的一部分，
     * 各页面首屏本来就会用正确的 cookie 拉到正确账号的数据；再发一次信号等于让每个
     * 页面白拉一遍（与 [sourceGeneration] 刻意不用 [activeProviderFlow] 是同一个理由）。
     * 靠 [profileResolvedOnce] 把「首次恢复」与「之后真的登录了」区分开：
     * 首次恢复之后才比较 uid。
     */
    private val _accountGeneration = MutableStateFlow(0)
    val accountGeneration: StateFlow<Int> = _accountGeneration.asStateFlow()

    private fun bumpAccountGeneration() { _accountGeneration.value += 1 }

    /**
     * 首次资料恢复是否已经跑完（见 [accountGeneration]）。
     * 只在 Main 之外的 IO 上写，读也只在同一条 IO 链里 —— 加 `@Volatile` 只是为了让
     * 读取方看到最新值，不做复合原子性假设。
     */
    @Volatile
    private var profileResolvedOnce = false

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

    /**
     * 私信 / 联系人门面。
     *
     * 与 [musicRepository] 同样每次访问新建 —— 它只是 `MusicApiService` 的一层**无状态**
     * 解析包装（真正有状态、需要单例的是 [settings] 那一类）。
     */
    val socialRepository: SocialRepository get() = SocialRepository(backend.musicApi)

    val authRepository: AuthRepository get() = AuthRepository(backend.musicApi)

    /**
     * 睡眠定时「播完当前曲目后暂停」的哨兵值。
     *
     * 转发 [PlaybackController.SLEEP_AFTER_TRACK]：UI（SleepTimerDialog）不直接
     * import `core.playback.PlaybackController`，经这里取（ARCHITECTURE.md §3.2）。
     */
    val sleepAfterTrack: Int get() = PlaybackController.SLEEP_AFTER_TRACK

    /** 当前活跃 Provider 唯一 ID（无活跃时返回 "default"）。 */
    fun activeProviderId(): String = backend.activeProviderId()

    val health: HealthMonitor get() = backend.health

    /** 播放控制器（前端唯一播放入口；UI 只 collect 其 state）。 */
    val playback: PlaybackController get() = backend.playbackController

    /**
     * 一起听（房间生命周期 + 邀请闭环 + 心跳 + **指令同步**）。
     *
     * ### 为什么挂在应用级而不是页面的 ScreenModel
     * 「在房」是**跨页面**状态：用户进房后会去听歌、翻歌单，房间页早就出栈了，
     * 但心跳必须继续 —— 否则会被判定离开。挂在 ScreenModel 上会随页面销毁停掉，
     * 症状是「一离开房间页就掉线」。这里复用 [modelScope]（应用级协程域）。
     *
     * ### 心跳/指令上报的是「裸 id」而不是 mediaId
     * 房间协议里的 `songId` 是**音源内的资源 id**。把 `netease://song/123` 原样上报，
     * 对端 [cp.player.core.music.CPMediaId.parse] 解析出来的 providerId 会是它自己的，
     * 直接错位。所以这里拆出 `resourceId`，并在**音源不一致时干脆不发** ——
     * 宁可不上报，也不要往房间里塞一个别人解析不了的 id。
     *
     * ### 指令同步的接线（切歌 / 播放态 / 进度）
     * 引擎拿到 [playback] 后自己观察状态、自己应用远端指令（切歌 / seek / 暂停），
     * AppModel 只负责两件它才知道的事：mediaId ↔ 裸 songId 的互转。
     * `bareSongIdOf` 在 providerId 或 resourceType 对不上时返回 null ——
     * 引擎据此对「不属于当前音源的内容」整体静默（见引擎侧 KDoc）。
     */
    val listenTogether: cp.player.core.listentogether.ListenTogetherEngine by lazy {
        cp.player.core.listentogether.ListenTogetherEngine(
            backend = cp.player.core.listentogether.NeteaseListenTogetherBackend(backend.musicApi),
            scope = modelScope,
            myUserId = { userProfileFlow.value?.uid ?: 0L },
            heartbeatInfo = {
                val snapshot = playback.state.value
                val track = snapshot.currentTrack
                val parsed = track?.let {
                    runCatching { cp.player.core.music.CPMediaId.parse(it.id) }.getOrNull()
                }
                val current = backend.activeProviderId()
                if (parsed == null || parsed.providerId != current) {
                    null
                } else {
                    cp.player.core.listentogether.HeartbeatInfo(
                        songId = parsed.resourceId,
                        isPlaying = snapshot.isPlaying,
                        progressMs = snapshot.positionMs,
                    )
                }
            },
            playback = backend.playbackController,
            bareSongIdOf = { mediaId ->
                runCatching { cp.player.core.music.CPMediaId.parse(mediaId) }.getOrNull()
                    ?.takeIf {
                        it.providerId == backend.activeProviderId() &&
                            it.resourceType == "song"
                    }
                    ?.resourceId
            },
            mediaIdForSong = { songId -> "${backend.activeProviderId()}://song/$songId" },
        )
            // **自动启动**：引擎挂在应用级 scope 上，「在房」才能跨页面存活
            // （用户不可能一直停在房间页）。第一个读到本状态的组合点就会把它拉起来 ——
            // 实际是任意页面小播放器里那条 `ListenTogetherStrip`，
            // 所以「不在房间页也能一起听」不依赖用户手动进过房间页。
            .also { it.start() }
    }

    val listenTogetherState: StateFlow<cp.player.core.listentogether.ListenTogetherState>
        get() = listenTogether.state

    /**
     * 单曲点击的统一门面：**一起听进行中时，点歌 = 「下一首播放」**（不打断房间
     * 正在播的歌，通过队列同步广播给所有成员）；否则执行默认动作 [defaultPlay]。
     *
     * ### 为什么拦在这里而不是改每个播放引擎调用
     * 房间里点歌的语义从「立即大家一起听这首」变成「把这首排进共享队列」——
     * 立即播放 = GOTO 广播 = 打断所有成员正在听的歌；而队列追加经队列同步
     * REPLACE 广播，轮到它时自然 GOTO。「立即播放」类入口（队列弹层跳播、
     * 播放页控制）不走这里，保持原语义。
     *
     * @param mediaId 点击曲目的 mediaId（调用方已构造好）
     * @param defaultPlay 非一起听时的默认动作（通常是 `playQueue(ids, startIndex)`）
     */
    fun playTrackClicked(mediaId: String, defaultPlay: suspend () -> Unit) {
        val lt = listenTogetherState.value
        if (lt.inRoom && listenTogether.followEnabled) {
            modelScope.launch {
                runCatching { playback.addNextToQueue(mediaId) }
                cp.player.app.ui.util.UiEvents.notify("一起听中：已添加到下一首播放")
            }
        } else {
            modelScope.launch { defaultPlay() }
        }
    }

    fun markInitialized() { _initialized.value = true }

    fun retryBackendBootstrap() {
        _initialized.value = true
    }

    // ============ 设置（持久化） ============

    private val KEY_THEME_MODE = "theme_mode"
    private val KEY_APP_LANGUAGE = "app_language"

    /**
     * 旧键：只存「动态取色 开 / 关」。已被 [KEY_COLOR_SOURCE] 取代，
     * 但仍保留读取，用于一次性迁移（见 [colorSource]）。
     */
    private val KEY_DYNAMIC_COLOR_LEGACY = "dynamic_color"
    private val KEY_COLOR_SOURCE = "color_source"
    private val KEY_PURE_BLACK = "pure_black"
    private val KEY_FLUID_BACKGROUND = "fluid_background"
    private val KEY_PLAYBACK_QUALITY = "playback_quality"
    private val KEY_METERED_PLAYBACK_QUALITY = "playback_quality_metered"

    private val _themeMode = MutableStateFlow(themeMode())
    val themeModeFlow: StateFlow<cp.player.app.ui.theme.ThemeMode> = _themeMode.asStateFlow()

    /**
     * 应用显示语言。
     *
     * ⚠️ 切换语言**不需要重启**：`ProvideCpStrings` 把它写进 `LocalCpStrings`，
     * 而读它的 composable 会被 CompositionLocal 失效驱动重组合。
     * 真正做不到即时切换的是那些**在组合之外就把文案固化进状态**的调用点
     * （例如已经 push 进返回栈的 `StartupScreen("…")`）—— 那种地方要存取值函数 /
     * 枚举，别存现成的字符串。非组合侧需要文案时用 [strings]。
     */
    private val _appLanguage = MutableStateFlow(appLanguage())
    val appLanguageFlow: StateFlow<cp.player.app.i18n.AppLanguage> = _appLanguage.asStateFlow()

    fun appLanguage(): cp.player.app.i18n.AppLanguage =
        cp.player.app.i18n.AppLanguage.ofStorageKey(settings.getString(KEY_APP_LANGUAGE))

    fun setAppLanguage(language: cp.player.app.i18n.AppLanguage) {
        settings.putString(KEY_APP_LANGUAGE, language.storageKey)
        _appLanguage.value = language
    }

    /**
     * **非组合上下文**取文案的唯一入口。
     *
     * 用于 `UiEvents.notify(...)`、MediaSession / 通知文案这类「没有 Composable 作用域、
     * 但必须给用户看一句人话」的地方。每次调用现解析，成本只是一个属性读取，
     * 没必要缓存 —— 缓存反而会在切换语言后返回旧语言的文案。
     */
    fun strings(): cp.player.app.i18n.CpStrings = cp.player.app.i18n.CpStrings.of(appLanguage())

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

    // ============ 播放页流体背景（持久化） ============

    private val _fluidBackground = MutableStateFlow(fluidBackground())

    /**
     * 播放页是否使用流体背景（仿 Apple Music 的流动网格渐变）。
     *
     * **默认开**：这是播放页的主视觉，默认关掉等于「做了个没人看得见的功能」。
     * 低版本安卓（API < 33，没有 `RuntimeShader`）本来就自动回退成静态渐变，
     * 不需要用户为平台差异操心。
     *
     * 关掉的意义只有一条：**着色器在屏期间持续出帧**（见 `cpFluidBackground` 的 KDoc），
     * 低端机 / 想省电的用户需要一个开关。所以这里存的是用户偏好，不是能力探测 ——
     * 「这个平台跑不跑得动」由库自己判，别在设置层再猜一遍。
     */
    val fluidBackgroundFlow: StateFlow<Boolean> = _fluidBackground.asStateFlow()

    fun fluidBackground(): Boolean =
        settings.getString(KEY_FLUID_BACKGROUND)?.toBooleanStrictOrNull() ?: true

    fun setFluidBackground(enabled: Boolean) {
        settings.putString(KEY_FLUID_BACKGROUND, enabled.toString())
        _fluidBackground.value = enabled
    }

    // ============ 保留上次播放（持久化） ============

    private val _keepLastPlayback = MutableStateFlow(keepLastPlayback())

    /**
     * 启动时是否恢复上次的播放队列与进度（默认开，见
     * [PlaybackSessionSettings.DEFAULT_KEEP_LAST_PLAYBACK]）。
     *
     * 开关只在这里读写；**快照的落盘与恢复在播放内核**（`PlaybackControllerImpl`）。
     * 两者共用同一份 [SettingsStorage]（`defaultSettingsStorage()` 的共享实例）与
     * 同一组键常量，所以设置页一改、内核下一次落盘就按新值走，无需任何通知。
     */
    val keepLastPlaybackFlow: StateFlow<Boolean> = _keepLastPlayback.asStateFlow()

    fun keepLastPlayback(): Boolean =
        settings.getString(PlaybackSessionSettings.KEY_KEEP_LAST_PLAYBACK)?.toBooleanStrictOrNull()
            ?: PlaybackSessionSettings.DEFAULT_KEEP_LAST_PLAYBACK

    fun setKeepLastPlayback(enabled: Boolean) {
        settings.putString(PlaybackSessionSettings.KEY_KEEP_LAST_PLAYBACK, enabled.toString())
        // 关掉时立刻清掉已有快照：否则「关掉 → 再打开」之间旧队列一直躺在盘上，
        // 重新打开会恢复到一份早已过期的会话（期间可能换了音源 / 删了歌单）。
        if (!enabled) settings.remove(PlaybackSessionSettings.KEY_LAST_SESSION)
        _keepLastPlayback.value = enabled
    }

    // ============ 音效（持久化，Android 生效 / 桌面明示不支持） ============
    //
    // 这里只做三件事：
    // ① 读盘得到初值（键与编码在 `AudioEffectSettings`，与后端共用同一份常量）；
    // ② 把改变后的配置**同时**落盘与下发播放控制器；
    // ③ 暴露能力位供设置页决定是否禁用控件。
    //
    // ⚠️ 能力位**不在启动时缓存**：它是平台相关的常量，但订阅它要碰平台播放器
    // （Android 侧首次读会促使 ExoPlayer 申请音频会话）。用 `StateFlow` 惰性暴露、
    // 只在设置页真正渲染时读一次即可。

    private val _audioEffect =
        MutableStateFlow(cp.player.core.playback.AudioEffectSettings.read(settings))

    /** 当前音效配置（设置页渲染用）。 */
    val audioEffectFlow: StateFlow<cp.player.core.playback.AudioEffectConfig> =
        _audioEffect.asStateFlow()

    /** 当前音效配置快照。 */
    fun audioEffect(): cp.player.core.playback.AudioEffectConfig = _audioEffect.value

    /**
     * 音效能力（平台相关）。
     *
     * 读它会**触达平台播放器**（Android 侧会申请音频会话），
     * 所以只应由音效设置页调用 —— 别放进启动路径。
     */
    fun audioEffectCapabilities(): cp.player.core.playback.AudioEffectCapabilities =
        runCatching { playback.audioEffectCapabilities }
            .getOrDefault(cp.player.core.playback.AudioEffectCapabilities.NONE)

    /**
     * 覆盖式保存音效配置：先落盘、再下发、最后更新流。
     *
     * ### 顺序为什么是「盘 → 引擎 → 流」
     * - 先落盘：即使下发失败（平台不支持 / effect 被回收），用户的选择也不丢；
     * - 再下发：引擎拿到的永远是最新的一份；
     * - 最后更新流：UI 永远在看到新值的同时，盘上已经有一份了。
     *
     * 反过来的话，UI 会先变、用户在极短窗口里杀掉进程就丢设置。
     */
    fun setAudioEffect(config: cp.player.core.playback.AudioEffectConfig) {
        cp.player.core.playback.AudioEffectSettings.write(settings, config)
        runCatching { playback.setAudioEffect(config) }
        _audioEffect.value = config
    }

    /**
     * 启动时把持久化的音效配置同步给播放控制器。
     *
     * 与 [syncPlaybackQuality] 同一类：设置页不在监听链路上时（冷启动直接播放），
     * 引擎也必须按用户上次的设置走。
     */
    fun syncAudioEffect() {
        runCatching { playback.setAudioEffect(_audioEffect.value) }
    }

    // ============ 淡入淡出 ============

    private val _fade = MutableStateFlow(cp.player.core.playback.FadeSettings.read(settings))

    /** 当前淡入淡出配置（设置页渲染用）。 */
    val fadeFlow: StateFlow<cp.player.core.playback.FadeConfig> = _fade.asStateFlow()

    /** 当前淡入淡出配置快照。 */
    fun fade(): cp.player.core.playback.FadeConfig = _fade.value

    /**
     * 覆盖式保存淡入淡出配置：先落盘、再下发、最后更新流（顺序理由同 [setAudioEffect]）。
     *
     * 与音效不同的是**这里没有能力位要判**：淡入淡出只改音量，
     * 而「改音量」是所有平台都有的能力（[PlaybackController.setVolume] 是抽象成员，
     * 两端都实现了）。所以桌面端也能用，不需要像 PEQ 那样明示不支持。
     */
    fun setFade(config: cp.player.core.playback.FadeConfig) {
        cp.player.core.playback.FadeSettings.write(settings, config)
        runCatching { playback.setFade(config) }
        _fade.value = config
    }

    /**
     * 启动时把持久化的淡入淡出配置同步给播放控制器。
     *
     * 与 [syncAudioEffect] 同一类：冷启动直接播放时，引擎也必须按用户上次的设置走。
     * （控制器自己也会在构造期读一次盘；这里再同步一次是为了覆盖
     * 「控制器先于设置页创建、而设置页又改过值」的时序。）
     */
    fun syncFade() {
        runCatching { playback.setFade(_fade.value) }
    }

    // ============ 字体圆滑度（持久化，Google Sans Flex 的 ROND 轴） ============

    private const val KEY_FONT_ROUNDNESS = "font_roundness"

    private val _fontRoundness = MutableStateFlow(fontRoundness())

    /**
     * 用户自定义的字体圆滑度（0–100）。
     *
     * **null 表示「未自定义」**，渲染时回退到 [cp.player.app.platform.defaultFontRoundness]
     * （Android 16+ = 100，其余平台 = 0）。存 null 而不是把平台默认值写死进盘：
     * 平台默认是系统观感的一部分，将来调整判据时老用户应当自动跟上。
     */
    val fontRoundnessFlow: StateFlow<Int?> = _fontRoundness.asStateFlow()

    fun fontRoundness(): Int? =
        settings.getString(KEY_FONT_ROUNDNESS)?.toIntOrNull()?.coerceIn(0, 100)

    /** 当前**生效**的圆滑度：自定义值优先，未自定义时取平台默认。 */
    fun effectiveFontRoundness(): Int = fontRoundness() ?: cp.player.app.platform.defaultFontRoundness()

    /** 设置字体圆滑度；传 null 恢复「跟随平台默认」。 */
    fun setFontRoundness(value: Int?) {
        if (value == null) {
            settings.remove(KEY_FONT_ROUNDNESS)
        } else {
            settings.putString(KEY_FONT_ROUNDNESS, value.coerceIn(0, 100).toString())
        }
        _fontRoundness.value = value
    }

    // ============ 底部导航栏（持久化） ============

    private const val KEY_BOTTOM_BAR_AUTO_HIDE = "bottom_bar_auto_hide"

    private val _bottomBarAutoHide = MutableStateFlow(
        settings.getString(KEY_BOTTOM_BAR_AUTO_HIDE)?.toBooleanStrictOrNull() ?: true
    )

    /**
     * 窄屏（手机）底部导航栏是否**随内容上滑自动隐藏**（默认开）。
     *
     * 生效范围是整个窄屏布局的底栏（Android 手机 / 窄窗口）；桌面宽屏有侧栏，
     * 本来就没有底栏。实现见 [cp.player.app.ui.component.BottomBarHideState]。
     */
    val bottomBarAutoHideFlow: StateFlow<Boolean> = _bottomBarAutoHide.asStateFlow()

    fun setBottomBarAutoHide(enabled: Boolean) {
        settings.putString(KEY_BOTTOM_BAR_AUTO_HIDE, enabled.toString())
        _bottomBarAutoHide.value = enabled
    }

    // ============ 封面飞行动画（持久化） ============

    private const val KEY_COVER_FLIGHT_ANIMATION = "cover_flight_animation"

    private val _coverFlightAnimation = MutableStateFlow(
        settings.getString(KEY_COVER_FLIGHT_ANIMATION)?.toBooleanStrictOrNull() ?: true
    )

    /**
     * 点击封面时是否播放「封面飞行」共享元素动画（默认开）。
     *
     * 这条动画把被点的封面从列表位**飞向**落点：歌曲 → MiniPlayer 封面，
     * 歌单 → 详情页头部。它是跨越大半个屏幕的大面积位移，也是「点击后画面自己动起来」
     * 这一类动效里最显眼的一个；关掉后点击只保留页面自身的转场。
     *
     * 默认**开**：与加入开关之前的行为一致，老用户升级后观感不变。
     *
     * 消费点在 [cp.player.app.ui.anim.CoverFlight.start] —— 它在**每次起飞前**读一次
     * （经 [coverFlightAnimation]，读的是内存态不是磁盘），所以切换即时生效、不用重启。
     */
    val coverFlightAnimationFlow: StateFlow<Boolean> = _coverFlightAnimation.asStateFlow()

    /**
     * 当前是否启用封面飞行动画。
     *
     * 给非 Compose 侧（[cp.player.app.ui.anim.CoverFlight] 的点击回调）同步读取用；
     * UI 侧一律 collect [coverFlightAnimationFlow]。
     */
    fun coverFlightAnimation(): Boolean = _coverFlightAnimation.value

    fun setCoverFlightAnimation(enabled: Boolean) {
        settings.putString(KEY_COVER_FLIGHT_ANIMATION, enabled.toString())
        _coverFlightAnimation.value = enabled
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
    //
    // 两套档位，按当前网络二选一生效：
    // - 默认音质（WiFi / 非计费网络）—— `playbackQuality()`；
    // - 移动数据音质（蜂窝 / 计费热点，仅 Android 有意义）—— `meteredPlaybackQuality()`。
    // 生效值由 `effectivePlaybackQuality()` 解析；网络类型变化时
    // App.kt 订阅 `networkMeteredChanges()` 调 `onNetworkMeteredChanged()` 重同步。
    // `setQuality` 只作用于**后续加载**的曲目，切换网络不打断正在播的歌。

    /**
     * 可选在线音质等级（**只有落盘用的 level**，不含展示名）。
     *
     * ⚠️ 这里刻意**不存**展示名：以前是 `level to "标准"` 的二元组，但 `AppModel` 是
     * `object`，构造这行代码时还没有语言状态 ⇒ 英文界面下拉里会并排出现中文词。
     * 展示名统一查 `QualityStrings.labelOf(level)`（见 `i18n` 包），
     * 两处（设置页下拉 / 缓存明细行）用同一份词表。
     */
    val qualityLevels: List<String> = listOf("standard", "exhigh", "lossless", "hires")

    private val _playbackQuality = MutableStateFlow(playbackQuality())
    val playbackQualityFlow: StateFlow<String> = _playbackQuality.asStateFlow()

    fun playbackQuality(): String = settings.getString(KEY_PLAYBACK_QUALITY) ?: "exhigh"

    /** 设置在线播放音质并立即同步到播放控制器（作用于后续加载的曲目）。 */
    fun setPlaybackQuality(level: String) {
        settings.putString(KEY_PLAYBACK_QUALITY, level)
        _playbackQuality.value = level
        runCatching { playback.setQuality(effectivePlaybackQuality()) }
    }

    private val _meteredPlaybackQuality = MutableStateFlow(meteredPlaybackQuality())
    val meteredPlaybackQualityFlow: StateFlow<String> = _meteredPlaybackQuality.asStateFlow()

    /** 移动数据（计费网络：蜂窝 / 热点）下的音质档位。默认「标准」省流量。 */
    fun meteredPlaybackQuality(): String =
        settings.getString(KEY_METERED_PLAYBACK_QUALITY) ?: "standard"

    /** 设置移动数据音质；若当前正在计费网络，立即同步到播放控制器。 */
    fun setMeteredPlaybackQuality(level: String) {
        settings.putString(KEY_METERED_PLAYBACK_QUALITY, level)
        _meteredPlaybackQuality.value = level
        runCatching { playback.setQuality(effectivePlaybackQuality()) }
    }

    /**
     * 当前网络是否按计费网络处理（蜂窝 / 热点）。
     * 初始值由 App.kt 订阅 `networkMeteredChanges()` 的首发射纠正。
     */
    private val _networkMetered = MutableStateFlow(false)

    /** 按当前网络类型解析生效音质：计费网络用移动数据音质，其余用默认音质。 */
    fun effectivePlaybackQuality(): String =
        if (_networkMetered.value) meteredPlaybackQuality() else playbackQuality()

    /** 网络计费状态变化回调：更新内部状态，并把生效音质同步给播放控制器。 */
    fun onNetworkMeteredChanged(metered: Boolean) {
        if (_networkMetered.value == metered) return
        _networkMetered.value = metered
        syncPlaybackQuality()
    }

    /** 启动时把持久化音质同步给播放控制器。 */
    fun syncPlaybackQuality() {
        runCatching { playback.setQuality(effectivePlaybackQuality()) }
    }

    // ============ 歌词来源（持久化，对齐旧版三档模式） ============

    private val _lyricsSourceMode =
        MutableStateFlow(cp.player.core.api.LyricsSourceMode.fromKey(settings.getString(cp.player.core.api.LyricsSourceMode.SETTINGS_KEY)))
    val lyricsSourceModeFlow: StateFlow<cp.player.core.api.LyricsSourceMode> = _lyricsSourceMode.asStateFlow()

    fun lyricsSourceMode(): cp.player.core.api.LyricsSourceMode =
        cp.player.core.api.LyricsSourceMode.fromKey(settings.getString(cp.player.core.api.LyricsSourceMode.SETTINGS_KEY))

    /** 修改歌词来源模式。取词时现读，对下一次刷新歌词生效。 */
    fun setLyricsSourceMode(mode: cp.player.core.api.LyricsSourceMode) {
        settings.putString(cp.player.core.api.LyricsSourceMode.SETTINGS_KEY, mode.key)
        _lyricsSourceMode.value = mode
    }

    /**
     * 歌词来源注册表（统一来源体系）。
     *
     * 取代 [lyricsSourceMode] 成为**取词顺序的唯一事实源**：内置三源（边车 / AMLL / 音源）
     * 与第三方插件在同一个可排序列表里。旧模式键只在首次迁移时被读一次。
     */
    val lyricsSources: cp.player.core.lyrics.LyricsSourceRegistry
        get() = backend.lyricsSourceRegistry

    // ============ 桌面快捷键（持久化，桌面端消费） ============
    //
    // 动作清单与默认键位全部声明在 `cp.player.app.shortcut.ShortcutAction`，这里只管三件事：
    // ① 把用户改过的绑定落盘（键名 `shortcut_<actionId>`）；
    // ② 把「生效绑定」暴露成流，供设置页渲染；
    // ③ 给窗口级按键回调做一次「事件 → 动作」匹配。
    //
    // 三种存储状态必须分清（理由见 `UNBOUND_SHORTCUT_MARKER` 的 KDoc）：
    // - 没有记录   → 走 `defaultBinding`（将来调默认键位，这类用户跟着变）；
    // - `unbound`  → 用户明确解绑，**不再**回落到默认；
    // - 其它字符串 → 解析出的自定义绑定，解析失败才回落到默认。

    private const val KEY_SHORTCUT_PREFIX = "shortcut_"

    /** 生效绑定（action id → 绑定；值为 null 表示该动作已解绑）。 */
    private val _shortcutBindings = MutableStateFlow(resolveShortcutBindings())
    val shortcutBindingsFlow: StateFlow<Map<String, ShortcutBinding?>> = _shortcutBindings.asStateFlow()

    private fun resolveShortcutBindings(): Map<String, ShortcutBinding?> =
        ShortcutAction.entries.associate { action -> action.id to shortcutBinding(action) }

    /** 某个动作当前生效的绑定；null 表示未绑定。 */
    fun shortcutBinding(action: ShortcutAction): ShortcutBinding? {
        val raw = settings.getString(KEY_SHORTCUT_PREFIX + action.id)
            ?: return action.defaultBinding
        if (raw == UNBOUND_SHORTCUT_MARKER) return null
        return ShortcutBinding.parse(raw) ?: action.defaultBinding
    }

    fun setShortcutBinding(action: ShortcutAction, binding: ShortcutBinding) {
        settings.putString(KEY_SHORTCUT_PREFIX + action.id, binding.serialize())
        _shortcutBindings.value = resolveShortcutBindings()
    }

    /** 解绑（**不是**恢复默认：用户按「清除绑定」是想关掉它，不该把它又变回来）。 */
    fun unbindShortcut(action: ShortcutAction) {
        settings.putString(KEY_SHORTCUT_PREFIX + action.id, UNBOUND_SHORTCUT_MARKER)
        _shortcutBindings.value = resolveShortcutBindings()
    }

    /** 单个动作恢复默认键位。 */
    fun resetShortcut(action: ShortcutAction) {
        settings.remove(KEY_SHORTCUT_PREFIX + action.id)
        _shortcutBindings.value = resolveShortcutBindings()
    }

    /** 全部动作恢复默认键位。 */
    fun resetAllShortcuts() {
        ShortcutAction.entries.forEach { settings.remove(KEY_SHORTCUT_PREFIX + it.id) }
        _shortcutBindings.value = resolveShortcutBindings()
    }

    /**
     * 把一次按键事件匹配成动作；无命中返回 null。
     *
     * 全等匹配（见 [ShortcutBinding.matches]）⇒ 不需要像旧的硬编码 `when` 那样
     * 靠「把更长的组合排在前面」来防止 `Ctrl+Shift+←` 被 `Ctrl+←` 抢走。
     * 多个动作绑到同一个组合时取**声明顺序靠前**的那个（设置页会把冲突标出来）。
     */
    fun matchShortcut(event: KeyEvent): ShortcutAction? {
        val bindings = _shortcutBindings.value
        return ShortcutAction.entries.firstOrNull { action ->
            bindings[action.id]?.matches(event) == true
        }
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

    /** 在途的未读数刷新（见 [refreshUnreadMessages]）。 */
    private var unreadRefreshJob: kotlinx.coroutines.Job? = null

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
            val status = backend.musicApi.getLoginStatus()
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
        val previousUid = _userProfile.value?.uid
        _userProfile.value = profile
        // 换人了 ⇒ 发一次账号代际（见 [accountGeneration]）。首次恢复不算：
        // 那一刻各页面首屏还在用同一份 cookie 拉数据，多发一次就是白拉一遍。
        if (profileResolvedOnce && previousUid != profile?.uid) bumpAccountGeneration()
        profileResolvedOnce = true
        // 收藏列表与账号绑定，资料刷新后同步刷新
        runCatching { playback.refreshFavorites() }
        // 未读私信数同样绑定账号：登出 / 切号后角标必须跟着变，否则会留着上一个账号的数字。
        if (profile != null) refreshUnreadMessages() else _unreadMessages.value = 0
        // 私信轮询同样绑定账号（未登录时必须停）。
        syncMessageWatch()
        return profile
    }

    /**
     * 未读私信数（侧栏 / 顶栏角标）。
     *
     * 由 [refreshUnreadMessages] 主动拉取，**不做轮询** —— 这个值只在「应用启动 / 登录成功 /
     * 打开消息页」这几个时点有意义，后台定时打 `pl/count` 只是白白占连接。
     */
    private val _unreadMessages = MutableStateFlow(0)
    val unreadMessagesFlow: StateFlow<Int> = _unreadMessages.asStateFlow()

    fun refreshUnreadMessages() {
        // 取消在途的旧请求：切号/登出瞬间，上一个账号的慢响应不能覆盖新状态
        // （与 refreshUserProfile 的 profileRefreshJob 同一模式）。
        unreadRefreshJob?.cancel()
        unreadRefreshJob = modelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _unreadMessages.value = runCatching { socialRepository.getUnreadCount() }.getOrDefault(0)
        }
    }

    fun clearUnreadMessages() {
        _unreadMessages.value = 0
    }

    // ============ 私信新消息通知（本地轮询 → 系统通知） ============

    /**
     * 通知偏好（订阅表 / 提醒游标 / 首次引导标志）。
     *
     * ⚠️ 用 `by lazy` 而不是 `get()` —— 与 [settings] 同一个理由：桌面实现每次写入
     * **全量回写**整个文件，同 namespace 上多个实例会互相覆盖。
     */
    val messageNotifyPrefs: cp.player.app.notify.MessageNotifyPrefs by lazy {
        cp.player.app.notify.MessageNotifyPrefs(settings)
    }

    /**
     * 私信轮询服务。
     *
     * 挂在应用级 [modelScope]（不绑 Compose）—— 「有没有人给我发消息」这件事
     * 不该因为用户切到别的页面就停掉。**默认不启动**：见 [syncMessageWatch] 的三条闸门。
     */
    private val messageWatch: cp.player.app.notify.MessageWatchService by lazy {
        cp.player.app.notify.MessageWatchService(
            prefs = messageNotifyPrefs,
            providerId = { activeProviderId() },
            // 未登录就没有私信可谈 —— 也顺手挡住「登出瞬间旧账号的慢响应」。
            isReady = { userProfileFlow.value != null },
            fetchContacts = {
                when (val result = socialRepository.getContacts()) {
                    is cp.player.core.BackendResult.Success -> result.data
                    else -> emptyList()
                }
            },
            post = { cp.player.app.platform.postMessageNotification(it) },
        )
    }

    /**
     * 按当前状态起停轮询。**这是唯一的开关点** —— 登录态 / 总开关 / 订阅数
     * 任何一处变化都要调它，否则会出现「关掉了还在轮询」或「开了却不响」。
     *
     * 三条闸门缺一不可：
     * 1. 已登录（没账号谈不上私信）；
     * 2. 总开关开着（默认 false ⇒ 默认零请求）；
     * 3. 该音源至少有一个订阅（没有目标就不该占连接）。
     */
    fun syncMessageWatch() {
        val shouldRun = userProfileFlow.value != null &&
            messageNotifyPrefs.isMasterEnabled() &&
            messageNotifyPrefs.subscribedCount(activeProviderId()) > 0
        if (shouldRun) messageWatch.start(modelScope) else messageWatch.stop()
    }

    /** 该联系人是否已开启新消息通知（当前音源下）。 */
    fun isMessageNotifySubscribed(uid: Long): Boolean =
        messageNotifyPrefs.isSubscribed(activeProviderId(), uid)

    /** 当前音源下**全部**已开启推送的 uid（消息列表一次读回，避免逐行查询）。 */
    fun messageNotifySubscribedUids(): Set<Long> =
        messageNotifyPrefs.subscribedUids(activeProviderId())

    fun messageNotifySubscribedCount(): Int =
        messageNotifyPrefs.subscribedCount(activeProviderId())

    fun messageNotifyGuideDone(): Boolean = messageNotifyPrefs.isGuideDone()

    fun markMessageNotifyGuideDone() = messageNotifyPrefs.setGuideDone()

    fun messageNotifyMasterEnabled(): Boolean = messageNotifyPrefs.isMasterEnabled()

    fun setMessageNotifyMasterEnabled(enabled: Boolean) {
        messageNotifyPrefs.setMasterEnabled(enabled)
        syncMessageWatch()
        if (enabled) messageWatch.tickNow(modelScope)
    }

    /**
     * 切换某人的新消息通知。
     *
     * 首次开启某人时**自动把总开关带上**：用户点的是「开启这个人的通知」，
     * 如果因为总开关关着而毫无反应，他只会认为功能坏了。
     *
     * @return 切换后的状态
     */
    fun toggleMessageNotify(uid: Long, enabled: Boolean = !isMessageNotifySubscribed(uid)): Boolean {
        if (enabled && !messageNotifyPrefs.isMasterEnabled()) messageNotifyPrefs.setMasterEnabled(true)
        messageNotifyPrefs.setSubscribed(activeProviderId(), uid, enabled)
        syncMessageWatch()
        // 立刻跑一轮：用户刚开开关，马上看到效果才像「生效了」（否则最多等 45s）。
        if (enabled) messageWatch.tickNow(modelScope)
        return enabled
    }

    /**
     * 告诉轮询「用户此刻正开着谁的会话」。
     *
     * 开着的时候不弹这个人的通知（他已经看见了）。由 `ChatContent` 在进出组合时设置；
     * 传 null 表示离开。
     */
    fun setActiveChatPeer(uid: Long?) {
        messageWatch.activePeerUid = uid
    }

    /**
     * 通知被点击 → 待打开的会话。
     *
     * 走一条 Flow 而不是直接导航：点击可能发生在**进程刚被拉起、UI 还没组合**的时候
     * （Android 冷启动）。存成状态，`App.kt` 组合起来后自然会消费到。
     */
    private val _pendingMessageOpen = MutableStateFlow<MessageOpenRequest?>(null)
    val pendingMessageOpenFlow: StateFlow<MessageOpenRequest?> = _pendingMessageOpen.asStateFlow()

    fun onMessageNotificationClicked(providerId: String, peerUid: Long, title: String) {
        _pendingMessageOpen.value = MessageOpenRequest(providerId, peerUid, title)
    }

    fun consumePendingMessageOpen() {
        _pendingMessageOpen.value = null
    }

    /** 「点通知要打开的会话」。 */
    data class MessageOpenRequest(
        val providerId: String,
        val peerUid: Long,
        val title: String,
    )

    /** 清空当前用户资料（登出后调用）。 */
    fun clearUserProfile() {
        val wasLoggedIn = _userProfile.value != null
        _userProfile.value = null
        // 从「有账号」变成「没账号」同样是一次账号代际：绑定账号的数据必须清掉重拉，
        // 否则侧栏会留着上一个账号的歌单（见 [accountGeneration]）。
        if (profileResolvedOnce && wasLoggedIn) bumpAccountGeneration()
        _unreadMessages.value = 0
        // 登出即停轮询（订阅关系保留 —— 用户回来还想收）。
        syncMessageWatch()
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

    // ============ 听歌习惯（本地采集 + 聚合） ============
    //
    // 与上面的「最近播放」是**两套并行**的记录，刻意不合并：
    // - 最近播放只关心「听过哪些曲目」，一条曲目一条、需要按曲目去重前移；
    // - 听歌习惯要的是**时长与完成度**，同一曲目播 N 次就是 N 条。
    // 把两者塞进一个采集器必然互相带偏（去重逻辑会把时长记录吃掉）。

    /** 记录落盘目录来自后端持有的平台上下文（Android: filesDir；桌面: ~/.cpplayer）。 */
    private val insightsStore: cp.player.core.insights.InsightsStore by lazy {
        cp.player.core.insights.InsightsStore(
            cp.player.core.insights.InsightsStore.directoryUnder(
                cp.player.core.util.PlatformSupport.dataDir(backend.platformContext),
            ),
        )
    }

    private val _listeningRecords = MutableStateFlow<List<cp.player.core.insights.PlayRecord>>(emptyList())

    /** 全部原始听歌记录（升序）。习惯页的「最近」明细与日聚合都由它派生。 */
    val listeningRecordsFlow: StateFlow<List<cp.player.core.insights.PlayRecord>> =
        _listeningRecords.asStateFlow()

    private val _insightsSummary = MutableStateFlow(cp.player.core.insights.InsightsSummary())

    /** 概览 / 习惯两个 tab 的全部派生指标。 */
    val insightsSummaryFlow: StateFlow<cp.player.core.insights.InsightsSummary> =
        _insightsSummary.asStateFlow()

    private val _dailyInsights = MutableStateFlow<List<cp.player.core.insights.DailyAgg>>(emptyList())

    /** 日历墙的日聚合（升序）。 */
    val dailyInsightsFlow: StateFlow<List<cp.player.core.insights.DailyAgg>> = _dailyInsights.asStateFlow()

    private var listeningRecorderStarted = false

    /** 单次状态回调最多计入的时长。进程被挂起（Android Doze）后恢复时，两次回调可能隔很久。 */
    private val maxTickMs = 2_000L

    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_AGGRESSIVE_STANDBY = "standby_aggressive"
    private const val KEY_HEATMAP_GREEN = "insights_heatmap_green"

    /**
     * 一次「在途」收听会话。
     *
     * 可变字段刻意都收在这里而不是散在上面的采集协程里：会话只有
     * 「开一条 / 累加 / 关一条」三个动作，状态散落会让「暂停不关会话」这条规则
     * 在三个分支里各写一遍。
     */
    private class ListeningSession(
        val mediaId: String,
        val name: String,
        val artist: String,
        val provider: String,
        val startedAt: Long,
        var durationMs: Long,
    ) {
        var playedMs: Long = 0L

        /** 上次累加墙钟增量时的时刻。 */
        var lastTick: Long = startedAt

        /** 引擎报告过的最新位置，用于判定「是否播到尾部」。 */
        var lastPositionMs: Long = 0L
    }

    /** 「今天」的日期键（本地时区）。UI 与聚合共用，避免两处各取一次时钟导致跨零点不一致。 */
    fun todayKey(): String = cp.player.core.insights.Insights.dateKeyOf(
        cp.player.core.util.currentTimeMillis(),
    )

    /**
     * 启动听歌采集（幂等）。
     *
     * 会话判定（何时关一条记录）只有三种：**换曲 / 队列清空 / 进程退出**。
     * 暂停**不关**会话 —— 用户暂停后接着听同一首是常态，关掉会记成两条并把完成度算错。
     */
    fun startListeningRecorder() {
        if (listeningRecorderStarted) return
        listeningRecorderStarted = true
        modelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // 先把历史读进内存，让习惯页首帧就有数（否则要先播一首才看得到东西）。
            runCatching { insightsStore.loadAll() }
                .onSuccess { _listeningRecords.value = it.records }
            recomputeInsights()

            var session: ListeningSession? = null
            try {
                playback.state.collect { st ->
                    session = advanceSession(session, st)
                }
            } finally {
                // 进程退出（组合被 dispose ⇒ 协程取消）前尽力把在途会话落盘，
                // 否则「听到一半关掉应用」这一次会完全丢失。
                session?.let { closeSession(it) }
            }
        }
    }

    /**
     * 推进会话状态机（纯状态变换，不碰 IO）。
     *
     * ⚠️ 时长用**墙钟增量**累加，不用 `positionMs` 差：`seekTo` 会让 position 跳变，
     * 用差值会把一次拖动算成几十分钟收听。
     */
    private fun advanceSession(
        current: ListeningSession?,
        st: cp.player.core.playback.PlaybackUiState,
    ): ListeningSession? {
        val track = st.currentTrack
        val now = cp.player.core.util.currentTimeMillis()

        if (track == null) {
            current?.let { closeSession(it) }
            return null
        }

        var session = current
        if (session == null || session.mediaId != track.id) {
            current?.let { closeSession(it, reachedTailOf(st)) }
            session = ListeningSession(
                mediaId = track.id,
                name = track.name,
                artist = track.artist,
                provider = providerIdOf(track.id),
                startedAt = now,
                durationMs = track.durationMs.takeIf { it > 0 } ?: st.durationMs,
            )
        }

        if (st.isPlaying) {
            // clamp：进程被挂起后恢复时，两次回调的间隔可能是分钟级，
            // 不夹住会把「应用在后台睡了 10 分钟」算成 10 分钟收听。
            session.playedMs += (now - session.lastTick).coerceIn(0L, maxTickMs)
        }
        session.lastTick = now
        if (st.positionMs > 0) session.lastPositionMs = st.positionMs
        if (st.durationMs > 0) session.durationMs = st.durationMs
        return session
    }

    /** 曲目已播到尾部（引擎自然结束）的判据：位置到达时长末尾 1 秒内。 */
    private fun reachedTailOf(st: cp.player.core.playback.PlaybackUiState): Boolean =
        st.durationMs > 0 && st.positionMs >= st.durationMs - 1_000L

    private fun providerIdOf(mediaId: String): String =
        runCatching { cp.player.core.music.CPMediaId.parse(mediaId).providerId }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: activeProviderId().orEmpty()

    /** 关闭会话并落盘。多条路径都可能关同一个会话（换曲 / 清空 / 取消），本方法只被调用一次。 */
    private fun closeSession(session: ListeningSession, reachedTail: Boolean = false) {
        // 一秒以下不收：误触播放、拖动进度条时的瞬间起播不该污染统计与「连续天数」。
        if (session.playedMs < 1_000L) return

        val (completed, skipped) = cp.player.core.insights.Insights.classify(
            playedMs = session.playedMs,
            durationMs = session.durationMs,
            reachedTail = reachedTail,
        )
        val record = cp.player.core.insights.PlayRecord(
            id = newRecordId(session.startedAt),
            mediaId = session.mediaId,
            name = session.name,
            artist = session.artist,
            provider = session.provider,
            startedAt = session.startedAt,
            playedMs = session.playedMs,
            durationMs = session.durationMs,
            completed = completed,
            skipped = skipped,
            deviceId = deviceId(),
        )
        val written = runCatching { insightsStore.append(record) }.getOrDefault(false)
        _insightsWriteError.value = if (written) null else "听歌记录写入失败，这次收听可能没有计入统计"
        _listeningRecords.value = _listeningRecords.value + record
        recomputeInsights()
    }

    /**
     * 记录 id。
     *
     * 只用「开始时刻 + 随机数」而不是 UUID：两者碰撞概率都可忽略，而这一个不需要
     * 引入实验性的 `kotlin.uuid`，也不需要追加依赖。同步阶段它是去重主键，仅此而已。
     */
    private fun newRecordId(startedAt: Long): String =
        "$startedAt-${kotlin.random.Random.nextInt(0, Int.MAX_VALUE)}"

    /** 本机设备标识。设备同步接入前用固定串，接入后改为持久化的真实 deviceId。 */
    private fun deviceId(): String =
        settings.getString(KEY_DEVICE_ID)?.takeIf { it.isNotBlank() } ?: "local"

    private fun recomputeInsights() {
        val records = _listeningRecords.value
        val today = todayKey()
        _dailyInsights.value = cp.player.core.insights.Insights.aggregate(records)
        _insightsSummary.value = cp.player.core.insights.Insights.summarize(records, today)
    }

    /** 清空全部听歌记录（不可逆）。UI 必须先经二次确认。 */
    fun clearListeningRecords(): Boolean {
        _listeningRecords.value = emptyList()
        recomputeInsights()
        return runCatching { insightsStore.clear() }.getOrDefault(false)
    }

    private val _insightsWriteError = MutableStateFlow<String?>(null)

    /**
     * 最近一次听歌记录写入失败的原因；null 表示正常。
     *
     * 写盘失败**不能**打断播放（收尾路径上抛异常会把播放流程带崩），但也不能静默 ——
     * 静默吞掉的表现是「统计数字莫名其妙不涨」，用户只会以为功能坏了。
     * 由习惯页读它显示一条提示。
     */
    val insightsWriteErrorFlow: StateFlow<String?> = _insightsWriteError.asStateFlow()

    // ============ 激进保活（Android） ============

    private val _aggressiveStandby = MutableStateFlow(
        settings.getString(KEY_AGGRESSIVE_STANDBY)?.toBooleanStrictOrNull() ?: false,
    )

    /**
     * 是否启用激进保活。
     *
     * 默认关。开启后由平台侧持有 Wi-Fi / 组播锁，并在退到后台后维持一段在线窗口，
     * 让设备发现（换设备播放）在本机不处于前台时仍可能命中。
     * **代价是耗电**，所以必须由用户显式开启，且设置页要如实写明代价。
     */
    val aggressiveStandbyFlow: StateFlow<Boolean> = _aggressiveStandby.asStateFlow()

    fun setAggressiveStandby(enabled: Boolean) {
        settings.putString(KEY_AGGRESSIVE_STANDBY, enabled.toString())
        _aggressiveStandby.value = enabled
        cp.player.app.platform.applyAggressiveStandby(enabled)
    }

    /** 恢复持久化的保活设置（启动时调用一次）。 */
    fun restoreAggressiveStandby() = cp.player.app.platform.applyAggressiveStandby(_aggressiveStandby.value)

    // ============ 日历墙配色 ============

    private val _heatmapGreen = MutableStateFlow(
        settings.getString(KEY_HEATMAP_GREEN)?.toBooleanStrictOrNull() ?: false,
    )

    /**
     * 日历墙是否使用 GitHub 经典绿。
     *
     * 默认 false —— 跟随主题取色。默认成绿会让日历墙成为全应用唯一
     * 「不跟着封面取色变」的地方（设置页图标曾因同类问题改过一轮）。
     */
    val heatmapGreenFlow: StateFlow<Boolean> = _heatmapGreen.asStateFlow()

    fun setHeatmapGreen(enabled: Boolean) {
        settings.putString(KEY_HEATMAP_GREEN, enabled.toString())
        _heatmapGreen.value = enabled
    }

    // ============ 局域网设备发现 ============

    private const val KEY_DEVICE_NAME = "device_name"

    /**
     * 本机设备身份（首次访问时生成并**立即落盘**）。
     *
     * ⚠️ deviceId 必须在首次生成时就写入：它是设备表的去重键、将来的同步游标键 ——
     * 每次启动都重新生成的话，对端会把同一台机器当成不断出现的新设备。
     */
    private val _deviceIdentity: cp.player.core.sync.DeviceIdentity by lazy {
        val deviceId = settings.getString(KEY_DEVICE_ID)?.takeIf { it.isNotBlank() }
            ?: cp.player.core.sync.newDeviceId().also { settings.putString(KEY_DEVICE_ID, it) }
        val name = settings.getString(KEY_DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: cp.player.core.sync.defaultDeviceName()
                .also { settings.putString(KEY_DEVICE_NAME, it) }

        cp.player.core.sync.DeviceIdentity(
            deviceId = deviceId,
            name = name,
            platform = cp.player.core.sync.currentPlatformLabel(),
            appVersion = cp.player.app.version.AppVersion.fullVersion,
        )
    }

    /** 本机设备身份（名称、平台、版本）。设备页展示与信标广播都用它。 */
    val deviceIdentity: cp.player.core.sync.DeviceIdentity get() = _deviceIdentity

    private val _deviceDiscovery: cp.player.core.sync.SyncDiscovery by lazy {
        cp.player.core.sync.createSyncDiscovery(
            identity = _deviceIdentity,
            // 信标广播的端口 = 对端将来访问本机同步接口用的端口（复用 LocalServer 的端口）。
            resolveStreamPort = { localServerConfig().streamPort },
        )
    }

    /** 已发现的局域网设备（最新出现的排最前；含刚掉线的，按 lastSeenAt 现算在线与否）。 */
    val discoveredPeersFlow: StateFlow<List<cp.player.core.sync.PeerState>> get() = _deviceDiscovery.peers

    /** 发现层是否正在监听。 */
    val deviceDiscoveryRunningFlow: StateFlow<Boolean> get() = _deviceDiscovery.running

    /** 发现层启动失败原因；null 表示正常。 */
    val deviceDiscoveryErrorFlow: StateFlow<String?> get() = _deviceDiscovery.lastError

    /**
     * 发现层诊断计数。「搜不到设备」的三种真因（本机收不到任何包 / 收到了
     * 但不是 CPPlayer 的包 / 只收到自己的回环）在设备列表上都表现为同一个
     * 空白 —— 这组数字就是用来把它们区分开的，读法见 [DiscoveryStats]。
     */
    val deviceDiscoveryStatsFlow: StateFlow<cp.player.core.sync.DiscoveryStats>
        get() = _deviceDiscovery.stats

    // ---- 在局域网中可见 ----

    private const val KEY_LAN_VISIBLE = "lan_visible"

    private val _lanVisible = MutableStateFlow(
        settings.getString(KEY_LAN_VISIBLE)?.toBooleanStrictOrNull() ?: true,
    )

    /**
     * 是否在局域网中可见（持续广播信标并监听同网段的信标）。
     *
     * 默认**开**。曾按「最小暴露面」默认关，但实测暴露了一个更伤可用性的问题：
     * 「应用开着」和「停留在设备页」被当成了两件事 —— 用户手机明明一直开着
     * CPPlayer，电脑那边却永远搜不到，只能得到一个空白列表和一句「去打开设置页」。
     * 发现信标只有 ~200 字节、3 秒一发，耗电与暴露面都可忽略；而「可见」是
     * 无感同步与转移的**前提** —— 两台设备互相看不见，后面所有功能都不存在。
     */
    val lanVisibleFlow: StateFlow<Boolean> = _lanVisible.asStateFlow()

    fun setLanVisible(visible: Boolean) {
        settings.putString(KEY_LAN_VISIBLE, visible.toString())
        _lanVisible.value = visible
        if (visible) startDeviceDiscovery() else _deviceDiscovery.stop()
    }

    /**
     * 恢复局域网可见性（启动时调用）。
     *
     * 默认开 ⇒ 应用一启动就在局域网中可见、可被发现 —— 这就是「入口不明确」
     * 的最终答案：**没有入口**。用户不需要先翻到某个页面，两台设备只要都在
     * 运行 CPPlayer 就能互相看见。
     */
    fun restoreLanVisibility() {
        if (_lanVisible.value) startDeviceDiscovery()
    }

    /** 开始设备发现（幂等）。正常路径由 [restoreLanVisibility] / [setLanVisible] 驱动。 */
    fun startDeviceDiscovery() { _deviceDiscovery.start() }

    /**
     * 停止设备发现。
     *
     * ⚠️ 「在局域网中可见」开着时这里是**空操作**：自动同步与转移都依赖
     * 发现层持续在线 —— 关闭设备页不等于用户想让本机从局域网里消失
     * （页面关闭即停的旧策略正是「手机开着、电脑搜不到」的直接原因）。
     * 真正下线的路径是 [setLanVisible]（false），那会直接停掉发现层。
     */
    fun stopDeviceDiscovery() {
        if (!_lanVisible.value) _deviceDiscovery.stop()
    }
    // ============ 局域网同步（听歌记录） ============

    private const val KEY_LAN_SYNC = "lan_sync_enabled"

    /** 自动同步的节奏。60s：对个人设备而言开销可忽略，且能让「刚打开另一台」很快被感知。 */
    private const val SYNC_INTERVAL_MS = 60_000L

    /** 单次同步最多接收的记录数。同步面未认证，这是防灌水的第二道闸（第一道是字段校验）。 */
    private const val SYNC_MAX_INGEST = 5_000

    /** 局域网同步的运行状态（给设备页展示）。 */
    data class LanSyncState(
        val serverRunning: Boolean = false,
        val error: String? = null,
        val lastSyncAt: Long? = null,
        val lastSyncSummary: String = "",
    )

    private val _lanSyncEnabled = MutableStateFlow(
        settings.getString(KEY_LAN_SYNC)?.toBooleanStrictOrNull() ?: false,
    )

    /**
     * 是否开启局域网同步。
     *
     * ⚠️ **默认关，且必须如实告知代价**：开启后本机会在局域网监听一个未认证的
     * 同步端口，同网段任何设备都能读写本机的**听歌记录**（能读写的仅此一项 ——
     * 不含账号、凭据、歌单、收藏）。之所以仍按用户要求做成「无感」，
     * 是因为逐台输配对码就是把「入口不明确」换成另一种摩擦；配对（方案 §3.3）
     * 落地后应替换成令牌鉴权。
     */
    val lanSyncEnabledFlow: StateFlow<Boolean> = _lanSyncEnabled.asStateFlow()

    private val _lanSyncState = MutableStateFlow(LanSyncState())

    /** 同步服务与最近一次交换的结果。 */
    val lanSyncStateFlow: StateFlow<LanSyncState> = _lanSyncState.asStateFlow()

    private var syncServer: cp.player.core.sync.SyncTransport.Server? = null
    private var syncLoopJob: kotlinx.coroutines.Job? = null

    fun setLanSyncEnabled(enabled: Boolean) {
        settings.putString(KEY_LAN_SYNC, enabled.toString())
        _lanSyncEnabled.value = enabled
        if (enabled) startLanSync() else stopLanSync()
    }

    /**
     * 恢复持久化的同步开关（启动时调用）。
     *
     * **开启时这里就是「启动时自动尝试同步」的入口**：服务端开始监听、
     * 发现层开始跑、同步循环先立刻交换一次再进入定时轮询。
     */
    fun restoreLanSync() {
        if (_lanSyncEnabled.value) startLanSync()
    }

    // ============ 无缝转移播放 ============

    /**
     * 一次转移的**状态**（不是文案）。
     *
     * ### 为什么不能只发一个字符串
     *
     * 旧版这里发的是成品中文串，UI 靠 `msg.startsWith("转移失败")` 决定配色 ——
     * **状态与文案耦死了**：文案一改（翻译 / 改措辞），那个判断静默失配，
     * 失败提示会显示成普通信息色，且**没有任何编译或测试能发现**。
     *
     * 所以这里只发「发生了什么」，`textOf(strings)` 在渲染那一刻才组出文案。
     * 判成败看 [HandoffState.failed]，与用哪个语言无关。
     */
    sealed interface HandoffState {
        /** 本机没有正在播放的曲目，无从转移。 */
        data object NoTrack : HandoffState

        /** 正在转移中。 */
        data class Starting(val deviceName: String) : HandoffState

        /** 转移成功：本机已暂停、进度保留。 */
        data class Done(val deviceName: String) : HandoffState

        /** 转移失败；本机继续播放。 [reason] 是协议层给的原因，可能为空。 */
        data class Failed(val reason: String?) : HandoffState

        /** 本机是目标端，接管了对端的播放。 */
        data class TakenOver(val fromName: String, val trackName: String) : HandoffState

        /** 接管失败。 */
        data class TakeOverFailed(val fromName: String, val reason: String) : HandoffState

        /** 失败态 —— UI 据此上错误色。 */
        val failed: Boolean
            get() = this is Failed || this is TakeOverFailed || this is NoTrack

        /** 组出给用户看的文案（两种语言共用同一套判据）。 */
        fun textOf(strings: cp.player.app.i18n.CpStrings): String = when (this) {
            is NoTrack -> strings.standby.handoffNoTrack
            is Starting -> strings.standby.handoffStarting(deviceName)
            is Done -> strings.standby.handoffDone(deviceName)
            is Failed -> strings.standby.handoffFailed(
                reason?.takeIf { it.isNotBlank() } ?: strings.standby.noResponse,
            )
            is TakenOver -> strings.standby.handoffTakenOver(fromName, trackName)
            is TakeOverFailed -> strings.standby.handoffTakeOverFailed(fromName, reason)
        }
    }

    /** 最近一次转移的状态；`null` = 还没发起过。给设备页与设备选择弹层展示。 */
    private val _handoffState = MutableStateFlow<HandoffState?>(null)
    val handoffStateFlow: StateFlow<HandoffState?> = _handoffState.asStateFlow()

    /**
     * 把本机当前播放**无缝转移**到目标设备。
     *
     * ### 铁律：收到 READY（`accepted=true`）之前绝不动本机播放
     * 目标端是「真的出声了」才应答的 —— 先停再起会留下一段谁都不响的空窗。
     * 超时 / 拒绝 / 无响应时本机**什么都不做**，只提示失败；用户听感零损失。
     * 成功后本机只是**暂停**（保留进度与队列），随时可以一键接着放。
     *
     * ⚠️ 目标端必须开着「自动同步」（同步服务在监听）且已登录同一音源，
     * 否则它无法解析曲目 —— 这会表现为明确的失败提示，而不是静音。
     */
    fun handoffTo(address: String, deviceName: String) {
        val st = playback.state.value
        val track = st.currentTrack
        if (track == null) {
            _handoffState.value = HandoffState.NoTrack
            return
        }
        val wasPlaying = st.isPlaying
        val positionMs = st.positionMs
        // 队列一起带走：目标端重建后「下一首/上一首」在那边照样能用。
        // currentIndex 越界（乱序/随机模式下的边缘态）时按单首转移降级。
        val queue = st.queue.map {
            cp.player.core.sync.HandoffTrack(
                mediaId = it.mediaId,
                title = it.title,
                artist = it.artist,
                album = it.album,
                coverUrl = it.coverUrl,
                durationMs = it.durationMs,
            )
        }
        val queueIndex = st.currentIndex
            .takeIf { queue.isNotEmpty() && it in queue.indices && queue[it].mediaId == track.id }
            ?: -1
        modelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _handoffState.value = HandoffState.Starting(deviceName)
            val result = cp.player.core.sync.SyncTransport.handoff(
                address,
                cp.player.core.sync.HandoffRequest(
                    fromDeviceId = deviceIdentity.deviceId,
                    fromName = deviceIdentity.name,
                    mediaId = track.id,
                    trackName = track.name,
                    artist = track.artist,
                    positionMs = positionMs,
                    wasPlaying = wasPlaying,
                    sentAt = cp.player.core.util.currentTimeMillis(),
                    queue = queue,
                    queueIndex = queueIndex,
                ),
            )
            if (result?.accepted == true) {
                if (wasPlaying) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        playback.pause()
                    }
                }
                _handoffState.value = HandoffState.Done(deviceName)
            } else {
                _handoffState.value = HandoffState.Failed(result?.message)
            }
        }
    }

    /**
     * 接管对端推来的播放（目标端，suspend —— 应答前要等真的出声）。
     *
     * 播控线程约定与 `IntegrationService` 相同：写操作必须回
     * `Dispatchers.Main`（Android 主线程 / 桌面 EDT）。
     * 「READY」的判据是 `currentTrack` 变成请求的那首 —— 起流、URL 解析失败
     * 都过不了这一关，源端就会收到明确的失败并继续播放。
     */
    private suspend fun receiveHandoff(req: cp.player.core.sync.HandoffRequest): cp.player.core.sync.HandoffResult {
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            kotlinx.coroutines.withTimeoutOrNull(HANDOFF_START_TIMEOUT_MS) {
                // 有合法队列 → 用 playQueue 重建整条（当前曲目在下标处开始播，
                // URL 后台解析不阻塞），「下一首/上一首」在目标端照常可用；
                // 没有队列（旧对端 / 随机模式边缘态 / 校验降级）→ 单首路径。
                val wantId = if (req.queue.isNotEmpty() && req.queueIndex >= 0) {
                    playback.playQueue(req.queue.map { it.mediaId }, startIndex = req.queueIndex)
                    req.queue[req.queueIndex].mediaId
                } else {
                    playback.play(req.mediaId)
                    req.mediaId
                }
                // 等播放引擎真的换上这首（起流失败 / 音源未登录时永远不会换上）
                val deadline = cp.player.core.util.currentTimeMillis() + HANDOFF_START_TIMEOUT_MS - 500
                while (cp.player.core.util.currentTimeMillis() < deadline) {
                    if (playback.state.value.currentTrack?.id == wantId) break
                    kotlinx.coroutines.delay(150)
                }
                val st = playback.state.value
                if (st.currentTrack?.id != wantId) {
                    return@withTimeoutOrNull cp.player.core.sync.HandoffResult(
                        accepted = false,
                        // ⚠️ 这个串会**跨设备**送到源端显示，而两端语言可能不同。
                        // 彻底解决要把它改成协议级错误码（core 的改动，影响两端兼容），
                        // 本批不碰；此处保持既有行为 —— 它是诊断信息，不参与本地化，
                        // 界面上会作为「对端给出的原因」原样透出。
                        message = "本机无法播放该曲目（音源未登录或曲目不存在）",
                    )
                }
                if (req.positionMs > 1_500L) playback.seekTo(req.positionMs)
                if (!req.wasPlaying) playback.pause()
                cp.player.core.sync.HandoffResult(accepted = true)
            } ?: cp.player.core.sync.HandoffResult(
                accepted = false,
                message = "本机播放未能在时限内启动",
            )
        }
        val from = req.fromName.ifBlank { req.fromDeviceId.take(8) }
        _handoffState.value = if (result.accepted) {
            HandoffState.TakenOver(from, req.trackName)
        } else {
            // 失败原因来自对端（可能是它不认识的旧版本说法的中文），原样透出。
            HandoffState.TakeOverFailed(req.fromName.ifBlank { from }, result.message.orEmpty())
        }
        return result
    }

    /** 目标端等待「真的出声」的上限；必须小于客户端读超时（10s），给应答留出余量。 */
    private const val HANDOFF_START_TIMEOUT_MS = 7_000L

    private fun startLanSync() {
        if (syncServer == null) {
            val server = cp.player.core.sync.SyncTransport.Server(
                snapshotProvider = {
                    cp.player.core.sync.SyncSnapshot(
                        deviceId = deviceIdentity.deviceId,
                        name = deviceIdentity.name,
                        records = _listeningRecords.value,
                    )
                },
                onIncoming = { snapshot -> ingestRemote(snapshot) },
                onHandoff = { req -> receiveHandoff(req) },
                onStateChanged = { running, err ->
                    _lanSyncState.value = _lanSyncState.value.copy(serverRunning = running, error = err)
                },
            )
            server.start()
            syncServer = server
        }
        // 没有发现层就拿不到对端 IP —— 同步依赖它，这里顺手确保它在跑。
        startDeviceDiscovery()
        if (syncLoopJob == null) {
            syncLoopJob = modelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                // 先立刻交换一次：这就是「启动时自动尝试同步」。
                syncWithOnlinePeers()
                while (true) {
                    kotlinx.coroutines.delay(SYNC_INTERVAL_MS)
                    syncWithOnlinePeers()
                }
            }
        }
    }

    private fun stopLanSync() {
        syncLoopJob?.cancel()
        syncLoopJob = null
        syncServer?.stop()
        syncServer = null
        _lanSyncState.value = _lanSyncState.value.copy(serverRunning = false)
    }

    /** 与当前所有在线设备各交换一次（双向：先拉并合并，再把并集推回去）。 */
    fun syncNow() {
        modelScope.launch(kotlinx.coroutines.Dispatchers.IO) { syncWithOnlinePeers() }
    }

    private suspend fun syncWithOnlinePeers() {
        val now = cp.player.core.util.currentTimeMillis()
        val online = cp.player.core.sync.Peers.online(_deviceDiscovery.peers.value, now)
        if (online.isEmpty()) {
            _lanSyncState.value = _lanSyncState.value
                .copy(lastSyncAt = now, lastSyncSummary = "没有在线设备")
            return
        }

        var contacted = 0
        var received = 0
        var pushed = 0
        val collected = ArrayList<cp.player.core.insights.PlayRecord>()

        online.forEach { peer ->
            val snapshot = cp.player.core.sync.SyncTransport.pull(peer.address) ?: return@forEach
            contacted++
            val mine = _listeningRecords.value
            val theirs = cp.player.core.sync.SyncMerge.sanitize(snapshot.records, now)
            // 推**并集**而不是只推自己的新增：对端按 id 去重后，缺的正好是它没有的
            // —— 一来一回两边都齐，这才是「无论谁新谁旧、交替使用」都成立的合并。
            val union = cp.player.core.sync.SyncMerge.merge(mine, theirs)
            pushed += cp.player.core.sync.SyncTransport.push(
                peer.address,
                cp.player.core.sync.SyncSnapshot(
                    deviceId = deviceIdentity.deviceId,
                    name = deviceIdentity.name,
                    records = union,
                ),
            )
            collected.addAll(cp.player.core.sync.SyncMerge.missing(mine, union))
        }

        val incoming = collected.take(SYNC_MAX_INGEST)
        if (incoming.isNotEmpty()) {
            var ok = 0
            incoming.forEach { r ->
                if (runCatching { insightsStore.append(r) }.getOrDefault(false)) ok++
            }
            received = ok
            _listeningRecords.value = _listeningRecords.value + incoming
            recomputeInsights()
        }

        _lanSyncState.value = _lanSyncState.value.copy(
            lastSyncAt = now,
            lastSyncSummary = "联系 $contacted 台 · 收到 $received 条 · 推送 $pushed 条",
        )
    }

    /**
     * 收下对端推来的记录：校验 → 去重 → 只把**本机没有的**落盘。
     *
     * 返回实际接受的条数（给应答里的 `accepted`，让对端能判断交换是否真的发生了）。
     * 落盘失败的那几条**仍然计入内存与统计** —— 磁盘问题不该让这次同步白做，
     * 但 [insightsWriteErrorFlow] 会亮出来。
     */
    private fun ingestRemote(snapshot: cp.player.core.sync.SyncSnapshot): Int {
        val now = cp.player.core.util.currentTimeMillis()
        val valid = cp.player.core.sync.SyncMerge.sanitize(snapshot.records, now)
        val missing = cp.player.core.sync.SyncMerge.missing(_listeningRecords.value, valid)
            .take(SYNC_MAX_INGEST)
        if (missing.isEmpty()) return 0

        var accepted = 0
        missing.forEach { r ->
            if (runCatching { insightsStore.append(r) }.getOrDefault(false)) accepted++
        }
        _insightsWriteError.value = if (accepted == missing.size) null else "部分听歌记录写入失败"
        _listeningRecords.value = _listeningRecords.value + missing
        recomputeInsights()
        return accepted
    }


    // ============ Provider 管理（封装 [MusicBackend] 并返回类型安全结果） ============

    fun availableProviders(): List<BackendProvider> = backend.getAvailableProviders()

    fun activeProvider(): BackendProvider? = backend.activeProvider()

    fun switchProvider(provider: BackendProvider): BackendResult<Unit> {
        val result = backend.switchProvider(provider)
        if (result is BackendResult.Success) bumpSourceGeneration()
        return result
    }

    /** 切换 Provider，返回是否成功（便捷版，错误信息存入 [lastSwitchError]）。 */
    var lastSwitchError: String? = null
        private set

    fun switchOrReport(provider: BackendProvider): Boolean {
        val result = backend.switchProvider(provider)
        lastSwitchError = (result as? BackendResult.Error)?.message
            ?: (result as? BackendResult.Unsupported)?.message
        if (result.isSuccess) {
            bumpSourceGeneration()
            // 音源隔离：cookie 是按音源存的，切过去之后要按**新音源的 cookie** 重新拉资料，
            // 否则界面会继续显示上一个音源的账号（用户看到的是「切了源但账号没换」）。
            if (isolationSwitchAccount()) {
                refreshUserProfile()
            }
        }
        return result.isSuccess
    }

    /** 导入模块，自动激活（此前无活跃时），返回 [ImportResult]。 */
    fun importModule(zipPath: String): ImportResult {
        val result = backend.importModule(zipPath)
        // 导入自动激活等价于一次用户切换：页面数据要跟着换源刷新。
        if (result is ImportResult.Activated) bumpSourceGeneration()
        return result
    }

    /**
     * 用 zip 包更新已安装模块（包内 manifest.id 必须与 [targetId] 一致），
     * 更新活跃 Provider 时自动重新激活新实例，返回 [ImportResult]。
     */
    fun updateModule(zipPath: String, targetId: String): ImportResult {
        val result = backend.updateModule(zipPath, targetId)
        if (result is ImportResult.Activated) bumpSourceGeneration()
        return result
    }

    /** 导出模块目录为 zip 文件（用于分享 / 备份音源模块），返回 [BackendResult]。 */
    fun exportModule(providerId: String, zipPath: String): BackendResult<Unit> =
        backend.exportModule(providerId, zipPath)

    /** 删除模块，返回 [BackendResult]。 */
    fun deleteProvider(id: String): BackendResult<Unit> = backend.deleteModule(id)

    val lastLoadError: String? get() = backend.lastLoadError
}