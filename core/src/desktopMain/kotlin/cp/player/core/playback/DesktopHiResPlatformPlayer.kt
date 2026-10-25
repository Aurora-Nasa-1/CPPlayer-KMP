package cp.player.core.playback

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
import java.util.logging.Logger

/**
 * 桌面端 Hi-Res 发烧播放器（专有 FlickEngine：支持 WASAPI 独占直通、Bit-Perfect 源码输出与 DSD 软解）。
 *
 * 作为桌面端未来的专有付费高级功能或 Hi-Fi 发烧友特权模式。
 */
class DesktopHiResPlatformPlayer(
    private val localizer: StreamLocalizer = DesktopStreamLocalizer(),
) : PlatformPlayer {

    companion object {
        private val logger = Logger.getLogger("DesktopHiResPlatformPlayer")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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

    override val audioEffectCapabilities = AudioEffectCapabilities(
        supportsEqualizer = true,
        supportsBalance = true,
        supportsVirtualizer = true,
        supportsLeveling = false,
    )

    private var currentVolume = 1.0f
    private var lastReportedEnginePositionMs = 0L

    private val seekTracker = PendingSeekTracker(
        enginePositionMs = { lastReportedEnginePositionMs },
        dispatchSeek = { target ->
            scope.launch(DesktopHiResEngine.engineDispatcher) {
                DesktopHiResEngine.seek(target / 1000.0)
            }
            true
        },
    )

    init {
        val ready = DesktopHiResEngine.initEngine()
        if (!ready) {
            logger.warning("DesktopHiResEngine not initialized (library not found).")
            _state.value = PlatformPlaybackState.Error("Desktop Hi-Res native library not ready")
        } else {
            observeEngineEvents()
        }
    }

    private fun observeEngineEvents() {
        eventJob?.cancel()
        eventJob = scope.launch {
            DesktopHiResEngine.audioEvents.collect { event ->
                when (event) {
                    is DesktopAudioEvent.StateChanged -> handleStateChanged(event.state)
                    is DesktopAudioEvent.Progress -> handleProgress(event)
                    is DesktopAudioEvent.FormatChanged -> handleFormatChanged(event)
                    is DesktopAudioEvent.TrackEnded -> handleTrackEnded()
                    is DesktopAudioEvent.Error -> handleError(event.message)
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
        _state.value = newState
    }

    private fun handleProgress(event: DesktopAudioEvent.Progress) {
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
                // 起始位置（恢复上次进度）失败是误报，不打扰用户 —— 见 [SeekOrigin]。
                if (tick.origin == SeekOrigin.USER) {
                    _seekFailures.tryEmit(SeekFailure(tick.targetMs, tick.actualMs))
                }
            }
            PendingSeekTracker.Tick.Idle -> _positionMs.value = actualPosMs
        }
    }

    private fun handleFormatChanged(event: DesktopAudioEvent.FormatChanged) {
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

    private fun handleError(message: String) {
        if (message.contains("seek", ignoreCase = true)) {
            logger.warning("Desktop Hi-Res seek error (non-fatal): $message")
            // 起始位置（恢复上次进度）失败是误报，不打扰用户 —— 见 [SeekOrigin]。
            if (seekTracker.pendingOrigin != SeekOrigin.START_POSITION) {
                _seekFailures.tryEmit(SeekFailure(seekTracker.targetMs ?: lastReportedEnginePositionMs, lastReportedEnginePositionMs))
            }
            return
        }
        logger.severe("Desktop Hi-Res engine error: $message")
        _state.value = PlatformPlaybackState.Error(message)
    }

    override suspend fun load(
        url: String,
        startPositionMs: Long,
        headers: Map<String, String>,
        metadata: PlaybackMetadata?,
    ) {
        seekTracker.cancel()
        _durationMs.value = metadata?.durationMs ?: 0L
        _positionMs.value = startPositionMs
        _state.value = PlatformPlaybackState.Buffering

        val playablePath = resolvePlayablePath(url, headers, metadata)
        println("[CPPlayer Hi-Res Player] Playing physical path: $playablePath")
        withContext(DesktopHiResEngine.engineDispatcher) {
            val ok = DesktopHiResEngine.play(playablePath)
            println("[CPPlayer Hi-Res Player] DesktopHiResEngine.nativePlay result: $ok")
            if (!ok) {
                println("[CPPlayer Hi-Res Player] FAILED to open audio track: $playablePath")
                _state.value = PlatformPlaybackState.Error("Failed to open audio track in Desktop Hi-Res engine: $playablePath")
            } else if (startPositionMs > 0L) {
                // 发起方是 [SeekOrigin.START_POSITION]：恢复上次进度落不了地时不能弹「该音源不支持拖动」。
                seekTracker.request(startPositionMs, engineReady = false, origin = SeekOrigin.START_POSITION)
            }
        }
    }

    private suspend fun resolvePlayablePath(
        url: String,
        headers: Map<String, String>,
        metadata: PlaybackMetadata?,
    ): String {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return url
        }

        val cacheKey = metadata?.cacheKey?.takeIf { it.isNotBlank() } ?: url

        val cached = localizer.cachedPath(cacheKey)
        if (cached != null) {
            logger.info("Using cached local audio file: $cached")
            return cached
        }

        val meta = SongCacheMeta(title = metadata?.title, artist = metadata?.artist)
        val localized = localizer.localize(url, cacheKey, headers, meta)
        if (localized != null) {
            logger.info("Stream successfully buffered to Hi-Res local file: $localized")
            return localized
        }
        return url
    }

    override fun play() {
        scope.launch(DesktopHiResEngine.engineDispatcher) {
            DesktopHiResEngine.resume()
        }
    }

    override fun pause() {
        scope.launch(DesktopHiResEngine.engineDispatcher) {
            DesktopHiResEngine.pause()
        }
    }

    override fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceIn(0L, _durationMs.value.coerceAtLeast(0L))
        val isReady = _state.value == PlatformPlaybackState.Playing || _state.value == PlatformPlaybackState.Paused
        seekTracker.request(clamped, isReady)
        _positionMs.value = clamped
    }

    override fun stop() {
        seekTracker.cancel()
        scope.launch(DesktopHiResEngine.engineDispatcher) {
            DesktopHiResEngine.stop()
        }
        _state.value = PlatformPlaybackState.Idle
    }

    override fun release() {
        eventJob?.cancel()
        eventJob = null
        seekTracker.cancel()
        try {
            DesktopHiResEngine.stop()
            DesktopHiResEngine.shutdownEngine()
        } catch (_: Throwable) {
        }
        _state.value = PlatformPlaybackState.Idle
    }

    override fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        scope.launch(DesktopHiResEngine.engineDispatcher) {
            DesktopHiResEngine.setVolume(currentVolume)
        }
    }

    override fun getVolume(): Float = currentVolume

    override fun applyAudioEffect(config: AudioEffectConfig) {
        if (!DesktopHiResEngine.isAvailable) return

        scope.launch(DesktopHiResEngine.engineDispatcher) {
            // 1. 同步参数均衡器 (PEQ)
            if (config.equalizer.enabled && config.equalizer.bands.isNotEmpty()) {
                val bands = config.equalizer.bands
                val freqs = FloatArray(bands.size) { bands[it].frequencyHz.toFloat() }
                val gains = FloatArray(bands.size) { bands[it].gainDb }
                val qs = FloatArray(bands.size) { 1.0f }
                DesktopHiResEngine.setEqualizer(true, freqs, gains, qs)
            } else {
                DesktopHiResEngine.setEqualizer(false, floatArrayOf(), floatArrayOf(), floatArrayOf())
            }

            // 2. 同步声相平衡与立体声宽度
            if (config.mixer.balance != 0f || config.mixer.virtualizerStrength != 0f) {
                DesktopHiResEngine.setFx(
                    enabled = true,
                    balance = config.mixer.balance,
                    width = 1.0f + (config.mixer.virtualizerStrength * 0.5f),
                )
            } else {
                DesktopHiResEngine.setFx(enabled = false, balance = 0f)
            }
        }
    }
}
