package cp.player.core.playback

import cp.player.core.util.PlatformContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import io.github.kdroidfilter.composemediaplayer.audio.AudioPlayer
import io.github.kdroidfilter.composemediaplayer.audio.AudioPlayerState

/**
 * 一次 seek 最终没能生效。
 *
 * 这是 [PendingSeekTracker.Tick.GaveUp] 的对外形态：宽限期已过、乐观值已放弃、
 * 进度条已回落到 [actualMs]。它是**失败信号**，前端应据此提示用户——
 * 否则失败只能表现为「进度条自己弹回原位」，用户无法区分「我拖错了」和
 * 「这个音源根本不能定位」，也就是最初的「拖了没反应」。
 */
data class SeekFailure(val targetMs: Long, val actualMs: Long)

/**
 * 「无需上报 seek 失败」的实现共用的空流。
 *
 * 用单例而不是在默认实现里现场 `MutableSharedFlow()`：后者每次读属性都会新建对象。
 */
private val NoSeekFailures: SharedFlow<SeekFailure> = MutableSharedFlow()

interface PlatformPlayer {
    val state: StateFlow<PlatformPlaybackState>
    val positionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val formatInfo: StateFlow<AudioFormatInfo?>

    /**
     * 本平台是否支持**音频独占**（绕过系统混音器直接输出）。
     *
     * ⚠️ 全仓当前**没有任何实现返回 true**，也没有消费方 —— 保留它是作为能力位，
     * 供将来「音效 vs 独占」的取舍决策使用：独占输出会绕过系统音效链，
     * 开了独占之后 [applyAudioEffect] 的效果将不可用。一旦某平台真的实现独占，
     * 这条约束必须同时在 UI 上体现（两者互斥，不能各自安好地各显示一个开关）。
     */
    val supportsExclusiveAudio: Boolean get() = false

    /**
     * seek 未能生效的事件流。
     *
     * 给默认实现，是为了让「无需上报」的实现（静默输出、测试假播放器）不必改动；
     * 真正的平台播放器应覆盖它，把 [PendingSeekTracker.Tick.GaveUp] 转成事件。
     * 用 SharedFlow 而非 StateFlow：这是**事件**，重新订阅时不该被重放。
     */
    val seekFailures: SharedFlow<SeekFailure> get() = NoSeekFailures

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

    // ============ 音效 ============

    /**
     * 本平台对音效各能力的支持情况（见 [AudioEffectCapabilities]）。
     *
     * 给默认值 [AudioEffectCapabilities.NONE]，是为了让「不支持音效」的实现
     * （桌面 rodio、静默输出装饰器、测试假播放器）**不必改动** ——
     * 与 [supportsExclusiveAudio] 同一套思路。
     *
     * ⚠️ 设置页必须读它来决定是否**明示禁用**。不读的后果是桌面端出现一组
     * 「能拖、能存盘、但什么都不发生」的滑杆 —— 用户会以为自己调生效了。
     */
    val audioEffectCapabilities: AudioEffectCapabilities get() = AudioEffectCapabilities.NONE

