package cp.player.kmp.playback

/**
 * 播放引擎接口（KMP 版）。
 *
 * 对标旧项目的 `CPPlayerManager`（ExoPlayer / FlickPlayer 双引擎），
 * 但抽象为平台无关接口。当前版本为**占位骨架**（stub），
 * 播放控制实现将在后续版本由各平台 actual 提供：
 * - Android：Media3 ExoPlayer + FlickPlayer（Rust JNI）
 * - Desktop：待定（JavaFX/AWT 音频或 Rust 桥接）
 *
 * ### 设计意图
 * [MusicBackend] 将播放引擎与音乐源解耦：
 * 音乐源负责"取数据"（云 API / 本地文件），
 * 播放引擎负责"播放数据"（解码、缓冲、输出）。
 * 前端通过 [MusicBackend.playback] 统一访问，切换引擎不影响数据层。
 *
 * ### 引擎类型
 * @see EngineType 可用引擎枚举
 */
interface PlaybackEngine {

    /** 当前引擎类型。 */
    val type: EngineType

    /** 是否支持独占音频通路（如 Android USB DAC UAC 2.0）。 */
    val supportsExclusiveAudio: Boolean get() = false

    /**
     * 播放指定 URL（可能是云端播放 URL 或本地文件路径）。
     *
     * @param url 播放地址
     * @param metadata 元信息（用于 UI 显示，可选）
     */
    suspend fun play(url: String, metadata: PlaybackMetadata? = null)

    /** 暂停。 */
    fun pause()

    /** 恢复。 */
    fun resume()

    /** 停止并释放。 */
    fun stop()

    /**
     * 跳转到指定进度（毫秒）。
     * @param positionMs 目标位置
     */
    fun seekTo(positionMs: Long)

    /** 获取当前播放状态流。 */
    fun stateFlow(): kotlinx.coroutines.flow.StateFlow<PlaybackState>

    /**
     * 设置音量（0.0~1.0）。
     * @param volume 音量比例
     */
    fun setVolume(volume: Float)
}

/**
 * 播放引擎类型。
 */
enum class EngineType {
    /** 基于 Media3 ExoPlayer（Android 默认）。 */
    EXO_PLAYER,
    /** 基于 FlickPlayer / Rust JNI 原生引擎。 */
    FLICK_PLAYER,
    /** Desktop 桌面端引擎（待定）。 */
    DESKTOP,
}

/**
 * 播放状态。
 */
sealed class PlaybackState {
    object Idle : PlaybackState()
    object Buffering : PlaybackState()
    object Ready : PlaybackState()
    object Playing : PlaybackState()
    object Paused : PlaybackState()
    object Ended : PlaybackState()
    data class Error(val message: String) : PlaybackState()
}

/**
 * 播放元信息（与播放引擎解耦的最小集合）。
 */
data class PlaybackMetadata(
    val title: String,
    val artist: String?,
    val album: String?,
    val coverUrl: String?,
    val durationMs: Long,
)