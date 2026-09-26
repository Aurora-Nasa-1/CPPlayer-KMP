package cp.player.core.playback

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * [DesktopStreamLocalizer] 的机制测试。
 *
 * 它站在「能不能听歌」的关键路径上（无损档位的**首播**要经过它），所以每个失败分支都必须
 * 退回 `null`（= 边下边播）而不是抛异常，并且**不能留下半成品文件** ——
 * 留了的话下次会命中截断的缓存，表现为「歌播到一半就没了」。
 */
class DesktopStreamLocalizerTest {

    private var server: TestServer? = null
    private var cacheDir: File? = null

    @AfterTest
    fun tearDown() {
        runCatching { server?.stop() }
        runCatching { cacheDir?.deleteRecursively() }
    }

    private fun newLocalizer(): DesktopStreamLocalizer {
        val dir = java.nio.file.Files.createTempDirectory("cpplayer-localizer").toFile()
        cacheDir = dir
        return DesktopStreamLocalizer(cacheDir = dir)
    }

    private fun start(body: ByteArray, status: Int = 200, declaredLengthOffset: Int = 0): TestServer {
        val srv = TestServer(body, status, declaredLengthOffset).also { it.start() }
        server = srv
        return srv
    }

    // ============ 用例 ============

    @Test
    fun `non-http urls are left alone`() = runBlocking {
        val localizer = newLocalizer()
        assertNull(localizer.localize("/music/local.flac", "k", emptyMap()), "本地文件本来就能定位")
        assertNull(localizer.localize("file:///music/local.flac", "k", emptyMap()))
    }

    @Test
    fun `downloads and names the file after its magic bytes`() = runBlocking {
        val flac = FLAC_HEADER + ByteArray(4096) { (it % 251).toByte() }
        val srv = start(flac)
        val localizer = newLocalizer()

        val path = localizer.localize(srv.url(), "cp_api://song/1@lossless", emptyMap())

        assertNotNull(path, "正常响应必须落地成功")
        val file = File(path)
        assertTrue(file.isFile, "返回的路径必须真实存在：$path")
        assertEquals(flac.size.toLong(), file.length())
        assertTrue(
            file.name.endsWith(".flac"),
            "扩展名必须按 magic bytes 判定（上游 Content-Type 会谎报成 audio/mpeg）：${file.name}",
        )
        // mediaId 里带 `://`，必须被安全化成合法文件名。
        assertTrue(!file.name.contains('/') && !file.name.contains('\\') && !file.name.contains(':'), file.name)
        assertEquals(1, srv.requests.get())
    }

    @Test
    fun `a second call is served from the cache`() = runBlocking {
        val flac = FLAC_HEADER + ByteArray(2048)
        val srv = start(flac)
        val localizer = newLocalizer()

        val first = localizer.localize(srv.url(), "cp_api://song/2@lossless", emptyMap())
        val second = localizer.localize(srv.url(), "cp_api://song/2@lossless", emptyMap())

        assertEquals(first, second, "同一个 key 必须命中同一份缓存")
        assertEquals(1, srv.requests.get(), "第二次不该再打网络 —— 否则每播一次都重下整曲")
    }

    @Test
    fun `cachedPath reports a ready copy without touching the network`() = runBlocking {
        val flac = FLAC_HEADER + ByteArray(2048)
        val srv = start(flac)
        val localizer = newLocalizer()
        val key = "cp_api://song/5@lossless"

        assertNull(localizer.cachedPath(key), "还没下过就不该报「有本地副本」")

        val path = localizer.localize(srv.url(), key, emptyMap())
        assertNotNull(path)
        assertEquals(path, localizer.cachedPath(key), "下好之后必须能同步查到同一份副本")
        assertEquals(1, srv.requests.get(), "查缓存不能打网络 —— 它在播放路径上被同步调用")
    }

