package cp.player.core.playback

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue

/**
 * **端到端** seek 测试：直接驱动真实的 [AudioPlayerImpl]（底层是 rodio / Rust JNI），
 * 而不是像 [PendingSeekTrackerTest] 那样只喂一个假引擎。
 *
 * ### 为什么必须补这一层
 * 单元测试只能证明状态机本身对；它证明不了「状态机真的被接进了播放器」，
 * 也证明不了「真实引擎在装载期确实会丢弃 seek」。这里用**真实的 HTTP 流**
 * 覆盖完整链路：HTTP(Range) → rodio → AudioPlayerImpl → `positionMs`。
 *
 * ### 怎么稳定复现原 bug
 * 关键是让引擎在 seek 发起后的 **600ms 内还没准备好**（旧实现的补发预算就 3 次 ≈ 600ms）。
 * 服务端对 `/tone.wav` 的**首个**请求故意延迟 [FIRST_RESPONSE_DELAY_MS] 才回 body，
 * 引擎于是长时间停在「未装载」——此时 `AudioPlayer.seekTo` 会**静默变成空操作**
 * （`AudioPlayer` 内部 `player == null` 时直接 return，连异常都没有）。
 * - 旧实现：3 次补发在 600ms 内全部落空，此后**再无人补发**，乐观值挂到 4s 宽限期结束才弹回
 *   ⇒ 最终位置停在 ~1.5s 而不是目标；
 * - 新实现：补发与宽限期同生命周期，引擎一就绪就补上 ⇒ 位置落在目标之后。
 *
 * ### 断言为什么是「单边」的
 * 断言的是 `位置 >= 目标 - 容差`，不是 `≈ 目标`：音频**在播放**，落定后位置会继续前进，
 * 用双边容差会因为「等宽限期」而必然越界。而本 bug 的失效形态是**回弹到低位**，
 * 单边下界恰好精确命中它，且不受播放推进影响。
 *
 * ### 环境要求
 * 需要能出声的音频设备 + 能直连 127.0.0.1。任一不满足时用 `assumeTrue` **跳过**，
 * 而不是伪装成失败——否则在无音频设备的 CI 上会变成假红灯。
 * （沙箱里 `http_proxy` 会让原生 HTTP 打到 127.0.0.1 时收到 502，也走这条跳过路径。）
 */
class AudioPlayerImplSeekE2ETest {

    private var server: RangeServer? = null
    private var player: AudioPlayerImpl? = null

    @AfterTest
    fun tearDown() {
        runCatching { player?.release() }
        runCatching { server?.stop() }
    }

    // ============ 用例 1：装载窗口内拖动（用户最常见的场景） ============

    @Test
    fun `seek issued while the stream is still loading eventually lands on the target`() {
        val url = startFixture()

        // 装载窗口：load() 刚返回、引擎还没准备好，用户此刻拖了进度条。
        runBlocking { requireNotNull(player).load(url) }
        requireNotNull(player).seekTo(TARGET_MS)

        waitPastGracePeriod(requireNotNull(player))

        val landed = requireNotNull(player).positionMs.value
        assertTrue(
            landed >= TARGET_MS - LANDED_TOLERANCE_MS,
            "装载期发起的 seek 必须最终落在目标之后：期望>=${TARGET_MS - LANDED_TOLERANCE_MS}ms，" +
                "实际=${landed}ms。停在低位说明补发在引擎就绪前就放弃了（旧的固定 3 次预算 ≈600ms）。",
        )
    }

    // ============ 用例 2：连续拖动（基线取乐观值就会在这里失效） ============

