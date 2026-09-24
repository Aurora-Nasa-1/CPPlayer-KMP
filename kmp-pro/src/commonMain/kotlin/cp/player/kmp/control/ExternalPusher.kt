package cp.player.kmp.control

/**
 * 推送给接收端的曲目描述。
 *
 * @property url 接收端应拉取的流地址（通常是本机流输出服务的 `/stream?mediaId=…`）。
 * @property title 曲名。
 * @property artist 艺术家。
 * @property album 专辑。
 * @property artworkUrl 封面地址（接收端可能自行拉取；注意跨机访问时需可达）。
 * @property durationMs 时长（毫秒）。
 */
data class PushTrack(
    val url: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long? = null,
)

/**
 * 推送结果。
 *
 * @property httpCode 接收端返回的 HTTP 状态码；网络异常时为 null。
 */
sealed interface PushResult {

    /** 推送成功。[body] 为接收端原始响应体，便于排障。 */
    data class Ok(val body: String) : PushResult

    /** 推送失败。 */
    data class Failed(val message: String, val httpCode: Int? = null) : PushResult

    val isSuccess: Boolean get() = this is Ok
}

/**
 * 接收端推送客户端。
 *
 * 对齐接收端的控制文档（默认 `http://127.0.0.1:8420`）：
 * ```
 * GET  /api/health
 * GET  /api/v1/status
 * POST /api/v1/player/{play|pause|stop|next|previous}
 * POST /api/v1/play-url          # 单曲推送（最常用）
 * POST /api/v1/queue             # 整队列替换
 * POST /api/v1/queue/items       # 追加单曲
 * DELETE /api/v1/queue           # 清空
 * ```
 *
 * 所有方法都不抛异常：网络失败一律收敛为 [PushResult.Failed]，
 * 避免推送失败影响本机播放体验。
 */
interface ExternalPusher {

    /** `GET /api/health` —— 探测接收端是否在线。 */
    suspend fun health(): PushResult

    /** `POST /api/v1/play-url` —— 替换接收端队列为单曲并立即播放。 */
    suspend fun playUrl(track: PushTrack): PushResult

    /** `POST /api/v1/queue` —— 整队列替换。 */
    suspend fun pushQueue(
        tracks: List<PushTrack>,
        autoplay: Boolean = true,
        startIndex: Int = 0,
    ): PushResult

    /** `POST /api/v1/queue/items` —— 追加单曲。 */
    suspend fun enqueue(track: PushTrack, playNow: Boolean = false): PushResult

    /** `DELETE /api/v1/queue` —— 清空接收端队列。 */
    suspend fun clearQueue(): PushResult

    /**
     * `POST /api/v1/player/{action}` —— 传输控制。
     *
     * @param action `play` / `pause` / `stop` / `next` / `previous`
     */
    suspend fun transport(action: String): PushResult

    /** `GET /api/v1/status` —— 接收端当前状态。 */
    suspend fun status(): PushResult
}

/** 平台工厂：JVM 提供 `HttpURLConnection` 实现。 */
expect fun createExternalPusher(configProvider: () -> LocalServerConfig): ExternalPusher
