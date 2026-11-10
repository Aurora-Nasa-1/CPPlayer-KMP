package cp.player.core.playback.flick

import android.content.Context
import android.util.Log
import cp.player.core.playback.AudioEffectCapabilities
import cp.player.core.playback.AudioEffectConfig
import cp.player.core.playback.AudioFormatInfo
import cp.player.core.playback.DesktopStreamLocalizer
import cp.player.core.playback.FlickPeqMapper
import cp.player.core.playback.PendingSeekTracker
import cp.player.core.playback.PlatformPlaybackState
import cp.player.core.playback.PlatformPlayer
import cp.player.core.playback.PlaybackMetadata
import cp.player.core.playback.NativeTrackTransition
import cp.player.core.playback.isNativeQueuedTrackEnding
import cp.player.core.playback.SeekFailure
import cp.player.core.playback.SeekOrigin
import cp.player.core.playback.StreamLocalizer
import cp.player.engine.AudioEvent
import cp.player.engine.RustEngine
import java.io.File
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
import kotlinx.coroutines.withContext

/**
 * 基于 FlickPlayer (Rust 核心) 的发烧音频播放器实现。
 *
 * ### 能力特性
 * - 独占模式 [supportsExclusiveAudio] = true（支持 UAC 2.0 USB DAC 源码直通与 DAP Bit-Perfect）；
 * - 纯 Rust 底层 PEQ 均衡器与空间音频效果，绕过 Android 系统自带效果链的重采样；
 * - 原生支持 DSD (DSF/DFF) 软解与 DoP 输出；
 * - 优雅降级：当未打包底层 so 库时，明确报错并不影响其余模块。
 *
 * 作为「VIP / 发烧友 Hi-Res 模式」的高级扩展播放引擎。
 */
