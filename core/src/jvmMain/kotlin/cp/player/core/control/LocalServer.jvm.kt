package cp.player.core.control

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection

/**
 * [LocalServer] 的 JVM 实现（Android 与 Desktop 共用）——**流输出服务**。
 *
 * 基于 Ktor CIO（**不使用 `com.sun.net.httpserver`**，Android 运行时没有该包）。
 *
 * ### 路由
 * ```
 * GET /health                          健康检查
 * GET /stream?mediaId=…&token=…        把指定曲目（省略 mediaId 时为当前曲目）以 HTTP 流转发
 * ```
 *
 * ### 转发策略
 * **字节直通**：不转码、不重封装。上游返回什么就转发什么，
 * `Range` / `Content-Range` / `Accept-Ranges` / `Content-Length` 原样透传，
 * 因此接收端可以正常拖动进度与断点续传。
 *
 * 上游拉取用 [HttpURLConnection]：这条路径只需要「带自定义头 GET 一个 URL 并回灌字节」，
 * 用 JDK 自带能力即可，避免为服务端再引入一个 Ktor client 引擎。
 */
internal class KtorLocalServer(
    private val config: LocalServerConfig,
    private val resolveStreamUrl: suspend (mediaId: String?) -> StreamTarget?,
) : LocalServer {

    private val _status = MutableStateFlow(
        LocalServerStatus(
            running = false,
            bindAddress = config.bindAddress,
            streamPort = config.streamPort,
        )
    )
    override val status: StateFlow<LocalServerStatus> = _status.asStateFlow()

    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    override fun start() {
        if (engine != null) return
        try {
            val server = embeddedServer(
                CIO,
                port = config.streamPort,
                host = config.bindAddress,
            ) {
                routing {
                    get("/health") {
                        call.respondJson(
                            buildJsonObject {
                                put("code", JsonPrimitive(200))
                                put("status", JsonPrimitive("ok"))
                                put("streamPort", JsonPrimitive(config.streamPort))
                            }
                        )
                    }

                    get("/stream") {
                        if (!call.ensureAuthorized()) return@get
                        val mediaId = call.request.queryParameters["mediaId"]
                        val target = try {
                            resolveStreamUrl(mediaId)
                        } catch (e: Throwable) {
                            null
                        }
                        if (target == null) {
                            call.respondJson(
                                jsonError(if (mediaId.isNullOrBlank()) "当前没有可输出的曲目" else "无法解析曲目: $mediaId"),
                                HttpStatusCode.NotFound,
                            )
                            return@get
                        }
                        relay(call, target)
                    }
                }
            }
            server.start(wait = false)
            engine = server
            _status.value = _status.value.copy(running = true, error = null)
        } catch (e: Throwable) {
            engine = null
            _status.value = _status.value.copy(
                running = false,
                error = "流输出端口 ${config.streamPort} 启动失败：${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    override fun stop() {
        runCatching { engine?.stop(gracePeriodMillis = 300, timeoutMillis = 1_000) }
        engine = null
        _status.value = _status.value.copy(running = false)
    }

    // ============ 字节转发 ============

    private suspend fun relay(call: ApplicationCall, target: StreamTarget) {
        val rangeHeader = call.request.headers[HttpHeaders.Range]

        val conn = try {
            (java.net.URI(target.url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 0 // 长流不设读超时
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                rangeHeader?.takeIf { it.isNotBlank() }?.let { setRequestProperty(HttpHeaders.Range, it) }
                target.cookie?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
            }
        } catch (e: Throwable) {
            call.respondJson(jsonError("上游连接失败：${e.message}"), HttpStatusCode.BadGateway)
            return
        }

        val code = try {
            conn.responseCode
        } catch (e: Throwable) {
            runCatching { conn.disconnect() }
            call.respondJson(jsonError("上游无响应：${e.message}"), HttpStatusCode.BadGateway)
            return
        }

        if (code !in 200..299) {
            runCatching { conn.disconnect() }
            // 上游错误（如 403 防盗链、404 失效签名）如实透传，便于接收端与用户定位
            call.respondJson(jsonError("上游返回 HTTP $code"), HttpStatusCode.fromValue(code))
            return
        }

        // 关键头原样回传：缺 Content-Length / Accept-Ranges 会让接收端进度条失效
        conn.getHeaderField(HttpHeaders.ContentLength)
            ?.let { call.response.header(HttpHeaders.ContentLength, it) }
        conn.getHeaderField(HttpHeaders.ContentRange)
            ?.let { call.response.header(HttpHeaders.ContentRange, it) }
        conn.getHeaderField(HttpHeaders.AcceptRanges)
            ?.let { call.response.header(HttpHeaders.AcceptRanges, it) }

        val contentType = conn.contentType
            ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
            ?: ContentType.Application.OctetStream

        call.respondOutputStream(
            contentType = contentType,
            status = HttpStatusCode.fromValue(code),
        ) {
            try {
                conn.inputStream.use { it.copyTo(this) }
            } finally {
                runCatching { conn.disconnect() }
            }
        }
    }

    // ============ 工具 ============

    private fun jsonError(message: String, code: Int = 500) = buildJsonObject {
        put("code", JsonPrimitive(code))
        put("msg", JsonPrimitive(message))
    }

    private suspend fun ApplicationCall.respondJson(
        body: kotlinx.serialization.json.JsonObject,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) {
        respondText(body.toString(), ContentType.Application.Json, status)
    }

    /** 令牌校验。未配置令牌时放行；`/stream` 只接受 `?token=`。 */
    private suspend fun ApplicationCall.ensureAuthorized(): Boolean {
        if (!config.requiresToken) return true
        if (request.queryParameters["token"] == config.accessToken) return true
        respondJson(jsonError("unauthorized", code = 401), HttpStatusCode.Unauthorized)
        return false
    }

    private companion object {
        const val USER_AGENT = "CPPlayer/1.0"
    }
}

actual fun createLocalServer(
    config: LocalServerConfig,
    resolveStreamUrl: suspend (mediaId: String?) -> StreamTarget?,
): LocalServer = KtorLocalServer(config, resolveStreamUrl)

actual fun resolveAdvertisedHost(bindAddress: String): String {
    if (bindAddress != LocalServerConfig.BIND_ALL) return bindAddress
    // 绑定 0.0.0.0 时必须给出真实网卡地址，否则接收端会拿到不可连接的 0.0.0.0
    return runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull() ?: LocalServerConfig.BIND_LOOPBACK
}
