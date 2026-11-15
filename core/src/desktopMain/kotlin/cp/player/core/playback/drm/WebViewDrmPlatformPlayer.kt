package cp.player.core.playback.drm

import cp.player.core.music.DrmPlaybackInfo
import cp.player.core.playback.AudioFormatInfo
import cp.player.core.playback.PlaybackMetadata
import cp.player.core.playback.PlatformPlaybackState
import cp.player.core.playback.PlatformPlayer
import cp.player.core.playback.SeekFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * 桌面端 WebView EME DRM 播放器（Windows Widevine 路径）。
 *
 * 解密发生在浏览器内核（Edge / Chrome / WebView2 Runtime 的 Widevine CDM）
 * 里，宿主只做三件事：把媒体 playlist 经 [WebViewDrmServer] 归一化后喂给
 * 播放页；把 CDM challenge 经 Provider 的 `exchangeLicense` 换成 license
 * 原样交回 CDM；把页面事件映射成 [PlatformPlayer] 状态流。
 *
 * challenge / license 不落日志、不缓存；加密流不进磁盘缓存
 * （`PlaybackMediaLoader` 已在源头拦截，这里只是第二道防线）。
 */
class WebViewDrmPlatformPlayer internal constructor(
    private val transport: WebViewDrmTransport,
    private val launchBrowser: ((pageUrl: String) -> Unit)?,
    private val onRelease: (() -> Unit)?,
) : PlatformPlayer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<PlatformPlaybackState>(PlatformPlaybackState.Idle)
    override val state: StateFlow<PlatformPlaybackState> = _state.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _formatInfo = MutableStateFlow<AudioFormatInfo?>(null)
    override val formatInfo: StateFlow<AudioFormatInfo?> = _formatInfo.asStateFlow()

    override val seekFailures: SharedFlow<SeekFailure> = MutableSharedFlow()

    override val supportsDrm: Boolean = true

    override val supportsPlaybackSpeed: Boolean = true

    @Volatile
    private var emeReady = false

    /** 测试探针：确认 hello 事件已被状态机消费（避免测试与事件协程竞速）。 */
    internal val isEmeReady: Boolean
        get() = emeReady

    private var eventJob: Job? = null
    private var lastVolume: Float = 1f
    private var lastRate: Float? = null

    init {
        eventJob = scope.launch {
            transport.events.collect { event -> handleEvent(event) }
        }
    }

    /**
     * 生产构造入口：启动本地桥接服务并接上浏览器启动器。
     * 服务起不来（端口耗尽等）返回 null，宿主回退「无 DRM 能力」语义。
     */
    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000L

        fun create(): WebViewDrmPlatformPlayer? {
            val server = WebViewDrmServer()
            try {
                kotlinx.coroutines.runBlocking { server.start() }
            } catch (_: Exception) {
                return null
            }
            val browserSession = WebViewDrmBrowserSession()
            return WebViewDrmPlatformPlayer(
                transport = server,
                launchBrowser = browserSession::ensureLaunched,
                onRelease = {
                    server.stop()
                    browserSession.shutdown()
                },
            )
        }
    }

    override suspend fun load(
        url: String,
        startPositionMs: Long,
        headers: Map<String, String>,
        metadata: PlaybackMetadata?,
    ) {
        // 明文流不该进入本播放器；宿主误用时仍按页面协议装载（页面同样走 hls.js），
        // 并统一经代理归一化，避免上游清单里的相对 URI 解析失败。
        transport.send(
            WebViewPlayerCommand.Load(transport.playlistUrl(url), startPositionMs.coerceAtLeast(0)),
        )
    }

    override suspend fun loadDrm(
        url: String,
        drm: DrmPlaybackInfo,
        startPositionMs: Long,
        headers: Map<String, String>,
        metadata: PlaybackMetadata?,
    ) {
        requireWidevine(drm.keySystem)
        val exchange = drm.exchangeLicense
            ?: throw UnsupportedOperationException("DRM 描述缺少 exchangeLicense 回调")
        transport.setLicenseExchange { challenge -> exchange(challenge) }
        try {
            launchBrowser?.invoke(transport.pageUrl)
        } catch (error: Exception) {
            throw IllegalStateException(
                "WebView EME 播放器启动失败：${error.message ?: error::class.simpleName}",
                error,
            )
        }
        if (!transport.awaitPageConnected(CONNECT_TIMEOUT_MS)) {
            throw IllegalStateException(
                "WebView 播放页未在超时内就绪（浏览器未启动、EME 探测失败或 Widevine CDM 缺失）",
            )
        }
        if (!emeReady) {
            throw UnsupportedOperationException("当前浏览器内核不支持 Widevine EME")
        }
        val start = startPositionMs.coerceAtLeast(0)
        _state.value = PlatformPlaybackState.Buffering
        _positionMs.value = start
        _durationMs.value = 0L
        // 必须经 /manifest 归一化：Apple 的 ISO-23001-7 与相对 URI 直接交给
        // hls.js 会解析失败（详见 HlsPlaylistNormalizer）。
        transport.send(WebViewPlayerCommand.Load(transport.playlistUrl(url), start))
        lastRate?.let { transport.send(WebViewPlayerCommand.Rate(it)) }
        transport.send(WebViewPlayerCommand.Volume(lastVolume))
    }

    override fun play() {
        scope.launch { transport.send(WebViewPlayerCommand.Play) }
    }

    override fun pause() {
        scope.launch { transport.send(WebViewPlayerCommand.Pause) }
    }

    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0)
        _positionMs.value = target
        scope.launch { transport.send(WebViewPlayerCommand.Seek(target)) }
    }

    override fun stop() {
        scope.launch { transport.send(WebViewPlayerCommand.Stop) }
        _state.value = PlatformPlaybackState.Idle
        _positionMs.value = 0L
        _durationMs.value = 0L
    }

    override fun release() {
        eventJob?.cancel()
        eventJob = null
        scope.cancel()
        onRelease?.invoke()
    }

    override fun setVolume(volume: Float) {
        lastVolume = volume.coerceIn(0f, 1f)
        scope.launch { transport.send(WebViewPlayerCommand.Volume(lastVolume)) }
    }

    override fun getVolume(): Float = lastVolume

    override var playbackSpeed: Float = 1f
        private set

    override fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed
        lastRate = speed
        scope.launch { transport.send(WebViewPlayerCommand.Rate(speed)) }
    }

    private fun handleEvent(event: WebViewPlayerEvent) {
        when (event) {
            is WebViewPlayerEvent.Hello -> emeReady = event.eme
            is WebViewPlayerEvent.Progress -> {
                _positionMs.value = event.positionMs
                if (event.durationMs > 0) _durationMs.value = event.durationMs
            }
            is WebViewPlayerEvent.State -> _state.value = when (event.state) {
                "buffering" -> PlatformPlaybackState.Buffering
                "ready" -> PlatformPlaybackState.Ready
                "playing" -> PlatformPlaybackState.Playing
                "paused" -> PlatformPlaybackState.Paused
                "ended" -> PlatformPlaybackState.Ended
                "error" -> PlatformPlaybackState.Error(event.message ?: "WebView 播放失败")
                else -> return
            }
        }
    }

    private fun requireWidevine(keySystem: String) {
        val normalized = keySystem.trim().lowercase()
        val supported = setOf(
            "widevine",
            "com.widevine.alpha",
            "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed",
        )
        if (normalized !in supported) {
            throw UnsupportedOperationException("WebView EME 仅接入 Widevine，收到：$keySystem")
        }
    }
}

/**
 * 浏览器进程生命周期：进程活着就复用（换曲不重开窗口），退出后才重启。
 */
class WebViewDrmBrowserSession(
    private val userDataDir: Path = Path.of(
        System.getProperty("java.io.tmpdir"),
        "cpplayer-drm-profile",
    ),
) {

    @Volatile
    private var process: java.lang.Process? = null

    fun ensureLaunched(pageUrl: String) {
        val existing = process
        if (existing != null && existing.isAlive) return
        val executable = WebViewDrmBrowser.findExecutable()
            ?: throw UnsupportedOperationException("未找到可承载 Widevine 的浏览器内核（Edge / Chrome / WebView2 Runtime）")
        process = WebViewDrmBrowser.launch(Path.of(executable), pageUrl, userDataDir)
    }

    fun shutdown() {
        process?.destroy()
        process = null
    }
}
