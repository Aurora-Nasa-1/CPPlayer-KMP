@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package cp.player.kmp.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import cp.player.kmp.util.PlatformContext
import cp.player.kmp.util.androidContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Android 端 [PlatformPlayer]：基于 Media3 ExoPlayer。 */
internal class ExoPlayerPlatform(
    context: Context,
) : PlatformPlayer {

    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .build()
        .apply { volume = 1f }

    private val _state = MutableStateFlow<PlatformPlaybackState>(PlatformPlaybackState.Idle)
    override val state: StateFlow<PlatformPlaybackState> = _state.asStateFlow()
    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()
    private val _formatInfo = MutableStateFlow<AudioFormatInfo?>(null)
    override val formatInfo: StateFlow<AudioFormatInfo?> = _formatInfo.asStateFlow()

    private var positionTicker: Job? = null
    private var pendingLoad: Player.Listener? = null
    private val tickerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val LOAD_TIMEOUT_MS = 30_000L
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_IDLE -> _state.value = PlatformPlaybackState.Idle
                    Player.STATE_BUFFERING -> _state.value = PlatformPlaybackState.Buffering
                    Player.STATE_READY -> {
                        val target = if (player.playWhenReady) PlatformPlaybackState.Playing
                        else PlatformPlaybackState.Ready
                        _state.value = target
                        val d = player.duration
                        if (d != C.TIME_UNSET) _durationMs.value = d
                    }
                    Player.STATE_ENDED -> _state.value = PlatformPlaybackState.Ended
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) _state.value = PlatformPlaybackState.Playing
                else if (player.playbackState == Player.STATE_READY) _state.value = PlatformPlaybackState.Paused
                startPositionTicker(isPlaying)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                _state.value = PlatformPlaybackState.Error(error.message ?: "ExoPlayer 错误")
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val audioFormat = tracks.groups
                    .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }
                    ?.getTrackFormat(0)
                _formatInfo.value = audioFormat?.toAudioFormatInfo()
            }
        })
    }

    override suspend fun load(url: String, startPositionMs: Long, headers: Map<String, String>) {
        stop()
        val mediaSource = buildMediaSource(url, headers)
        withTimeout(LOAD_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            player.removeListener(this)
                            if (cont.isActive) cont.resume(Unit)
                        } else if (playbackState == Player.STATE_IDLE) {
                            player.removeListener(this)
                            if (cont.isActive) cont.resumeWithException(
                                IllegalStateException("媒体无法加载: 服务器未返回可播放内容")
                            )
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        player.removeListener(this)
                        if (cont.isActive) {
                            val msg = when (error.errorCode) {
                                2001 -> "无法连接服务器（检查网络或CDN可达性）"
                                2002 -> "不支持该音频格式"
                                2008 -> "服务器返回错误（可能需要登录或Cookie已过期）"
                                else -> "播放错误: ${error.message ?: "未知错误"}"
                            }
                            cont.resumeWithException(IllegalStateException(msg))
                        }
                    }
                }
                pendingLoad = listener
                player.addListener(listener)
                player.setMediaSource(mediaSource, startPositionMs.coerceAtLeast(0))
                player.prepare()
                cont.invokeOnCancellation { player.removeListener(listener) }
            }
        }
    }

    override fun play() {
        player.playWhenReady = true
    }

    override fun pause() {
        player.playWhenReady = false
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0))
        _positionMs.value = positionMs.coerceAtLeast(0)
    }

    override fun stop() {
        pendingLoad?.let { player.removeListener(it) }
        pendingLoad = null
        player.stop()
        player.clearMediaItems()
        _state.value = PlatformPlaybackState.Idle
        _positionMs.value = 0L
        _durationMs.value = 0L
        _formatInfo.value = null
        startPositionTicker(false)
    }

    override fun release() {
        startPositionTicker(false)
        tickerScope.cancel()
        player.release()
    }

    override fun setVolume(volume: Float) {
        player.volume = volume.coerceIn(0f, 1f)
    }

    override fun getVolume(): Float = player.volume

    private fun buildMediaSource(url: String, headers: Map<String, String>): MediaSource {
        val mediaItem = MediaItem.fromUri(Uri.parse(url))
        return if (url.startsWith("/")) {
            ProgressiveMediaSource.Factory(FileDataSource.Factory()).createMediaSource(mediaItem)
        } else {
            val httpFactory = DefaultHttpDataSource.Factory().apply {
                setDefaultRequestProperties(headers)
            }
            ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
        }
    }

    private fun startPositionTicker(running: Boolean) {
        positionTicker?.cancel()
        if (!running) return
        positionTicker = tickerScope.launch {
            while (true) {
                delay(500L)
                if (player.isPlaying) _positionMs.value = player.currentPosition.coerceAtLeast(0)
            }
        }
    }

    private fun Format.toAudioFormatInfo(): AudioFormatInfo = AudioFormatInfo(
        codecName = sampleMimeType?.substringAfter('/'),
        sampleRate = sampleRate.takeIf { it != Format.NO_VALUE },
        channels = channelCount.takeIf { it != Format.NO_VALUE },
        bitrate = bitrate.takeIf { it != Format.NO_VALUE },
        mimeType = sampleMimeType,
    )
}

/** Android 平台播放器工厂：从 [PlatformContext] 取 [Context] 并构建 ExoPlayer。 */
actual fun createPlatformPlayer(context: PlatformContext): PlatformPlayer {
    val ctx = context.androidContext() ?: error("Android PlatformContext 内的 Context 为空；请先调用 initKmpAndroidContext")
    return ExoPlayerPlatform(ctx)
}