    @Test
    fun `back to back seeks land on the second target`() {
        val url = startFixture()

        runBlocking { requireNotNull(player).load(url) }
        // 连续两次拖动：旧实现会把第二次的基线记成**第一次的目标**（对外显示的乐观值），
        // 于是「引擎一步没动 → 补发」永远不成立，第二次 seek 从此再没有补救机会。
        requireNotNull(player).seekTo(FIRST_TARGET_MS)
        requireNotNull(player).seekTo(SECOND_TARGET_MS)

        waitPastGracePeriod(requireNotNull(player))

        val landed = requireNotNull(player).positionMs.value
        assertTrue(
            landed >= SECOND_TARGET_MS - LANDED_TOLERANCE_MS,
            "连续拖动必须落在最后一次的目标之后：期望>=${SECOND_TARGET_MS - LANDED_TOLERANCE_MS}ms，" +
                "实际=${landed}ms",
        )
    }

    // ============ 用例 3：落定后不得回弹（护栏，非 bug 复现） ============

    @Test
    fun `position does not snap back after the seek settles`() {
        val url = startFixture()
        val p = requireNotNull(player)

        runBlocking { p.load(url) }
        // 这次先等装载完成再拖：模拟「正常播放中拖动」。
        assumeTrue("引擎未在限期内装载完成——跳过", waitFor(LOAD_TIMEOUT_MS) { p.durationMs.value > 0L })
        p.seekTo(TARGET_MS)

        // 采样观察：一旦乐观值被释放，位置必须由引擎自己撑住，不能掉回低位。
        val lowest = (0 until 12).minOf {
            Thread.sleep(200)
            p.positionMs.value
        }
        assertTrue(
            lowest >= TARGET_MS - REVERT_TOLERANCE_MS,
            "落定后位置不得回弹到低位（这正是「拖了没反应」的观感）。最低采样=${lowest}ms",
        )
    }

    // ============ 夹具 ============

    /**
     * 起服务端 + 一个**已确认可播**的新播放器，返回带延迟的流地址。
     *
     * 先用不带延迟的 `/probe.wav` 探测环境（音频设备 + 网络），确认能播后再用
     * `/tone.wav`（首响应延迟）做正式断言——**不能**让探测消耗掉延迟，
     * 否则「引擎在 600ms 内没准备好」这个复现条件就不成立了。
     */
    private fun startFixture(): String {
        val srv = RangeServer(wavBytes(seconds = 120), FIRST_RESPONSE_DELAY_MS).also { it.start() }
        server = srv

        val probe = AudioPlayerImpl().also { player = it }
        runBlocking { probe.load(srv.probeUrl()) }
        val playable = waitFor(LOAD_TIMEOUT_MS) { probe.durationMs.value > 0L }
        probe.release()
        player = null
        assumeTrue(
            "本环境无法播放（无音频设备，或原生 HTTP 被代理拦截）——跳过端到端 seek 校验",
            playable,
        )

        player = AudioPlayerImpl()
        return srv.delayedUrl()
    }

    /**
     * 等过装载期宽限期（4s）再断言。
     *
     * ⚠️ 必须等过它：旧实现在宽限期内一直对外汇报**乐观值**（= 目标），
     * 此时断言会误判为通过；只有宽限期结束、乐观值被放弃之后，
     * 「有没有真的落到引擎上」才看得出来。
     */
    private fun waitPastGracePeriod(p: AudioPlayerImpl) {
        val samples = mutableListOf<String>()
        val end = System.currentTimeMillis() + GRACE_PERIOD_MS
        while (System.currentTimeMillis() < end) {
            samples += "${p.positionMs.value}|${p.durationMs.value}"
            Thread.sleep(250)
        }
        println("[e2e] pos|dur 时间线: $samples")
    }

