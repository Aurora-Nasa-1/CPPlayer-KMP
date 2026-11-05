package cp.player.core.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 虚拟线程（Project Loom / Java 21+ Virtual Threads）自适应协程调度器。
 *
 * ## 为什么引入它
 * Kotlin 的 [Dispatchers.IO] 依赖平台操作系统线程池（默认最多 64 个 OS 线程）。
 * 每个平台线程在 64 位 JVM 上占用约 1MB 独立虚拟内存和栈空间。在桌面端进行大量并发 I/O
 * （例如本地媒体库几万首歌曲标签并发解析、高并发流媒体下载、歌词网络拉取、多源元数据检索）时，
 * 平台线程池会被快速占满，引起线程争用、上下文切换开销和内存膨胀。
 *
 * Java 21/25 引入的虚拟线程驻留在 JVM 堆上，单线程初始化开销仅几百字节，阻塞 I/O 时载体线程
 * （Carrier Thread）会自动挂载其他虚拟任务，吞吐量极大提高，特别适合桌面高吞吐 I/O 密集型场景。
 *
 * ## 跨运行时兼容
 * Desktop JVM 跑在 JBR 21/25 上，拥有完整的 Virtual Threads 支持；
 * 而 Android ART 运行时目前尚未搭载 Loom，因此通过特性嗅探（嗅探 `Executors.newVirtualThreadPerTaskExecutor`）：
 * - 支持虚拟线程的运行时：使用 `Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()`；
 * - 不支持的运行时（如 Android 或旧版环境）：安全降级为 [Dispatchers.IO]。
 */
object VirtualThreadDispatcher {

    /** 检查当前 JVM 运行时是否真正支持 Virtual Threads（Java 21+ / Loom）。 */
    val isSupported: Boolean by lazy {
        try {
            val method = Executors::class.java.getMethod("newVirtualThreadPerTaskExecutor")
            val executor = method.invoke(null) as? ExecutorService
            executor?.shutdown()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 面向并发阻塞 I/O 的虚拟线程协程调度器。
     * 若当前运行时不支持（如 Android ART），安全回退至 [Dispatchers.IO]。
     */
    val dispatcher: CoroutineDispatcher by lazy {
        if (isSupported) {
            try {
                newVirtualThreadExecutor().asCoroutineDispatcher()
            } catch (_: Throwable) {
                Dispatchers.IO
            }
        } else {
            Dispatchers.IO
        }
    }

    /**
     * 创建基于虚拟线程的任务执行器（每个任务独占一个虚拟线程）。
     * 若当前运行时不支持虚拟线程（如 Android ART），安全降级为普通的缓存线程池。
     */
    fun newVirtualThreadExecutor(): ExecutorService {
        return if (isSupported) {
            try {
                val method = Executors::class.java.getMethod("newVirtualThreadPerTaskExecutor")
                method.invoke(null) as ExecutorService
            } catch (_: Throwable) {
                Executors.newCachedThreadPool()
            }
        } else {
            Executors.newCachedThreadPool()
        }
    }

    /**
     * 在虚拟线程上启动一个独立运行的任务。
     * 若当前运行时不支持虚拟线程，平滑降级为守护平台线程。
     */
    fun runOnVirtualThread(name: String? = null, block: () -> Unit): Thread {
        if (isSupported) {
            try {
                val ofVirtualMethod = Thread::class.java.getMethod("ofVirtual")
                var builder = ofVirtualMethod.invoke(null)
                if (name != null) {
                    val nameMethod = builder.javaClass.getMethod("name", String::class.java)
                    builder = nameMethod.invoke(builder, name)
                }
                val startMethod = builder.javaClass.getMethod("start", Runnable::class.java)
                return startMethod.invoke(builder, Runnable { block() }) as Thread
            } catch (_: Throwable) {
                // fall through to platform thread
            }
        }
        return Thread(block, name ?: "virtual-thread-fallback").apply {
            isDaemon = true
            start()
        }
    }

    /** 检查给定线程是否为虚拟线程。 */
    fun isVirtualThread(thread: Thread = Thread.currentThread()): Boolean {
        return try {
            val method = Thread::class.java.getMethod("isVirtual")
            method.invoke(thread) as Boolean
        } catch (_: Throwable) {
            false
        }
    }
}
