package cp.player.core.control

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 流输出服务的端到端冒烟测试。
 *
 * 覆盖三条最容易写错的路径：
 * 1. `/health` 可达；
 * 2. `/stream` 字节完整转发（含上游 `Content-Length` / `Content-Type` 透传）；
 * 3. `Range` 请求返回 206 且带 `Content-Range`（接收端拖进度条依赖它）。
 *
 * 用 [HttpServer] 充当"上游音源"，不依赖外网。
 */
class LocalServerSmokeTest {

    private val payload = ByteArray(64 * 1024) { (it % 251).toByte() }

    private var upstream: HttpServer? = null
    private var server: LocalServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop()
        upstream?.stop(0)
    }

    @Test
    fun `health is reachable and stream relays bytes with range support`() = runBlocking {
        val upstreamPort = startUpstream()
        val streamPort = freePort()

        val config = LocalServerConfig(
            enabled = true,
            bindAddress = LocalServerConfig.BIND_LOOPBACK,
            streamPort = streamPort,
            accessToken = "secret",
        )
        val local = createLocalServer(config) { mediaId ->
            if (mediaId == null) null
            else StreamTarget(url = "http://127.0.0.1:$upstreamPort/audio", cookie = "SESS=abc")
        }
        server = local
        local.start()

        // 等监听就绪
        waitUntilReady("http://127.0.0.1:$streamPort/health")

        // 1) health
        val health = get("http://127.0.0.1:$streamPort/health")
        assertEquals(200, health.code, "health 应返回 200")
        assertTrue(health.body.contains("\"status\":\"ok\""), "health 响应体异常: ${health.body}")

        // 2) 未带令牌 → 401
        val unauthorized = get("http://127.0.0.1:$streamPort/stream?mediaId=x")
        assertEquals(401, unauthorized.code, "缺少令牌应返回 401")

        // 3) 全量转发
        val full = get("http://127.0.0.1:$streamPort/stream?mediaId=demo&token=secret")
        assertEquals(200, full.code, "stream 应返回 200")
        assertEquals(payload.size, full.bytes.size, "转发字节数应与上游一致")
        assertTrue(payload.contentEquals(full.bytes), "转发内容应与上游一致")

        // 4) Range 转发
        val ranged = get(
            url = "http://127.0.0.1:$streamPort/stream?mediaId=demo&token=secret",
            range = "bytes=100-199",
        )
        assertEquals(206, ranged.code, "Range 请求应返回 206")
        assertEquals(100, ranged.bytes.size, "Range 长度应为 100")
        assertNotNull(ranged.contentRange, "Range 响应必须带 Content-Range")
        assertTrue(ranged.contentRange.startsWith("bytes 100-199/"), "Content-Range 异常: ${ranged.contentRange}")

        // 5) 解析不到曲目 → 404
        val missing = get("http://127.0.0.1:$streamPort/stream?token=secret")
        assertEquals(404, missing.code, "无当前曲目应返回 404")
    }

    // ============ 测试脚手架 ============

    /** 起一个支持 Range 的上游服务，记录收到的 Cookie 以便断言。 */
    private fun startUpstream(): Int {
        var receivedCookie: String? = null
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/audio") { exchange: HttpExchange ->
            receivedCookie = exchange.requestHeaders.getFirst("Cookie")
            val range = exchange.requestHeaders.getFirst("Range")
            if (range == null) {
                exchange.responseHeaders.add("Content-Type", "audio/mpeg")
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            } else {
                val spec = range.removePrefix("bytes=").split('-')
                val from = spec[0].toInt()
                val to = spec.getOrNull(1)?.toIntOrNull() ?: payload.lastIndex
                val slice = payload.copyOfRange(from, to + 1)
                exchange.responseHeaders.add("Content-Type", "audio/mpeg")
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                exchange.responseHeaders.add("Content-Range", "bytes $from-$to/${payload.size}")
                exchange.sendResponseHeaders(206, slice.size.toLong())
                exchange.responseBody.use { it.write(slice) }
            }
        }
        http.start()
        upstream = http
        return http.address.port
    }

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    private fun waitUntilReady(url: String, attempts: Int = 50) {
        repeat(attempts) {
            val ok = runCatching { get(url).code == 200 }.getOrDefault(false)
            if (ok) return
            Thread.sleep(100)
        }
        error("服务未在预期时间内就绪: $url")
    }

    private data class Response(
        val code: Int,
        val body: String,
        val bytes: ByteArray,
        val contentRange: String?,
    )

    private fun get(url: String, range: String? = null): Response {
        val conn = (java.net.URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2_000
            readTimeout = 5_000
            range?.let { setRequestProperty("Range", it) }
        }
        val code = conn.responseCode
        val bytes = runCatching {
            (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.readBytes() ?: ByteArray(0)
        }.getOrDefault(ByteArray(0))
        val contentRange = conn.getHeaderField("Content-Range")
        runCatching { conn.disconnect() }
        return Response(code, String(bytes, Charsets.UTF_8), bytes, contentRange)
    }
}
