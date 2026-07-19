package cp.player.kmp.playback

import cp.player.kmp.music.TrackSummary

/**
 * 前端播放界面的完整渲染状态（不可变）。
 *
 * 由 [PlaybackController] 经 [PlaybackController.state] 暴露的 StateFlow 推送；
 * 前端**只** collect 此状态渲染，**不**做任何 Song ↔ MediaItem 转换、URL 解析、
 * 歌词格式转换或引擎类型判断。
 */
data class PlaybackUiState(
    val currentTrack: TrackSummary? = null,
    val currentIndex: Int = -1,
    val queue: List<QueueItem> = emptyList(),
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val lyrics: LyricsState = LyricsState.Idle,
    val activeLyricIndex: Int = -1,
    val formatInfo: AudioFormatInfo? = null,
    val error: String? = null,
    val sourceId: String? = null,
    /** 是否被收藏（接入 likeSong；未登录时恒为 false）。 */
    val isFavorite: Boolean = false,
    /** 当前在线播放音质等级（standard/exhigh/lossless/hires…）。 */
    val qualityLevel: String = "exhigh",
    /** 睡眠定时剩余毫秒；null 表示未设置。 */
    val sleepTimerRemainingMs: Long? = null,
    /** 睡眠定时模式为"播完当前曲目后暂停"。 */
    val sleepAfterTrack: Boolean = false,
)