    @Test
    fun `a failed download is never reported as cached`() = runBlocking {
        // 谎报长度 ⇒ 响应被截断 ⇒ 落盘失败。
        val srv = start(FLAC_HEADER + ByteArray(4096), declaredLengthOffset = 4096)
        val localizer = newLocalizer()
        val key = "cp_api://song/6@lossless"

        assertNull(localizer.localize(srv.url(), key, emptyMap()))
        assertNull(
            localizer.cachedPath(key),
            "落盘失败的曲子不能被报成「有本地副本」，否则会去播一个不存在的文件",
        )
    }

    @Test
    fun `a truncated response is rejected and leaves nothing behind`() = runBlocking {
        val flac = FLAC_HEADER + ByteArray(4096)
        // 谎报一个更大的长度：模拟 CDN 断流。
        val srv = start(flac, declaredLengthOffset = 4096)
        val localizer = newLocalizer()

        assertNull(localizer.localize(srv.url(), "cp_api://song/3@lossless", emptyMap()))

        val leftovers = cacheDir!!.listFiles()?.map { it.name } ?: emptyList()
        assertTrue(
            leftovers.none { it.endsWith(".part") },
            "失败后不得留下 .part，否则下次会命中半截文件：$leftovers",
        )
        assertTrue(
            leftovers.isEmpty(),
            "被截断的响应不能被当成缓存命中：$leftovers",
        )
    }

    @Test
    fun `a non-success status is rejected`() = runBlocking {
        val srv = start(FLAC_HEADER + ByteArray(64), status = 404)
        val localizer = newLocalizer()

        assertNull(localizer.localize(srv.url(), "cp_api://song/4@lossless", emptyMap()))
        assertTrue((cacheDir!!.listFiles() ?: emptyArray()).isEmpty())
    }

    @Test
    fun `a connection failure falls back instead of throwing`() = runBlocking {
        val localizer = newLocalizer()
        // 端口上没有任何东西在监听。
        assertNull(localizer.localize("http://127.0.0.1:1/nope.flac", "k@lossless", emptyMap()))
    }

    @Test
    fun `eviction keeps the cache under the cap`() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("cpplayer-localizer-evict").toFile()
        cacheDir = dir
        // 上限设成比一份文件还小 ⇒ 新文件落地后旧文件必须被清掉。
        val localizer = DesktopStreamLocalizer(cacheDir = dir, maxCacheBytes = 1024)
        val body = FLAC_HEADER + ByteArray(8192)
        val srv = start(body)

        localizer.localize(srv.url(), "cp_api://song/old@lossless", emptyMap())
        val newest = localizer.localize(srv.url(), "cp_api://song/new@lossless", emptyMap())

        assertNotNull(newest)
        val remaining = dir.listFiles()?.map { it.name } ?: emptyList()
        assertTrue(
            remaining.any { it == File(newest).name },
            "刚下好的文件不能被自己淘汰掉：$remaining",
        )
        assertTrue(remaining.size <= 2, "超出上限时应按 LRU 淘汰：$remaining")
    }

    private companion object {
        /** 只用来让扩展名判定走到 `.flac` 分支，不需要是完整可解码的 FLAC。 */
        val FLAC_HEADER = "fLaC".toByteArray(Charsets.US_ASCII)
    }

    /** 极简 HTTP 服务端：统计请求数，可选谎报 `Content-Length`。 */
    private class TestServer(
        private val body: ByteArray,
        private val status: Int,
        private val declaredLengthOffset: Int,
    ) {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = AtomicInteger(0)

        fun start() {
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/song.flac") { exchange ->
                requests.incrementAndGet()
                if (status != 200) {
                    exchange.sendResponseHeaders(status, -1)
                    exchange.close()
                    return@createContext
                }
                val declared = (body.size + declaredLengthOffset).toLong()
                exchange.responseHeaders.add("Content-Type", "audio/mpeg") // 故意谎报，模拟上游
                exchange.sendResponseHeaders(200, declared)
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
        }

        fun stop() = server.stop(0)

        fun url(): String = "http://127.0.0.1:${server.address.port}/song.flac"
    }
}
