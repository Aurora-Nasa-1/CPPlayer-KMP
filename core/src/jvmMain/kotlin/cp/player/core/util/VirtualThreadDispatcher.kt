package cp.player.core.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
 * Desktop JVM 跑在 JBR 21/25 上，拥有完整的 Virtual Threads 支持；Android ART 至今没有 Loom。
 * 因此这里做两件事：
 * - **按平台短路**：[PlatformInfo.isAndroid] 为真时一律用 [Dispatchers.IO]。本文件属于 `jvmMain`，
 *   而 `androidMain.dependsOn(jvmMain)` —— Android 会原样继承这里的每一行，不能只靠反射探测兜底。
 * - **探测必须真的起一条线程**：见 [isSupported]。
 *
 * ## ⚠️ 为什么"探测方法是否存在"是错的（真实事故）
 * 旧实现只判断 `Executors.newVirtualThreadPerTaskExecutor` 这个方法**在不在**。但
 * **方法存在 ≠ 产出的是虚拟线程**：Android 上该方法可见，产出的却是普通平台线程。
 * 于是 `jvmMain` 里所有 `withContext(VirtualThreadDispatcher.dispatcher)` 的函数体都被搬到
 * 一条**每个任务新建的**平台线程上执行，而这条线程是从 native 回调线程（JNI / Oboe / Rust
 * 回调，`ThreadGroup` 为 null）的上下文里创建的 —— `Thread.start()` 立刻在 `ThreadGroup.add`
 * 上抛 NPE：`Attempt to invoke virtual method 'void java.lang.ThreadGroup.add(java.lang.Thread)'
 * on a null object reference`（Sentry CPPLAYER-KMP-2 / issue #168，v1.8.0 起量，命中路径是
 * `PluginRepositoryService.loadCached` → `JvmPluginRepositoryIO.cachedCatalog`，启动即崩）。
 *
 * 所以现在：Android 直接不走这条路；其余运行时也要**真的提交一次任务、确认线程 `isVirtual`**，
 * 探测失败一律按"不支持"处理（fail closed）。
 */
object VirtualThreadDispatcher {

    /** 探测超时：正常实现立刻有结果；卡住就说明这套 Loom 不能用。 */
    private const val PROBE_TIMEOUT_MILLIS = 2_000L

    private val platformThreadSequence = AtomicInteger()

    /** 缓存的可用 ThreadGroup，见 [fallbackThreadGroup]。 */
    @Volatile
    private var cachedThreadGroup: ThreadGroup? = null

    /**
     * 类加载期一次性初始化的 Loom MethodHandle 缓存。
     *
     * 用 MethodHandle 替代 `Method.invoke`：
     *   - 避免每次 [runOnVirtualThread] / [isVirtualThread] 调用都走 `Class.forName` + `getMethod`；
     *   - MethodHandle 经 HotSpot C2 内联后开销接近直接调用，比反射快约 10×；
     *   - Android 上 Loom 类不存在，`init` 块里的所有 forName 失败 → 全部 null，安全降级。
     */
    private object LoomHandles {
        val newVirtualThreadExecutor: MethodHandle?
        val ofVirtual: MethodHandle?
        /** OfVirtual.name(String) —— 绑定在**公开接口**上，不是运行时的包私有实现类。 */
        val builderName: MethodHandle?
        val builderStart: MethodHandle?
        val isVirtual: MethodHandle?

        init {
            val lookup = MethodHandles.publicLookup()
            var newVTExec: MethodHandle? = null
            var ofV: MethodHandle? = null
            var bName: MethodHandle? = null
            var bStart: MethodHandle? = null
            var isVirt: MethodHandle? = null
            runCatching {
                // Executors.newVirtualThreadPerTaskExecutor()
                newVTExec = lookup.findStatic(
                    Executors::class.java,
                    "newVirtualThreadPerTaskExecutor",
                    MethodType.methodType(ExecutorService::class.java),
                )
                // Thread.ofVirtual() -> Thread.Builder.OfVirtual
                val ofVirtualClass = Class.forName("java.lang.Thread\$Builder\$OfVirtual")
                ofV = lookup.findStatic(
                    Thread::class.java, "ofVirtual",
                    MethodType.methodType(ofVirtualClass),
                )
                // OfVirtual.name(String) -> OfVirtual
                // ⚠️ 必须从公开接口 OfVirtual 上取：运行时类型是包私有的
                // ThreadBuilders$VirtualThreadBuilder，对它 lookup 会抛 IllegalAccessException。
                bName = lookup.findVirtual(
                    ofVirtualClass, "name",
                    MethodType.methodType(ofVirtualClass, String::class.java),
                )
                // Thread.Builder.start(Runnable) -> Thread
                val builderClass = Class.forName("java.lang.Thread\$Builder")
                bStart = lookup.findVirtual(
                    builderClass, "start",
                    MethodType.methodType(Thread::class.java, Runnable::class.java),
                )
                // Thread.isVirtual() -> boolean
                isVirt = lookup.findVirtual(
                    Thread::class.java, "isVirtual",
                    MethodType.methodType(Boolean::class.javaPrimitiveType),
                )
            }
            newVirtualThreadExecutor = newVTExec
            ofVirtual = ofV
            builderName = bName
            builderStart = bStart
            isVirtual = isVirt
        }
    }

