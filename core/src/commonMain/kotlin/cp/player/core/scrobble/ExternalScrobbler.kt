package cp.player.core.scrobble

import kotlinx.coroutines.flow.StateFlow

/**
 * 通用外部打卡服务接口（如 Last.fm、ListenBrainz、Libre.fm 等）。
 *
 * 采用插件化服务抽象，底层由 [ScrobbleHub] 统一调度分发，
 * 方便后续无缝扩展新的外部打卡平台。
 */
interface ExternalScrobbler {
    /** 服务唯一标识符（例如 "lastfm", "listenbrainz"）。 */
    val id: String

    /** 展示名称。 */
    val displayName: String

    /** 用户是否启用了该服务。 */
    val isEnabled: Boolean

    /** 是否已完成授权认证。 */
    val isAuthorized: Boolean

    /** 已登录用户名（未授权时为 null）。 */
    val username: String?

    /** 服务配置/状态流。 */
    val statusFlow: StateFlow<ScrobblerStatus>

    /**
     * 发送「正在播放」（Now Playing）广播。
     *
     * 在曲目刚开始稳定播放时触发。
     */
    suspend fun updateNowPlaying(track: ScrobbleTrack): ScrobbleResult

    /**
     * 提交一条已听打卡记录（Scrobble）。
     *
     * 在曲目播放满足服务规范（如播放超 50% 或满 4 分钟）时触发。
     */
    suspend fun scrobble(track: ScrobbleTrack): ScrobbleResult

    /** 标记喜爱（Love / Favorite）。 */
    suspend fun love(track: ScrobbleTrack): ScrobbleResult

    /** 取消标记喜爱（Unlove）。 */
    suspend fun unlove(track: ScrobbleTrack): ScrobbleResult
}

/**
 * 打卡平台状态快照。
 */
data class ScrobblerStatus(
    val isEnabled: Boolean,
    val isAuthorized: Boolean,
    val username: String? = null,
    val lastError: String? = null,
)
