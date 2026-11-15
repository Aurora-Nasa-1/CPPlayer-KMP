package cp.player.core.playback

import cp.player.core.playback.drm.HlsPlaylistNormalizer
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Android 侧的 DRM 清单归一化代理。
 *
 * Media3 1.11 的 HLS 解析器与 hls.js 一样只认 `SAMPLE-AES` 系列，Apple 增强
 * 流的 `METHOD=ISO-23001-7` 会被判成不支持加密方式；此外媒体清单里的相对
 * 分片 URI 需要相对真实 CDN 解析，而不是相对代理地址。因此 `loadDrm` 不能
 * 把 Provider 返回的 URL 直接交给 ExoPlayer，必须先经这里改写。
 *
 * 与桌面桥一致：只绑 `127.0.0.1`，一次性 token 校验，只搬运清单文本，
 * 不接触分片、challenge 或 license 字节。
 */
internal class DrmPlaylistProxy {

    private val token: String = UUID.randomUUID().toString().replace("-", "")
    private var server: EmbeddedServer<*, *>? = null

    @Volatile
    var port: Int = -1
        private set

    private val errorRef = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /** 最近一次清单拉取失败原因；清单请求与播放协程不在同一线程。 */
    val lastError: String?
        get() = errorRef.get()

    suspend fun start() {
        check(server == null) { "DrmPlaylistProxy already started" }
        errorRef.set(null)
        val started = embeddedServer(CIO, port = 0, host = BIND_ADDRESS) {
            routing {
                get("/manifest") {
                    if (call.request.queryParameters["token"] != token) {
                        call.respondText("unauthorized", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
                        return@get
                    }
                    val upstream = call.request.queryParameters["url"].orEmpty()
                    if (!upstream.startsWith("https://") && !upstream.startsWith("http://")) {
                        call.respondText("missing url", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                        return@get
                    }
                    val text = fetch(upstream)
                    if (text == null) {
                        errorRef.set("播放清单拉取失败")
                        call.respondText(
                            "upstream playlist unavailable",
                            ContentType.Text.Plain,
                            HttpStatusCode.BadGateway,
                        )
                        return@get
                    }
                    val normalized = HlsPlaylistNormalizer.normalize(text, upstream) { playlistUrl(it) }
                    call.respondText(normalized, ContentType.parse("application/vnd.apple.mpegurl"))
                }
            }
        }.start(wait = false)
        server = started
        port = started.engine.resolvedConnectors().first().port
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        server = null
        port = -1
    }

    /** playlist 地址必须带回 token；分片 URL 不走代理。 */
    fun playlistUrl(upstream: String): String {
        check(port > 0) { "DRM playlist proxy is not running" }
        val encoded = URLEncoder.encode(upstream, StandardCharsets.UTF_8)
        return "http://$BIND_ADDRESS:$port/manifest?token=$token&url=$encoded"
    }

    private fun fetch(url: String): String? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0 Mobile Safari/537.36",
        )
        if (connection.responseCode !in 200..299) {
            null
        } else {
            connection.inputStream.use { stream ->
                stream.readBytes().toString(StandardCharsets.UTF_8)
            }
        }
    } catch (_: IOException) {
        null
    }

    private companion object {
        const val BIND_ADDRESS = "127.0.0.1"
    }
}
