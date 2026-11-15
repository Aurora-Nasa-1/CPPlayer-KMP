package cp.player.core.playback.drm

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receive
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.routing.Route
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * WebView EME DRM 桥的本地服务。
 *
 * 职责只有三件事：
 * 1. 把播放页（`/player`）与 hls.js（`/hls.min.js`）发给浏览器内核；
 * 2. 归一化 Apple 增强清单（`/manifest`，见 [HlsPlaylistNormalizer]）；
 * 3. 在播放页 CDM 与宿主 Provider 之间搬运 challenge / license（`/license`）
 *    与播放控制（`/ws`）。
 *
 * 所有路由要求一次性 token（构造期生成），播放页从自己的 URL 里带回它，
 * 阻止本机其他进程/页面探测 DRM 桥。只绑定 127.0.0.1。
 */
class WebViewDrmServer(
    private val fetchPlaylist: suspend (String) -> String? = WebViewDrmServer::fetchUpstreamPlaylist,
) : WebViewDrmTransport {

    private val token: String = UUID.randomUUID().toString().replace("-", "")

    private val _events = MutableSharedFlow<WebViewPlayerEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<WebViewPlayerEvent> = _events.asSharedFlow()

    private val _pageConnected = MutableStateFlow(false)
    val pageConnected: StateFlow<Boolean> = _pageConnected.asStateFlow()
    override val isPageConnected: Boolean
        get() = _pageConnected.value

    /**
     * 最近一次 hello。用 StateFlow 而不是 SharedFlow：播放页可能在宿主进入
     * `awaitPageConnected` 之前就连上并上报，事件型流会把这次 hello 丢掉，
     * 让 DRM 装载误判为「页面未就绪」。
     */
    private val helloState = MutableStateFlow<WebViewPlayerEvent.Hello?>(null)

    @Volatile
    private var pageSession: WebSocketSession? = null

    private val sendMutex = Mutex()

    @Volatile
    private var licenseExchange: (suspend (ByteArray) -> ByteArray)? = null

    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @Volatile
    var port: Int = -1
        private set

    override val pageUrl: String
        get() = "http://127.0.0.1:$port/player?token=$token"

    suspend fun start() {
        check(engine == null) { "WebViewDrmServer already started" }
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing { routes() }
        }.start(wait = false)
        engine = server
        port = server.engine.resolvedConnectors().first().port
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        engine = null
        port = -1
        pageSession = null
        licenseExchange = null
        _pageConnected.value = false
    }

    override suspend fun awaitPageConnected(timeoutMs: Long): Boolean =
        // 页面连上后还要等 EME 探测结果；探测失败（无 Widevine CDM）同样返回 false。
        withTimeoutOrNull(timeoutMs) { helloState.filterNotNull().first().eme } == true

    override suspend fun send(command: WebViewPlayerCommand) {
        val session = pageSession ?: return
        val payload = Json.encodeToString(JsonObject.serializer(), command.toJson())
        sendMutex.withLock {
            try {
                session.outgoing.send(Frame.Text(payload))
            } catch (_: Exception) {
                // 页面刚好断开：瞬态指令丢弃即可，状态机随后由重连/错误事件纠正。
            }
        }
    }

    override fun setLicenseExchange(exchange: (suspend (ByteArray) -> ByteArray)?) {
        licenseExchange = exchange
    }

    override fun playlistUrl(upstreamUrl: String): String {
        val encoded = URLEncoder.encode(upstreamUrl, StandardCharsets.UTF_8)
        return "http://127.0.0.1:$port/manifest?token=$token&url=$encoded"
    }

    fun proxyPlaylistUrl(upstreamUrl: String): String = playlistUrl(upstreamUrl)

    private fun Route.routes() {
        get("/player") {
            if (!requireToken(call)) return@get
            call.respondText(readResource(PLAYER_RESOURCE), ContentType.Text.Html)
        }
        get("/hls.min.js") {
            // 静态第三方库（hls.js，Apache-2.0），不含任何敏感数据，不做 token 校验。
            call.respondText(readResource(HLS_RESOURCE), ContentType.parse("application/javascript"))
        }
        get("/manifest") {
            if (!requireToken(call)) return@get
            val upstream = call.request.queryParameters["url"].orEmpty()
            if (!upstream.startsWith("https://") && !upstream.startsWith("http://")) {
                call.respondText("missing or invalid url", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                return@get
            }
            val text = fetchPlaylist(upstream)
            if (text == null) {
                call.respondText("upstream playlist unavailable", ContentType.Text.Plain, HttpStatusCode.BadGateway)
                return@get
            }
            val normalized = HlsPlaylistNormalizer.normalize(text, upstream) { playlistUrl(it) }
            call.respondText(normalized, ContentType.parse("application/vnd.apple.mpegurl"))
        }
        post("/license") {
            if (!requireToken(call)) return@post
            val exchange = licenseExchange
            if (exchange == null) {
                call.respondText("no active drm session", ContentType.Text.Plain, HttpStatusCode.ServiceUnavailable)
                return@post
            }
            val challenge = try {
                call.receive<ByteArray>()
            } catch (_: Exception) {
                call.respondText("unreadable challenge", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                return@post
            }
            val license = try {
                exchange(challenge)
            } catch (error: Exception) {
                // challenge 与 license 字节不进日志，只回错误语义。
                call.respondText(
                    error.message ?: "license exchange failed",
                    ContentType.Text.Plain,
                    HttpStatusCode.BadGateway,
                )
                return@post
            }
            call.respondBytes(license, ContentType.Application.OctetStream)
        }
        webSocket("/ws") {
            if (call.request.queryParameters["token"] != token) {
                close(CloseReason(4401, "unauthorized"))
                return@webSocket
            }
            pageSession = this
            _pageConnected.value = true
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val event = parseEvent(frame.readText()) ?: continue
                    if (event is WebViewPlayerEvent.Hello) helloState.value = event
                    _events.tryEmit(event)
                }
            } finally {
                if (pageSession === this) {
                    pageSession = null
                    _pageConnected.value = false
                    helloState.value = null
                }
            }
        }
    }

    private suspend fun requireToken(call: ApplicationCall): Boolean {
        if (call.request.queryParameters["token"] == token) return true
        call.respondText("unauthorized", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
        return false
    }

    private fun parseEvent(text: String): WebViewPlayerEvent? = try {
        val obj = Json.parseToJsonElement(text).jsonObject
        fun field(name: String): JsonPrimitive? = obj[name] as? JsonPrimitive
        fun string(name: String): String? =
            (obj[name] as? JsonNull)?.let { null } ?: (obj[name] as? JsonPrimitive)?.content
        when (string("type")) {
            "hello" -> WebViewPlayerEvent.Hello(
                eme = field("eme")?.booleanOrNull ?: false,
                keySystem = string("keySystem"),
            )
            "state" -> WebViewPlayerEvent.State(
                state = string("state") ?: return null,
                message = string("message"),
            )
            "progress" -> WebViewPlayerEvent.Progress(
                positionMs = field("positionMs")?.longOrNull ?: 0L,
                durationMs = field("durationMs")?.longOrNull ?: 0L,
            )
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun readResource(name: String): String =
        WebViewDrmServer::class.java.classLoader.getResourceAsStream(name)
            ?.use { stream -> stream.readBytes().toString(StandardCharsets.UTF_8) }
            ?: throw IOException("resource missing: $name")

    companion object {

        private const val PLAYER_RESOURCE = "cp/player/core/playback/drm/player.html"
        private const val HLS_RESOURCE = "cp/player/core/playback/drm/hls.min.js"

        private fun WebViewPlayerCommand.toJson(): JsonObject = buildJsonObject {
            when (this@toJson) {
                is WebViewPlayerCommand.Load -> {
                    put("type", "load")
                    put("url", url)
                    put("startPositionMs", startPositionMs)
                }
                WebViewPlayerCommand.Play -> put("type", "play")
                WebViewPlayerCommand.Pause -> put("type", "pause")
                is WebViewPlayerCommand.Seek -> {
                    put("type", "seek")
                    put("positionMs", positionMs)
                }
                is WebViewPlayerCommand.Volume -> {
                    put("type", "volume")
                    put("value", value.toDouble())
                }
                is WebViewPlayerCommand.Rate -> {
                    put("type", "rate")
                    put("value", value.toDouble())
                }
                WebViewPlayerCommand.Stop -> put("type", "stop")
            }
        }

        /** 默认上游清单拉取：CDN 地址无需 Apple API 凭据，用 JDK 能力即可。 */
        internal fun fetchUpstreamPlaylist(url: String): String? = try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36",
            )
            if (connection.responseCode !in 200..299) null else {
                connection.inputStream.use { stream -> stream.readBytes().toString(StandardCharsets.UTF_8) }
            }
        } catch (_: Exception) {
            null
        }
    }
}
