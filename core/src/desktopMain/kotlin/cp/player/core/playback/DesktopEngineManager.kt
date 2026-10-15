package cp.player.core.playback

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
 * 桌面端播放引擎类型。
 */
enum class DesktopPlayerEngineType {
    /** 标准桌面引擎（基于 composemediaplayer / nucleus.rodio），轻量默认。 */
    STANDARD_RODIO,

    /**
     * 高级发烧引擎（基于专有 Rust 引擎 + WASAPI 独占直通 + Symphonia + DSD 软解）。
     * 原生免驱、源码输出（Bit-Perfect），适合用于后续「VIP / Hi-Res 专业版」桌面端付费特权功能。
     */
    HIRES_EXCLUSIVE,
}

/**
 * 桌面端播放器引擎管理器，支持动态切换标准 Rodio 与 Hi-Res 发烧独占引擎。
 */
object DesktopEngineManager {
    private val logger = Logger.getLogger("DesktopEngineManager")

    /** 检查当前系统环境是否已就绪桌面 Hi-Res 原生动态库 */
    val isHiResEngineSupported: Boolean
        get() = DesktopHiResEngine.isAvailable

    private var activeDynamicPlayer: DynamicDesktopPlatformPlayer? = null

    /**
     * 创建对应的桌面平台播放器（返回支持动态热切换的路由代理）。
     */
    fun createPlayer(
        preferredEngine: DesktopPlayerEngineType? = null,
        storage: SettingsStorage = defaultSettingsStorage(),
        isVipUser: Boolean = false,
    ): PlatformPlayer {
        val player = DynamicDesktopPlatformPlayer(storage, preferredEngine, isVipUser)
        activeDynamicPlayer = player
        return player
    }

    /** 主动通知当前播放器重载/热切换引擎 */
    fun notifyEngineChanged() {
        activeDynamicPlayer?.checkAndSwitchEngine(force = true)
    }
}

/**
 * 动态切换代理播放器：使应用无论何时在设置中切换引擎、或者切歌时，都能即时无缝切换底层物理引擎。
 */
class DynamicDesktopPlatformPlayer(
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

    private var currentEngineType: DesktopPlayerEngineType = resolveDesiredEngine()
    private var activePlayer: PlatformPlayer = createUnderlyingPlayer(currentEngineType)
    private var forwardJob: Job? = null

    private var lastVolume: Float = 1.0f
    private var lastEffectConfig: AudioEffectConfig? = null

    init {
        bindUnderlyingPlayer(activePlayer)
    }

    private fun resolveDesiredEngine(): DesktopPlayerEngineType {
        if (preferredEngine != null) return preferredEngine
        val key = storage.getString(PlaybackSessionSettings.KEY_AUDIO_ENGINE)
        return if (key == PlaybackSessionSettings.ENGINE_STANDARD) {
            DesktopPlayerEngineType.STANDARD_RODIO
        } else if (DesktopEngineManager.isHiResEngineSupported) {
            DesktopPlayerEngineType.HIRES_EXCLUSIVE
        } else {
            DesktopPlayerEngineType.STANDARD_RODIO
        }
    }

    private fun createUnderlyingPlayer(type: DesktopPlayerEngineType): PlatformPlayer {
        return if (type == DesktopPlayerEngineType.HIRES_EXCLUSIVE) {
            if (DesktopEngineManager.isHiResEngineSupported) {
                println("""
================================================================================
[CPPlayer Engine Manager] ACTIVE: FlickEngine WASAPI Exclusive Mode (Bit-Perfect)
[CPPlayer Engine Manager] Hardware Direct Output: Bypassing Windows Audio Mixer
================================================================================
                """.trimIndent())
                DesktopHiResPlatformPlayer()
            } else {
                println("[CPPlayer Engine Manager] Hi-Res engine requested but library unavailable. Falling back to Standard Rodio.")
                AudioPlayerImpl()
            }
        } else {
            println("[CPPlayer Engine Manager] Active: Standard AudioPlayerImpl (Rodio Shared)")
            AudioPlayerImpl()
        }
    }

    private fun bindUnderlyingPlayer(player: PlatformPlayer) {
        forwardJob?.cancel()
        forwardJob = scope.launch {
            launch {
                player.state.collect { st ->
                    _state.value = st
                    if (st is PlatformPlaybackState.Error && currentEngineType == DesktopPlayerEngineType.HIRES_EXCLUSIVE) {
                        println("[CPPlayer Engine Manager] Hi-Res engine encountered error: ${st.message}. Automatically failing over to Standard Rodio engine!")
                        switchEngine(DesktopPlayerEngineType.STANDARD_RODIO)
                    }
                }
            }
            launch { player.positionMs.collect { _positionMs.value = it } }
            launch { player.durationMs.collect { _durationMs.value = it } }
            launch { player.formatInfo.collect { _formatInfo.value = it } }
            launch { player.seekFailures.collect { _seekFailures.tryEmit(it) } }
        }
        player.setVolume(lastVolume)
        lastEffectConfig?.let { player.applyAudioEffect(it) }
    }

    private fun switchEngine(target: DesktopPlayerEngineType) {
        if (target == currentEngineType) return
        println("[CPPlayer Engine Manager] Switching active engine from $currentEngineType to $target")
        val oldPlayer = activePlayer
        try {
            oldPlayer.stop()
            oldPlayer.release()
            if (currentEngineType == DesktopPlayerEngineType.HIRES_EXCLUSIVE) {
                DesktopHiResEngine.shutdownEngine()
            }
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
            } catch (t: Throwable) {
                logger.warning("[ENGINE] Error releasing previous player: ${t.message}")
            }
            currentEngineType = desired
            activePlayer = createUnderlyingPlayer(desired)
            bindUnderlyingPlayer(activePlayer)
        }
    }

    override suspend fun load(
        url: String,
        startPositionMs: Long,
        headers: Map<String, String>,
        metadata: PlaybackMetadata?,
    ) {
        checkAndSwitchEngine()
        println("[CPPlayer Engine] Track load() -> Engine: $currentEngineType, URL: $url")
        activePlayer.load(url, startPositionMs, headers, metadata)
    }

    override fun play() {
        checkAndSwitchEngine()
        activePlayer.play()
    }

    override fun pause() {
        activePlayer.pause()
    }

    override fun stop() {
        activePlayer.stop()
    }

    override fun seekTo(positionMs: Long) {
        activePlayer.seekTo(positionMs)
    }

    override fun setVolume(volume: Float) {
        lastVolume = volume
        activePlayer.setVolume(volume)
    }

    override fun getVolume(): Float = activePlayer.getVolume()

    override fun release() {
        forwardJob?.cancel()
        activePlayer.release()
    }

    override fun applyAudioEffect(config: AudioEffectConfig) {
        lastEffectConfig = config
        activePlayer.applyAudioEffect(config)
    }

    override fun reloadEngine() {
        checkAndSwitchEngine(force = true)
    }
}
