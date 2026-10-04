package cp.player.core.sync

import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 同步服务「端口被占」降级测试 —— 回归 Android 实锤过的崩溃：
 *
 * Ktor CIO 的端口绑定发生在引擎内部 accept 协程里（`httpServer$acceptJob`），
 * `start(wait = false)` 外面的 try/catch **接不住** `BindException` —— 异常直达
 * 全局未捕获处理器，Android 上直接 FATAL 杀进程。修复后 `start()` 必须先探测
 * 端口（[cp.player.core.util.isTcpPortBindable]，语义与 CIO 的 bind 一致），
 * 占不上就报状态错误，绝不把绑定失败留给 Ktor 内部协程。
 */
class SyncServerPortGuardTest {

    private var server: SyncTransport.Server? = null
    private var blocker: ServerSocket? = null

    @AfterTest
    fun tearDown() {
        server?.stop()
        runCatching { blocker?.close() }
    }

    @Test
    fun `occupied port reports error instead of crashing then starts after release`() = runBlocking {
        // 先占住一个临时端口（模拟另一个 CPPlayer 实例还活着 / 旧进程未释放）。
        val blockerSocket = ServerSocket()
        blockerSocket.bind(InetSocketAddress("127.0.0.1", 0))
        blocker = blockerSocket
        val port = blockerSocket.localPort

        var running: Boolean? = null
        var error: String? = null
        val syncServer = SyncTransport.Server(
            bindHost = "127.0.0.1",
            bindPort = port,
            snapshotProvider = { SyncSnapshot(deviceId = "test-device", name = "test", records = emptyList()) },
            onIncoming = { 0 },
            onStateChanged = { r, e -> running = r; error = e },
        )
        server = syncServer

        // 1) 端口被占 → 不启动、报状态错误（而不是把 BindException 甩给全局处理器）。
        syncServer.start()
        assertEquals(false, running, "端口被占时不应报告 running=true")
        assertNotNull(error, "端口被占时必须给出错误信息")
        assertTrue(error!!.contains("占用"), "错误信息应说明端口被占用: $error")

        // 2) 探测失败路径不残留半启动状态：释放端口后同一实例能直接起服务。
        blockerSocket.close()
        syncServer.start()
        assertEquals(true, running, "端口释放后应能正常启动")

        // 3) stop() 后端口可复用：立即重启能再起服务（本用例无连接，纯守卫；
        //    行为级断言 —— 不直接引用 core 的 internal 探测函数）。
        syncServer.stop()
        assertEquals(false, running, "stop() 后应报告 running=false")
        syncServer.start()
        assertEquals(true, running, "stop() 后应能立即重启（端口已释放）")
    }
}
