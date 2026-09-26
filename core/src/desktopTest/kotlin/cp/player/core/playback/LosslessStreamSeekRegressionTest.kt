package cp.player.core.playback

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * **无损流 seek 的回归测试** —— 钉住「桌面引擎无法定位 FLAC over HTTP」这个实测结论，
 * 以及「边播边落盘」这个 workaround 真的有效。
 *
 * ### 为什么必须有这一层
 * 原有的 [AudioPlayerImplSeekE2ETest] 用的是**合成 WAV**，而 WAV-over-HTTP 恰好是
 * 能定位的那一格 —— 于是它在 FLAC 场景下**全绿**，却对真实用户毫无保证。
 * 这个 bug 因此躲过了多轮「修 seek 逻辑」和「测试全绿」。**夹具必须覆盖真实格式。**
 *
 * 夹具是仓库里的 `fixtures/tone.flac`（ffmpeg 合成，120s 单声道正弦），
 * 不依赖任何外部服务。
 */
class LosslessStreamSeekRegressionTest {

    private var server: RangeServer? = null

    @AfterTest
    fun tearDown() {
        runCatching { server?.stop() }
    }

    // ============ 用例 1：引擎侧的已知局限（canary） ============

    /**
     * 直接以 HTTP 流播放 FLAC 时，seek **不会**生效。
     *
     * ⚠️ 这条断言的是「局限存在」。**如果它开始失败，是好消息**：说明引擎（或依赖版本）
     * 已经能定位 HTTP FLAC，那么 [DesktopStreamLocalizer] 这个 workaround 与
     * [StreamLocalizer.LOSSLESS_LEVELS] 那套判断都可以删掉了 —— 别改这条断言去迁就它。
     */
    @Test
    fun `flac streamed over http is not seekable on the desktop engine`() {
        val url = startFixture()
        val player = AudioPlayerImpl()
        try {
            runBlocking { player.load(url) }
            assumeTrue(
                "本环境无法播放（无音频设备或原生 HTTP 被代理拦截）——跳过",
                waitFor(LOAD_TIMEOUT_MS) { player.durationMs.value > 0L },
            )

            val landed = seekAndSettle(player, TARGET_MS)

            assertTrue(
                landed < TARGET_MS - LANDED_TOLERANCE_MS,
                "引擎现在**能**定位 HTTP FLAC 了（落在 ${landed}ms）。" +
                    "这是好消息 —— 请删掉 DesktopStreamLocalizer 与 StreamLocalizer.LOSSLESS_LEVELS，" +
                    "并删掉本用例；不要为了让测试变绿而放宽它。",
            )
        } finally {
            runCatching { player.release() }
        }
    }

    // ============ 用例 2：workaround 真的解决问题 ============

    /**
     * 先经 [DesktopStreamLocalizer] 落盘、再播本地文件时，seek **必须**落在目标上。
     *
     * 这是用户可见的承诺：无损档位拖动进度条要能用。
     */
    @Test
    fun `localized lossless stream becomes seekable`() {
        val url = startFixture()

        val cacheDir = java.nio.file.Files.createTempDirectory("cpplayer-localizer-test").toFile()
        val localizer = DesktopStreamLocalizer(cacheDir = cacheDir)
        val localPath = runBlocking { localizer.localize(url, "cp_api://song/1@lossless", emptyMap()) }
        assertTrue(localPath != null, "无损流必须被落地成本地文件（否则 seek 一定不可用）")

        val player = AudioPlayerImpl()
        try {
            runBlocking { player.load(requireNotNull(localPath)) }
            assumeTrue(
                "本环境无法播放（无音频设备）——跳过",
                waitFor(LOAD_TIMEOUT_MS) { player.durationMs.value > 0L },
            )

            val landed = seekAndSettle(player, TARGET_MS)

            assertTrue(
                landed >= TARGET_MS - LANDED_TOLERANCE_MS,
                "落盘后的无损曲目必须可定位：期望>=${TARGET_MS - LANDED_TOLERANCE_MS}ms，实际=${landed}ms。" +
                    "停在低位说明「边播边落盘」没有真正解决问题。",
            )
        } finally {
            runCatching { player.release() }
            runCatching { cacheDir.deleteRecursively() }
        }
    }