class FlickPlatformPlayer(
    private val context: Context,
    private val localizer: StreamLocalizer = DesktopStreamLocalizer(
        cacheDir = File(context.cacheDir, "flick-stream-cache").apply { mkdirs() }
    ),
) : PlatformPlayer {

    companion object {
        private const val TAG = "FlickPlatformPlayer"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var eventJob: Job? = null

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

    override val supportsExclusiveAudio: Boolean get() = true
    override val supportsSeekableRemoteStreams: Boolean get() = true
    override val supportsPlaybackSpeed: Boolean get() = true
    override val playbackSpeed: Float get() = RustEngine.getPlaybackSpeed()
    override val supportsNativeCrossfade: Boolean get() = true
    override val supportsGaplessQueue: Boolean get() = true

    private val _nativeTrackTransitions = MutableStateFlow<NativeTrackTransition?>(null)
    override val nativeTrackTransitions: StateFlow<NativeTrackTransition?> = _nativeTrackTransitions.asStateFlow()
    private var nativeTransitionSequence = 0L
    private var currentMediaId: String? = null
    private val queuedMediaIds = mutableMapOf<String, String>()

    override fun queueNext(mediaId: String, source: String, headers: Map<String, String>) {
        queuedMediaIds[source] = mediaId
        scope.launch(RustEngine.engineDispatcher) {
            val ok = if (source.startsWith("http://", true) || source.startsWith("https://", true)) {
                RustEngine.queueNextUrl(source, headers)
            } else {
                RustEngine.queueNext(source)
            }
            if (!ok) Log.w(TAG, "Unable to queue next source for native transition")
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        scope.launch(RustEngine.engineDispatcher) { RustEngine.setPlaybackSpeed(speed) }
    }

    override fun setNativeCrossfade(enabled: Boolean, durationMs: Long) {
        scope.launch(RustEngine.engineDispatcher) {
            RustEngine.setCrossfade(enabled, durationMs.coerceAtLeast(0L) / 1000f)
        }
    }

    override val audioEffectCapabilities = AudioEffectCapabilities(
        supportsEqualizer = true,
        supportsBalance = true,
        supportsVirtualizer = true,
        supportsLeveling = false,
    )

    private var currentVolume = 1.0f
    private var currentUrl: String? = null
    private var lastReportedEnginePositionMs = 0L

    private val lifecycleManager = AndroidAudioLifecycleManager(
        context = context,
        onPauseRequested = { pause() },
        onResumeRequested = { play() },
    )

    private val seekTracker = PendingSeekTracker(
        enginePositionMs = { lastReportedEnginePositionMs },
        dispatchSeek = { target ->
            RustEngine.seek(target / 1000.0)
            true
        },
    )

    init {
        lifecycleManager.startListening()
        val ready = RustEngine.initEngine(context)
        if (!ready) {
            Log.w(TAG, "RustEngine native backend not initialized (possibly missing .so library)")
            _state.value = PlatformPlaybackState.Error("Hi-Res Engine native library not available")
        } else {
            observeRustEvents()
            scope.launch(RustEngine.engineDispatcher) {
                lifecycleManager.scanForUsbAudioDevices()
            }
        }
    }

    private fun observeRustEvents() {
        eventJob?.cancel()
        eventJob = scope.launch {
            RustEngine.audioEvents.collect { event ->
                when (event) {
                    is AudioEvent.StateChanged -> handleStateChanged(event.state)
                    is AudioEvent.Progress -> handleProgress(event)
                    is AudioEvent.FormatChanged -> handleFormatChanged(event)
                    is AudioEvent.TrackEnded -> handleTrackEnded(event.path)
                    is AudioEvent.CrossfadeStarted -> handleNativeTransition(event.toPath)
                    is AudioEvent.NextTrackReady -> handleNativeTransition(event.path)
                    is AudioEvent.Error -> handleError(event.message)
                    else -> {}
                }
            }
        }
    }

    private fun handleStateChanged(engineState: String) {
        val newState = when (engineState.lowercase()) {
            "playing" -> PlatformPlaybackState.Playing
            "paused" -> PlatformPlaybackState.Paused
            "buffering" -> PlatformPlaybackState.Buffering
            "idle", "stopped" -> PlatformPlaybackState.Idle
            else -> _state.value
        }
        // ⚠️ 引擎播完时先发 TrackEnded 紧跟 StateChanged(Stopped)，StateFlow 合并
        // 快速赋值会让 Ended 被 Idle 吞掉 ⇒ onTrackEnded() 不触发、播完不切下一首。
        // 与 PlatformPlayer.android.kt 的 STATE_ENDED 保护同构：Ended 只能被
        // Playing/Buffering（新曲开始）覆写，不能被 Idle/Paused 吞掉。
        if (_state.value == PlatformPlaybackState.Ended &&
            (newState == PlatformPlaybackState.Idle || newState == PlatformPlaybackState.Paused)
        ) {
            return
        }
        _state.value = newState
    }

    private fun handleProgress(event: AudioEvent.Progress) {
        val actualPosMs = (event.positionSecs * 1000.0).toLong()
        val durMs = (event.durationSecs * 1000.0).toLong()

        if (durMs > 0 && _durationMs.value != durMs) {
            _durationMs.value = durMs
        }

        lastReportedEnginePositionMs = actualPosMs
        val isReady = _state.value == PlatformPlaybackState.Playing || _state.value == PlatformPlaybackState.Paused
        val isLoading = _state.value == PlatformPlaybackState.Buffering
        val tick = seekTracker.tick(actualPosMs, isReady, isLoading)

        when (tick) {
            is PendingSeekTracker.Tick.Hold -> _positionMs.value = tick.valueMs
            is PendingSeekTracker.Tick.Reached -> _positionMs.value = actualPosMs
            is PendingSeekTracker.Tick.GaveUp -> {
                _positionMs.value = tick.actualMs
                _seekFailures.tryEmit(SeekFailure(tick.targetMs, tick.actualMs))
            }
            PendingSeekTracker.Tick.Idle -> _positionMs.value = actualPosMs
        }
    }

    private fun handleFormatChanged(event: AudioEvent.FormatChanged) {
        _formatInfo.value = AudioFormatInfo(
            codecName = event.codecName.ifEmpty { "PCM" },
            sampleRate = event.sampleRate,
            bitDepth = if (event.bitDepth > 0) event.bitDepth else 16,
            channels = if (event.channels > 0) event.channels else 2,
            bitrate = if (event.bitrate > 0) event.bitrate else null,
        )
    }

    private fun handleTrackEnded() {
        _state.value = PlatformPlaybackState.Ended
    }

    private fun handleTrackEnded(path: String) {
        if (isNativeQueuedTrackEnding(currentMediaId, queuedMediaIds, path)) return
        handleTrackEnded()
    }

    private fun handleNativeTransition(path: String) {
        val mediaId = queuedMediaIds[path] ?: return
        seekTracker.cancel()
        val previous = currentMediaId
        currentMediaId = mediaId
        _positionMs.value = 0L
        _nativeTrackTransitions.value = NativeTrackTransition(mediaId, previous, ++nativeTransitionSequence)
    }

    private fun handleError(message: String) {
        Log.e(TAG, "Rust engine playback error: $message")
        _state.value = PlatformPlaybackState.Error(message)
    }

    override suspend fun load(
        url: String,
        startPositionMs: Long,
        headers: Map<String, String>,
        metadata: PlaybackMetadata?,
    ) {
        queuedMediaIds.clear()
        currentMediaId = metadata?.id
        currentUrl = url
        seekTracker.cancel()
        _durationMs.value = metadata?.durationMs ?: 0L
        _positionMs.value = startPositionMs
        _state.value = PlatformPlaybackState.Buffering

        withContext(RustEngine.engineDispatcher) {
            val isHttp = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
            val playablePath = if (isHttp) {
                val cacheKey = metadata?.cacheKey?.takeIf { it.isNotBlank() } ?: url
                localizer.cachedPath(cacheKey)
            } else {
                url
            }
            val ok = if (playablePath != null) {
                RustEngine.play(playablePath)
            } else {
                val direct = RustEngine.playUrl(url, headers)
                if (direct || !isHttp) direct else {
                    val cacheKey = metadata?.cacheKey?.takeIf { it.isNotBlank() } ?: url
                    val cached = localizer.cachedPath(cacheKey)
                    val fallback = cached ?: localizer.localize(url, cacheKey, headers, null)
                    fallback?.let(RustEngine::play) ?: false
                }
            }
            val source = playablePath ?: url
            if (!ok) {
                _state.value = PlatformPlaybackState.Error("Failed to open source in Hi-Res engine: $source")
            } else if (startPositionMs > 0L) {
                seekTracker.request(startPositionMs, engineReady = false, origin = SeekOrigin.START_POSITION)
            }
        }
    }

    override fun play() {
        if (!lifecycleManager.requestAudioFocus()) {
            Log.w(TAG, "Cannot start playback: Audio focus denied by Android system")
            // ⚠️ 不能静默返回：此刻状态还停在 Buffering（load 里置的），
            // 焦点被拒又不报告 ⇒ 控制器看到的就是「点了播放、转圈、没反应」。
            // 上报错误态让上层按「加载失败」路径处理（UI 提示 + 状态归位）。
            _state.value = PlatformPlaybackState.Error("音频焦点被其他应用占用，无法开始播放")
            return
        }
        scope.launch(RustEngine.engineDispatcher) {
            RustEngine.resume()
        }
    }

    override fun pause() {
        scope.launch(RustEngine.engineDispatcher) {
            RustEngine.pause()
        }
    }

    override fun seekTo(positionMs: Long) {
        val duration = _durationMs.value
        val clamped = if (duration > 0L) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        val isReady = _state.value == PlatformPlaybackState.Playing || _state.value == PlatformPlaybackState.Paused
        seekTracker.request(clamped, isReady)
        _positionMs.value = clamped
    }

    override fun stop() {
        seekTracker.cancel()
        lifecycleManager.abandonAudioFocus()
        scope.launch(RustEngine.engineDispatcher) {
            RustEngine.stop()
        }
        _state.value = PlatformPlaybackState.Idle
    }

    override fun release() {
        eventJob?.cancel()
        eventJob = null
        seekTracker.cancel()
        lifecycleManager.stopListening()
        scope.launch(RustEngine.engineDispatcher) {
            RustEngine.stop()
            RustEngine.stopEngine()
        }
        _state.value = PlatformPlaybackState.Idle
    }

    override fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        scope.launch(RustEngine.engineDispatcher) {
            RustEngine.setVolume(currentVolume)
        }
    }

    override fun getVolume(): Float = currentVolume

    override fun applyAudioEffect(config: AudioEffectConfig) {
        if (!RustEngine.isAvailable) return

        scope.launch(RustEngine.engineDispatcher) {
            val peq = FlickPeqMapper.peqPlan(config.equalizer)
            RustEngine.setEqualizer(peq.active, peq.freqsHz, peq.gainsDb, peq.qs)
            val fx = FlickPeqMapper.fxPlan(config.mixer)
            RustEngine.setFx(
                enabled = fx.enabled,
                balance = fx.balance,
                width = fx.width,
                mix = FlickPeqMapper.CLEAN_FX_MIX,
                feedback = FlickPeqMapper.CLEAN_FX_FEEDBACK,
                damp = FlickPeqMapper.CLEAN_FX_DAMP,
            )
        }
    }
}
