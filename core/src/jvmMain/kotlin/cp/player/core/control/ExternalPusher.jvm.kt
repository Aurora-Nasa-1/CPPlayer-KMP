package cp.player.core.control

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.net.HttpURLConnection

/**
 * [ExternalPusher] 的 JVM 实现（Android 与 Desktop 共用）。
 *
 * 用 [HttpURLConnection] 直连接收端——只需「发一个带 JSON body 的请求并读响应」，
 * 不值得为此引入 Ktor client 引擎。
 *
 * ### 接口兼容策略
 * 优先走文档里的 `/api/v1/...`；若接收端返回 404/405（旧版本或未实现该端点），
 * 自动回退到兼容别名 `/api/source/{sourceName}/...`。
 * 这样同一份代码能同时对接新旧接收端。
 */
internal class HttpExternalPusher(
    private val configProvider: () -> LocalServerConfig,
) : ExternalPusher {

    override suspend fun health(): PushResult = call("GET", "/api/health")

    override suspend fun status(): PushResult = call("GET", "/api/v1/status")

    override suspend fun transport(action: String): PushResult =
        callWithFallback(
            path = "/api/v1/player/$action",
            fallbackPath = "/api/source/$SOURCE_NAME/$action",
            method = "POST",
        )

    override suspend fun playUrl(track: PushTrack): PushResult =
        callWithFallback(
            path = "/api/v1/play-url",
            fallbackPath = "/api/source/$SOURCE_NAME/cast",
            method = "POST",
            body = track.toJson().toString(),
        )

    override suspend fun pushQueue(
        tracks: List<PushTrack>,
        autoplay: Boolean,
        startIndex: Int,
    ): PushResult {
        val body = buildJsonObject {
            put("tracks", buildJsonArray { tracks.forEach { add(it.toJson()) } })
            put("autoplay", JsonPrimitive(autoplay))
            put("start_index", JsonPrimitive(startIndex))
        }.toString()
        return callWithFallback(
            path = "/api/v1/queue",
            fallbackPath = "/api/source/$SOURCE_NAME/play_queue",
            method = "POST",
            body = body,
        )
    }

    override suspend fun enqueue(track: PushTrack, playNow: Boolean): PushResult {
        val body = buildJsonObject {
            put("track", track.toJson())
            put("play_now", JsonPrimitive(playNow))
        }.toString()
        return callWithFallback(
            path = "/api/v1/queue/items",
            fallbackPath = "/api/source/$SOURCE_NAME/enqueue",
            method = "POST",
            body = body,
        )
    }

    override suspend fun clearQueue(): PushResult =
        callWithFallback(
            path = "/api/v1/queue",
            fallbackPath = "/api/source/$SOURCE_NAME/clear",
            method = "DELETE",
        )

    // ============ HTTP ============

    /** 单次请求。 */
    private suspend fun call(
        method: String,
        path: String,
        body: String? = null,
    ): PushResult = withContext(Dispatchers.IO) {
        val base = configProvider().receiverUrl
        if (base.isBlank()) return@withContext PushResult.Failed("未配置接收端地址")
        try {
            val conn = (java.net.URI(base + path).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
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
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.use(BufferedReader::readText)
            }.getOrNull().orEmpty()
            runCatching { conn.disconnect() }

            if (code in 200..299) PushResult.Ok(text)
            else PushResult.Failed("接收端返回 HTTP $code", httpCode = code)
        } catch (e: Throwable) {
            PushResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 先走主端点；404/405 时回退到兼容别名。 */
    private suspend fun callWithFallback(
        path: String,
        fallbackPath: String,
        method: String,
        body: String? = null,
    ): PushResult {
        val first = call(method, path, body)
        val code = (first as? PushResult.Failed)?.httpCode
        if (code == 404 || code == 405) {
            return call(method, fallbackPath, body)
        }
        return first
    }

    private companion object {
        /** 兼容别名里使用的音源名（对应接收端 config.toml 的 default_source）。 */
        const val SOURCE_NAME = "external"
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 5_000
    }
}

/**
 * 曲目 JSON。
 *
 * 字段名按接收端文档的 snake_case 约定：
 * `url` / `title` / `artist` / `album` / `artwork_url` / `duration_ms`。
 * `null` 字段不发送，避免接收端把 null 当成"显式清空"。
 */
private fun PushTrack.toJson() = buildJsonObject {
    put("url", JsonPrimitive(url))
    title?.let { put("title", JsonPrimitive(it)) }
    artist?.let { put("artist", JsonPrimitive(it)) }
    album?.let { put("album", JsonPrimitive(it)) }
    artworkUrl?.let { put("artwork_url", JsonPrimitive(it)) }
    durationMs?.let { put("duration_ms", JsonPrimitive(it)) }
}

actual fun createExternalPusher(configProvider: () -> LocalServerConfig): ExternalPusher =
    HttpExternalPusher(configProvider)
