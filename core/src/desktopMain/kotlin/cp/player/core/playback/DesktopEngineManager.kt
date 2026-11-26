package cp.player.core.playback

import cp.player.core.license.VipLicenseManager
import cp.player.core.music.DrmPlaybackInfo
import cp.player.core.playback.drm.WebViewDrmBrowser
import cp.player.core.playback.drm.WebViewDrmPlatformPlayer
import cp.player.core.util.SettingsStorage
import cp.player.core.util.defaultSettingsStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.logging.Logger

/**
 * 桌面端播放引擎**输出模式**。
 *
 * ⚠️ 两个值都跑同一个 Rust FlickEngine（解码 / DSP / 输出全在引擎内），
 * 差别只在 WASAPI 的共享 / 独占这一层：
 * - [FLICK_STANDARD]：WASAPI Shared。经 Windows 会话混音器输出，能与其他应用共存（**默认**）；
 * - [FLICK_EXCLUSIVE]：WASAPI Exclusive。独占声卡与采样率，Bit-Perfect 直通（VIP 特权，需显式开启）。
 *
 * 「系统引擎」这个模式**不存在** —— 桌面端没有 rodio / 系统播放器回退路径，
 * 原生库缺失时是直接无输出（见 `NoopDesktopPlatformPlayer`）。
 */
enum class DesktopPlayerEngineType {
    /** 标准共享模式（FlickEngine + WASAPI Shared 共享混音通道）。 */
    FLICK_STANDARD,

    /**
     * 高级发烧独占模式（FlickEngine + WASAPI Exclusive 独占直通 + Bit-Perfect 源码输出 + DSD 软解）。
     * 完全绕过 Windows 系统混音器，专供 VIP 用户，且必须由用户显式打开。
     */
    FLICK_EXCLUSIVE,
}

/**
 * 桌面端播放器引擎管理器，统一由 Rust FlickEngine 驱动，支持动态切换标准共享与 VIP 硬件独占直通。
 */
object DesktopEngineManager {
    private val logger = Logger.getLogger("DesktopEngineManager")

    /** 检查当前系统环境是否已就绪桌面 Rust FlickEngine 原生动态库 */
    val isHiResEngineSupported: Boolean
        get() = DesktopHiResEngine.isAvailable

    private var activeDynamicPlayer: DynamicDesktopPlatformPlayer? = null

    /**
     * 创建对应的桌面平台播放器（返回支持动态热切换的路由代理）。
     */
    fun createPlayer(
        preferredEngine: DesktopPlayerEngineType? = null,
        storage: SettingsStorage = defaultSettingsStorage(),
    ): PlatformPlayer = createPlayer(preferredEngine, storage, false)

    internal fun createPlayer(
        preferredEngine: DesktopPlayerEngineType? = null,
        storage: SettingsStorage,
        isVipUser: Boolean,
    ): PlatformPlayer {
        // 引擎特性开关是**能力位的一部分**（gapless / 直连流 / 原生交叉淡化 / 响度），
        // 底层播放器在构造期就会读它们。这里先对齐一次快照，保证「冷启动直接播放」
        // 也按用户上次的选择走，而不是等 AppModel 的启动同步。
        FlickEngineToggles.applyFrom(storage)
        val player = DynamicDesktopPlatformPlayer(storage, preferredEngine, isVipUser)
        activeDynamicPlayer = player
        return player
    }

    /** 主动通知当前播放器重载/热切换引擎模式 */
    fun notifyEngineChanged() {
        activeDynamicPlayer?.checkAndSwitchEngine(force = true)
    }
}

/**
 * 动态切换代理播放器：使桌面端无论何时在设置中切换标准/独占引擎模式、或者卡密激活时，都能即时无缝切换底层模式。
 */
