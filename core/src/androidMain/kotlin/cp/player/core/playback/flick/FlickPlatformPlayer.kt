package cp.player.core.playback.flick

import android.content.Context
import android.util.Log
import cp.player.core.license.VipLicenseManager
import cp.player.core.playback.AudioEffectCapabilities
import cp.player.core.playback.AudioEffectConfig
import cp.player.core.playback.AudioFormatInfo
import cp.player.core.playback.DesktopStreamLocalizer
import cp.player.core.playback.FlickEngineSettings
import cp.player.core.playback.FlickEngineToggles
import cp.player.core.playback.FlickPeqMapper
import cp.player.core.playback.PendingSeekTracker
import cp.player.core.playback.PlatformPlaybackState
import cp.player.core.playback.PlatformPlayer
import cp.player.core.playback.PlaybackMetadata
import cp.player.core.playback.PlaybackSessionSettings
import cp.player.core.playback.NativeTrackTransition
import cp.player.core.playback.isNativeQueuedTrackEnding
import cp.player.core.playback.SeekFailure
import cp.player.core.playback.SeekOrigin
import cp.player.core.playback.StreamLocalizer
import cp.player.core.util.SettingsStorage
import cp.player.core.util.defaultSettingsStorage
import cp.player.engine.AudioEvent
import cp.player.engine.RustEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
 * - 默认使用 AAudio / Oboe 共享低延迟输出；开启 USB 独占（[PlaybackSessionSettings.KEY_ANDROID_USB_EXCLUSIVE]）后，
 *   仅在实际播放期间接管 UAC 2.0 USB DAC，暂停 / 停止 / 播完 / 退出或失焦瞬间立刻释放，
 *   确保其它应用与通话音频不受影响、恢复播放也不因失效句柄而无声；
 * - 纯 Rust 底层 PEQ 均衡器与空间音频效果；
 * - 原生支持 DSD (DSF/DFF) 软解 / DoP / Native 输出与 HTTP Range 流式点播/Seek；
 * - 优雅降级：未授权 USB 或打开独占失败时无感回退 AAudio，保证始终有声。
 */