    /**
     * 应用一份音效配置（全量覆盖，平台层自行 diff）。
     *
     * ### 为什么传整体快照而不是三个 setter
     * 三块效果（PEQ / 声道平衡 / 响度均衡）在平台层往往落在**同一条效果链**上
     * （Android 就是 `DynamicsProcessing` 一个实例）。分次下发会让每次调节都
     * 重建链或局部改动，前者导致听感断续、后者容易漏掉必须一起改的耦合参数。
     * 详见 [AudioEffectConfig] 的 KDoc。
     *
     * ### 契约
     * - **必须可重复调用**：同一份配置连续调用两次应当无副作用；
     * - **必须容忍 [AudioEffectConfig.enabled] = false**：等价于拆除效果链回直通；
     * - **不得抛异常**：平台不支持时静默忽略即可（能力已由
     *   [audioEffectCapabilities] 声明，UI 侧已据此禁用）；
     * - [AudioEffectConfig.enabled] = true 但平台不支持时，**不报错也不尝试**。
     *
     * 默认空实现：不支持音效的平台无需覆写。
     */
    fun applyAudioEffect(config: AudioEffectConfig) {}
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
     * 待定 seek 的状态机：**桌面与安卓共用 [PendingSeekTracker]**，不再各写一份同构逻辑。
     *
     * ⚠️ `enginePositionMs` 必须读**引擎真实位置**。传 `_positionMs.value` 是错的——
     * 那是乐观值，会让「引擎一步没动就补发」的判定永久失效（详见该类 KDoc）。
     *
     * 轮询跑在 [Dispatchers.Default] 上，故 tracker 内部状态均用 @Volatile。
     */
    private val pendingSeek = PendingSeekTracker(
        enginePositionMs = { (player.currentPosition() as? Number)?.toLong() ?: 0L },
        dispatchSeek = ::applySeekToEngine,
    )

    /**
     * seek 失败事件：见 [SeekFailure]。
     *
     * 用 `tryEmit` 而非 `emit`：轮询协程不能被 UI 消费端拖住（消费端挂起时宁可丢事件，
     * 也不能让 200ms 的位置轮询卡住）。缓冲 4 条足够——同一时刻只可能有一次待定 seek。
     */
    private val _seekFailures = MutableSharedFlow<SeekFailure>(extraBufferCapacity = 4)
    override val seekFailures: SharedFlow<SeekFailure> = _seekFailures.asSharedFlow()

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
                val rawPos = player.currentPosition() as? Number
                val pos = rawPos?.toLong() ?: 0L
                val rawDur = player.currentDuration()
                val dur = (rawDur as? Number)?.toLong() ?: 0L
                // 「引擎是否就绪」的真信号 = **时长已可知**，而不是「RodioPlayer 对象存在」。
                // 库的 `play(url)` 会同步 `ensurePlayer()` 建出 RodioPlayer，但此时**源还没装载**，
                // 这个窗口里 seekTo 依旧是静默空操作。只有解析出时长才说明源真的可定位了。
                // 也绝不能改用「位置有没有动」去反推（见 PendingSeekTracker 的 KDoc）。
                val engineReady = rawDur != null
                // 装载/缓冲中：流媒体首包可能还没到，seek 的宽限期要放宽，否则会误判「seek 失效」。
                val engineLoading = currentPlayerState == AudioPlayerState.BUFFERING ||
                    currentPlayerState == AudioPlayerState.IDLE
                // 推进待定 seek：落定则释放乐观值；刚就绪 / 未就绪 / 就绪后没动 都要补发。
                val tick = pendingSeek.tick(
                    enginePositionMs = pos,
                    engineReady = engineReady,
                    engineLoading = engineLoading,
                )
                // 宽限期过完仍没追上 ⇒ 上报失败，让 UI 能提示用户，
                // 而不是让进度条静默弹回原位（那正是「拖了没反应」的观感）。
                if (tick is PendingSeekTracker.Tick.GaveUp) {
                    _seekFailures.tryEmit(SeekFailure(tick.targetMs, tick.actualMs))
                }
                _positionMs.value = pendingSeek.displayMs() ?: pos
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
     * 现在改为：失败只记录，乐观值继续显示，由轮询在引擎就绪后补发（直到宽限期）。
     *
     * @return 引擎是否接受了这次定位。
     */
    private fun applySeekToEngine(target: Long): Boolean =
        runCatching { player.seekTo(target) }.isSuccess

