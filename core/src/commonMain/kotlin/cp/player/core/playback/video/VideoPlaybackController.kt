package cp.player.core.playback.video

import cp.player.core.BackendResult
import cp.player.core.media.MediaQualityOption
import cp.player.core.media.MediaStream
import cp.player.core.media.UnifiedMediaResolver
import cp.player.core.media.VideoResolution
import cp.player.core.media.VideoScalingMode
import cp.player.core.playback.PlatformPlaybackState
import cp.player.core.playback.PlaybackMetadata
import cp.player.core.playback.SeekFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 视频播放渲染与交互状态。
 */
data class VideoPlaybackUiState(
    val currentMedia: MediaStream? = null,
    val playbackState: PlatformPlaybackState = PlatformPlaybackState.Idle,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val resolution: VideoResolution? = null,
    val currentQuality: MediaQualityOption? = null,
    val availableQualities: List<MediaQualityOption> = emptyList(),
    val errorMessage: String? = null,
    val scalingMode: VideoScalingMode = VideoScalingMode.FIT,
    val volume: Float = 1.0f,
)

/**
 * 前端视频播放控制的唯一入口接口。
 *
 * 统一管理在线 MV（如网易云）与本地视频文件的加载、播控、清晰度切换与渲染。
 */
interface VideoPlaybackController {
    val state: StateFlow<VideoPlaybackUiState>
    val seekFailures: SharedFlow<SeekFailure>

    /** 平台底层视频播放内核句柄（供渲染视图挂载） */
    val platformPlayer: PlatformVideoPlayer

    /**
     * 播放指定的媒体资源。
     *
     * @param mediaId 统一媒体 ID（例如 `local://video/{path}` 或 `netease://mv/{mvId}`）
     * @param quality 期望清晰度，null 表示使用最高可用画质
     * @param startPositionMs 起始播放进度
     */
    suspend fun play(
        mediaId: String,
        quality: String? = null,
        startPositionMs: Long = 0L,
    )

    fun togglePlayPause()
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)

    /** 切换清晰度（保留当前播放进度与状态） */
    suspend fun switchQuality(quality: MediaQualityOption)

    /** 切换画幅适应模式 */
    fun setScalingMode(mode: VideoScalingMode)

    /** 调节音量 (0f..1f) */
    fun setVolume(volume: Float)

    /** 停止播放并释放当前视频媒体 */
    fun stop()

    /** 销毁播放控制器与底层内核 */
    fun release()
}

class VideoPlaybackControllerImpl(
    override val platformPlayer: PlatformVideoPlayer,
    private val mediaResolver: UnifiedMediaResolver,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    /** 当视频开始播放时触发的回调（通常用于暂停背景音乐） */
    private val onPlaybackStarted: (() -> Unit)? = null,
    /** 当视频关闭或停止时触发的回调 */
    private val onPlaybackStopped: (() -> Unit)? = null,
) : VideoPlaybackController {

    private val _state = MutableStateFlow(VideoPlaybackUiState())
    override val state: StateFlow<VideoPlaybackUiState> = _state.asStateFlow()

    override val seekFailures: SharedFlow<SeekFailure> = platformPlayer.seekFailures

    private var loadJob: Job? = null

    init {
        // 监听平台播放状态
        scope.launch {
            platformPlayer.state.collect { playbackState ->
                _state.value = _state.value.copy(
                    playbackState = playbackState,
                    isPlaying = playbackState == PlatformPlaybackState.Playing,
                    isBuffering = playbackState == PlatformPlaybackState.Buffering,
                    errorMessage = (playbackState as? PlatformPlaybackState.Error)?.message,
                )
            }
        }

        // 监听进度
        scope.launch {
            platformPlayer.positionMs.collect { pos ->
                _state.value = _state.value.copy(positionMs = pos)
            }
        }

        // 监听时长
        scope.launch {
            platformPlayer.durationMs.collect { dur ->
                if (dur > 0L) {
                    _state.value = _state.value.copy(durationMs = dur)
                }
            }
        }

        // 监听分辨率
        scope.launch {
            platformPlayer.videoResolution.collect { res ->
                if (res != null) {
                    _state.value = _state.value.copy(resolution = res)
                }
            }
        }
    }

    override suspend fun play(
        mediaId: String,
        quality: String?,
        startPositionMs: Long,
    ) {
        loadJob?.cancel()
        _state.value = _state.value.copy(
            isBuffering = true,
            errorMessage = null,
            positionMs = startPositionMs,
        )

        // 触发音视频互斥：开视频停音频
        onPlaybackStarted?.invoke()

        loadJob = scope.launch {
            when (val result = mediaResolver.resolveStream(mediaId, quality)) {
                is BackendResult.Success -> {
                    val stream = result.data
                    _state.value = _state.value.copy(
                        currentMedia = stream,
                        currentQuality = stream.currentQuality,
                        availableQualities = stream.availableQualities,
                        durationMs = stream.durationMs,
                    )

                    platformPlayer.load(
                        url = stream.url,
                        startPositionMs = startPositionMs,
                        headers = stream.headers,
                        metadata = PlaybackMetadata(
                            id = stream.mediaId,
                            title = stream.title ?: "未知视频",
                            artist = stream.artist,
                            album = null,
                            coverUrl = stream.coverUrl,
                            durationMs = stream.durationMs,
                        ),
                    )
                }
                is BackendResult.Error -> {
                    _state.value = _state.value.copy(
                        isBuffering = false,
                        errorMessage = result.message,
                    )
                }
                is BackendResult.Unsupported -> {
                    _state.value = _state.value.copy(
                        isBuffering = false,
                        errorMessage = result.message,
                    )
                }
            }
        }
    }

    override fun togglePlayPause() {
        if (_state.value.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    override fun play() {
        platformPlayer.play()
    }

    override fun pause() {
        platformPlayer.pause()
    }

    override fun seekTo(positionMs: Long) {
        platformPlayer.seekTo(positionMs)
    }

    override suspend fun switchQuality(quality: MediaQualityOption) {
        val current = _state.value.currentMedia ?: return
        if (_state.value.currentQuality?.id == quality.id) return

        when (val result = mediaResolver.resolveStream(current.mediaId, quality.id)) {
            is BackendResult.Success -> {
                val newStream = result.data
                _state.value = _state.value.copy(
                    currentMedia = newStream,
                    currentQuality = newStream.currentQuality ?: quality,
                )
                platformPlayer.switchQuality(newStream.url, newStream.headers)
            }
            is BackendResult.Error -> {
                _state.value = _state.value.copy(errorMessage = "切换清晰度失败: ${result.message}")
            }
            is BackendResult.Unsupported -> {
                _state.value = _state.value.copy(errorMessage = result.message)
            }
        }
    }

    override fun setScalingMode(mode: VideoScalingMode) {
        _state.value = _state.value.copy(scalingMode = mode)
    }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        platformPlayer.setVolume(clamped)
        _state.value = _state.value.copy(volume = clamped)
    }

    override fun stop() {
        loadJob?.cancel()
        platformPlayer.stop()
        _state.value = VideoPlaybackUiState()
        onPlaybackStopped?.invoke()
    }

    override fun release() {
        loadJob?.cancel()
        platformPlayer.release()
        _state.value = VideoPlaybackUiState()
        onPlaybackStopped?.invoke()
    }
}
