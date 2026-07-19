package cp.player.kmp.playback

import cp.player.kmp.util.PlatformContext
import kotlinx.coroutines.flow.StateFlow

/**
 * 平台播放器桥接抽象（commonMain 定义接口，各平台在 actual 中提供实现类）。
 *
 * [PlaybackControllerImpl] 通过此接口操作底层播放器，
 * 不感知具体是 ExoPlayer / FlickPlayer / JavaFX MediaPlayer。
 *
 * ### 契约
 * - [state] / [positionMs] / [durationMs] / [formatInfo] 由实现**主动推送**，
 *   控制器零轮询。
 * - [load] 是 suspend，返回时媒体已就绪（可立即 [play]）；失败抛异常。
 * - [ended] 事件通过 [state] 变为 [PlatformPlaybackState.Ended] 推送。
 */
interface PlatformPlayer {
    /** 播放器状态流。 */
    val state: StateFlow<PlatformPlaybackState>
    /** 当前播放位置（毫秒）。 */
    val positionMs: StateFlow<Long>
    /** 当前媒体总时长（毫秒，未知=0）。 */
    val durationMs: StateFlow<Long>
    /** 当前音频格式信息流。 */
    val formatInfo: StateFlow<AudioFormatInfo?>
    /** 是否支持独占音频通路（如 Android USB DAC UAC 2.0）。 */
    val supportsExclusiveAudio: Boolean get() = false

    /**
     * 加载媒体并准备到可播放状态。
     * @param url 远端 URL 或本地文件路径
     * @param startPositionMs 起始位置（0=从头）
     * @param headers 请求头（如 Cookie），不支持的平台应忽略
     */
    suspend fun load(url: String, startPositionMs: Long = 0L, headers: Map<String, String> = emptyMap())

    /** 从就绪状态开始/恢复播放。 */
    fun play()

    /** 暂停（保持当前位置）。 */
    fun pause()

    /** 跳转到指定位置（毫秒）。 */
    fun seekTo(positionMs: Long)

    /** 停止并清空当前媒体（回到 Idle）。 */
    fun stop()

    /** 释放底层资源，之后不可再用。 */
    fun release()

    /** 设置音量 0.0~1.0。 */
    fun setVolume(volume: Float)

    /** 当前音量 0.0~1.0。 */
    fun getVolume(): Float
}

/** 平台播放器状态（与 [PlatformPlayer.state] 元素一致）。 */
sealed class PlatformPlaybackState {
    data object Idle : PlatformPlaybackState()
    /** 缓冲/加载中。 */
    data object Buffering : PlatformPlaybackState()
    /** 已就绪但未播放。 */
    data object Ready : PlatformPlaybackState()
    data object Playing : PlatformPlaybackState()
    data object Paused : PlatformPlaybackState()
    /** 自然播完。 */
    data object Ended : PlatformPlaybackState()
    data class Error(val message: String) : PlatformPlaybackState()
}

/**
 * 平台播放器工厂（expect/actual）。
 * 各平台在其 source set 提供 actual 实现，注入正确的播放器后端。
 */
expect fun createPlatformPlayer(context: PlatformContext): PlatformPlayer