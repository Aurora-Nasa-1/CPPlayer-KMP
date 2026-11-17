package cp.player.app.recent

import cp.player.core.music.CPMediaId
import cp.player.core.music.TrackSummary

/**
 * 「最近播放」最多保留的曲目数。
 *
 * 去重键是**完整 mediaId**（含音源前缀），所以同一首歌在不同音源下会各留一条 ——
 * 这正是「区分音源」的前提，不能退回按裸 id 去重。
 */
internal const val RECENT_TRACKS_LIMIT = 300

/** 旧数据可能只存了裸 id；这类条目只能按当前音源解释。 */
internal fun recentTrackProviderId(track: TrackSummary, fallbackProviderId: String): String =
    CPMediaId.parseOrNull(track.id)?.providerId ?: fallbackProviderId

internal fun recentMediaId(track: TrackSummary, fallbackProviderId: String): String =
    CPMediaId.toCanonicalMediaId(track.id, providerId = fallbackProviderId)

/**
 * 最近播放里的曲目是否属于当前可播范围。
 *
 * 本地文件不依赖云音源会话，切源后仍可播放；其它音源的在线曲目只能置灰，
 * 避免把对端音源解析不了的 mediaId 塞进播放队列。
 */
internal fun isRecentTrackPlayable(track: TrackSummary, activeProviderId: String): Boolean {
    val providerId = recentTrackProviderId(track, activeProviderId)
    return providerId == activeProviderId || providerId == "local"
}

internal fun recentPlayableTracks(
    tracks: List<TrackSummary>,
    activeProviderId: String,
): List<TrackSummary> = tracks.filter { isRecentTrackPlayable(it, activeProviderId) }

/**
 * 把一条新记录前移并去重。
 *
 * 去重键必须是完整 mediaId：同一首数字 id 的歌在网易云与 QQ 音乐下是两条记录，
 * 只有完全相同的 mediaId 才算「同一来源的同一首」。
 */
internal fun mergeRecentTrack(
    current: List<TrackSummary>,
    track: TrackSummary,
    limit: Int = RECENT_TRACKS_LIMIT,
): List<TrackSummary> =
    (listOf(track) + current.filter { it.id != track.id }).take(limit.coerceAtLeast(1))

/** 点击最近列表某一首后，实际应排入队列的曲目与起始下标。 */
internal data class RecentPlaybackPlan(
    val tracks: List<TrackSummary>,
    val startIndex: Int,
)

/**
 * 只把当前音源（以及本地文件）的曲目排进队列。
 *
 * 返回 null 表示被点击的曲目不属于当前音源，调用方应当只提示、不发起播放 ——
 * 绝不能「先按原列表播，再指望播放器自己跳过」。
 */
internal fun recentPlaybackPlan(
    tracks: List<TrackSummary>,
    activeProviderId: String,
    clickedTrackId: String,
): RecentPlaybackPlan? {
    val playable = recentPlayableTracks(tracks, activeProviderId)
    val startIndex = playable.indexOfFirst { it.id == clickedTrackId }
    if (startIndex < 0) return null
    return RecentPlaybackPlan(tracks = playable, startIndex = startIndex)
}
