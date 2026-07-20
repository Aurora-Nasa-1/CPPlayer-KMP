package cp.player.app

import cp.player.kmp.BackendResult
import cp.player.kmp.BackendState
import cp.player.kmp.ImportResult
import cp.player.kmp.MusicBackend
import cp.player.kmp.monitor.HealthMonitor
import cp.player.kmp.playback.PlaybackController
import cp.player.kmp.provider.BackendProvider
import cp.player.kmp.provider.ProviderCookieStorage
import cp.player.kmp.util.SettingsStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

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
    val settings: SettingsStorage get() = cp.player.kmp.util.defaultSettingsStorage()

    /** 直通版音乐 API（无缓存，cookie 自动注入 + 健康记录）。 */
    val api: cp.player.kmp.api.MusicApiService get() = backend.musicApi

    /** 带缓存的音乐 API（先返回缓存，后台拉取，指纹比对，差异 Fresh）。 */
    val cachedApi: cp.player.kmp.cache.CachedMusicApiService get() = backend.cachedApi

    /** 当前活跃 Provider 唯一 ID（无活跃时返回 "default"）。 */
    fun activeProviderId(): String = backend.activeProviderId()

    val health: HealthMonitor get() = backend.health

    /** 播放控制器（前端唯一播放入口；UI 只 collect 其 state）。 */
    val playback: PlaybackController get() = backend.playbackController

    fun markInitialized() { _initialized.value = true }

    // ============ 设置（持久化） ============

    private val KEY_THEME_MODE = "theme_mode"
    private val KEY_DYNAMIC_COLOR = "dynamic_color"
    private val KEY_PURE_BLACK = "pure_black"
    private val KEY_PLAYBACK_QUALITY = "playback_quality"

    private val _themeMode = MutableStateFlow(themeMode())
    val themeModeFlow: StateFlow<cp.player.app.ui.theme.ThemeMode> = _themeMode.asStateFlow()

    private val _dynamicColor = MutableStateFlow(dynamicColor())
    val dynamicColorFlow: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    private val _pureBlack = MutableStateFlow(pureBlack())
    val pureBlackFlow: StateFlow<Boolean> = _pureBlack.asStateFlow()

    fun themeMode(): cp.player.app.ui.theme.ThemeMode =
        runCatching { cp.player.app.ui.theme.ThemeMode.valueOf(settings.getString(KEY_THEME_MODE) ?: "SYSTEM") }
            .getOrDefault(cp.player.app.ui.theme.ThemeMode.SYSTEM)

    fun setThemeMode(mode: cp.player.app.ui.theme.ThemeMode) {
        settings.putString(KEY_THEME_MODE, mode.name)
        _themeMode.value = mode
    }

    fun dynamicColor(): Boolean = settings.getString(KEY_DYNAMIC_COLOR)?.toBooleanStrictOrNull() ?: false
    fun setDynamicColor(enabled: Boolean) {
        settings.putString(KEY_DYNAMIC_COLOR, enabled.toString())
        _dynamicColor.value = enabled
    }

    fun pureBlack(): Boolean = settings.getString(KEY_PURE_BLACK)?.toBooleanStrictOrNull() ?: false
    fun setPureBlack(enabled: Boolean) {
        settings.putString(KEY_PURE_BLACK, enabled.toString())
        _pureBlack.value = enabled
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
            val profile = runCatching {
                val status = api.getLoginStatus()
                val root = status as? kotlinx.serialization.json.JsonObject ?: return@runCatching null
                val data = (root["data"] as? kotlinx.serialization.json.JsonObject) ?: root
                val account = (data["account"] as? kotlinx.serialization.json.JsonObject)
                val prof = (data["profile"] as? kotlinx.serialization.json.JsonObject) ?: account
                val uid = (account?.get("id") ?: prof?.get("userId"))
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull }
                    ?: return@runCatching null
                UserProfile(
                    uid = uid,
                    nickname = (prof?.get("nickname") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "",
                    avatarUrl = (prof?.get("avatarUrl") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "",
                )
            }.getOrNull()
            _userProfile.value = profile
            // 收藏列表与账号绑定，资料刷新后同步刷新
            runCatching { playback.refreshFavorites() }
        }
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
    val recentTracksFlow: StateFlow<List<cp.player.kmp.music.TrackSummary>> = _recentTracks.asStateFlow()

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

    private fun recordRecentTrack(track: cp.player.kmp.music.TrackSummary) {
        val updated = (listOf(track) + _recentTracks.value.filter { it.id != track.id })
            .take(RECENT_LIMIT)
        _recentTracks.value = updated
        saveRecentTracks(updated)
    }

    private fun saveRecentTracks(tracks: List<cp.player.kmp.music.TrackSummary>) {
        runCatching {
            val array = kotlinx.serialization.json.buildJsonArray {
                tracks.forEach { t ->
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("id", t.id)
                        put("name", t.name)
                        put("artist", t.artist)
                        put("album", t.album ?: "")
                        put("coverUrl", t.coverUrl ?: "")
                        put("durationMs", t.durationMs)
                    })
                }
            }
            settings.putString(KEY_RECENT_TRACKS, array.toString())
        }
    }

    private fun loadRecentTracks(): List<cp.player.kmp.music.TrackSummary> {
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
                cp.player.kmp.music.TrackSummary(
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
        return result.isSuccess
    }

    /** 导入模块，自动激活（此前无活跃时），返回 [ImportResult]。 */
    fun importModule(zipPath: String): ImportResult = backend.importModule(zipPath)

    /** 删除模块，返回 [BackendResult]。 */
    fun deleteProvider(id: String): BackendResult<Unit> = backend.deleteModule(id)

    val lastLoadError: String? get() = backend.lastLoadError
}