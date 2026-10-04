package cp.player.core.sync

import cp.player.core.util.currentTimeMillis
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 局域网同步的传输层（Android / Desktop 共用 `jvmMain`）。
 *
 * ### 为什么是 public 而不是 internal
 * 调用方是应用层的 `AppModel`（不在 core 模块里）—— 与发现层不同：
 * 发现层藏在 expect/actual 工厂后面，可以 internal；传输层没有那样的间接层，
 * 强行 internal 只会让 app 层编译不过。public 的同时把安全边界写在类 KDoc 里，
 * 让读代码的人第一眼就看到「这是未认证的」。
 *
 * ### 安全模型（v1，如实说明）
 * **未认证**。开关默认关；开启即意味着「同一局域网内的任何设备都能读写本机的听歌记录」。
 * 之所以这样取舍：用户要的是「无感」—— 每台设备都要输一遍配对码的方案做出来
 * 就是「入口不明确」的另一种形态。防线落在三处：
 * 1. 开关默认关、进入设备页即知即改；
 * 2. 能被读写的**只有听歌记录**（无账号、无凭据、无歌单收藏）；
 * 3. 入站记录过 [SyncMerge.sanitize] 严格校验，畸形/越界数据直接丢弃。
 * 配对（方案 §3.3）落地后应替换成令牌鉴权 —— 那是下一步，不是现在。
 */
object SyncTransport {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ============ 服务端 ============

    /**
     * 嵌入式同步服务。
     *
     * 独立于 `LocalServer`：端口、生命周期、开关都是自己的。
     * 绑定 `0.0.0.0` —— 局域网同步的本意就是让别的设备连进来；
     * 关闭开关即整个服务停掉、端口释放。
     */
    class Server(
        private val snapshotProvider: () -> SyncSnapshot,
        private val onIncoming: (SyncSnapshot) -> Int,
        private val onStateChanged: (Boolean, String?) -> Unit,
        /**
         * 收到转移请求时的处理方（suspend —— 目标端要**等真的出声了**才应答）。
         * 默认实现回答「不支持」，让旧调用方不传也能跑。
         */
        private val onHandoff: suspend (HandoffRequest) -> HandoffResult = {
            HandoffResult(accepted = false, message = "对端不支持转移播放")
        },
    ) {
        private var server: EmbeddedServer<*, *>? = null

        fun start() {
            if (server != null) return
            try {
                val s = embeddedServer(CIO, port = SYNC_HTTP_PORT, host = "0.0.0.0") {
                    routing {
                        get(SYNC_ROUTE_RECORDS) {
                            call.respondJson(json.encodeToString(snapshotProvider()))
                        }
                        post(SYNC_ROUTE_RECORDS) {
                            val body = call.receiveText()
                            val snapshot = runCatching {
                                json.decodeFromString(SyncSnapshot.serializer(), body)
                            }.getOrNull()
                            if (snapshot == null) {
                                call.respondJson(
                                    json.encodeToString(SyncAck(accepted = 0, error = "无法解析")),
                                    HttpStatusCode.BadRequest,
                                )
                                return@post
                            }
                            if (snapshot.deviceId.isBlank()) {
                                call.respondJson(
                                    json.encodeToString(SyncAck(accepted = 0, error = "缺少 deviceId")),
                                    HttpStatusCode.BadRequest,
                                )
                                return@post
                            }
                            val accepted = onIncoming(snapshot)
                            call.respondJson(json.encodeToString(SyncAck(accepted = accepted)))
                        }
                        post(SYNC_ROUTE_HANDOFF) {
                            val body = call.receiveText()
                            val raw = runCatching {
                                json.decodeFromString(HandoffRequest.serializer(), body)
                            }.getOrNull()
                            val req = raw?.let { HandoffGuard.sanitized(it) }
                            if (req == null) {
                                call.respondJson(
                                    json.encodeToString(HandoffResult(accepted = false, message = "请求无效")),
                                    HttpStatusCode.BadRequest,
                                )
                                return@post
                            }
                            val result = onHandoff(req)
                            call.respondJson(json.encodeToString(result))
                        }
                    }
                }
                s.start(wait = false)
                server = s
                onStateChanged(true, null)
            } catch (t: Throwable) {
                server = null
                onStateChanged(false, "同步服务启动失败：${t.message ?: t.javaClass.simpleName}")
            }
        }

        fun stop() {
            runCatching { server?.stop(gracePeriodMillis = 200, timeoutMillis = 500) }
            server = null
            onStateChanged(false, null)
        }
    }

    // ============ 客户端 ============

    /**
     * 拉取对端记录。
     *
     * 用 `HttpURLConnection` 而不是 Ktor client：只需「发一个请求读响应」，
     * 与 `ExternalPusher.jvm.kt` 同一条既有路径，不为它引入第二个 HTTP 客户端。
     */
    fun pull(address: String): SyncSnapshot? {
        val body = request("GET", "http://$address:$SYNC_HTTP_PORT$SYNC_ROUTE_RECORDS", null) ?: return null
        return runCatching { json.decodeFromString(SyncSnapshot.serializer(), body) }.getOrNull()
    }

    /**
     * 把**合并后的并集**推给对端。
     *
     * 推并集（而不是只推自己的新增）是双向同步的关键：对端收到后按 id 去重，
     * 缺的那部分正好是它没有的 —— 一来一回，两边都齐了。
     */
    fun push(address: String, snapshot: SyncSnapshot): Int {
        val body = request(
            "POST",
            "http://$address:$SYNC_HTTP_PORT$SYNC_ROUTE_RECORDS",
            json.encodeToString(snapshot),
        ) ?: return 0
        return runCatching {
            json.decodeFromString(SyncAck.serializer(), body).accepted
        }.getOrDefault(0)
    }

    /**
     * 发起无缝转移。
     *
     * ⚠️ **调用方在收到 `accepted=true` 之前绝不能停本机播放** —— 目标端是
     * 「真的出声了」才应答的，所以这个调用的返回就是 READY 信号本身。
     * 读超时给了 10s：目标端要等播放真正启动（起流 + 解码首帧），比普通请求慢。
     * 返回 null = 设备无响应/超时 —— 调用方应保持本机播放不动并如实提示。
     */
    fun handoff(address: String, request: HandoffRequest): HandoffResult? {
        val body = request(
            "POST",
            "http://$address:$SYNC_HTTP_PORT$SYNC_ROUTE_HANDOFF",
            json.encodeToString(request),
            readTimeoutMs = 10_000,
        ) ?: return null
        return runCatching { json.decodeFromString(HandoffResult.serializer(), body) }.getOrNull()
    }

    private fun request(method: String, url: String, body: String?, readTimeoutMs: Int = 8_000): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3_000
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            setRequestProperty("Accept", "application/json")
        }
        if (body != null) {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val text = runCatching {
            (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use(BufferedReader::readText)
        }.getOrNull().orEmpty()
        runCatching { conn.disconnect() }
        if (code !in 200..299) return@runCatching null
        text
    }.getOrNull()
}

/** 推送的应答。 */
@kotlinx.serialization.Serializable
data class SyncAck(val accepted: Int = 0, val error: String? = null)

/** 两个路由处理里的公共小工具：统一 JSON 响应。 */
private suspend fun io.ktor.server.application.ApplicationCall.respondJson(text: String) {
    respondText(text, io.ktor.http.ContentType.Application.Json)
}

private suspend fun io.ktor.server.application.ApplicationCall.respondJson(
    text: String,
    status: HttpStatusCode,
) {
    respondText(text, io.ktor.http.ContentType.Application.Json, status)
}

