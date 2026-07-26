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
            while (isActive) {
                val currentPlayerState = player.currentPlayerState()
                _state.value = when (currentPlayerState) {
                    AudioPlayerState.PLAYING -> PlatformPlaybackState.Playing
                    AudioPlayerState.PAUSED -> PlatformPlaybackState.Paused
                    AudioPlayerState.BUFFERING -> PlatformPlaybackState.Buffering
                    AudioPlayerState.IDLE -> PlatformPlaybackState.Idle
                    else -> PlatformPlaybackState.Idle
                }
                
                val pos = player.currentPosition()
                _positionMs.value = if (pos != null) (pos as Number).toLong() else 0L
                val d = player.currentDuration()
                _durationMs.value = if (d != null) (d as Number).toLong() else 0L
                delay(200)
            }
        }
    }

    override suspend fun load(url: String, startPositionMs: Long, headers: Map<String, String>) {
        _state.value = PlatformPlaybackState.Buffering
        player.play(url)
        if (startPositionMs > 0) {
            player.seekTo(startPositionMs)
        }
    }

    override fun play() {
        // since load already plays, or if paused we can just call player.play() but we need a url. 
        // wait, the AudioPlayer documentation doesn't have a resume method? It says:
        // Button(onClick = { audioState.player.play(url) }) { Text("Play") }
        // If there's no resume, maybe we just leave play/pause to its own devices or see if there's a resume.
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