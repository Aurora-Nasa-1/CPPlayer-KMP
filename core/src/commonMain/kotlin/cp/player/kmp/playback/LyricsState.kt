package cp.player.kmp.playback

import cp.player.kmp.music.TrackSummary

/** 歌词状态（前端渲染用）。 */
sealed class LyricsState {
    /** 未请求或已清空。 */
    data object Idle : LyricsState()
    /** 正在拉取/解析。 */
    data object Loading : LyricsState()
    /** 获取成功；[lines] 已按 [SyncedLyricLine.time] 升序。 */
    data class Success(val lines: List<SyncedLyricLine>) : LyricsState()
    /** 该曲目没有可用歌词。 */
    data object NoLyrics : LyricsState()
    /** 获取失败；UI 可展示 [message]。 */
    data class Error(val message: String) : LyricsState()
}

/**
 * 队列中的曲目条目。
 *
 * 由 [PlaybackController] 在加入队列时一次性从 [TrackSummary] 派生，
 * 前端不再做 Song ↔ MediaItem 转换。
 */
data class QueueItem(
    val mediaId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val coverUrl: String?,
    val durationMs: Long,
)

internal fun TrackSummary.toQueueItem(): QueueItem = QueueItem(
    mediaId = id,
    title = name,
    artist = artist,
    album = album,
    coverUrl = coverUrl,
    durationMs = durationMs,
)