    override suspend fun load(url: String, startPositionMs: Long, headers: Map<String, String>, metadata: PlaybackMetadata?) {
        // 换曲：清掉上一首遗留的待定 seek，避免污染新曲目的位置。
        pendingSeek.cancel()
        // 换曲导致的 IDLE 不是"播完"，先抑制，等本曲真正 PLAYING 后自动解除。
        suppressEnded = true
        _state.value = PlatformPlaybackState.Buffering
        lastUrl = url
        justLoaded = true
        player.play(url)
        if (startPositionMs > 0) {
            // 从头播放时把起始位置也纳入乐观值：流媒体建连期间引擎位置还是 0，
            // 若不接管，进度条会先显示 0 再跳到目标。
            // 刚发起 load()，rodio 的 player 还没建好 ⇒ engineReady = false，
            // 这次下发多半是空操作，由轮询在引擎就绪后补发。
            pendingSeek.request(startPositionMs, engineReady = false)
            _positionMs.value = startPositionMs
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
        // 基线捕获、补发、落定全部交给 [PendingSeekTracker]，本类不再内联这套逻辑。
        // engineReady 必须是**引擎真值**：未就绪时下发是空操作，需要轮询补发。
        pendingSeek.request(target, engineReady = player.currentDuration() != null)
        _positionMs.value = target
    }

    override fun stop() {
        // 主动停止不是"播完"，抑制随后的 IDLE 转换，避免被误判为 Ended 而触发自动续播。
        suppressEnded = true
        // 停止后引擎位置无意义，残留的待定 seek 只会让进度条停在旧目标上。
        pendingSeek.cancel()
        player.stop()
    }

    override fun release() {
        suppressEnded = true
        pendingSeek.cancel()
        player.stop()
        pollJob?.cancel()
    }

    override fun setVolume(volume: Float) {
        lastVolume = volume.coerceIn(0f, 1f)
        player.setVolume(lastVolume)
    }

    override fun getVolume(): Float = player.currentVolume() ?: lastVolume

    // ============ 音效：桌面端不可用 ============

    /**
     * ⚠️ 桌面端**没有**音效能力，这里显式写出来而不是靠默认值 ——
     * 这是本文件唯一需要"说明为什么是空"的地方，不写清楚会让人以为漏了实现。
     *
     * ### 为什么桌面做不到
     * 音频链是 `composemediaplayer-audio` → `nucleus.rodio` → `nucleus_rodio.dll`
     * 的 Rust JNI 实现。逐层核对过：
     * - `AudioPlayer`（composemediaplayer）公开方法只有
     *   `play/stop/pause/release/currentPosition/currentDuration/currentPlayerState/
     *   currentVolume/setVolume/setRate/seekTo/setOnErrorListener`；
     * - `RodioPlayer`（nucleus.rodio）只有
     *   `playFile/playFileAsync/playUrl/playUrlAsync/playRadio/playRadioAsync/
     *   playSine/play/pause/stop/clear/getPositionMs/getDurationMs/seekToMs/
     *   isSeekable/setVolume/...`；
     * - `nucleus_rodio.dll` 的 24 个 JNI 导出符号里，**只有 `nativeSetVolume`**，
     *   没有任何 EQ / filter / biquad / effect 相关符号。
     *
     * 要做 PEQ 只能自建信号处理链（重采样回灌或换播放后端），成本显著高于
     * Android 的现成能力。本次不做，桌面侧如实声明不支持 ——
     * 设置页据此**明示禁用**并在页面上说明原因，而不是给一排无效滑杆。
     *
     * ### 为什么不是返回默认值就算了
     * 默认值（[AudioEffectCapabilities.NONE]）语义上是"没覆写"，
     * 而这里是"明确知道做不到"。两者在将来某人给桌面换后端时，
     * 应当让他**先看到这段 KDoc 再决定**，而不是以为只是"还没实现"。
     */
    override val audioEffectCapabilities: AudioEffectCapabilities
        get() = AudioEffectCapabilities.NONE

    /**
     * 桌面端空实现。设置页已据 [audioEffectCapabilities] 禁用所有音效控件，
     * 正常流程下不会调到；即便如此也不抛异常（见 [PlatformPlayer.applyAudioEffect] 契约）。
     */
    override fun applyAudioEffect(config: AudioEffectConfig) = Unit
}

expect fun createPlatformPlayer(context: PlatformContext): PlatformPlayer