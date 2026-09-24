package cp.player.kmp.control

import kotlinx.coroutines.flow.StateFlow

/**
 * 流输出的解析结果。
 *
 * @property url 上游可播放地址（可能带时效签名）。
 * @property cookie 拉流所需的 Cookie；由服务端注入，**不下发给接收端**。
 */
data class StreamTarget(
    val url: String,
    val cookie: String? = null,
)

/**
 * 本地服务器运行状态。
 *
 * @property running 是否已成功监听。
 * @property bindAddress 实际绑定地址。
 * @property streamPort 流输出端口。
 * @property error 启动失败原因；null 表示正常。
 */
data class LocalServerStatus(
    val running: Boolean = false,
    val bindAddress: String = "",
    val streamPort: Int = 0,
    val error: String? = null,
) {
    /** 流输出地址；未运行时为空串。 */
    val streamUrl: String get() = if (running) "http://$bindAddress:$streamPort/stream" else ""
}

/**
 * 本地流输出服务器。
 *
 * 职责单一：把**当前曲目**以 HTTP 流形式对外提供（`GET /stream`）。
 * 不承担任何播控职责——播控由 CPPlayer 自身的 [cp.player.kmp.playback.PlaybackController]
 * 负责，并通过 [ExternalPusher] 推送给接收端。
 *
 * ### 为什么是「按曲目」而不是「连续流」
 * 接收端按曲目拉流（每个 track 一个 URL），自己负责队列推进。
 * 这样 CPPlayer 不需要转码/拼接容器，只需逐曲目转发字节即可，
 * 且接收端的进度条、切歌、缓存都能正常工作。
 */
interface LocalServer {

    /** 运行状态流。 */
    val status: StateFlow<LocalServerStatus>

    /** 按构造时的配置启动监听；已在运行则为空操作。 */
    fun start()

    /** 停止监听并释放端口。 */
    fun stop()
}

/**
 * 平台工厂：JVM（Android 与 Desktop 共用的 `jvmMain`）提供 Ktor CIO 实现。
 *
 * @param config 生效配置（端口 / 绑定地址 / 令牌）。
 * @param resolveStreamUrl 把 mediaId 解析为上游可播放地址；传 null 表示「当前曲目」。
 *        由 [cp.player.kmp.MusicBackend] 用 `UnifiedMusicSource` + 当前 Provider 的 cookie 实现。
 */
expect fun createLocalServer(
    config: LocalServerConfig,
    resolveStreamUrl: suspend (mediaId: String?) -> StreamTarget?,
): LocalServer

/**
 * 解析下发给接收端的**可达**主机地址。
 *
 * 绑定 `0.0.0.0` 时不能把 `0.0.0.0` 当目标地址下发——接收端连不上它。
 * 此时替换为本机第一个局域网 IPv4；取不到时回退到回环地址。
 */
expect fun resolveAdvertisedHost(bindAddress: String): String
