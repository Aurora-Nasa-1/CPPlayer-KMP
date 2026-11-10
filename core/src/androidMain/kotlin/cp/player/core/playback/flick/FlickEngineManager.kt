package cp.player.core.playback.flick

import android.content.Context
import android.util.Log
import cp.player.core.license.VipLicenseManager
import cp.player.core.playback.AudioEffectCapabilities
import cp.player.core.playback.AudioEffectConfig
import cp.player.core.playback.AudioFormatInfo
import cp.player.core.playback.Media3PlatformPlayer
import cp.player.core.playback.PlatformPlaybackState
import cp.player.core.playback.PlatformPlayer
import cp.player.core.playback.PlaybackMetadata
import cp.player.core.playback.NativeTrackTransition
import cp.player.core.playback.PlaybackSessionSettings
import cp.player.core.playback.SeekFailure
import cp.player.core.util.SettingsStorage
import cp.player.core.util.defaultSettingsStorage
import cp.player.engine.RustEngine
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

/**
 * 手机端播放引擎类型。
 */
enum class AndroidPlayerEngineType {
    /** 标准系统引擎（基于 AndroidX Media3 ExoPlayer），网络流鲁棒性高，兼容性好。 */
    STANDARD_MEDIA3,

    /**
     * 高级发烧引擎（基于 Rust Oboe + Symphonia + UAC2 USB 独占 + DSD 软解）。
     * 适合用于后续「VIP / Hi-Res 专业版」付费特权功能。
     */
    HIRES_FLICK,
}

/**
 * 播放器引擎管理器，支持动态切换标准 Media3 (ExoPlayer) 与 Flick Hi-Res 引擎。
 */
object FlickEngineManager {
    private const val TAG = "FlickEngineManager"

    /** 检查当前设备与安装包是否支持 Flick Hi-Res 原生引擎 */
    val isHiResEngineSupported: Boolean
        get() = RustEngine.isAvailable

    private var activeDynamicPlayer: DynamicAndroidPlatformPlayer? = null

    /**
     * 根据偏好设置与底层可用性创建对应的平台播放器（返回支持动态热切换的路由代理）。
     */
    fun createPlayer(
        context: Context,
        preferredEngine: AndroidPlayerEngineType? = null,
        storage: SettingsStorage = defaultSettingsStorage(),
        isVipUser: Boolean = false,
    ): PlatformPlayer {
        val player = DynamicAndroidPlatformPlayer(context, storage, preferredEngine, isVipUser)
        activeDynamicPlayer = player
        return player
    }

    /** 主动通知当前播放器重载/热切换引擎 */
    fun notifyEngineChanged() {
        activeDynamicPlayer?.checkAndSwitchEngine(force = true)
    }
}

/**
 * 安卓端动态切换代理播放器：在标准 ExoPlayer 与发烧 FlickEngine 之间无缝热切换。
 */
