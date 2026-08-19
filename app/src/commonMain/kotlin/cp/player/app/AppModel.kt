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
import cp.player.app.repository.AuthRepository
import cp.player.app.repository.MusicRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val settings: SettingsStorage get() = cp.player.kmp.util.defaultSettingsStorage()

    /** Application-facing repository; new UI code should use this instead of raw API. */
    val musicRepository: MusicRepository get() = MusicRepository(backend.musicApi)

    val authRepository: AuthRepository get() = AuthRepository(backend.musicApi)

    /** Transitional raw API access for operations not migrated yet. */
    @Deprecated("Use musicRepository or a feature repository")
    val api: cp.player.kmp.api.MusicApiService get() = backend.musicApi

    /** 带缓存的音乐 API（先返回缓存，后台拉取，指纹比对，差异 Fresh）。 */
    @Deprecated("Use a repository method")
    val cachedApi: cp.player.kmp.cache.CachedMusicApiService get() = backend.cachedApi

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

    // ============ 下载与本地媒体（转发后端门面） ============

    /** 媒体下载管理门面（前端唯一下载入口）。 */
    val downloads get() = backend.downloadManager

    /** 本地媒体源（扫描 / 导入 / 下载产物登记）。 */
    val localMedia get() = backend.localMedia

    // ============ 下载目录（持久化，key 与 DownloadConfig 保持一致） ============

    /** SettingsStorage key：自定义下载根目录（与 [cp.player.kmp.download.DownloadConfig.KEY_DOWNLOAD_ROOT_DIR] 同值）。 */
    val KEY_DOWNLOAD_DIR: String = cp.player.kmp.download.DownloadConfig.KEY_DOWNLOAD_ROOT_DIR

    /** 下载管理器的配置实例（优先经 DownloadConfig 读写；装配异常时为 null）。 */
    private val downloadConfig: cp.player.kmp.download.DownloadConfig?
        get() = runCatching { downloads as? cp.player.kmp.download.MediaDownloadManagerImpl }
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
    fun downloadTrack(track: cp.player.kmp.music.TrackSummary, level: String = playbackQuality()) {
        modelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = runCatching {
                downloads.enqueue(
                    mediaId = resolveDownloadMediaId(track.id),
                    title = track.name,
                    artist = track.artist,
                    coverUrl = track.coverUrl,
                    mediaType = cp.player.kmp.media.MediaType.AUDIO,
                    level = level,
                )
            }.isSuccess
            cp.player.app.platform.sendPlatformToast(
                if (ok) "已加入下载" else "加入下载失败"
            )
        }
    }

    /** 批量入队下载（歌单「全部下载」用），完成后 toast 报告入队数量。 */
    fun downloadTracks(tracks: List<cp.player.kmp.music.TrackSummary>, level: String = playbackQuality()) {
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
                        mediaType = cp.player.kmp.media.MediaType.AUDIO,
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
        // update 为原子 CAS，避免与 enrich 回写的读改写窗口互覆
        var recorded: List<cp.player.kmp.music.TrackSummary>? = null
        _recentTracks.update { list ->
            ((listOf(track) + list.filter { it.id != track.id }).take(RECENT_LIMIT)).also {
                recorded = it
            }
        }
        recorded?.let { saveRecentTracks(it) }
    }

    private fun saveRecentTracks(tracks: List<cp.player.kmp.music.TrackSummary>) {
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
                val details = (result as? cp.player.kmp.BackendResult.Success)?.data
                    ?: return@runCatching
                if (details.isEmpty()) return@runCatching
                // mediaId → 详情；同时按裸 id 建索引（新记录条目可能已存完整 mediaId）
                val byMediaId = details.associateBy { it.id }
                val byBareId = details.associateBy {
                    it.id.substringAfterLast("/", it.id)
                }
                // 以「最新」列表为基线按 id 合并，仅回填缺失字段，
                // 保留挂起期间 historyRecorder 新写入的条目与顺序（避免旧快照整体覆盖）
                var mergedList: List<cp.player.kmp.music.TrackSummary>? = null
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
        return result.isSuccess
    }

    /** 导入模块，自动激活（此前无活跃时），返回 [ImportResult]。 */
    fun importModule(zipPath: String): ImportResult = backend.importModule(zipPath)

    /** 删除模块，返回 [BackendResult]。 */
    fun deleteProvider(id: String): BackendResult<Unit> = backend.deleteModule(id)

    val lastLoadError: String? get() = backend.lastLoadError
}