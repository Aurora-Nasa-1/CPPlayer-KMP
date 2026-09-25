package cp.player.kmp.playback

import cp.player.kmp.util.PlatformContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import io.github.kdroidfilter.composemediaplayer.audio.AudioPlayer
import io.github.kdroidfilter.composemediaplayer.audio.AudioPlayerState

interface PlatformPlayer {
    val state: StateFlow<PlatformPlaybackState>
    val positionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val formatInfo: StateFlow<AudioFormatInfo?>
    val supportsExclusiveAudio: Boolean get() = false

    suspend fun load(
        url: String,
        startPositionMs: Long = 0L,
        headers: Map<String, String> = emptyMap(),
        metadata: PlaybackMetadata? = null,
    )
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun stop()
    fun release()
    fun setVolume(volume: Float)
    fun getVolume(): Float
}


sealed class PlatformPlaybackState {
    data object Idle : PlatformPlaybackState()
    data object Buffering : PlatformPlaybackState()
    data object Ready : PlatformPlaybackState()
    data object Playing : PlatformPlaybackState()
    data object Paused : PlatformPlaybackState()
    data object Ended : PlatformPlaybackState()
    data class Error(val message: String) : PlatformPlaybackState()
}

class AudioPlayerImpl : PlatformPlayer {
    private val player = AudioPlayer()
    
    private val _state = MutableStateFlow<PlatformPlaybackState>(PlatformPlaybackState.Idle)
    override val state: StateFlow<PlatformPlaybackState> = _state.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _formatInfo = MutableStateFlow<AudioFormatInfo?>(null)
    override val formatInfo: StateFlow<AudioFormatInfo?> = _formatInfo.asStateFlow()

    private var pollJob: Job? = null

    /**
     * 位置轮询协程域——**刻意不用 [Dispatchers.Main]**。
     *
     * 桌面端 `Dispatchers.Main` 就是 Swing EDT，也就是 Compose 的合成/出帧线程；
     * 而这里每一轮都要经 JNI 调进 Rust/rodio 播放器取状态（见 [startPolling]）。
     * 挂在 EDT 上等于每 200 ms 阻塞一次出帧，直接破坏帧节奏——在开启 VRR /
     * 系统帧节奏控制的 Windows 显示器上会被放大成刷新率抖动与闪烁。
     *
     * 轮询只写 StateFlow（跨线程安全），不需要 Main。
     */
    private val scope = CoroutineScope(Dispatchers.Default)

    /** 最近一次加载的 URL：库的 play() 在 IDLE（播完/停止）时无效，需重新加载才能重播。 */
    private var lastUrl: String? = null

    /** load() 刚发起播放，随后的 play() 无需重复加载。 */
    private var justLoaded = false

    /**
     * 乐观 seek 目标：底层位置追上目标之前，对外始终汇报该值，
     * 避免 UI 松手后先回弹到旧位置、再跳到新位置。
     * 轮询跑在 [Dispatchers.Default] 上，故用 @Volatile 保证跨线程可见。
     */
    @Volatile private var pendingSeekMs: Long? = null
    @Volatile private var pendingSeekAtMs: Long = 0L

    /** seek 发起时 UI 显示的位置，用于区分「引擎已按 seek 移动」与「引擎还没动」。 */
    @Volatile private var pendingSeekFromMs: Long = 0L

    /**
     * 已向引擎补发过几次待定 seek。
     *
     * 流媒体首次定位要先建连、拿首包，这期间 rodio 会**拒绝**定位（或静默忽略）。
     * 因此 [seekTo] 失败不抛给 UI，而是保留乐观值，由轮询在引擎就绪后重发——
     * 但必须限量，否则每 200ms 重发会一直跟引擎打架。
     */
    @Volatile private var pendingSeekAttempts = 0

    /** 最近一次设定的音量；底层 [AudioPlayer.currentVolume] 为空时作为兜底。 */
    private var lastVolume: Float = 1f

    /**
     * 「接下来这次转入 IDLE 是意料之中的」标记。
     *
     * 底层库只暴露 PLAYING / PAUSED / BUFFERING / IDLE 四个状态，**没有播完事件**，
     * 因此无法单凭状态区分「自然播完」与「我们主动 stop() / 换曲」。
     * 但这两件事的发起方都是我们自己，于是改用**意图**消歧：
     * [load] / [stop] 时置位，轮询观察到 PLAYING 后复位。
     * 这样「PLAYING → IDLE 且未置位」就等价于自然播完——
     * 不再需要靠位置与时长去猜（那套猜法在时长不准的流上会漏判）。
     */
    @Volatile private var suppressEnded = false