    /** 检查当前运行时是否**真的**能用虚拟线程（Java 21+ / Loom，且不是半成品实现）。 */
    val isSupported: Boolean by lazy { !PlatformInfo.isAndroid && probeVirtualThread() }

    /**
     * 真探针：走一遍和真实使用完全相同的路径 —— 建执行器、提交任务、拿到线程、确认它是虚拟线程。
     * 任何一步抛异常（包括 `Thread.start()` 里的 `ThreadGroup.add` NPE）都判为不支持。
     */
    private fun probeVirtualThread(): Boolean {
        val executor = newVirtualThreadExecutorOrNull() ?: return false
        return try {
            executor.submit(Callable { currentThreadIsVirtual() })
                .get(PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            false
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * 面向并发阻塞 I/O 的虚拟线程协程调度器。
     * 若当前运行时不支持（Android / 旧版环境 / 半成品 Loom），安全回退至 [Dispatchers.IO]。
     */
    val dispatcher: CoroutineDispatcher by lazy {
        val executor = if (isSupported) newVirtualThreadExecutorOrNull() else null
        if (executor == null) {
            Dispatchers.IO
        } else {
            runCatching { executor.asCoroutineDispatcher() }.getOrElse {
                executor.shutdownNow()
                Dispatchers.IO
            }
        }
    }

    /**
     * 创建基于虚拟线程的任务执行器（每个任务独占一个虚拟线程）。
     * 若当前运行时不支持虚拟线程，降级为带名字的守护线程池。
     */
    fun newVirtualThreadExecutor(): ExecutorService {
        if (isSupported) newVirtualThreadExecutorOrNull()?.let { return it }
        return Executors.newCachedThreadPool(platformThreadFactory("cpplayer-worker"))
    }

    /**
     * 在虚拟线程上启动一个独立运行的任务。
     * 若当前运行时不支持虚拟线程，平滑降级为守护平台线程。
     */
    fun runOnVirtualThread(name: String? = null, block: () -> Unit): Thread {
        if (isSupported) {
            val ofV = LoomHandles.ofVirtual
            val bStart = LoomHandles.builderStart
            if (ofV != null && bStart != null) {
                try {
                    var builder: Any = ofV.invoke()
                    if (name != null) {
                        LoomHandles.builderName?.let { bName ->
                            builder = bName.invoke(builder, name)
                        }
                    }
                    return bStart.invoke(builder, Runnable { block() }) as Thread
                } catch (_: Throwable) {
                    // fall through to platform thread
                }
            }
        }
        return Thread(fallbackThreadGroup(), block, name ?: "virtual-thread-fallback").apply {
            isDaemon = true
            start()
        }
    }

    /** 检查给定线程是否为虚拟线程。 */
    fun isVirtualThread(thread: Thread = Thread.currentThread()): Boolean {
        return try {
            LoomHandles.isVirtual?.invoke(thread) as? Boolean ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun newVirtualThreadExecutorOrNull(): ExecutorService? = try {
        LoomHandles.newVirtualThreadExecutor?.invoke() as? ExecutorService
    } catch (_: Throwable) {
        null
    }

    private fun currentThreadIsVirtual(): Boolean = isVirtualThread()

    private fun platformThreadFactory(prefix: String) = ThreadFactory { runnable ->
        val group = fallbackThreadGroup()
        Thread(group, runnable, "$prefix-${platformThreadSequence.incrementAndGet()}").apply { isDaemon = true }
    }

    /**
     * 建线程时要显式带上的 [ThreadGroup]。
     *
     * `Executors.defaultThreadFactory()` 会在**构造它的那个线程**上抓
     * `Thread.currentThread().threadGroup`；一旦工厂是从 native 回调线程（JNI / Oboe，group 为 null）
     * 里构造的，它此后产出的**每一条**线程都带 null 组，`start()` 必炸在 `ThreadGroup.add`。
     * 这里改成显式传组；当前线程没有组时，退而从任意存活线程里找一个有效的并缓存下来。
     */
    private fun fallbackThreadGroup(): ThreadGroup? {
        cachedThreadGroup?.let { return it }
        val group = Thread.currentThread().threadGroup
            ?: Thread.getAllStackTraces().keys.firstNotNullOfOrNull { it.threadGroup }
        if (group != null) cachedThreadGroup = group
        return group
    }
}
