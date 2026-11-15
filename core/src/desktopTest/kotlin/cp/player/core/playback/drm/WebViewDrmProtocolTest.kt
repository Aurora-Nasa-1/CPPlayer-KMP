package cp.player.core.playback.drm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WebView DRM 桥的端到端协议测试：起真实 [WebViewDrmServer]，
 * 用 HTTP/WS 客户端扮演浏览器播放页，验证路由、清单归一化、
 * license 字节往返与命令/事件通道。
 */
class WebViewDrmProtocolTest {

    private var server: WebViewDrmServer? = null
    private val client = HttpClient(CIO) { install(WebSockets) }

    @AfterTest
    fun tearDown() {
        server?.stop()
        client.close()
    }

    private fun startServer(fetch: suspend (String) -> String?): WebViewDrmServer = runBlocking {
        WebViewDrmServer(fetch).also {
            it.start()
            server = it
        }
    }

    private fun tokenOf(server: WebViewDrmServer): String =
        server.pageUrl.substringAfter("token=").trim()

    @Test
    fun `serves player page and hls bundle`() = runBlocking {
        val s = startServer { null }
        val page = client.get("http://127.0.0.1:${s.port}/player?token=${tokenOf(s)}")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue("requestMediaKeySystemAccess" in page.bodyAsText(), "播放页必须包含 EME 探测")
        val js = client.get("http://127.0.0.1:${s.port}/hls.min.js")
        assertEquals(HttpStatusCode.OK, js.status)
        assertTrue(js.bodyAsText().length > 100_000, "hls.js 资源应完整返回")
    }

    @Test
    fun `rejects requests without or with wrong token`() = runBlocking {
        val s = startServer { null }
        val noToken = client.get("http://127.0.0.1:${s.port}/player")
        assertEquals(HttpStatusCode.Unauthorized, noToken.status)
        val wrongToken = client.post("http://127.0.0.1:${s.port}/license?token=wrong") { setBody("challenge") }
        assertEquals(HttpStatusCode.Unauthorized, wrongToken.status)
        val wrongPageToken = client.get("http://127.0.0.1:${s.port}/player?token=wrong")
        assertEquals(HttpStatusCode.Unauthorized, wrongPageToken.status)
    }

    @Test
    fun `manifest endpoint normalizes playlists through the proxy chain`() = runBlocking {
        val master = listOf(
            "#EXTM3U",
            "#EXT-X-SESSION-KEY:METHOD=ISO-23001-7,URI=\"data:text/plain;base64,cHNzaA==\",KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"",
            "#EXT-X-STREAM-INF:BANDWIDTH=256000,CODECS=\"mp4a.40.2\"",
            "audio/aac.m3u8",
        ).joinToString("\n")
        val media = listOf(
            "#EXTM3U",
            "#EXT-X-KEY:METHOD=ISO-23001-7,URI=\"data:text/plain;base64,cHNzaA==\"",
            "seg_0.mp4",
        ).joinToString("\n")
        val fetched = mutableListOf<String>()
        val s = startServer { url ->
            fetched.add(url)
            when {
                url.endsWith("P42_default.m3u8") -> master
                url.endsWith("audio/aac.m3u8") -> media
                else -> null
            }
        }
        val upstream = "https://cdn.example.test/abc/P42_default.m3u8"
        val proxy = s.proxyPlaylistUrl(upstream)
        val response = client.get(proxy)
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue("METHOD=SAMPLE-AES-CTR" in body)
        assertTrue("data:text/plain;base64,cHNzaA==" in body)
        assertTrue(fetched.contains(upstream), "master 应从上游拉取")
        // variant 已被代理化：再经代理取媒体清单，分片应指向 CDN 绝对地址。
        val variantUrl = body.lineSequence().first { it.contains("/manifest?token=") }
        val mediaResponse = client.get(variantUrl)
        val mediaBody = mediaResponse.bodyAsText()
        assertTrue("https://cdn.example.test/abc/audio/seg_0.mp4" in mediaBody)
        assertEquals(2, fetched.size, "master 与 variant 各拉取一次")
    }

    @Test
    fun `license endpoint round trips challenge and license bytes`() = runBlocking {
        val s = startServer { null }
        s.setLicenseExchange { challenge -> challenge.reversedArray() }
        val challenge = byteArrayOf(1, 2, 3, 4, 5)
        val response = client.post("http://127.0.0.1:${s.port}/license?token=${tokenOf(s)}") {
            setBody(challenge)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val license = response.body<ByteArray>()
        assertEquals(5, license.size)
        assertEquals(byteArrayOf(5, 4, 3, 2, 1).toList(), license.toList())
        // 未注册交换回调 → 503；交换抛错 → 502 带语义信息。
        s.setLicenseExchange(null)
        val unavailable = client.post("http://127.0.0.1:${s.port}/license?token=${tokenOf(s)}") {
            setBody(challenge)
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
        s.setLicenseExchange { throw IllegalStateException("apple rejected") }
        val failure = client.post("http://127.0.0.1:${s.port}/license?token=${tokenOf(s)}") {
            setBody(challenge)
        }
        assertEquals(HttpStatusCode.BadGateway, failure.status)
        assertTrue("apple rejected" in failure.bodyAsText())
    }

    @Test
    fun `web socket carries hello event and host commands`() = runBlocking {
        val s = startServer { null }
        val token = tokenOf(s)
        val port = s.port
        val commandReceived = kotlinx.coroutines.CompletableDeferred<String>()
        val pageJob = launch {
            client.webSocket(urlString = "ws://127.0.0.1:$port/ws?token=$token") {
                send(Frame.Text("""{"type":"hello","eme":true,"keySystem":"com.widevine.alpha"}"""))
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        commandReceived.complete(frame.readText())
                        break
                    }
                }
            }
        }
        withTimeout(10_000) {
            s.awaitPageConnected(10_000)
            s.send(WebViewPlayerCommand.Load("https://cdn.example.test/media.m3u8", 1500))
            val command = commandReceived.await()
            assertTrue("media.m3u8" in command, "Load 命令应携带 URL: $command")
            assertTrue("1500" in command, "Load 命令应携带起始位置: $command")
        }
        pageJob.cancel()
    }
}
