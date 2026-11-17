package cp.player.core.playback

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * **预加载下一首（queue-next）不得打断当前曲目出声** 的回归测试。
 *
 * ### 曾经的故障
 * [PlaybackMediaLoader.triggerPreloadNext] 在当前曲开始约 1.2 秒后把下一首 URL
 * 排进引擎。Rust 侧 `audio_queue_next_url()` 当时复用了 `prepare_stream()`，
 * 而它会按**下一首**的采样率调用 `ensure_audio_engine()`：设备默认输出 96 kHz、
 * 下一首 44.1 kHz 时输出签名不匹配，整个引擎被关掉重建 —— 新引擎里没有当前音源。
 * 用户听到的是「播 1~2 秒后突然静音」，但 UI 仍是 playing、进度冻结、
 * `nativeGetProgress()` 直接返回 `null`。
 *
 * ### 为什么必须用真实引擎
 * 状态机 / 假引擎测试证明不了「原生排队有没有重建输出流」。
 * 这里用真实的本地 HTTP 流 + 真实 [DesktopHiResPlatformPlayer]：
 * 播放 → 约 1.2 秒时 `queueNextUrl` → 断言当前曲仍在推进。
 *
 * ### 环境要求
 * 需要能出声的音频设备 + 能直连 127.0.0.1（沙箱 `http_proxy` 会把 127.0.0.1
 * 打成 502）。任一不满足时 `assumeTrue` 跳过，而不是伪装成失败。
 */
class DesktopQueueNextUrlRegressionTest {

    private var server: HttpServer? = null
    private var player: DesktopHiResPlatformPlayer? = null

    @AfterTest
    fun tearDown() {
        runCatching { player?.release() }
        runCatching { server?.stop(0) }
    }

    @Test
    fun `queueing the next http url keeps the current track progressing`() = runBlocking {
        assumeTrue(
            "桌面 Hi-Res 引擎不可用（缺 DLL 或无音频设备）——跳过",
            DesktopHiResEngine.isAvailable,
        )
        val url = startToneServer()
        val p = DesktopHiResPlatformPlayer(isExclusive = false).also { player = it }

        val loaded = runCatching {
            p.load(
                url = url,
                startPositionMs = 0L,
                headers = emptyMap(),
                metadata = PlaybackMetadata(
                    id = "queue-next-regression",
                    title = "queue-next-regression",
                    artist = "queue-next-regression",
                    album = "",
                    coverUrl = null,
                    durationMs = 10_000L,
                    cacheKey = "queue-next-regression",
                ),
            )
        }
        assumeTrue(
            "本环境无法装载音频流（无音频设备，或原生 HTTP 被代理拦截）——跳过",
            loaded.isSuccess,
        )
        p.play()

        // 先确认真的在出声：旧 DLL 在 queue 之前是正常的，故障发生在 queue 之后。
        val audible = waitFor(6_000L) { p.positionMs.value >= 800L }
        assumeTrue(
            "引擎未在限期内开始推进（无音频设备）——跳过",
            audible,
        )
        val positionBeforeQueue = p.positionMs.value

        assertTrue(
            DesktopHiResEngine.queueNextUrl(url),
            "queueNextUrl 必须被接受；拒绝说明探测或引擎状态异常",
        )

        // 观察 2.5 秒：旧实现此刻引擎已被重建，getProgress() 恒为 null、位置冻结。
        var sawEngineProgress = false
        var highestPosition = positionBeforeQueue
        repeat(12) {
            Thread.sleep(200)
            if (runCatching { DesktopHiResEngine.getProgress() }.getOrNull() != null) {
                sawEngineProgress = true
            }
            highestPosition = maxOf(highestPosition, p.positionMs.value)
        }

        assertTrue(
            sawEngineProgress,
            "queueNextUrl 之后 nativeGetProgress() 变成 null —— 当前输出引擎被关掉重建了。" +
                "预加载下一首必须复用当前引擎的采样率/声道数，绝不能触发 ensure_audio_engine()。",
        )
        assertTrue(
            highestPosition >= positionBeforeQueue + 1_000L,
            "queueNextUrl 之后当前曲进度必须继续前进：排队前=${positionBeforeQueue}ms，" +
                "观察到的最高位置=${highestPosition}ms。停在原地就是「播 1~2 秒后突然静音」。",
        )
    }

    private fun startToneServer(): String {
        val bytes = wavBytes(seconds = 10, sampleRate = 44_100)
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newFixedThreadPool(4)
            createContext("/tone.wav") { exchange ->
                exchange.responseHeaders.add("Content-Type", "audio/wav")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        server = srv
        return "http://127.0.0.1:${srv.address.port}/tone.wav"
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private fun wavBytes(seconds: Int, sampleRate: Int): ByteArray {
        val samples = seconds * sampleRate
        val dataSize = samples * 2
        val out = ByteArray(44 + dataSize)
        fun putStr(off: Int, s: String) {
            for (i in s.indices) out[off + i] = s[i].code.toByte()
        }
        fun putInt(off: Int, v: Int) {
            out[off] = (v and 0xFF).toByte()
            out[off + 1] = ((v ushr 8) and 0xFF).toByte()
            out[off + 2] = ((v ushr 16) and 0xFF).toByte()
            out[off + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        fun putShort(off: Int, v: Int) {
            out[off] = (v and 0xFF).toByte()
            out[off + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        putStr(0, "RIFF")
        putInt(4, 36 + dataSize)
        putStr(8, "WAVE")
        putStr(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, 1)
        putInt(24, sampleRate)
        putInt(28, sampleRate * 2)
        putShort(32, 2)
        putShort(34, 16)
        putStr(36, "data")
        putInt(40, dataSize)
        for (i in 0 until samples) {
            val v = (12_000.0 * sin(2.0 * PI * 440.0 * i / sampleRate)).toInt()
            out[44 + i * 2] = (v and 0xFF).toByte()
            out[44 + i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        return out
    }
}
