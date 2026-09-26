package cp.player.core.integration

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.io.Writer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 数据面路由的 JVM 实现（Android 与 Desktop 共用 `jvmMain`）。
 *
 * ### 路由
 * ```
 * GET  /api/v1/meta                版本协商 + 能力清单
 * GET  /api/v1/providers           已加载音源
 * POST /api/v1/search              搜索
 * GET  /api/v1/tracks/{mediaId}    单曲详情（mediaId 需 URL 编码）
 * POST /api/v1/tracks/batch        批量曲目详情
 * GET  /api/v1/playback            播放状态（只读）
 * POST /api/v1/playback/{action}   播控（写；需 allowRemoteControl）
 * GET  /api/v1/events              播放状态事件流（SSE）
 * ```
 *
 * ### 职责边界
 * 这一层**只做** HTTP 解析 / 鉴权 / JSON 编解码 / 状态码；
 * 所有业务判断都在 [IntegrationService] 里。
 *
 * ### 为什么是独立类而不是塞进 `KtorLocalServer`
 * `KtorLocalServer` 的职责是「字节流转发」。把 JSON 路由塞进去会让它同时承担两件事，
 * 而这两件事的变更节奏完全不同（转发策略 vs 对外契约）。
 * 因此它只多一个「挂载点」，路由定义留在本文件。
 *
 * ### 为什么开关不能是构造期参数
 * `exposeDataApi` 是**路由级**开关，改了不该重启监听端口。
 * 所以闸门由宿主按请求实时读取配置后判定（见 [mountInto] 的 `gate`）。
 */
internal class KtorIntegrationRoutes(
    private val service: IntegrationService,
) : KtorIntegrationRoutesHandle {

    /**
     * 把数据面路由挂进宿主引擎的 `routing { }`。
     *
     * @param gate 由宿主提供的闸门。它持有「实时配置读取器」并按
     *   [decideDataApiGate] 判定；返回 `false` 表示**已经自行响应**过，
     *   路由处理器必须立即返回，不能再写响应体。
     */
    override fun mountInto(routing: Routing, gate: suspend (ApplicationCall) -> Boolean) {
        routing.get(IntegrationRoutes.META) {
            if (!gate(call)) return@get
            call.respondIntegrationJson(IntegrationJson.encodeToString(MetaDto.serializer(), service.meta()))
        }

        routing.get(IntegrationRoutes.PROVIDERS) {
            if (!gate(call)) return@get
            call.respondIntegrationJson(
                IntegrationJson.encodeToString(ProvidersDto.serializer(), service.providers()),
            )
        }

        routing.post(IntegrationRoutes.SEARCH) {
            if (!gate(call)) return@post
            val request = call.receiveJson(SearchRequestDto.serializer()) ?: return@post
            call.respondOutcome(service.search(request), SearchResponseDto.serializer())
        }

        routing.get(IntegrationRoutes.TRACK) {
            if (!gate(call)) return@get
            // Ktor 已对路径参数做过 percent-decoding，这里拿到的是原始 mediaId
            val mediaId = call.parameters["mediaId"].orEmpty()
            call.respondOutcome(service.track(mediaId), TrackDto.serializer())
        }

        routing.post(IntegrationRoutes.TRACK_BATCH) {
            if (!gate(call)) return@post
            val request = call.receiveJson(TrackBatchRequestDto.serializer()) ?: return@post
            call.respondOutcome(service.tracks(request.mediaIds), ListSerializer(TrackBatchItemDto.serializer()))
        }

        routing.get(IntegrationRoutes.PLAYBACK) {
            if (!gate(call)) return@get
            call.respondIntegrationJson(
                IntegrationJson.encodeToString(PlaybackDto.serializer(), service.playback()),
            )
        }

        routing.post(IntegrationRoutes.PLAYBACK_ACTION) {
            if (!gate(call)) return@post
            // Ktor 已对路径参数做过 percent-decoding
            val action = call.parameters["action"].orEmpty()
            call.respondOutcome(service.playbackAction(action), PlaybackDto.serializer())
        }

        routing.get(IntegrationRoutes.EVENTS) {
            if (!gate(call)) return@get
            call.streamPlaybackEvents(service)
        }
    }
}

