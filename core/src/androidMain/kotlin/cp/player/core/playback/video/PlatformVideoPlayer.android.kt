package cp.player.core.playback.video

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import cp.player.core.media.VideoResolution
import cp.player.core.playback.PendingSeekTracker
import cp.player.core.playback.PlatformPlaybackState
import cp.player.core.playback.PlaybackMetadata
import cp.player.core.playback.SeekFailure
import cp.player.core.util.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android 端基于 Media3 (ExoPlayer) 的视频播放内核实现。
 *
 * 核心特性：
 * 1. 开启解码器回退 ([DefaultRenderersFactory.setEnableDecoderFallback])，保障多编码格式兼容；
 * 2. 动态画幅与分辨率监测 ([VideoSize] -> [VideoResolution])；
 * 3. 支持无缝清晰度热切换 ([switchQuality])；
 * 4. 跨平台状态机与 Seek 防回弹状态机 ([PendingSeekTracker]) 深度复用；
 * 5. 渲染载体可灵活挂载 [PlayerView]、[SurfaceView]、[TextureView] 或原始 [Surface]。
 */
class Media3VideoPlayer(context: Context) : PlatformVideoPlayer {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main)

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)

    private val player: ExoPlayer = ExoPlayer.Builder(
        context.applicationContext,
        DefaultRenderersFactory(context.applicationContext).setEnableDecoderFallback(true),
    )
        .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true, // 自动处理 AudioFocus
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val _state = MutableStateFlow<PlatformPlaybackState>(PlatformPlaybackState.Idle)
    override val state: StateFlow<PlatformPlaybackState> = _state.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _videoResolution = MutableStateFlow<VideoResolution?>(null)
    override val videoResolution: StateFlow<VideoResolution?> = _videoResolution.asStateFlow()

    private val _seekFailures = MutableSharedFlow<SeekFailure>(extraBufferCapacity = 4)
    override val seekFailures: SharedFlow<SeekFailure> = _seekFailures.asSharedFlow()

    private val pendingSeek = PendingSeekTracker(
        enginePositionMs = {
            var pos = 0L
            if (Looper.myLooper() == Looper.getMainLooper()) {
                pos = runCatching { player.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)
            } else {
                runOnMainThread {
                    pos = runCatching { player.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)
                }
            }
            pos
        },
        dispatchSeek = ::applySeekToEngine,
    )

    private var pollJob: Job? = null
    private var lastHeaders: Map<String, String> = emptyMap()

    init {
        runOnMainThread {
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    updatePlaybackState()
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    updatePlaybackState()
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        _videoResolution.value = VideoResolution(videoSize.width, videoSize.height)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    _state.value = PlatformPlaybackState.Error(error.localizedMessage ?: "ExoPlayer Error")
                }
            })
        }
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val dur = player.duration.coerceAtLeast(0L)
                val rawPos = player.currentPosition.coerceAtLeast(0L)
                val engineReady = player.mediaItemCount > 0 && dur > 0
                val engineLoading = player.playbackState == Player.STATE_BUFFERING

                val tick = pendingSeek.tick(
                    enginePositionMs = rawPos,
                    engineReady = engineReady,
                    engineLoading = engineLoading,
                )

                if (tick is PendingSeekTracker.Tick.GaveUp) {
                    _seekFailures.tryEmit(SeekFailure(tick.targetMs, tick.actualMs))
                }

                _positionMs.value = pendingSeek.displayMs() ?: rawPos
                if (dur > 0L) {
                    _durationMs.value = dur
                }

                delay(200)
            }
        }
    }

    private fun applySeekToEngine(target: Long): Boolean {
        return runCatching {
            runOnMainThread {
                player.seekTo(target)
            }
            true
        }.getOrDefault(false)
    }

    private fun updatePlaybackState() {
        _state.value = when (player.playbackState) {
            Player.STATE_IDLE -> PlatformPlaybackState.Idle
            Player.STATE_BUFFERING -> PlatformPlaybackState.Buffering
            Player.STATE_READY -> if (player.playWhenReady) PlatformPlaybackState.Playing else PlatformPlaybackState.Paused
            Player.STATE_ENDED -> PlatformPlaybackState.Ended
            else -> PlatformPlaybackState.Idle
        }
    }

    override suspend fun load(
        url: String,
        startPositionMs: Long,
        headers: Map<String, String>,
        metadata: PlaybackMetadata?,
    ) {
        pendingSeek.cancel()
        lastHeaders = headers
        _state.value = PlatformPlaybackState.Buffering

        runOnMainThread {
            if (headers.isNotEmpty()) {
                httpDataSourceFactory.setDefaultRequestProperties(headers)
            }
            val mediaItem = MediaItem.fromUri(url)
            player.setMediaItem(mediaItem, startPositionMs.coerceAtLeast(0L))
            player.prepare()
            player.playWhenReady = true

            if (startPositionMs > 0) {
                pendingSeek.request(startPositionMs, engineReady = false)
                _positionMs.value = startPositionMs
            }
        }
    }

    override fun play() {
        runOnMainThread {
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0)
            }
            player.playWhenReady = true
        }
    }

    override fun pause() {
        runOnMainThread {
            player.playWhenReady = false
        }
    }

    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        pendingSeek.request(target, engineReady = player.mediaItemCount > 0 && player.duration > 0)
        _positionMs.value = target
        applySeekToEngine(target)
    }

    override suspend fun switchQuality(url: String, headers: Map<String, String>) {
        val currentPos = _positionMs.value
        val isPlaying = player.playWhenReady
        load(url = url, startPositionMs = currentPos, headers = headers)
        if (!isPlaying) {
            pause()
        }
    }

    override fun stop() {
        pendingSeek.cancel()
        runOnMainThread {
            player.stop()
            _state.value = PlatformPlaybackState.Idle
        }
    }

    override fun release() {
        pendingSeek.cancel()
        pollJob?.cancel()
        runOnMainThread {
            player.release()
            _state.value = PlatformPlaybackState.Idle
        }
    }

    override fun setVolume(volume: Float) {
        runOnMainThread {
            player.volume = volume.coerceIn(0f, 1f)
        }
    }

    override fun getVolume(): Float = player.volume

    /** 供 Android 上层 UI 或 Compose AndroidView 直接访问底层播放实例（如 PlayerView 绑定） */
    val rawExoPlayer: ExoPlayer get() = player

    override fun attachSurface(surfaceTarget: Any?) {
        runOnMainThread {
            when (surfaceTarget) {
                is SurfaceView -> player.setVideoSurfaceView(surfaceTarget)
                is TextureView -> player.setVideoTextureView(surfaceTarget)
                is Surface -> player.setVideoSurface(surfaceTarget)
                null -> player.clearVideoSurface()
                else -> {
                    // 支持上层传来 androidx.media3.ui.PlayerView 等扩展视图
                    runCatching {
                        val method = surfaceTarget.javaClass.getMethod("setPlayer", Player::class.java)
                        method.invoke(surfaceTarget, player)
                    }
                }
            }
        }
    }
}

actual fun createPlatformVideoPlayer(context: PlatformContext): PlatformVideoPlayer {
    val androidCtx = context.androidContext
        ?: throw IllegalStateException("PlatformContext does not contain Android Context")
    return Media3VideoPlayer(androidCtx)
}
