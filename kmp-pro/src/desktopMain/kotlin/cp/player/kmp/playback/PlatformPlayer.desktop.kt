package cp.player.kmp.playback

import cp.player.kmp.util.PlatformContext
import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaException
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaPlayer.Status
import javafx.util.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Desktop 端 [PlatformPlayer]：基于 JavaFX [MediaPlayer]。
 *
 * 纯 JVM 实现（无 native 依赖），支持 MP3/AAC/M4A/WAV/AIFF 等 JavaFX 可解码格式。
 * Cookie/请求头不支持（JavaFX Media 无自定义 HTTP header 能力）——桌面端基本播放够用。
 *
 * 所有 JavaFX 调用都桥接到 JavaFX Application Thread；进度由 [MediaPlayer.currentTimeProperty]
 * 主动推送（前端零轮询）。
 */
internal class JavaFxPlatformPlayer : PlatformPlayer {

    private val _state = MutableStateFlow<PlatformPlaybackState>(PlatformPlaybackState.Idle)
    override val state: StateFlow<PlatformPlaybackState> = _state.asStateFlow()
    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()
    private val _formatInfo = MutableStateFlow<AudioFormatInfo?>(null)
    override val formatInfo: StateFlow<AudioFormatInfo?> = _formatInfo.asStateFlow()

    private var current: MediaPlayer? = null
    private var endReached = false
    @Volatile private var lastVolume = 1f

    init {
        ensureJavaFxStarted()
    }

    override suspend fun load(url: String, startPositionMs: Long, headers: Map<String, String>) {
        stop()
        suspendCancellableCoroutine<Unit> { cont ->
            Platform.runLater {
                try {
                    val mediaUri = if (url.startsWith("/")) File(url).toURI().toString() else url
                    val media = Media(mediaUri)
                    val mp = MediaPlayer(media)
                    mp.volume = lastVolume.toDouble()
                    current = mp
                    endReached = false
                    mp.currentTimeProperty().addListener { _, _, new ->
                        _positionMs.value = new.toMillis().toLong()
                    }
                    mp.totalDurationProperty().addListener { _, _, new ->
                        val d = new?.toMillis()?.toLong() ?: 0L
                        if (d > 0) _durationMs.value = d
                    }
                    mp.statusProperty().addListener { _, _, newStatus ->
                        when (newStatus) {
                            Status.READY -> {
                                val d = mp.totalDuration.toMillis().toLong()
                                if (d > 0) _durationMs.value = d
                                if (cont.isActive) cont.resume(Unit)
                                _state.value = PlatformPlaybackState.Ready
                            }
                            Status.PLAYING -> _state.value = PlatformPlaybackState.Playing
                            Status.PAUSED -> _state.value = PlatformPlaybackState.Paused
                            Status.STALLED -> _state.value = PlatformPlaybackState.Buffering
                            Status.STOPPED -> {
                                if (endReached) _state.value = PlatformPlaybackState.Ended
                                else _state.value = PlatformPlaybackState.Idle
                            }
                            else -> {}
                        }
                    }
                    mp.setOnEndOfMedia {
                        endReached = true
                        _state.value = PlatformPlaybackState.Ended
                    }
                    mp.setOnError {
                        val msg = (mp.error as? MediaException)?.message ?: "未知媒体错误"
                        _state.value = PlatformPlaybackState.Error(msg)
                        if (cont.isActive) cont.resumeWithException(RuntimeException(msg))
                    }
                    if (startPositionMs > 0) mp.seek(Duration.millis(startPositionMs.toDouble()))
                } catch (e: Throwable) {
                    _state.value = PlatformPlaybackState.Error(e.message ?: "媒体加载失败")
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        }
    }

    override fun play() {
        runOnFx { current?.play() }
    }

    override fun pause() {
        runOnFx { current?.pause() }
    }

    override fun seekTo(positionMs: Long) {
        runOnFx { current?.seek(Duration.millis(positionMs.toDouble())) }
        _positionMs.value = positionMs
    }

    override fun stop() {
        runOnFx {
            current?.stop()
            current?.dispose()
            current = null
        }
        _state.value = PlatformPlaybackState.Idle
        _positionMs.value = 0L
        _durationMs.value = 0L
        _formatInfo.value = null
    }

    override fun release() {
        stop()
    }

    override fun setVolume(volume: Float) {
        lastVolume = volume.coerceIn(0f, 1f)
        runOnFx { current?.volume = lastVolume.toDouble() }
    }

    override fun getVolume(): Float = lastVolume

    private inline fun runOnFx(crossinline block: () -> Unit) {
        Platform.runLater { runCatching { block() } }
    }

    companion object {
        @Volatile private var jfxStarted = false
        fun ensureJavaFxStarted() {
            if (jfxStarted) return
            synchronized(JavaFxPlatformPlayer::class.java) {
                if (jfxStarted) return
                try {
                    Platform.startup { /* no primary stage needed for audio */ }
                } catch (_: IllegalStateException) {
                    // 已由其它组件启动过，忽略
                }
                jfxStarted = true
            }
        }
    }
}

/** Desktop 平台播放器工厂。 */
actual fun createPlatformPlayer(context: PlatformContext): PlatformPlayer = JavaFxPlatformPlayer()