    // ============ 夹具 ============

    private fun startFixture(): String {
        val bytes = readFixture()
        val srv = RangeServer(bytes).also { it.start() }
        server = srv

        // 先用真实播放器探测环境（有无音频设备）；不能靠「能不能下载」判断。
        val probe = AudioPlayerImpl()
        try {
            runBlocking { probe.load(srv.url()) }
            val playable = waitFor(LOAD_TIMEOUT_MS) { probe.durationMs.value > 0L }
            assumeTrue(
                "本环境无法播放（无音频设备，或原生 HTTP 被代理拦截）——跳过",
                playable,
            )
        } finally {
            runCatching { probe.release() }
        }
        return srv.url()
    }

    /** 夹具优先走 classpath（Gradle 会把 `src/desktopTest/resources` 打进测试运行时）。 */
    private fun readFixture(): ByteArray {
        val stream = javaClass.getResourceAsStream(FIXTURE_RESOURCE)
        if (stream != null) return stream.use { it.readBytes() }
        // 回退：直接从源码树读，免得资源目录没接上时整条用例静默失效。
        val candidates = listOf(
            "core/src/desktopTest/resources$FIXTURE_RESOURCE",
            "src/desktopTest/resources$FIXTURE_RESOURCE",
        )
        for (path in candidates) {
            val f = java.io.File(path)
            if (f.isFile) return f.readBytes()
        }
        error("找不到 FLAC 夹具 $FIXTURE_RESOURCE —— 回归测试不能静默跳过")
    }

    /**
     * seek 并等过宽限期后返回位置。
     *
     * ⚠️ 必须等过宽限期：待定 seek 期间对外汇报的是**乐观值**（= 目标），
     * 此时断言会误判为通过。只有乐观值被放弃之后，才看得出 seek 有没有真的落到引擎上。
     */
    private fun seekAndSettle(player: AudioPlayerImpl, target: Long): Long {
        player.seekTo(target)
        val deadline = System.currentTimeMillis() + SETTLE_WINDOW_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
        }
        return player.positionMs.value
    }

    private fun waitFor(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(100)
        }
        return predicate()
    }

    /** 支持 Range 的最小 HTTP 服务端（返回 206 + Content-Range）。 */
    private class RangeServer(private val body: ByteArray) {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        fun start() {
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/tone.flac") { exchange ->
                val range = exchange.requestHeaders.getFirst("Range")
                val (start, end) = parseRange(range, body.size)
                val length = end - start + 1
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                exchange.responseHeaders.add("Content-Type", "audio/flac")
                if (range != null) {
                    exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${body.size}")
                    exchange.sendResponseHeaders(206, length.toLong())
                } else {
                    exchange.sendResponseHeaders(200, length.toLong())
                }
                exchange.responseBody.use { it.write(body, start, length) }
            }
            server.start()
        }

        fun stop() = server.stop(0)

        fun url(): String = "http://127.0.0.1:${server.address.port}/tone.flac"

        private fun parseRange(header: String?, size: Int): Pair<Int, Int> {
            val m = header?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) } ?: return 0 to size - 1
            val start = m.groupValues[1].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            val end = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: size - 1
            return start.coerceIn(0, size - 1) to end.coerceIn(0, size - 1)
        }
    }

    private companion object {
        const val FIXTURE_RESOURCE = "/fixtures/tone.flac"

        /** 夹具是 120s 的音源，取一半当目标。 */
        const val TARGET_MS = 60_000L

        const val LOAD_TIMEOUT_MS = 20_000L

        /** 装载期宽限期 4s + 落定余量，等过它才能看出 seek 有没有真的生效。 */
        const val SETTLE_WINDOW_MS = 7_000L

        const val LANDED_TOLERANCE_MS = 3_000L
    }
}
