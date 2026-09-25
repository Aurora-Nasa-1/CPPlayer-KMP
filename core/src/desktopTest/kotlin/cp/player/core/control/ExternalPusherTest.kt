package cp.player.core.control

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 推送客户端的契约测试。
 *
 * 用一个假接收端记录收到的请求，验证三件容易写错的事：
 * 1. 请求打到正确的端点，body 用接收端要求的 snake_case 字段；
 * 2. `/api/v1/...` 不可用时能回退到兼容别名 `/api/source/{name}/...`；
 * 3. 网络失败收敛为 [PushResult.Failed]，不抛异常（推送失败不该影响本机播放）。
 */
class ExternalPusherTest {

    private var receiver: HttpServer? = null

    @AfterTest
    fun tearDown() {
        receiver?.stop(0)
    }

    @Test
    fun `playUrl posts snake_case body to v1 endpoint`() = runBlocking {
        val rec = startReceiver(v1Available = true)
        val pusher = pusherFor(rec.port)

        val result = pusher.playUrl(
            PushTrack(
                url = "http://127.0.0.1:8080/stream?mediaId=netease%3A%2F%2Fsong%2F1",
                title = "曲名",
                artist = "歌手",
                album = "专辑",
                artworkUrl = "http://127.0.0.1:8080/art/1",
                durationMs = 240_000,
            )
        )

        assertTrue(result.isSuccess, "推送应成功：$result")
        assertEquals("POST", rec.lastMethod)
        assertEquals("/api/v1/play-url", rec.lastPath)
        val body = rec.lastBody
        assertTrue(body.contains("\"url\""), "缺少 url: $body")
        assertTrue(body.contains("\"title\""), "缺少 title: $body")
        assertTrue(body.contains("\"artwork_url\""), "字段名应为 snake_case: $body")
        assertTrue(body.contains("\"duration_ms\""), "字段名应为 snake_case: $body")
        assertTrue(body.contains("240000"), "时长应原样传递: $body")
    }

    @Test
    fun `omits null fields so receiver does not clear metadata`() = runBlocking {
        val rec = startReceiver(v1Available = true)
        val pusher = pusherFor(rec.port)

        pusher.playUrl(PushTrack(url = "http://127.0.0.1:8080/stream"))

        val body = rec.lastBody
        assertTrue(!body.contains("artist"), "null 字段不应出现: $body")
        assertTrue(!body.contains("artwork_url"), "null 字段不应出现: $body")
        assertTrue(!body.contains("duration_ms"), "null 字段不应出现: $body")
    }

    @Test
    fun `falls back to compat alias when v1 is unavailable`() = runBlocking {
        val rec = startReceiver(v1Available = false)
        val pusher = pusherFor(rec.port)

        val result = pusher.playUrl(PushTrack(url = "http://127.0.0.1:8080/stream"))

        assertTrue(result.isSuccess, "回退后应成功：$result")
        assertEquals("/api/source/external/cast", rec.lastPath, "应回退到兼容别名")
        assertEquals(2, rec.requests.size, "应恰好请求两次（v1 + 别名）")
    }

    @Test
    fun `transport hits player endpoint and falls back`() = runBlocking {
        val rec = startReceiver(v1Available = true)
        val pusher = pusherFor(rec.port)

        assertTrue(pusher.transport("pause").isSuccess)
        assertEquals("/api/v1/player/pause", rec.lastPath)

        val fallbackRec = startReceiver(v1Available = false)
        val fallbackPusher = pusherFor(fallbackRec.port)
        assertTrue(fallbackPusher.transport("next").isSuccess)
        assertEquals("/api/source/external/next", fallbackRec.lastPath)
    }

    @Test
    fun `queue replace and clear use documented shapes`() = runBlocking {
        val rec = startReceiver(v1Available = true)
        val pusher = pusherFor(rec.port)

        pusher.pushQueue(
            tracks = listOf(PushTrack(url = "http://a/1"), PushTrack(url = "http://a/2")),
            autoplay = true,
            startIndex = 1,
        )
        assertEquals("/api/v1/queue", rec.lastPath)
        assertTrue(rec.lastBody.contains("\"tracks\""), "缺少 tracks: ${rec.lastBody}")
        assertTrue(rec.lastBody.contains("\"autoplay\":true"), "缺少 autoplay: ${rec.lastBody}")
        assertTrue(rec.lastBody.contains("\"start_index\":1"), "缺少 start_index: ${rec.lastBody}")

        pusher.clearQueue()
        assertEquals("DELETE", rec.lastMethod)
        assertEquals("/api/v1/queue", rec.lastPath)
    }

    @Test
    fun `network failure becomes Failed instead of throwing`() = runBlocking {
        val deadPort = java.net.ServerSocket(0).use { it.localPort }
        val pusher = pusherFor(deadPort)

        val result = pusher.playUrl(PushTrack(url = "http://127.0.0.1:8080/stream"))

        assertTrue(result is PushResult.Failed, "连接失败应返回 Failed，实际：$result")
    }

    // ============ 测试脚手架 ============

    private fun pusherFor(port: Int): ExternalPusher = createExternalPusher {
        LocalServerConfig(enabled = true, receiverBaseUrl = "http://127.0.0.1:$port")
    }

    /** 记录请求的假接收端；[v1Available] = false 时 `/api/v1/...` 一律 404。 */
    private fun startReceiver(v1Available: Boolean): Recorder {
        val recorder = Recorder(v1Available)
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { exchange: HttpExchange ->
            val path = exchange.requestURI.path
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            recorder.record(exchange.requestMethod, path, body)

            val known = path == "/api/health" ||
                (v1Available && path.startsWith("/api/v1/")) ||
                (!v1Available && path.startsWith("/api/source/"))
            val code = if (known) 200 else 404
            val payload = """{"ok":$known}""".toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        http.start()
        receiver = http
        recorder.port = http.address.port
        return recorder
    }

    private class Recorder(private val v1Available: Boolean) {
        var port: Int = 0
        val requests = mutableListOf<Triple<String, String, String>>()
        var lastMethod: String = ""
        var lastPath: String = ""
        var lastBody: String = ""

        fun record(method: String, path: String, body: String) {
            requests.add(Triple(method, path, body))
            lastMethod = method
            lastPath = path
            lastBody = body
        }
    }
}
