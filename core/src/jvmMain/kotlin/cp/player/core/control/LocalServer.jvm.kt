package cp.player.core.control

import cp.player.core.integration.ApiErrorCodes
import cp.player.core.integration.IntegrationGate
import cp.player.core.integration.IntegrationRouteMount
import cp.player.core.integration.KtorIntegrationRoutesHandle
import cp.player.core.integration.decideDataApiGate
import cp.player.core.integration.parseBearerToken
import cp.player.core.integration.respondIntegrationError
import cp.player.core.util.isTcpPortBindable
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
 * GET /health                          健康检查（始终可用，探活用）
 * GET /stream?mediaId=…&token=…        把指定曲目（省略 mediaId 时为当前曲目）以 HTTP 流转发
 * GET /api/v1/...                      数据面；路由定义在 integration 包，经构造参数挂入
 * ```
 *
 * ### 转发策略
 * **字节直通**：不转码、不重封装。上游返回什么就转发什么，
 * `Range` / `Content-Range` / `Accept-Ranges` / `Content-Length` 原样透传，
 * 因此接收端可以正常拖动进度与断点续传。
 *
 * 上游拉取用 [HttpURLConnection]：这条路径只需要「带自定义头 GET 一个 URL 并回灌字节」，
 * 用 JDK 自带能力即可，避免为服务端再引入一个 Ktor client 引擎。
 *
 * ### 开关为什么按请求现读
 * `exposeStream` / `exposeDataApi` 是路由级开关，改它们**不该**重启监听端口。
 * 因此判定一律走 [activeConfig]（实时读取器），而不是构造期捕获的 [config]。
 * [config] 只用于绑定期字段（端口 / 地址 / 令牌）。
 */
internal class KtorLocalServer(
    private val config: LocalServerConfig,
    private val resolveStreamUrl: suspend (mediaId: String?) -> StreamTarget?,
    private val integration: IntegrationRouteMount? = null,
    private val activeConfig: () -> LocalServerConfig = { config },
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
        // ⚠️ 必须先探测：Ktor CIO 的端口绑定发生在引擎内部 accept 协程里，
        // 这里的 try/catch 接不住 BindException —— 异常直达全局未捕获处理器，
        // Android 上直接杀进程（实锤过的概率崩溃）。占不上就降级为状态报错。
        // 语义与 CIO 的 bind 一致（reuseAddress 同为关），见 isTcpPortBindable。
        if (!isTcpPortBindable(config.bindAddress, config.streamPort)) {
            _status.value = _status.value.copy(
                running = false,
                error = "流输出端口 ${config.streamPort} 启动失败：端口被占用（可能有另一个 CPPlayer 实例正在运行）",
            )
            return
        }
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
                        // 媒体面开关：默认开，所以既有接收端行为不变；
                        // 关掉后返回的是**新**状态码/形态，老接收端从未见过，不构成破坏。
                        if (!activeConfig().exposeStream) {
                            call.respondIntegrationError(
                                HttpStatusCode.Forbidden,
                                ApiErrorCodes.FACE_DISABLED,
                                "媒体面未开放（local_server_expose_stream = false）",
                            )
                            return@get
                        }
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

                    // 数据面：只负责「挂上去 + 提供闸门」，路由定义留在 integration 包。
                    // 若传入的不是 JVM 实现（理论上不会），这里静默不挂载 ——
                    // 数据面本就是可选面，缺它不影响媒体面。
                    (integration as? KtorIntegrationRoutesHandle)?.mountInto(this) { call ->
                        call.ensureDataFaceAuthorized()
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

    // ============ 数据面闸门 ============

    /**
     * 数据面闸门：按**请求时刻**的配置判定，并在拒绝时自行响应。
     *
     * 拒绝走 [respondIntegrationError]，与数据面同一错误形态。
     * `/stream` 既有的 401 / 404 仍沿用旧形态 `{ "code", "msg" }`，
     * 避免动到已经对接好的接收端 —— 只有**新增**的拒绝路径用新形态。
     */
    private suspend fun ApplicationCall.ensureDataFaceAuthorized(): Boolean {
        val gate = decideDataApiGate(
            config = activeConfig(),
            bearerToken = parseBearerToken(request.headers[HttpHeaders.Authorization]),
            queryToken = request.queryParameters["token"],
        )
        return when (gate) {
            IntegrationGate.ALLOW -> true
            IntegrationGate.FACE_DISABLED -> {
                respondIntegrationError(
                    HttpStatusCode.Forbidden,
                    ApiErrorCodes.FACE_DISABLED,
                    "数据面未开放（local_server_expose_data_api = false）",
                )
                false
            }
            IntegrationGate.UNAUTHORIZED -> {
                respondIntegrationError(
                    HttpStatusCode.Unauthorized,
                    ApiErrorCodes.UNAUTHORIZED,
                    "令牌缺失或不匹配",
                )
                false
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

    /**
     * 媒体面令牌校验（`/stream` 只接受 `?token=`，不接受 `Authorization` 头）。
     *
     * 判定委托给 [isTokenSatisfied] —— 与数据面**共用同一条规则**：
     * - 配置了令牌 → 必须匹配；
     * - 未配置令牌 + 绑定回环 → 放行（外部根本连不上）；
     * - 未配置令牌 + 绑定非回环 → **拒绝**。
     *
     * 最后一条不是新策略，而是补一个洞：此前这里写的是 `if (!config.requiresToken) return true`，
     * 于是一旦「绑定 `0.0.0.0`」且「令牌为空」（用户手工清掉令牌键、或配置文件被改），
     * 同网段任何人都能无限拉流。**这条不可配置关闭** —— 局域网裸奔没有正当场景。
     *
     * 用构造期 [config] 而非 [activeConfig]：`bindAddress` 与 `accessToken` 都是
     * **绑定期字段**，改它们本来就会重建服务（见 `MusicBackend.applyOutputConfig`），
     * 所以构造期快照在这里永远是最新的。
     *
     * 错误形态沿用旧的 `{ "code": 401, "msg": … }`，**不是**数据面的 `{ "error": … }`：
     * 这是为兼容已对接的接收端刻意保留的（见 `docs/dev/INTEGRATION_API.md` §3.2 的例外说明）。
     */
    private suspend fun ApplicationCall.ensureAuthorized(): Boolean {
        if (isTokenSatisfied(config, request.queryParameters["token"])) return true
        respondJson(jsonError("unauthorized", code = 401), HttpStatusCode.Unauthorized)
        return false
    }

    private companion object {
        const val USER_AGENT = "CPPlayer/1.0"
    }
}

actual fun createLocalServer(
    config: LocalServerConfig,
    integration: IntegrationRouteMount?,
    activeConfig: () -> LocalServerConfig,
    resolveStreamUrl: suspend (mediaId: String?) -> StreamTarget?,
): LocalServer = KtorLocalServer(
    config = config,
    resolveStreamUrl = resolveStreamUrl,
    integration = integration,
    activeConfig = activeConfig,
)

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