internal class DynamicDesktopPlatformPlayer(
    private val storage: SettingsStorage,
    private val preferredEngine: DesktopPlayerEngineType?,
    private val isVipUser: Boolean,
) : PlatformPlayer {

    companion object {
        private val logger = Logger.getLogger("DynamicDesktopPlatformPlayer")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<PlatformPlaybackState>(PlatformPlaybackState.Idle)
    override val state: StateFlow<PlatformPlaybackState> = _state.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _formatInfo = MutableStateFlow<AudioFormatInfo?>(null)
    override val formatInfo: StateFlow<AudioFormatInfo?> = _formatInfo.asStateFlow()

    private val _seekFailures = MutableSharedFlow<SeekFailure>(extraBufferCapacity = 8)
    override val seekFailures: SharedFlow<SeekFailure> = _seekFailures.asSharedFlow()

    override val supportsExclusiveAudio: Boolean
        get() = activePlayer.supportsExclusiveAudio

    override val audioEffectCapabilities: AudioEffectCapabilities
        get() = activePlayer.audioEffectCapabilities

    // 引擎级通用能力必须逐个转发：DynamicDesktopPlatformPlayer 是 UI 唯一拿到的实例，
    // 少了任何一个 override，桌面端就会静默退回接口的「不支持」默认值。
    override val supportsPlaybackSpeed: Boolean
        get() = if (drmActive) webViewDrmPlayer?.supportsPlaybackSpeed ?: false else activePlayer.supportsPlaybackSpeed

    override val playbackSpeed: Float
        get() = if (drmActive) webViewDrmPlayer?.playbackSpeed ?: 1f else activePlayer.playbackSpeed
    override val supportsGaplessQueue: Boolean get() = activePlayer.supportsGaplessQueue
    override val nativeTrackTransitions: StateFlow<NativeTrackTransition?> get() = activePlayer.nativeTrackTransitions
    override val supportsNativeCrossfade: Boolean get() = activePlayer.supportsNativeCrossfade
    override val supportsSeekableRemoteStreams: Boolean get() = activePlayer.supportsSeekableRemoteStreams

    /**
     * 桌面 DRM 由 WebView EME 路径承载，与当前 Flick 引擎无关；
     * 内核存在即声明能力（`supportedDrmKeySystems()` 已做同一判断），
     * 否则 `PlaybackMediaLoader` 会在 `loadDrm` 之前直接拒绝。
     */
    override val supportsDrm: Boolean
        get() = if (drmActive) webViewDrmPlayer?.supportsDrm ?: false else WebViewDrmBrowser.isAvailable

    private var currentEngineType: DesktopPlayerEngineType = resolveDesiredEngine()
    private var activePlayer: PlatformPlayer = createUnderlyingPlayer(currentEngineType)
    private var forwardJob: Job? = null
    private var webViewForwardJob: Job? = null
    private var licenseJob: Job? = null

    /** 惰性创建的 WebView EME 播放器；null = 尚未创建（首次 DRM 装载时建）。 */
    private var webViewDrmPlayer: WebViewDrmPlatformPlayer? = null

    /**
     * 当前是否由 WebView DRM 路径接管播放。Flick 引擎与浏览器内核是两个
     * 独立音频出口，同一时刻只有一条路径处于活跃状态。
     */
    @Volatile
    private var drmActive: Boolean = false

    private var lastVolume: Float = 1.0f
    private var lastEffectConfig: AudioEffectConfig? = null
    /** 用户设定的倍速；null = 从未改过（引擎默认 1.0，不必重放）。 */
    private var lastPlaybackSpeed: Float? = null
    /** 用户设定的引擎侧交叉淡化（enabled, durationMs）。 */
    private var lastCrossfade: Pair<Boolean, Long>? = null

    init {
        bindUnderlyingPlayer(activePlayer)
        licenseJob = scope.launch(Dispatchers.Main) {
            VipLicenseManager.licenseState.collect { info ->
                if (currentEngineType == DesktopPlayerEngineType.FLICK_EXCLUSIVE) {
                    if (!info.isActivated) {
                        if (!isVipUser) switchEngine(DesktopPlayerEngineType.FLICK_STANDARD)
                    } else if (info.expiresAtEpochSeconds != null) {
                        DesktopHiResEngine.setExclusiveMode(true)
                    }
                } else if (info.isActivated) {
                    checkAndSwitchEngine()
                }
            }
        }
    }

    /**
     * 解析本次应当使用的输出模式。
     *
     * ### 两条硬规则（都对应过真实事故）
     * 1. **独占必须有 VIP**：卡密失效 / 未激活时一律回到共享，不复用上一次的独占会话；
     * 2. **独占绝不默认开启**：读 [PlaybackSessionSettings.KEY_WASAPI_EXCLUSIVE]，
     *    缺失即为 `false`。旧写法是「键不是 STANDARD 且是 VIP ⇒ 独占」，
     *    等于让老用户（甚至只激活过卡密、从没进过设置页的用户）在启动瞬间就被
     *    抢走声卡 —— 日志里那句 `SUCCESS: Initialized in EXCLUSIVE (Bit-Perfect) hardware mode!`
     *    就是这么来的。**用户没点过那个开关，就不该听到独占。**
     */
    private fun resolveDesiredEngine(): DesktopPlayerEngineType {
        val isVip = isVipUser || VipLicenseManager.isVipActive
        val wantsExclusive = preferredEngine?.let { it == DesktopPlayerEngineType.FLICK_EXCLUSIVE }
            ?: PlaybackSessionSettings.wasapiExclusive(storage)
        return if (isVip && wantsExclusive) {
            DesktopPlayerEngineType.FLICK_EXCLUSIVE
        } else {
            DesktopPlayerEngineType.FLICK_STANDARD
        }
    }

    private fun createUnderlyingPlayer(type: DesktopPlayerEngineType): PlatformPlayer {
        if (!DesktopEngineManager.isHiResEngineSupported) {
            println("[CPPlayer Engine Manager] Rust FlickEngine 原生动态库未就绪 (rust_lib_flick_player)")
            return NoopDesktopPlatformPlayer("FlickEngine 原生动态库未就绪")
        }

        return if (type == DesktopPlayerEngineType.FLICK_EXCLUSIVE) {
            println("""
================================================================================
[CPPlayer Engine Manager] ACTIVE: FlickEngine WASAPI Exclusive Mode (Bit-Perfect VIP)
[CPPlayer Engine Manager] Hardware Direct Output: Bypassing Windows Audio Mixer
================================================================================
            """.trimIndent())
            runCatching {
                DesktopHiResPlatformPlayer(isExclusive = true)
            }.getOrElse { err ->
                println("[CPPlayer Engine Manager] FlickEngine Exclusive 创建失败 ($err)，自动回退至 FlickEngine Standard 模式。")
                DesktopHiResPlatformPlayer(isExclusive = false)
            }
        } else {
            println("""
================================================================================
[CPPlayer Engine Manager] ACTIVE: FlickEngine Standard Mode (WASAPI Shared)
[CPPlayer Engine Manager] System Mixer Friendly Output
================================================================================
            """.trimIndent())
            runCatching {
                DesktopHiResPlatformPlayer(isExclusive = false)
            }.getOrElse { err ->
                NoopDesktopPlatformPlayer("FlickEngine 初始化失败: ${err.message}")
            }
        }
    }

    private fun bindUnderlyingPlayer(player: PlatformPlayer) {
        forwardJob?.cancel()
        forwardJob = scope.launch {
            launch {
                player.state.collect { st ->
                    // WebView DRM 活跃时引擎处于静默状态，其事件不得覆盖 DRM 状态。
                    if (drmActive) return@collect
                    _state.value = st
                    if (st is PlatformPlaybackState.Error && currentEngineType == DesktopPlayerEngineType.FLICK_EXCLUSIVE) {
                        println("[CPPlayer Engine Manager] FlickEngine 独占模式遇到错误: ${st.message}，自动降级至标准模式！")
                        switchEngine(DesktopPlayerEngineType.FLICK_STANDARD)
                    }
                }
            }
            launch { player.positionMs.collect { if (!drmActive) _positionMs.value = it } }
            launch { player.durationMs.collect { if (!drmActive) _durationMs.value = it } }
            launch { player.formatInfo.collect { if (!drmActive) _formatInfo.value = it } }
            launch { player.seekFailures.collect { if (!drmActive) _seekFailures.tryEmit(it) } }
        }
        player.setVolume(lastVolume)
        lastEffectConfig?.let { player.applyAudioEffect(it) }
        // 热切换（标准 ↔ 独占、VIP 状态变化）之后引擎是全新的，
        // 用户之前设过的倍速与淡化必须重放，否则一换引擎就悄悄回原速。
        lastPlaybackSpeed?.let { player.setPlaybackSpeed(it) }
        lastCrossfade?.let { (enabled, durationMs) -> player.setNativeCrossfade(enabled, durationMs) }
    }

    private fun switchEngine(target: DesktopPlayerEngineType) {
        if (target == currentEngineType) return
        println("[CPPlayer Engine Manager] Switching active engine from $currentEngineType to $target")
        val oldPlayer = activePlayer
        try {
            oldPlayer.stop()
            oldPlayer.release()
            DesktopHiResEngine.shutdownEngine()
        } catch (t: Throwable) {
            logger.warning("[ENGINE] Error releasing player: ${t.message}")
        }
        currentEngineType = target
        activePlayer = createUnderlyingPlayer(target)
        bindUnderlyingPlayer(activePlayer)
    }

    fun checkAndSwitchEngine(force: Boolean = false) {
        val desired = resolveDesiredEngine()
        if (desired != currentEngineType || force) {
            logger.info("[ENGINE] Switching engine from $currentEngineType to $desired")
            val oldPlayer = activePlayer
            try {
                oldPlayer.stop()
                oldPlayer.release()
                DesktopHiResEngine.shutdownEngine()
            } catch (t: Throwable) {
                logger.warning("[ENGINE] Error releasing previous player: ${t.message}")
            }
            currentEngineType = desired
            activePlayer = createUnderlyingPlayer(desired)
            bindUnderlyingPlayer(activePlayer)
        }
    }

    private fun obtainWebViewDrmPlayer(): WebViewDrmPlatformPlayer? {
        webViewDrmPlayer?.let { return it }
        val created = WebViewDrmPlatformPlayer.create() ?: return null
        webViewDrmPlayer = created
        webViewForwardJob?.cancel()
        webViewForwardJob = scope.launch {
            launch { created.state.collect { st -> if (drmActive) _state.value = st } }
            launch { created.positionMs.collect { if (drmActive) _positionMs.value = it } }
            launch { created.durationMs.collect { if (drmActive) _durationMs.value = it } }
        }
        created.setVolume(lastVolume)
        return created
    }

    /** 退出 DRM 模式：WebView 侧停播（浏览器与桥接服务保留，下一首 DRM 曲目可复用）。 */
    private fun leaveDrmMode() {
        if (!drmActive) return
        drmActive = false
        webViewDrmPlayer?.stop()
        _formatInfo.value = null
    }

    override suspend fun loadDrm(
        url: String,
        drm: DrmPlaybackInfo,
        startPositionMs: Long,
        headers: Map<String, String>,
        metadata: PlaybackMetadata?,
    ) {
        val player = obtainWebViewDrmPlayer()
            ?: throw UnsupportedOperationException("WebView EME 播放器不可用：本地 DRM 桥接服务启动失败")
        drmActive = true
        println("[CPPlayer Engine Manager] Track loadDrm() -> WebView EME bridge")
        player.loadDrm(url, drm, startPositionMs, headers, metadata)
    }

    override suspend fun load(
        url: String,
        startPositionMs: Long,
        headers: Map<String, String>,
        metadata: PlaybackMetadata?,
    ) {
        leaveDrmMode()
        checkAndSwitchEngine()
        println("[CPPlayer Engine] Track load() -> Engine: $currentEngineType, URL: $url")
        activePlayer.load(url, startPositionMs, headers, metadata)
    }

    override fun play() {
        if (drmActive) {
            webViewDrmPlayer?.play()
        } else {
            checkAndSwitchEngine()
            activePlayer.play()
        }
    }

    override fun pause() {
        if (drmActive) webViewDrmPlayer?.pause() else activePlayer.pause()
    }

    override fun stop() {
        if (drmActive) webViewDrmPlayer?.stop() else activePlayer.stop()
    }

    override fun seekTo(positionMs: Long) {
        if (drmActive) webViewDrmPlayer?.seekTo(positionMs) else activePlayer.seekTo(positionMs)
    }

    override fun setVolume(volume: Float) {
        lastVolume = volume
        activePlayer.setVolume(volume)
        // DRM 活跃时音量立即作用于 WebView 播放页；非活跃时仅记录，创建时重放。
        if (drmActive) webViewDrmPlayer?.setVolume(volume)
    }

    override fun getVolume(): Float =
        if (drmActive) webViewDrmPlayer?.getVolume() ?: lastVolume else activePlayer.getVolume()

    override fun release() {
        licenseJob?.cancel()
        forwardJob?.cancel()
        webViewForwardJob?.cancel()
        activePlayer.release()
        webViewDrmPlayer?.release()
        webViewDrmPlayer = null
        drmActive = false
    }

    override fun applyAudioEffect(config: AudioEffectConfig) {
        lastEffectConfig = config
        activePlayer.applyAudioEffect(config)
    }

    override fun setPlaybackSpeed(speed: Float) {
        lastPlaybackSpeed = speed
        if (drmActive) webViewDrmPlayer?.setPlaybackSpeed(speed) else activePlayer.setPlaybackSpeed(speed)
    }

    override fun queueNext(source: String, headers: Map<String, String>) {
        activePlayer.queueNext(source, headers)
    }

    override fun queueNext(mediaId: String, source: String, headers: Map<String, String>) {
        activePlayer.queueNext(mediaId, source, headers)
    }

    override fun setNativeCrossfade(enabled: Boolean, durationMs: Long) {
        lastCrossfade = enabled to durationMs
        activePlayer.setNativeCrossfade(enabled, durationMs)
    }

    override fun reloadEngine() {
        checkAndSwitchEngine(force = true)
    }
}

private class NoopDesktopPlatformPlayer(val reason: String) : PlatformPlayer {
    private val _state = MutableStateFlow<PlatformPlaybackState>(PlatformPlaybackState.Error("音频设备不可用: $reason"))
    override val state: StateFlow<PlatformPlaybackState> = _state.asStateFlow()
    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()
    private val _formatInfo = MutableStateFlow<AudioFormatInfo?>(null)
    override val formatInfo: StateFlow<AudioFormatInfo?> = _formatInfo.asStateFlow()
    override val seekFailures: SharedFlow<SeekFailure> = MutableSharedFlow()
    override suspend fun load(url: String, startPositionMs: Long, headers: Map<String, String>, metadata: PlaybackMetadata?) {}
    override fun play() {}
    override fun pause() {}
    override fun seekTo(positionMs: Long) {}
    override fun stop() {}
    override fun release() {}
    override fun setVolume(volume: Float) {}
    override fun getVolume(): Float = 1.0f
    override val supportsExclusiveAudio: Boolean = false
    override val audioEffectCapabilities: AudioEffectCapabilities = AudioEffectCapabilities.NONE
    override fun applyAudioEffect(config: AudioEffectConfig) {}
}