class DynamicAndroidPlatformPlayer(
    private val context: Context,
    private val storage: SettingsStorage,
    private val preferredEngine: AndroidPlayerEngineType?,
    private val isVipUser: Boolean,
) : PlatformPlayer {

    companion object {
        private const val TAG = "DynamicAndroidPlatformPlayer"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

    private var currentEngineType: AndroidPlayerEngineType = resolveDesiredEngine()
    private var activePlayer: PlatformPlayer = createUnderlyingPlayer(currentEngineType)
    private var forwardJob: Job? = null

    private var lastVolume: Float = 1.0f
    private var lastEffectConfig: AudioEffectConfig? = null
    private var lastPlaybackSpeed: Float? = null
    private var lastCrossfade: Pair<Boolean, Long>? = null

    override val supportsPlaybackSpeed: Boolean get() = activePlayer.supportsPlaybackSpeed
    override val playbackSpeed: Float get() = activePlayer.playbackSpeed
    override val supportsNativeCrossfade: Boolean get() = activePlayer.supportsNativeCrossfade
    override val supportsGaplessQueue: Boolean get() = activePlayer.supportsGaplessQueue
    override val nativeTrackTransitions: StateFlow<NativeTrackTransition?> get() = activePlayer.nativeTrackTransitions
    override val supportsSeekableRemoteStreams: Boolean get() = activePlayer.supportsSeekableRemoteStreams

    init {
        bindUnderlyingPlayer(activePlayer)
    }

    private fun resolveDesiredEngine(): AndroidPlayerEngineType {
        if (preferredEngine != null) return preferredEngine
        val key = storage.getString(PlaybackSessionSettings.KEY_AUDIO_ENGINE)
        val isVip = isVipUser || VipLicenseManager.isVipActive
        return if (key == PlaybackSessionSettings.ENGINE_FLICK_HIRES) {
            if (isVip && FlickEngineManager.isHiResEngineSupported) {
                AndroidPlayerEngineType.HIRES_FLICK
            } else {
                AndroidPlayerEngineType.STANDARD_MEDIA3
            }
        } else {
            AndroidPlayerEngineType.STANDARD_MEDIA3
        }
    }

    private fun createUnderlyingPlayer(type: AndroidPlayerEngineType): PlatformPlayer {
        return if (type == AndroidPlayerEngineType.HIRES_FLICK) {
            if (FlickEngineManager.isHiResEngineSupported) {
                Log.i(TAG, "Active: FlickPlatformPlayer (Rust Oboe / Symphonia / USB DAC)")
                runCatching {
                    FlickPlatformPlayer(context)
                }.getOrElse { err ->
                    Log.w(TAG, "FlickPlatformPlayer creation failed ($err), falling back to ExoPlayer.")
                    Media3PlatformPlayer(context)
                }
            } else {
                Log.w(TAG, "Flick Hi-Res Engine unavailable (.so missing). Falling back to ExoPlayer.")
                Media3PlatformPlayer(context)
            }
        } else {
            Log.i(TAG, "Active: Media3PlatformPlayer (ExoPlayer)")
            Media3PlatformPlayer(context)
        }
    }

    private fun bindUnderlyingPlayer(player: PlatformPlayer) {
        forwardJob?.cancel()
        forwardJob = scope.launch {
            launch {
                player.state.collect { st ->
                    _state.value = st
                    if (st is PlatformPlaybackState.Error && currentEngineType == AndroidPlayerEngineType.HIRES_FLICK) {
                        Log.w(TAG, "FlickEngine encountered error: ${st.message}. Automatically failing over to ExoPlayer!")
                        switchEngine(AndroidPlayerEngineType.STANDARD_MEDIA3)
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
        lastPlaybackSpeed?.let { player.setPlaybackSpeed(it) }
        lastCrossfade?.let { player.setNativeCrossfade(it.first, it.second) }
    }

    private fun switchEngine(target: AndroidPlayerEngineType) {
        if (target == currentEngineType) return
        Log.i(TAG, "Switching active engine from $currentEngineType to $target")
        val oldPlayer = activePlayer
        try {
            oldPlayer.stop()
            oldPlayer.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing old player: ${t.message}")
        }
        currentEngineType = target
        activePlayer = createUnderlyingPlayer(target)
        bindUnderlyingPlayer(activePlayer)
    }

    fun checkAndSwitchEngine(force: Boolean = false) {
        val desired = resolveDesiredEngine()
        if (desired != currentEngineType || force) {
            Log.i(TAG, "Switching engine from $currentEngineType to $desired")
            val oldPlayer = activePlayer
            try {
                oldPlayer.stop()
                oldPlayer.release()
            } catch (t: Throwable) {
                Log.w(TAG, "Error releasing player: ${t.message}")
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

    override fun setPlaybackSpeed(speed: Float) {
        lastPlaybackSpeed = speed
        activePlayer.setPlaybackSpeed(speed)
    }

    override fun setNativeCrossfade(enabled: Boolean, durationMs: Long) {
        lastCrossfade = enabled to durationMs
        activePlayer.setNativeCrossfade(enabled, durationMs)
    }

    override fun queueNext(mediaId: String, source: String, headers: Map<String, String>) {
        activePlayer.queueNext(mediaId, source, headers)
    }

    override fun reloadEngine() {
        checkAndSwitchEngine(force = true)
    }
}

