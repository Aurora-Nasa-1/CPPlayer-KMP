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

    suspend fun load(url: String, startPositionMs: Long = 0L, headers: Map<String, String> = emptyMap())
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
    private val scope = CoroutineScope(Dispatchers.Main)

    /** 最近一次加载的 URL：库的 play() 在 IDLE（播完/停止）时无效，需重新加载才能重播。 */
    private var lastUrl: String? = null

    /** load() 刚发起播放，随后的 play() 无需重复加载。 */
    private var justLoaded = false

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
            var prevPosition = 0L
            while (isActive) {
                val currentPlayerState = player.currentPlayerState()
                val pos = (player.currentPosition() as? Number)?.toLong() ?: 0L
                val dur = (player.currentDuration() as? Number)?.toLong() ?: 0L
                _positionMs.value = pos
                _durationMs.value = dur
                // 库没有"播完"事件：自然播完时内部状态变为 IDLE。
                // 检测 播放中 → IDLE 且位置已到末尾 的转换，映射为 Ended 供上层自动续播。
                val ended = currentPlayerState == AudioPlayerState.IDLE &&
                    prevPlayerState == AudioPlayerState.PLAYING &&
                    dur > 0 && prevPosition >= dur - 2_000L
                _state.value = when {
                    ended -> PlatformPlaybackState.Ended
                    currentPlayerState == AudioPlayerState.PLAYING -> PlatformPlaybackState.Playing
                    currentPlayerState == AudioPlayerState.PAUSED -> PlatformPlaybackState.Paused
                    currentPlayerState == AudioPlayerState.BUFFERING -> PlatformPlaybackState.Buffering
                    else -> PlatformPlaybackState.Idle
                }
                prevPlayerState = currentPlayerState
                prevPosition = pos
                delay(200)
            }
        }
    }

    override suspend fun load(url: String, startPositionMs: Long, headers: Map<String, String>) {
        _state.value = PlatformPlaybackState.Buffering
        lastUrl = url
        justLoaded = true
        player.play(url)
        if (startPositionMs > 0) {
            player.seekTo(startPositionMs)
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
        player.seekTo(positionMs)
    }

    override fun stop() {
        player.stop()
    }

    override fun release() {
        player.stop()
        pollJob?.cancel()
    }

    override fun setVolume(volume: Float) {
        player.setVolume(volume)
    }

    override fun getVolume(): Float {
        return 1.0f // cannot get volume easily if not provided
    }
}

fun createPlatformPlayer(context: PlatformContext): PlatformPlayer {
    return AudioPlayerImpl()
}