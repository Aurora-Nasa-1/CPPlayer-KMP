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
    /** 是否被收藏（视觉用；当前实现永远是 false，留给后续接入 likeSong）。 */
    val isFavorite: Boolean = false,
)