internal class FlickPlatformPlayer(
    private val context: Context,
    private val storage: SettingsStorage = defaultSettingsStorage(),
    private val onExternalFocusLoss: () -> Unit = {},
    private val localizer: StreamLocalizer = DesktopStreamLocalizer(
        cacheDir = File(context.cacheDir, "flick-stream-cache").apply { mkdirs() }
    ),
) : PlatformPlayer {

    companion object {
        private const val TAG = "FlickPlatformPlayer"
        private const val SETTLED_END_RELEASE_DELAY_MS = 900L
    }

    private data class LoadRequest(
        val url: String,
        val startPositionMs: Long,
        val headers: Map<String, String>,
        val metadata: PlaybackMetadata?,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var eventJob: Job? = null
    private var settledEndReleaseJob: Job? = null

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

    override val supportsExclusiveAudio: Boolean get() = isUsbExclusiveEnabled()
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
        lastPlaybackSpeed = speed
        scope.launch(RustEngine.engineDispatcher) { RustEngine.setPlaybackSpeed(speed) }
    }

    override fun setNativeCrossfade(enabled: Boolean, durationMs: Long) {
        val clampedMs = durationMs.coerceAtLeast(0L)
        lastCrossfadeConfig = enabled to clampedMs
        scope.launch(RustEngine.engineDispatcher) {
            RustEngine.setCrossfade(enabled, clampedMs / 1000f)
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
    @Volatile private var usbDirectActive = false
    @Volatile private var lastLoadRequest: LoadRequest? = null
    @Volatile private var needsReloadOnPlay = false
    @Volatile private var audioRouteDirtyWhilePaused = false
    @Volatile private var lastPlaybackSpeed: Float? = null
    @Volatile private var lastCrossfadeConfig: Pair<Boolean, Long>? = null
    @Volatile private var lastAudioEffectConfig: AudioEffectConfig? = null

    private fun isUsbExclusiveEnabled(): Boolean =
        PlaybackSessionSettings.androidUsbExclusive(storage)

    private val lifecycleManager = AndroidAudioLifecycleManager(
        context = context,
        onAudioFocusLossPause = { transient ->
            if (!transient) {
                onExternalFocusLoss()
            }
            // 无论是否独占，失焦（来电/其它软件播放）时都暂停并释放 USB 独占，防止其它应用无声。
            pauseInternal(releaseUsb = true, forceShutdownOnRouteChange = false)
        },
        onResumeRequested = { play() },
        onUsbPermissionGranted = {
            if (isUsbExclusiveEnabled() &&
                !usbDirectActive &&
                (_state.value == PlatformPlaybackState.Playing || _state.value == PlatformPlaybackState.Buffering)
            ) {
                val req = lastLoadRequest
                if (req != null) {
                    val resumePos = currentResumePositionMs(req.startPositionMs)
                    scope.launch(RustEngine.engineDispatcher) {
                        loadOnEngineThreadLocked(req.copy(startPositionMs = resumePos))
                    }
                }
            }
        },
    )

    private val seekTracker = PendingSeekTracker(
        enginePositionMs = { lastReportedEnginePositionMs },
        dispatchSeek = { target ->
            scope.launch(RustEngine.engineDispatcher) {
                RustEngine.seek(target / 1000.0)
            }
            true
        },
    )

    init {
        val token = VipLicenseManager.signedToken()
        val ready = token != null && RustEngine.setVipLicense(token) && RustEngine.ensureInitialized(context)
        if (!ready) {
            Log.w(TAG, "RustEngine native backend not initialized (possibly missing .so library)")
            _state.value = PlatformPlaybackState.Error("Hi-Res Engine native library not available")
        } else {
            // 初始化阶段绝不抢占 USB 声卡：仅注册广播接收器并把直通开关设为当前用户偏好。
            RustEngine.setDirectUsbLockEnabled(false)
            RustEngine.setDirectUsbEnabled(isUsbExclusiveEnabled())
            lifecycleManager.startListening()
            observeRustEvents()
            scope.launch {
                var installedToken = token
                VipLicenseManager.licenseState.collect {
                    val updated = VipLicenseManager.signedToken()
                    if (updated != installedToken) {
                        RustEngine.setVipLicense(updated.orEmpty())
                        installedToken = updated
                    }
                }
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
                    is AudioEvent.NextTrackReady -> {
                        // NextTrackReady 仅表示下一首在原生队列中完成探测与排队，绝非换源事件。
                    }
                    is AudioEvent.Error -> handleError(event.message)
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
        // 在「手动暂停并主动释放 USB/shutdownEngine」期间，底层关闭流会吐 Idle，
        // 若被其覆盖，UI 会误以为已停止而非暂停。Paused/Ended 需要守住当前状态直到下次 load/play。
        if (needsReloadOnPlay &&
            _state.value == PlatformPlaybackState.Paused &&
            newState == PlatformPlaybackState.Idle
        ) {
            return
        }
        if (_state.value == PlatformPlaybackState.Ended &&
            (newState == PlatformPlaybackState.Idle || newState == PlatformPlaybackState.Paused)
        ) {
            return
        }
        _state.value = newState
    }

    private fun handleProgress(event: AudioEvent.Progress) {
        val direct = RustEngine.isRustDirectUsbSessionActive()
        if (direct != usbDirectActive) {
            usbDirectActive = direct
            Log.i(TAG, "USB direct session active: $direct")
        }
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
                // 仅用户主动拖动超时才提示「该音源不支持拖动」，切歌起播位置对账超时不误报。
                if (tick.origin == SeekOrigin.USER) {
                    _seekFailures.tryEmit(SeekFailure(tick.targetMs, tick.actualMs))
                }
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
        // 若队列已播到末尾且短时间内没有下一首 load() 进来，主动释放 USB 独占，避免空闲占着声卡。
        scheduleSettledEndUsbRelease()
    }

    private fun handleTrackEnded(path: String) {
        if (isNativeQueuedTrackEnding(currentMediaId, queuedMediaIds, path)) return
        handleTrackEnded()
    }

    private fun scheduleSettledEndUsbRelease() {
        settledEndReleaseJob?.cancel()
        settledEndReleaseJob = scope.launch {
            delay(SETTLED_END_RELEASE_DELAY_MS)
            val current = _state.value
            if (current == PlatformPlaybackState.Ended || current == PlatformPlaybackState.Idle) {
                if (usbDirectActive || lifecycleManager.hasUsbExclusiveActive()) {
                    withContext(RustEngine.engineDispatcher) {
                        releaseUsbAndShutdownLocked()
                    }
                }
            }
        }
    }

    private fun handleNativeTransition(path: String) {
        val mediaId = queuedMediaIds[path]
            ?: queuedMediaIds[path.replace('\\', '/')]
            ?: queuedMediaIds[path.replace('/', '\\')]
            ?: queuedMediaIds.values.singleOrNull()
            ?: return
        seekTracker.cancel()
        val previous = currentMediaId
        currentMediaId = mediaId
        _positionMs.value = 0L
        _nativeTrackTransitions.value = NativeTrackTransition(mediaId, previous, ++nativeTransitionSequence)
    }

    private fun handleError(message: String) {
        if (message.contains("seek", ignoreCase = true)) {
            Log.w(TAG, "FlickEngine seek error (non-fatal): $message")
            if (seekTracker.pendingOrigin != SeekOrigin.START_POSITION) {
                _seekFailures.tryEmit(
                    SeekFailure(seekTracker.targetMs ?: lastReportedEnginePositionMs, lastReportedEnginePositionMs)
                )
            }
            return
        }
        Log.e(TAG, "Rust engine playback error: $message")
        scope.launch(RustEngine.engineDispatcher) {
            releaseUsbAndShutdownLocked()
        }
        _state.value = PlatformPlaybackState.Error(message)
    }

    override suspend fun load(
        url: String,
        startPositionMs: Long,
        headers: Map<String, String>,
        metadata: PlaybackMetadata?,
    ) {
        settledEndReleaseJob?.cancel()
        queuedMediaIds.clear()
        currentMediaId = metadata?.id
        currentUrl = url
        needsReloadOnPlay = false
        audioRouteDirtyWhilePaused = false
        lastReportedEnginePositionMs = startPositionMs
        val req = LoadRequest(url, startPositionMs, headers, metadata)
        lastLoadRequest = req

        seekTracker.cancel()
        _durationMs.value = metadata?.durationMs ?: 0L
        _positionMs.value = startPositionMs
        _state.value = PlatformPlaybackState.Buffering

        withContext(RustEngine.engineDispatcher) {
            loadOnEngineThreadLocked(req)
        }
    }

    private suspend fun loadOnEngineThreadLocked(req: LoadRequest) {
        if (!RustEngine.ensureInitialized(context)) {
            _state.value = PlatformPlaybackState.Error("Hi-Res Engine native library not available")
            return
        }

        val usbExclusive = isUsbExclusiveEnabled()
        val hadActiveUsb = usbDirectActive || lifecycleManager.hasUsbExclusiveActive()
        if (!usbExclusive && hadActiveUsb) {
            releaseUsbAndShutdownLocked()
            if (!RustEngine.ensureInitialized(context)) {
                _state.value = PlatformPlaybackState.Error("Hi-Res Engine native library not available")
                return
            }
        }

        val acquiredUsb = if (usbExclusive) {
            val fmt = _formatInfo.value
            val dsdMode = FlickEngineToggles.dsdMode
            val codecLower = fmt?.codecName?.lowercase().orEmpty()
            val isDsd = codecLower.contains("dsd") || codecLower.contains("dsf") || codecLower.contains("dff")
            lifecycleManager.acquireUsbDeviceForPlayback(
                sampleRate = (fmt?.sampleRate ?: 48_000).coerceAtLeast(8_000),
                bitDepth = (fmt?.bitDepth ?: 24).coerceAtLeast(16),
                channels = (fmt?.channels ?: 2).coerceIn(1, 8),
                isDop = isDsd && dsdMode == FlickEngineSettings.DSD_MODE_DOP,
                isNativeDsd = isDsd && dsdMode == FlickEngineSettings.DSD_MODE_NATIVE,
            )
        } else {
            lifecycleManager.releaseUsbDevice()
            false
        }

        if (!acquiredUsb) {
            // 未插 USB、未授权或关闭独占：确保直通彻底关闭，平滑走 AAudio 共享输出。
            RustEngine.setDirectUsbLockEnabled(false)
            RustEngine.setDirectUsbEnabled(false)
            RustEngine.clearDirectUsbPlayback()
        }

        var ok = openTrackInRustLocked(req)
        if (!ok && acquiredUsb) {
            // 若挑剔 DAC 在 UAC2 独占打开失败，立刻释放 USB 并无感回退 AAudio，绝不让用户点击播放没声音。
            Log.w(TAG, "USB exclusive open failed; releasing USB and falling back to AAudio cleanly")
            releaseUsbAndShutdownLocked()
            if (RustEngine.ensureInitialized(context)) {
                RustEngine.setDirectUsbLockEnabled(false)
                RustEngine.setDirectUsbEnabled(false)
                RustEngine.clearDirectUsbPlayback()
                ok = openTrackInRustLocked(req)
            }
        }

        if (!ok) {
            if (acquiredUsb || lifecycleManager.hasUsbExclusiveActive()) {
                releaseUsbAndShutdownLocked()
            }
            _state.value = PlatformPlaybackState.Error("Failed to open source in Hi-Res engine: ${req.url}")
        } else {
            replayEngineStateLocked()
            if (req.startPositionMs > 0L) {
                seekTracker.request(req.startPositionMs, engineReady = false, origin = SeekOrigin.START_POSITION)
            }
        }
    }

    private suspend fun openTrackInRustLocked(req: LoadRequest): Boolean {
        val isHttp = req.url.startsWith("http://", ignoreCase = true) ||
            req.url.startsWith("https://", ignoreCase = true)
        val cachedPath = if (isHttp) {
            val cacheKey = req.metadata?.cacheKey?.takeIf { it.isNotBlank() } ?: req.url
            localizer.cachedPath(cacheKey)
        } else {
            req.url
        }
        return if (cachedPath != null) {
            RustEngine.play(cachedPath)
        } else {
            val direct = RustEngine.playUrl(req.url, req.headers)
            if (direct || !isHttp) direct else {
                val cacheKey = req.metadata?.cacheKey?.takeIf { it.isNotBlank() } ?: req.url
                val cached = localizer.cachedPath(cacheKey)
                val fallback = cached ?: localizer.localize(req.url, cacheKey, req.headers, null)
                fallback?.let(RustEngine::play) ?: false
            }
        }
    }

    private fun replayEngineStateLocked() {
        RustEngine.setVolume(currentVolume)
        lastPlaybackSpeed?.let { RustEngine.setPlaybackSpeed(it) }
        lastCrossfadeConfig?.let { RustEngine.setCrossfade(it.first, it.second / 1000f) }
        lastAudioEffectConfig?.let { applyAudioEffectLocked(it) }
        RustEngine.setDsdOutputMode(FlickEngineToggles.dsdMode)
    }

    private fun releaseUsbAndShutdownLocked() {
        usbDirectActive = false
        lifecycleManager.releaseUsbDevice()
        RustEngine.shutdownEngine()
    }

    private fun currentResumePositionMs(fallbackMs: Long): Long =
        maxOf(_positionMs.value, lastReportedEnginePositionMs, fallbackMs).coerceAtLeast(0L)

    override fun play() {
        settledEndReleaseJob?.cancel()
        if (!lifecycleManager.requestAudioFocus()) {
            Log.w(TAG, "Cannot start playback: Audio focus denied by Android system")
            _state.value = PlatformPlaybackState.Error("音频焦点被其他应用占用，无法开始播放")
            return
        }
        val usbExclusive = isUsbExclusiveEnabled()
        val req = lastLoadRequest
        val shouldReload = needsReloadOnPlay ||
            audioRouteDirtyWhilePaused ||
            !RustEngine.isEngineInitialized ||
            (usbExclusive && !usbDirectActive && lifecycleManager.hasConnectedUsbAudioDevice()) ||
            (!usbExclusive && (usbDirectActive || lifecycleManager.hasUsbExclusiveActive()))

        if (shouldReload && req != null) {
            val resumePos = currentResumePositionMs(req.startPositionMs)
            needsReloadOnPlay = false
            audioRouteDirtyWhilePaused = false
            _state.value = PlatformPlaybackState.Buffering
            scope.launch(RustEngine.engineDispatcher) {
                loadOnEngineThreadLocked(req.copy(startPositionMs = resumePos))
            }
            return
        }

        audioRouteDirtyWhilePaused = false
        scope.launch(RustEngine.engineDispatcher) {
            val resumed = RustEngine.resume()
            if (!resumed && req != null) {
                // Oboe / AAudio 流在长时间暂停后可能已失效；自动重装出声而不是静默无声。
                val resumePos = currentResumePositionMs(req.startPositionMs)
                _state.value = PlatformPlaybackState.Buffering
                loadOnEngineThreadLocked(req.copy(startPositionMs = resumePos))
            }
        }
    }

    override fun pause() {
        pauseInternal(
            releaseUsb = isUsbExclusiveEnabled() || usbDirectActive || lifecycleManager.hasUsbExclusiveActive(),
            forceShutdownOnRouteChange = false,
        )
    }

    /** 耳机拔出或 USB 断连触发的硬件暂停：不污染用户手势标记。 */
    internal fun pauseForHardwareDisconnect() {
        pauseInternal(
            releaseUsb = isUsbExclusiveEnabled() || usbDirectActive || lifecycleManager.hasUsbExclusiveActive(),
            forceShutdownOnRouteChange = true,
        )
    }

    private fun pauseInternal(releaseUsb: Boolean, forceShutdownOnRouteChange: Boolean) {
        settledEndReleaseJob?.cancel()
        val resumePos = currentResumePositionMs(0L)
        lastLoadRequest = lastLoadRequest?.copy(startPositionMs = resumePos)
        _positionMs.value = resumePos
        _state.value = PlatformPlaybackState.Paused

        val tearDown = releaseUsb || forceShutdownOnRouteChange
        if (tearDown) {
            needsReloadOnPlay = true
        }
        scope.launch(RustEngine.engineDispatcher) {
            RustEngine.pause()
            if (tearDown) {
                releaseUsbAndShutdownLocked()
            }
        }
    }

    internal fun onUsbAudioDisconnected(shouldPause: Boolean) {
        audioRouteDirtyWhilePaused = true
        lifecycleManager.onUsbAudioDetached(null)
        val activePlayback = shouldPause ||
            _state.value == PlatformPlaybackState.Playing ||
            _state.value == PlatformPlaybackState.Buffering
        if (activePlayback) {
            pauseInternal(releaseUsb = true, forceShutdownOnRouteChange = true)
        } else if (lifecycleManager.hasUsbExclusiveActive() || usbDirectActive) {
            needsReloadOnPlay = true
            scope.launch(RustEngine.engineDispatcher) {
                releaseUsbAndShutdownLocked()
            }
        }
    }

    internal fun onUsbAudioRouteChanged() {
        audioRouteDirtyWhilePaused = true
    }

    internal fun onUsbExclusiveModeChanged(enabled: Boolean) {
        val current = _state.value
        val req = lastLoadRequest
        val pos = currentResumePositionMs(0L)
        if ((current == PlatformPlaybackState.Playing || current == PlatformPlaybackState.Buffering) && req != null) {
            _state.value = PlatformPlaybackState.Buffering
            scope.launch(RustEngine.engineDispatcher) {
                releaseUsbAndShutdownLocked()
                RustEngine.setDirectUsbEnabled(enabled)
                loadOnEngineThreadLocked(req.copy(startPositionMs = pos))
            }
        } else {
            if (current == PlatformPlaybackState.Paused && req != null) {
                lastLoadRequest = req.copy(startPositionMs = pos)
                needsReloadOnPlay = true
            }
            scope.launch(RustEngine.engineDispatcher) {
                releaseUsbAndShutdownLocked()
                RustEngine.setDirectUsbEnabled(enabled)
            }
        }
    }

    internal fun releaseUsbIfIdle() {
        val current = _state.value
        if (current == PlatformPlaybackState.Playing || current == PlatformPlaybackState.Buffering) return
        if (current == PlatformPlaybackState.Paused) {
            val pos = currentResumePositionMs(0L)
            lastLoadRequest = lastLoadRequest?.copy(startPositionMs = pos)
            needsReloadOnPlay = true
        }
        if (lifecycleManager.hasUsbExclusiveActive() || usbDirectActive) {
            usbDirectActive = false
            lifecycleManager.releaseUsbDevice()
            RustEngine.shutdownEngine()
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
        settledEndReleaseJob?.cancel()
        seekTracker.cancel()
        lifecycleManager.abandonAudioFocus()
        lastLoadRequest = lastLoadRequest?.copy(startPositionMs = 0L)
        needsReloadOnPlay = true
        _positionMs.value = 0L
        _state.value = PlatformPlaybackState.Idle
        scope.launch(RustEngine.engineDispatcher) {
            RustEngine.stop()
            if (isUsbExclusiveEnabled() || usbDirectActive || lifecycleManager.hasUsbExclusiveActive()) {
                releaseUsbAndShutdownLocked()
            }
        }
    }

    override fun release() {
        settledEndReleaseJob?.cancel()
        scope.cancel()
        eventJob = null
        seekTracker.cancel()
        usbDirectActive = false
        lifecycleManager.stopListening()
        RustEngine.stopEngine()
        RustEngine.setVipLicense("")
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
        lastAudioEffectConfig = config
        if (!RustEngine.isAvailable) return

        scope.launch(RustEngine.engineDispatcher) {
            applyAudioEffectLocked(config)
        }
    }

    private fun applyAudioEffectLocked(config: AudioEffectConfig) {
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
