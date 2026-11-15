package cp.player.core.playback.drm

import kotlinx.coroutines.flow.SharedFlow

/**
 * WebView EME DRM 桥的传输层抽象。
 *
 * 生产实现是 [WebViewDrmServer]（页面经 WebSocket 连入）；协议测试用假实现
 * 扮演「播放页」，在不启动浏览器的情况下驱动 [WebViewDrmPlatformPlayer]。
 */
interface WebViewDrmTransport {
    /** 播放页事件流（hello / state / progress）。 */
    val events: SharedFlow<WebViewPlayerEvent>

    /** 交给浏览器打开的播放页地址。 */
    val pageUrl: String

    /**
     * 把上游 playlist 换成本地归一化代理地址。
     *
     * 播放页必须经此入口装载：Apple 增强 HLS 使用 hls.js 不认识的
     * `METHOD=ISO-23001-7`，且相对 URI 需绝对化，直接交上游 URL 会静默失败。
     */
    fun playlistUrl(upstreamUrl: String): String

    /** 播放页当前是否已连上 WebSocket。 */
    val isPageConnected: Boolean

    /** 等待播放页完成 EME 探测并连入；超时返回 false。 */
    suspend fun awaitPageConnected(timeoutMs: Long): Boolean

    /** 向播放页下发一条命令。未连接时静默丢弃（play/pause/seek 属瞬态指令）。 */
    suspend fun send(command: WebViewPlayerCommand)

    /**
     * 注册 license 交换回调：播放页把 CDM challenge POST 进来，宿主转给
     * Provider 的 `exchangeLicense`，返回的 license 字节原样回给 CDM。
     * challenge 与 license 不落日志、不缓存。
     */
    fun setLicenseExchange(exchange: (suspend (ByteArray) -> ByteArray)?)
}

/** 宿主 → 播放页命令。 */
sealed interface WebViewPlayerCommand {
    data class Load(val url: String, val startPositionMs: Long) : WebViewPlayerCommand
    data object Play : WebViewPlayerCommand
    data object Pause : WebViewPlayerCommand
    data class Seek(val positionMs: Long) : WebViewPlayerCommand
    data class Volume(val value: Float) : WebViewPlayerCommand
    data class Rate(val value: Float) : WebViewPlayerCommand
    data object Stop : WebViewPlayerCommand
}

/** 播放页 → 宿主事件。 */
sealed interface WebViewPlayerEvent {

    /** 页面连入后首条消息：携带 EME 探测结果。 */
    data class Hello(val eme: Boolean, val keySystem: String?) : WebViewPlayerEvent

    /** 播放状态：buffering / ready / playing / paused / ended / error。 */
    data class State(val state: String, val message: String?) : WebViewPlayerEvent

    /** 进度回报（页面 timeupdate 节流上报）。 */
    data class Progress(val positionMs: Long, val durationMs: Long) : WebViewPlayerEvent
}
