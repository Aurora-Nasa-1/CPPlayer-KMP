package cp.player.core.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    /**
     * 是否以 WASAPI 独占（Bit-Perfect）模式打开设备。
     *
     * ⚠️ **默认值必须是 `false`**：独占会让其它应用在播放期间彻底静音，
     * 这是用户必须自己点头的选择（VIP + 设置页开关），不能由构造函数顺手替人决定。
     */
    val isExclusive: Boolean = false,
) : PlatformPlayer {

    private companion object {
        val logger: Logger = Logger.getLogger("DesktopHiResPlatformPlayer")

        /**
         * 两次音效下发的最小间隔（ms）。
         *
         * 取 50 ms：拖推子时既能挡掉每帧一次的重建（听感上的细碎咔哒），
         * 又能在松手后 1~2 帧内把最终值送到引擎（不至于有迟滞感）。
         */
        const val EFFECT_MIN_INTERVAL_MS = 50L
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

    private val _nativeTrackTransitions = MutableStateFlow<NativeTrackTransition?>(null)
    override val nativeTrackTransitions: StateFlow<NativeTrackTransition?> = _nativeTrackTransitions.asStateFlow()
    private var nativeTransitionSequence = 0L
    private var currentMediaId: String? = null
    private val queuedMediaIds = mutableMapOf<String, String>()

    override val supportsExclusiveAudio: Boolean get() = isExclusive

    /**
     * 响度均衡（compressor + limiter）只有 apiLevel ≥ 2 的 DLL 才有 JNI 导出。
     * 用 getter 而不是一次性 val：设置页可能在引擎 init 之前就读能力位。
     */
    override val audioEffectCapabilities: AudioEffectCapabilities
        get() = AudioEffectCapabilities(
            supportsEqualizer = true,
            supportsBalance = true,
            supportsVirtualizer = true,
            supportsLeveling = DesktopHiResEngine.supportsExtendedFeatures,
        )

    // ============ 引擎级通用能力 ============

    override val supportsPlaybackSpeed: Boolean get() = DesktopHiResEngine.supportsExtendedFeatures
    override val playbackSpeed: Float get() = DesktopHiResEngine.getPlaybackSpeed()

    override fun setPlaybackSpeed(speed: Float) {
        if (!DesktopHiResEngine.supportsExtendedFeatures) return
        scope.launch(DesktopHiResEngine.engineDispatcher) {
            DesktopHiResEngine.setPlaybackSpeed(speed)
        }
    }

    override val supportsGaplessQueue: Boolean get() = DesktopHiResEngine.supportsExtendedFeatures

    override fun queueNext(source: String, headers: Map<String, String>) {
        if (!DesktopHiResEngine.supportsExtendedFeatures) return
        scope.launch(DesktopHiResEngine.engineDispatcher) {
            val ok = if (source.startsWith("http://", true) || source.startsWith("https://", true)) {
                DesktopHiResEngine.queueNextUrl(source, headers)
            } else {
                DesktopHiResEngine.queueNext(source)
            }
            if (!ok) logger.fine("queueNext rejected: $source")
        }
    }

    override fun queueNext(mediaId: String, source: String, headers: Map<String, String>) {
        queuedMediaIds[source] = mediaId
        queueNext(source, headers)
    }

    override val supportsNativeCrossfade: Boolean get() = DesktopHiResEngine.supportsExtendedFeatures

    override fun setNativeCrossfade(enabled: Boolean, durationMs: Long) {
        if (!DesktopHiResEngine.supportsExtendedFeatures) return
        scope.launch(DesktopHiResEngine.engineDispatcher) {
            DesktopHiResEngine.setCrossfade(enabled, durationMs.coerceAtLeast(0L) / 1000f)
        }
    }

    override val supportsSeekableRemoteStreams: Boolean
        get() = DesktopHiResEngine.supportsExtendedFeatures

    private var currentVolume = 1.0f
    private var lastReportedEnginePositionMs = 0L

    // ============ 音效下发的去抖与 diff ============
    //
    // 拖 PEQ 推子时 UI 每帧都会调 [applyAudioEffect]，一帧一次全量下发等于
    // 「每个 16 ms 重建一次整条 biquad 链」：JNI 调用排队、系数在双缓冲里连续翻页，
    // 听感上就是拖动时的细碎咔哒。这里做两件事：
    //  1. **diff**：参数指纹没变就什么都不做（松手后 UI 仍会重复提交同一份配置）；
    //  2. **去抖**：两次下发之间至少间隔 [EFFECT_MIN_INTERVAL_MS]，且**保证尾包一定发出** ——
    //     直接「限流丢弃」会让推子停在中间值时最后一格永远不生效，那比咔哒更糟。
    private var lastPeqSignature: String? = null
    private var lastFxSignature: String? = null
    private var lastDynamicsSignature: String? = null
    private var effectJob: Job? = null

    /** 上一次下发时刻（`System.nanoTime()` 的毫秒折算），只用于去抖间隔。 */
    @Volatile private var lastEffectDispatchAtMs = 0L

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
        DesktopHiResEngine.setExclusiveMode(isExclusive)
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
                    is DesktopAudioEvent.TrackEnded -> handleTrackEnded(event.path)
                    is DesktopAudioEvent.CrossfadeStarted -> handleNativeTransition(event.toPath)
                    is DesktopAudioEvent.NextTrackReady -> handleNativeTransition(event.path)
                    is DesktopAudioEvent.Error -> handleError(event.message)
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
        // ⚠️ 引擎播完（无 gapless 队列）时先发 TrackEnded 紧跟 StateChanged(Stopped)
        // （engine.rs finished_rx 处理），StateFlow 合并快速赋值会让 Ended 被 Idle 吞掉
        // ⇒ PlaybackControllerImpl.onTrackEnded() 永远不触发、播完不切下一首。
        // 与 PlatformPlayer.android.kt 的 STATE_ENDED 保护同构：Ended 只能被
        // Playing/Buffering（新曲开始）覆写，不能被 Idle/Paused 吞掉。
        if (_state.value == PlatformPlaybackState.Ended &&
            (newState == PlatformPlaybackState.Idle || newState == PlatformPlaybackState.Paused)
        ) {
            return
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
        queuedMediaIds.clear()
        currentMediaId = metadata?.id
        seekTracker.cancel()
        _durationMs.value = metadata?.durationMs ?: 0L
        _positionMs.value = startPositionMs
        _state.value = PlatformPlaybackState.Buffering

        when (val resolved = resolvePlayablePath(url, headers, metadata)) {
            is ResolvedSource.Local -> startLocalPlayback(resolved.path, startPositionMs)

            is ResolvedSource.Stream -> {
                // 新版 DLL：把 URL 连同鉴权头直接交给引擎的 Range 数据源，**零等待出声**。
                val streamOk = withContext(DesktopHiResEngine.engineDispatcher) {
                    DesktopHiResEngine.playUrl(resolved.url, resolved.headers)
                }
                if (streamOk) {
                    logger.info("Streaming directly (Range-seekable): ${resolved.url}")
                    if (startPositionMs > 0L) {
                        // 引擎的 play_url 不接受起始位置；交给 PendingSeekTracker 在
                        // 源就绪后补发一次 seek。源头是 [SeekOrigin.START_POSITION]，
                        // 落不了地也不该弹「该音源不支持拖动」。
                        seekTracker.request(startPositionMs, engineReady = false, origin = SeekOrigin.START_POSITION)
                    }
                } else {
                    // 直连失败**（旧 DLL 无此符号 / CDN 拒绝）**：退回整首落盘再播。
                    // 这条兜底让新旧 DLL 用同一份宿主代码都能出声。
                    logger.warning("Direct streaming unavailable, falling back to local buffering: ${resolved.url}")
                    val cached = localizer.cachedPath(resolved.cacheKey)
                    val local = cached ?: localizer.localize(
                        resolved.url, resolved.cacheKey, resolved.headers, resolved.meta,
                    )
                    if (local != null) {
                        startLocalPlayback(local, startPositionMs)
                    } else {
                        _state.value = PlatformPlaybackState.Error(
                            "无法播放该网络音频：直连与本地缓冲均失败（${resolved.host}）",
                        )
                    }
                }
            }

            is ResolvedSource.Unavailable -> {
                println("[CPPlayer Hi-Res Player] Stream unavailable: ${resolved.reason}")
                _state.value = PlatformPlaybackState.Error(
                    "当前音频内核不支持直连网络流，且本地缓冲失败（${resolved.reason}）",
                )
            }
        }
    }

    private suspend fun startLocalPlayback(path: String, startPositionMs: Long) {
        println("[CPPlayer Hi-Res Player] Playing physical path: $path")
        withContext(DesktopHiResEngine.engineDispatcher) {
            val ok = DesktopHiResEngine.play(path)
            if (!ok) {
                _state.value = PlatformPlaybackState.Error("Failed to open audio track in Desktop Hi-Res engine: $path")
            } else if (startPositionMs > 0L) {
                seekTracker.request(startPositionMs, engineReady = false, origin = SeekOrigin.START_POSITION)
            }
        }
    }

    /** [resolvePlayablePath] 的结论：优先直连流，缓存命中则播本地，旧引擎才落盘。 */
    private sealed interface ResolvedSource {
        data class Local(val path: String) : ResolvedSource
        data class Stream(
            val url: String,
            val headers: Map<String, String>,
            val cacheKey: String,
            val meta: SongCacheMeta,
            val host: String,
        ) : ResolvedSource
        data class Unavailable(val reason: String) : ResolvedSource
    }

    /**
     * 把 `load()` 收到的 URL 变成**引擎真能打开的东西**。
     *
     * - 本地路径 / 缓存命中 ⇒ [ResolvedSource.Local]（秒开、立刻可拖）；
     * - apiLevel ≥ 2 的 DLL ⇒ [ResolvedSource.Stream]，由 Rust 的 Range 数据源边下边播，
     *   **不再阻塞等待整首下载**；
     * - 旧 DLL（只会 `audio_play(PathBuf)`）⇒ 维持老的「先落盘再播」，
     *   落盘失败就明确报错，**绝不把 URL 原样喂给引擎**（那必然失败且报错误导）。
     */
    private suspend fun resolvePlayablePath(
        url: String,
        headers: Map<String, String>,
        metadata: PlaybackMetadata?,
    ): ResolvedSource {
        val isHttp = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
        if (!isHttp) return ResolvedSource.Local(url)

        val cacheKey = metadata?.cacheKey?.takeIf { it.isNotBlank() } ?: url
        val host = url.substringAfter("://").substringBefore('/').ifBlank { "unknown host" }
        val meta = SongCacheMeta(title = metadata?.title, artist = metadata?.artist)

        val cached = localizer.cachedPath(cacheKey)
        if (cached != null) {
            logger.info("Using cached local audio file: $cached")
            return ResolvedSource.Local(cached)
        }

        if (DesktopHiResEngine.supportsExtendedFeatures) {
            return ResolvedSource.Stream(url, headers, cacheKey, meta, host)
        }

        val localized = localizer.localize(url, cacheKey, headers, meta)
        return if (localized != null) {
            logger.info("Legacy engine: stream buffered to local file: $localized")
            ResolvedSource.Local(localized)
        } else {
            ResolvedSource.Unavailable(reason = host)
        }
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
        // ⚠️ 只有**时长已知**时才夹到时长内。旧写法是
        // `positionMs.coerceIn(0L, _durationMs.value.coerceAtLeast(0L))`，
        // 而时长在「引擎还没报 FormatChanged/Progress」或「曲目元信息没有 duration」时是 0
        // ⇒ coerceIn(0, 0) 恒等于 0，用户一拖进度条就被钉回开头，且 PendingSeekTracker
        // 会把这当成一次用户 seek 去和引擎对账，最终报一次假失败。
        val duration = _durationMs.value
        val clamped = if (duration > 0L) {
            positionMs.coerceIn(0L, duration)
        } else {
            positionMs.coerceAtLeast(0L)
        }
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
        effectJob?.cancel()
        effectJob = null
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

    /**
     * 应用音效配置（全量覆盖语义，内部做 diff + 去抖）。
     *
     * 参数怎么算、为什么这么算，全部在 [FlickPeqMapper] 里（可单测）。
     * 这里只负责「什么时候真的下发」：
     * - 指纹相同的重复提交直接返回 —— 设置页每次重组都会提交同一份配置；
     * - 指纹变了才排队，且与上一次下发至少间隔 [EFFECT_MIN_INTERVAL_MS]；
     * - 排队期间若又来了新配置，**取消旧任务、按最新那份重排**（尾包一定是最新值）。
     */
    override fun applyAudioEffect(config: AudioEffectConfig) {
        if (!DesktopHiResEngine.isAvailable) return

        val peq = FlickPeqMapper.peqPlan(config.equalizer)
        val fx = FlickPeqMapper.fxPlan(config.mixer)
        val dynamics = FlickPeqMapper.dynamicsPlan(config.leveling, config.enabled)
        val dynamicsSupported = DesktopHiResEngine.supportsExtendedFeatures
        val peqSignature = peq.signature()
        val fxSignature = fx.signature()
        val dynamicsSignature = if (dynamicsSupported) dynamics.signature() else "unsupported"
        if (peqSignature == lastPeqSignature &&
            fxSignature == lastFxSignature &&
            dynamicsSignature == lastDynamicsSignature
        ) return

        effectJob?.cancel()
        effectJob = scope.launch {
            val sinceLast = System.nanoTime() / 1_000_000L - lastEffectDispatchAtMs
            val waitMs = EFFECT_MIN_INTERVAL_MS - sinceLast
            if (waitMs > 0L) delay(waitMs)

            withContext(DesktopHiResEngine.engineDispatcher) {
                if (peqSignature != lastPeqSignature) {
                    // ⚠️ 只有**引擎真的接受了**才记指纹：引擎尚未 init（或 JNI 失败）时
                    // setEqualizer 返回 false，此时若把指纹记下，这份配置就再也不会被重发，
                    // 表现为「设置页调了没反应」。
                    val applied = if (peq.active) {
                        DesktopHiResEngine.setEqualizer(
                            enabled = true,
                            freqs = peq.freqsHz,
                            gains = peq.gainsDb,
                            qs = peq.qs,
                        )
                    } else {
                        // 已经是 off 就不必再发一次关：引擎的关操作会清零滤波器状态，
                        // 空发一次等于把「刚刚静音下重放」的状态又清一遍，没有意义。
                        lastPeqSignature == null || DesktopHiResEngine.setEqualizer(
                            enabled = false,
                            freqs = FloatArray(0),
                            gains = FloatArray(0),
                            qs = FloatArray(0),
                        )
                    }
                    if (applied) lastPeqSignature = peqSignature
                }

                if (fxSignature != lastFxSignature) {
                    // 与 PEQ 同理：出厂态本来就是「FX 关闭」，第一次拿到关闭态不必惊动引擎
                    // （引擎的 set(enabled=false) 会 reset 延迟线状态，纯属多余）。
                    val applied = if (fx.enabled || lastFxSignature != null) {
                        DesktopHiResEngine.setFx(
                            enabled = fx.enabled,
                            balance = fx.balance,
                            width = fx.width,
                            // ⚠️ 这三个必须显式写 0：引擎的 enabled 开关**同时**带着一条 240 ms 混响，
                            // 不清零的话「调左右平衡」会听到混响尾巴（见 FlickPeqMapper.CLEAN_FX_*）。
                            mix = FlickPeqMapper.CLEAN_FX_MIX,
                            feedback = FlickPeqMapper.CLEAN_FX_FEEDBACK,
                            damp = FlickPeqMapper.CLEAN_FX_DAMP,
                        )
                    } else {
                        true
                    }
                    if (applied) lastFxSignature = fxSignature
                }

                if (dynamicsSupported && dynamicsSignature != lastDynamicsSignature) {
                    // 首次拿到全 OFF 时不必惊动引擎（出厂态本来就是直通），
                    // 与 PEQ / FX 同一个「不要为了关闭而挂一条链」的取舍。
                    val shouldDispatch = dynamics.compressorEnabled ||
                        dynamics.limiterEnabled ||
                        lastDynamicsSignature != null
                    val applied = if (!shouldDispatch) {
                        true
                    } else {
                        val compressorOk = DesktopHiResEngine.setCompressor(
                            enabled = dynamics.compressorEnabled,
                            thresholdDb = dynamics.thresholdDb,
                            ratio = dynamics.ratio,
                            attackMs = dynamics.attackMs,
                            releaseMs = dynamics.releaseMs,
                            makeupGainDb = dynamics.makeupGainDb,
                        )
                        val limiterOk = DesktopHiResEngine.setLimiter(
                            enabled = dynamics.limiterEnabled,
                            inputGainDb = dynamics.inputGainDb,
                            ceilingDb = dynamics.ceilingDb,
                            releaseMs = dynamics.limiterReleaseMs,
                        )
                        compressorOk && limiterOk
                    }
                    if (applied) lastDynamicsSignature = dynamicsSignature
                } else if (!dynamicsSupported) {
                    // 老 DLL：能力位已如实关掉，这里只要别让指纹卡住 diff。
                    lastDynamicsSignature = dynamicsSignature
                }

                lastEffectDispatchAtMs = System.nanoTime() / 1_000_000L
            }
        }
    }
}