    init {
        player.setOnErrorListener(object : io.github.kdroidfilter.composemediaplayer.audio.ErrorListener {
            override fun onError(message: String?) {
                _state.value = PlatformPlaybackState.Error(message ?: "Unknown Error")
            }
        })
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var prevPlayerState: AudioPlayerState? = null
            while (isActive) {
                val currentPlayerState = player.currentPlayerState()
                val pos = (player.currentPosition() as? Number)?.toLong() ?: 0L
                val dur = (player.currentDuration() as? Number)?.toLong() ?: 0L
                // 装载/缓冲中：流媒体首包可能还没到，seek 的宽限期要放宽，否则会误判「seek 失效」。
                val engineLoading = currentPlayerState == AudioPlayerState.BUFFERING ||
                    currentPlayerState == AudioPlayerState.IDLE
                // 乐观 seek：目标被底层追上（或超时）前，对外汇报目标值，避免进度条回弹。
                val target = pendingSeekMs
                if (target != null) {
                    val settled = SeekSettle.isSettled(
                        enginePositionMs = pos,
                        targetMs = target,
                        fromPositionMs = pendingSeekFromMs,
                        elapsedMs = System.currentTimeMillis() - pendingSeekAtMs,
                        engineLoading = engineLoading,
                    )
                    if (settled) {
                        pendingSeekMs = null
                    } else if (pos == pendingSeekFromMs && pendingSeekAttempts < MAX_SEEK_RETRIES) {
                        // 引擎**一步没动**：这次定位多半是在建连期被丢掉了，补发一次。
                        // 若引擎已经在移动（只是还没到目标），补发反而会打断它，所以不补。
                        pendingSeekAttempts++
                        applySeekToEngine(target)
                    }
                }
                _positionMs.value = pendingSeekMs ?: pos
                _durationMs.value = dur
                // 底层库无"播完"事件。用「意图」消歧：
                // 见到 PLAYING 说明本曲已真正开始 → 解除抑制，此后转入 IDLE 即为自然播完；
                // 若期间是我们主动 stop()/换曲，标记仍在，则不会误判成 Ended。
                if (currentPlayerState == AudioPlayerState.PLAYING) suppressEnded = false
                val ended = currentPlayerState == AudioPlayerState.IDLE &&
                    prevPlayerState == AudioPlayerState.PLAYING &&
                    !suppressEnded
                _state.value = when {
                    ended -> PlatformPlaybackState.Ended
                    currentPlayerState == AudioPlayerState.PLAYING -> PlatformPlaybackState.Playing
                    currentPlayerState == AudioPlayerState.PAUSED -> PlatformPlaybackState.Paused
                    currentPlayerState == AudioPlayerState.BUFFERING -> PlatformPlaybackState.Buffering
                    else -> PlatformPlaybackState.Idle
                }
                prevPlayerState = currentPlayerState
                delay(200)
            }
        }
    }

    /**
     * 把一次 seek 交给底层引擎，**不让异常冒到 UI 线程**。
     *
     * rodio 对「尚未建连」或「不可定位」的源会拒绝 seek。旧写法直接调
     * `player.seekTo(...)`，异常会沿 `PlaybackController.seekTo` 一路抛进 Compose 的
     * `onValueChangeFinished` 回调里——用户看到的就是拖完毫无反应（甚至崩一下）。
     * 现在改为：失败只记录，乐观值继续显示，由轮询在引擎就绪后补发（限量）。
     */
    private fun applySeekToEngine(target: Long) {
        runCatching { player.seekTo(target) }
    }

    override suspend fun load(url: String, startPositionMs: Long, headers: Map<String, String>, metadata: PlaybackMetadata?) {
        // 换曲：清掉上一首遗留的待定 seek，避免污染新曲目的位置。
        pendingSeekMs = null
        // 换曲导致的 IDLE 不是"播完"，先抑制，等本曲真正 PLAYING 后自动解除。
        suppressEnded = true
        _state.value = PlatformPlaybackState.Buffering
        lastUrl = url
        justLoaded = true
        player.play(url)
        if (startPositionMs > 0) {
            // 从头播放时把起始位置也纳入乐观值：流媒体建连期间引擎位置还是 0，
            // 若不接管，进度条会先显示 0 再跳到目标。
            pendingSeekFromMs = 0L
            pendingSeekMs = startPositionMs
            pendingSeekAtMs = System.currentTimeMillis()
            pendingSeekAttempts = 0
            _positionMs.value = startPositionMs
            applySeekToEngine(startPositionMs)
        }
    }

    override fun play() {
        // load() 已开始播放，直接消费标志，避免重复加载同一 URL。
        if (justLoaded) {
            justLoaded = false
            return
        }
        // 暂停/缓冲中：恢复播放。
        if (player.currentPlayerState() != AudioPlayerState.IDLE) {
            player.play()
        } else {
            // 播完或停止后（IDLE）：无参 play 无效，重新加载最后一次 URL 从头播放（单曲循环/重播）。
            lastUrl?.let { player.play(it) }
        }
    }

    override fun pause() {
        player.pause()
    }

    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        // 乐观更新：立即把目标位置推给 UI，随后由轮询确认底层是否已追上。
        pendingSeekFromMs = _positionMs.value
        pendingSeekMs = target
        pendingSeekAtMs = System.currentTimeMillis()
        pendingSeekAttempts = 0
        _positionMs.value = target
        applySeekToEngine(target)
    }

    override fun stop() {
        // 主动停止不是"播完"，抑制随后的 IDLE 转换，避免被误判为 Ended 而触发自动续播。
        suppressEnded = true
        // 停止后引擎位置无意义，残留的待定 seek 只会让进度条停在旧目标上。
        pendingSeekMs = null
        player.stop()
    }

    override fun release() {
        suppressEnded = true
        pendingSeekMs = null
        player.stop()
        pollJob?.cancel()
    }

    override fun setVolume(volume: Float) {
        lastVolume = volume.coerceIn(0f, 1f)
        player.setVolume(lastVolume)
    }

    override fun getVolume(): Float = player.currentVolume() ?: lastVolume

    private companion object {
        /** 待定 seek 最多向引擎补发几次（每次间隔一个轮询周期）。 */
        const val MAX_SEEK_RETRIES = 3
    }
}

expect fun createPlatformPlayer(context: PlatformContext): PlatformPlayer