    private fun waitFor(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(100)
        }
        return predicate()
    }

    private companion object {
        const val TARGET_MS = 30_000L
        const val FIRST_TARGET_MS = 20_000L
        const val SECOND_TARGET_MS = 45_000L

        /** 让引擎在 seek 之后的 600ms 补发预算内仍然「未装载」。 */
        const val FIRST_RESPONSE_DELAY_MS = 1_500L

        const val LOAD_TIMEOUT_MS = 20_000L

        /** 装载期宽限期 4s + 落定余量。 */
        const val GRACE_PERIOD_MS = 7_000L

        /** 引擎通常能精确落点，留一点余量给按帧定位的解码器。 */
        const val LANDED_TOLERANCE_MS = 2_000L
        const val REVERT_TOLERANCE_MS = 5_000L
    }

    /** 内存里合成一段 440Hz 正弦 WAV（不依赖任何资源文件）。 */
    private fun wavBytes(seconds: Int, sampleRate: Int = 8_000): ByteArray {
        val samples = seconds * sampleRate
        val dataSize = samples * 2
        val out = ByteArray(44 + dataSize)
        fun putStr(off: Int, s: String) { for (i in s.indices) out[off + i] = s[i].code.toByte() }
        fun putInt(off: Int, v: Int) {
            out[off] = (v and 0xFF).toByte(); out[off + 1] = ((v ushr 8) and 0xFF).toByte()
            out[off + 2] = ((v ushr 16) and 0xFF).toByte(); out[off + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        fun putShort(off: Int, v: Int) {
            out[off] = (v and 0xFF).toByte(); out[off + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        putStr(0, "RIFF"); putInt(4, 36 + dataSize); putStr(8, "WAVE")
        putStr(12, "fmt "); putInt(16, 16); putShort(20, 1); putShort(22, 1)
        putInt(24, sampleRate); putInt(28, sampleRate * 2); putShort(32, 2); putShort(34, 16)
        putStr(36, "data"); putInt(40, dataSize)
        for (i in 0 until samples) {
            val v = (12_000.0 * sin(2.0 * PI * 440.0 * i / sampleRate)).toInt()
            out[44 + i * 2] = (v and 0xFF).toByte()
            out[44 + i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * 支持 Range 的最小 HTTP 服务端。
     *
     * ⚠️ 必须支持 Range（返回 206）：不支持时 rodio 根本无从定位，
     * 那测的就不是我们的补偿逻辑而是服务端能力了。
     * 也**不能**用 `python -m http.server`——它不回 206。
     *
     * `/probe.wav` 不延迟（环境探测用），`/tone.wav` 首响应延迟（复现用）。
     */
    private class RangeServer(private val body: ByteArray, private val firstDelayMs: Long) {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val delayedPathFirstRequest = AtomicBoolean(true)

        fun start() {
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/probe.wav") { exchange -> respond(exchange, delayMs = 0L) }
            server.createContext("/tone.wav") { exchange ->
                val delay = if (delayedPathFirstRequest.getAndSet(false)) firstDelayMs else 0L
                respond(exchange, delay)
            }
            server.start()
        }

        fun stop() {
            server.stop(0)
        }

        fun probeUrl(): String = "http://127.0.0.1:${server.address.port}/probe.wav"
        fun delayedUrl(): String = "http://127.0.0.1:${server.address.port}/tone.wav"

        private fun respond(exchange: com.sun.net.httpserver.HttpExchange, delayMs: Long) {
            if (delayMs > 0) Thread.sleep(delayMs)
            val range = exchange.requestHeaders.getFirst("Range")
            val (start, end) = parseRange(range, body.size)
            val length = end - start + 1
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            exchange.responseHeaders.add("Content-Type", "audio/wav")
            if (range != null) {
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${body.size}")
                exchange.sendResponseHeaders(206, length.toLong())
            } else {
                exchange.sendResponseHeaders(200, length.toLong())
            }
            exchange.responseBody.use { it.write(body, start, length) }
        }

        private fun parseRange(header: String?, size: Int): Pair<Int, Int> {
            val m = header?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) } ?: return 0 to size - 1
            val start = m.groupValues[1].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            val end = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: size - 1
            return start.coerceIn(0, size - 1) to end.coerceIn(0, size - 1)
        }
    }
}