/**
 * 宿主（`cp.player.core.control`）可见的内部视图。
 *
 * 宿主只需要知道「挂载」这一个动作，不需要知道 DTO、闸门与状态码怎么定；
 * 反过来 `commonMain` 也不能看到 [Routing]，因为 `ktor-server-*` 只在 `jvmMain` 声明。
 * 这个内部接口就是那道缝。
 */
internal interface KtorIntegrationRoutesHandle : IntegrationRouteMount {
    fun mountInto(routing: Routing, gate: suspend (ApplicationCall) -> Boolean)
}

/**
 * 数据面的 JSON 配置。
 *
 * - 显式打开 `encodeDefaults`：kotlinx 默认会**静默丢弃**「值等于默认值」的字段，
 *   集成方会以为字段不存在。契约里字段必须稳定出现。
 * - `ignoreUnknownKeys`：请求方向要**宽容**。v2 的客户端给 v1 服务端多发一个字段
 *   不该报 400 —— 这是 v1 内「只做加法」得以成立的另一半（服务端只做加法的同时，
 *   也要容忍客户端先跑）。
 */
internal val IntegrationJson: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
}

/** 按统一错误形态 `{ "error": { "code", "message" } }` 响应。 */
internal suspend fun ApplicationCall.respondIntegrationError(
    status: HttpStatusCode,
    code: String,
    message: String,
) {
    respondText(
        text = IntegrationJson.encodeToString(
            ApiErrorDto.serializer(),
            ApiErrorDto(ApiErrorBody(code = code, message = message)),
        ),
        contentType = ContentType.Application.Json,
        status = status,
    )
}

/** 以 JSON 形态响应成功结果。 */
internal suspend fun ApplicationCall.respondIntegrationJson(body: String) {
    respondText(body, ContentType.Application.Json, HttpStatusCode.OK)
}

/** 把用例层结果映射成 HTTP 响应。状态码与错误码全部来自 [FailureKind]。 */
internal suspend fun <T> ApplicationCall.respondOutcome(
    outcome: IntegrationResult<T>,
    serializer: KSerializer<T>,
) {
    when (outcome) {
        is IntegrationResult.Ok ->
            respondIntegrationJson(IntegrationJson.encodeToString(serializer, outcome.value))

        is IntegrationResult.Failure -> respondIntegrationError(
            status = HttpStatusCode.fromValue(outcome.kind.httpStatus),
            code = outcome.kind.errorCode,
            message = outcome.message,
        )
    }
}

/**
 * 读请求体并解码；失败时自行响应 `400` 并返回 `null`。
 *
 * 用 `receiveText()` + 手动解码，而不是 `call.receive<T>()`：后者在
 * `Content-Type` 不匹配时抛 `CannotTransformContentToTypeException`，
 * 于是「集成方忘了写 `Content-Type: application/json`」会变成 500 ——
 * 那明明是客户端的参数错误，必须是 400。
 */
internal suspend fun <T> ApplicationCall.receiveJson(serializer: KSerializer<T>): T? {
    val body = runCatching { receiveText() }.getOrNull().orEmpty()
    if (body.isBlank()) {
        respondIntegrationError(HttpStatusCode.BadRequest, ApiErrorCodes.BAD_REQUEST, "请求体不能为空")
        return null
    }
    return runCatching { IntegrationJson.decodeFromString(serializer, body) }
        .getOrElse { error ->
            respondIntegrationError(
                HttpStatusCode.BadRequest,
                ApiErrorCodes.BAD_REQUEST,
                "请求体不是合法 JSON：${error.message}",
            )
            null
        }
}

// ============ 事件流（SSE） ============

/**
 * 心跳间隔。
 *
 * 要小于常见反向代理的空闲超时（通常 30–60 秒），否则代理会在两次心跳之间把连接掐掉，
 * 而两端都以为连接还在。
 */
