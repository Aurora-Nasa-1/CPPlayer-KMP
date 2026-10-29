package cp.player.core.scrobble

import kotlinx.serialization.Serializable

/**
 * 听歌打卡曲目元数据。
 */
@Serializable
data class ScrobbleTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSec: Int = 0,
    /**
     * 该曲目开始播放的 UTC 秒级时间戳（Unix epoch seconds）。
     *
     * 官方打卡规范要求必须记录真实起播时刻，即使离线暂存后再提交，
     * 也要以此时间戳为准。
     */
    val timestampSec: Long = 0L,
    val mediaId: String? = null,
    val isLocal: Boolean = false,
)

/**
 * 打卡或状态更新结果。
 */
data class ScrobbleResult(
    val success: Boolean,
    val ignored: Boolean = false,
    val message: String? = null,
    val errorCode: Int? = null,
) {
    companion object {
        val Success = ScrobbleResult(success = true)
        val Ignored = ScrobbleResult(success = true, ignored = true)
        fun failure(message: String?, errorCode: Int? = null) =
            ScrobbleResult(success = false, message = message, errorCode = errorCode)
    }
}

/**
 * 离线持久化待办打卡条目。
 */
@Serializable
data class PendingScrobble(
    val targetServiceId: String,
    val track: ScrobbleTrack,
    val addedAtMs: Long,
    val retryCount: Int = 0,
)

/**
 * 外部打卡中心全局状态（UI 消费）。
 */
data class ScrobbleHubState(
    val isEnabled: Boolean = false,
    val lastFmConnected: Boolean = false,
    val lastFmUsername: String? = null,
    val todayScrobbledCount: Int = 0,
    val pendingQueueSize: Int = 0,
    val lastScrobbledTitle: String? = null,
    val lastScrobbledArtist: String? = null,
    val lastScrobbledTimeMs: Long = 0L,
    val lastSyncError: String? = null,
)
