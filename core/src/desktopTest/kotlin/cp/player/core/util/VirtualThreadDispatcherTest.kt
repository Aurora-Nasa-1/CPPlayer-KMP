package cp.player.core.util

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 覆盖 Sentry CPPLAYER-KMP-2（`ThreadGroup.add` NPE）的回归点。
 *
 * 核心不变量：[VirtualThreadDispatcher.isSupported] 必须与「执行器**真正**产出的线程性质」一致。
 * 旧实现只嗅探 `Executors.newVirtualThreadPerTaskExecutor` 这个方法在不在，在「方法存在但产出的是
 * 平台线程」的运行时（Android ART）上会误报 true —— 于是每个 `withContext` 都新建一条平台线程，
 * 从 native 回调上下文（ThreadGroup 为 null）创建时 `Thread.start()` 直接 NPE。
 */
class VirtualThreadDispatcherTest {

    @Test
    fun probeMatchesActualThreadNature() {
        val executor = VirtualThreadDispatcher.newVirtualThreadExecutor()
        val started = try {
            executor.submit(Callable { Thread.currentThread() }).get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
        assertEquals(
            VirtualThreadDispatcher.isSupported,
            VirtualThreadDispatcher.isVirtualThread(started),
            "isSupported=${VirtualThreadDispatcher.isSupported}，但执行器实际产出的是 " +
                "virtual=${VirtualThreadDispatcher.isVirtualThread(started)} 的线程",
        )
    }

    /** 复刻崩溃现场：`withContext(dispatcher) { File.readText() }`（见 JvmPluginRepositoryIO.cachedCatalog）。 */
    @Test
    fun dispatcherRunsBlockingFileIoOffCallerThread() {
        val file = File.createTempFile("cp-virtual-dispatcher", ".tmp")
        file.writeText("catalog")
        try {
            val caller = Thread.currentThread()
            var worker: Thread? = null
            val text = runBlocking {
                withContext(VirtualThreadDispatcher.dispatcher) {
                    worker = Thread.currentThread()
                    file.readText()
                }
            }
            assertEquals("catalog", text)
            assertTrue(worker != null && worker !== caller, "withContext 必须真的切到别的线程，不能原地执行")
        } finally {
            file.delete()
        }
    }

    @Test
    fun runOnVirtualThreadExecutesBlockWithGivenName() {
        val done = CountDownLatch(1)
        val thread = VirtualThreadDispatcher.runOnVirtualThread("cp-test-worker") { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS), "runOnVirtualThread 没有执行 block")
        assertEquals("cp-test-worker", thread.name)
        assertEquals(
            VirtualThreadDispatcher.isSupported,
            VirtualThreadDispatcher.isVirtualThread(thread),
            "降级路径产出的线程性质必须与 isSupported 一致",
        )
    }
}