private const val SSE_HEARTBEAT_MS = 15_000L

/** 事件流里可能出现的两种消息。 */
private sealed interface SseMessage {
    /** 一次状态变化。 */
    data class Playback(val dto: PlaybackDto) : SseMessage

    /** 心跳：只推动 TCP 写出，不含数据。 */
    data object Ping : SseMessage
}

/**
 * 播放状态事件流（Server-Sent Events）。
 *
 * ### 线上形状
 * ```
 * event: playback
 * data: {"isPlaying":true,...}
 *
 * : ping
 *
 * ```
 * 心跳用**注释行**（`:` 开头）而不是发一个空事件：`EventSource` 会忽略注释行，
 * 但它会推动 TCP 写出，于是**半开连接**（对端已消失但没发 FIN）会在写失败时暴露。
 * 没有心跳的话这种连接会一直挂着，服务端永远不知道要回收。
 *
 * ### 三个必须处理的点
 * 1. **先发当前快照**：否则用户在点播放之前集成方什么都收不到，现象是「连上了但一直
 *    没数据」，很难判断是坏了还是在等。这里靠 `StateFlow` 订阅时**立刻重放当前值**
 *    天然拿到快照，快照与后续变化走**同一条流**。
 * 2. **不要「手动写快照 + `drop(1)` 跳过重放」**：那是最直觉的写法，但它有一个窗口 ——
 *    `states.value` 是在订阅**之前**读的，而 `merge` 是**异步**订阅的；
 *    窗口内发生的状态变化会被 `drop(1)` 连着重放一起丢掉。集成方会一直停在旧状态上，
 *    直到下一次变化才「自己好起来」—— 症状是随机丢事件，本地几乎复现不出来。
 *    （2026-09-25 由 `事件流先发快照再推变化` 抓到：首帧到了、心跳到了、中间那次变化没了。）
 * 3. **断开时必须安静收尾**：客户端关掉页面会让写入抛异常。不捕获会记成服务端错误
 *    并污染日志，而「用户关了页面」根本不是错误。这里刻意 `catch (Throwable)` ——
 *    任何写失败都只意味着**这一个订阅者**没了，不该影响其它连接，更不该拖垮服务。
 *    ⚠️ 代价是**真 bug 也会被吞掉**：调试事件流时先把这里临时换成 `catch (e) { e.printStackTrace() }`。
 *
 * ### 不下发的东西
 * 只有 [PlaybackDto]。队列内容、cookie、上游签名 URL 一律不给 —— 与其余端点同一条纪律。
 */
private suspend fun ApplicationCall.streamPlaybackEvents(service: IntegrationService) {
    respondTextWriter(contentType = ContentType.Text.EventStream) {
        val states = service.playbackStates()

        val heartbeats = flow {
            while (true) {
                delay(SSE_HEARTBEAT_MS)
                emit(SseMessage.Ping)
            }
        }

        try {
            merge(
                states.map { SseMessage.Playback(service.playbackDto(it)) },
                heartbeats,
            ).collect { message ->
                when (message) {
                    is SseMessage.Playback -> writeSsePlayback(message.dto)
                    SseMessage.Ping -> {
                        write(": ping\n\n")
                        flush()
                    }
                }
            }
        } catch (_: Throwable) {
            // 订阅者断开，或服务正在停止。都不是错误，安静收尾。
        }
    }
}

/**
 * 写一个 `event: playback` 消息。
 *
 * 立刻 `flush()` 而不是攒着：事件流的意义就是**实时**，缓冲会让状态延迟若干秒才到，
 * 而集成方会以为推送坏了。
 */
private fun Writer.writeSsePlayback(playback: PlaybackDto) {
    write("event: playback\n")
    write("data: ${IntegrationJson.encodeToString(PlaybackDto.serializer(), playback)}\n\n")
    flush()
}

actual fun createIntegrationRoutes(service: IntegrationService): IntegrationRouteMount =
    KtorIntegrationRoutes(service)
