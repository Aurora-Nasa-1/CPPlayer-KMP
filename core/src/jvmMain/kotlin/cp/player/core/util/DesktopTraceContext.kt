package cp.player.core.util

import java.util.concurrent.atomic.AtomicLong

/**
 * 桌面端全链路追踪与诊断上下文（基于 [DesktopScopedValue] / JEP 506 实现）。
 *
 * 用于在虚拟线程、后台 I/O、JNI Worker 进程通信以及媒体管道执行期间传递不可变的请求标识与来源，
 * 无需逐层侵入函数形参，在作用域结束后自动解绑，100% 杜绝内存泄漏。
 */
object DesktopTraceContext {

    private val counter = AtomicLong(0)

    val TRACE_ID = DesktopScopedValue.create<String>("traceId")
    val CALLER = DesktopScopedValue.create<String>("caller")
    val OPERATION = DesktopScopedValue.create<String>("operation")

    /** 生成全局唯一的轻量 TraceId。 */
    fun newTraceId(prefix: String = "tr"): String {
        val seq = counter.incrementAndGet() and 0xFFFF
        val ts = System.currentTimeMillis() % 1_000_000
        return "$prefix-$ts-$seq"
    }

    /** 当前作用域内的 TraceId（未绑定则返回 null）。 */
    fun currentTraceId(): String? = TRACE_ID.getOrNull()

    /** 当前作用域内的调用方标识（未绑定则返回 null）。 */
    fun currentCaller(): String? = CALLER.getOrNull()

    /** 当前作用域内的操作名称（未绑定则返回 null）。 */
    fun currentOperation(): String? = OPERATION.getOrNull()

    /**
     * 在指定的追踪上下文中执行同步代码块 [block]。
     */
    fun <R> withTrace(
        traceId: String = newTraceId(),
        caller: String? = null,
        operation: String? = null,
        block: () -> R
    ): R {
        val bindings = mutableListOf<Pair<DesktopScopedValue<*>, Any?>>()
        bindings.add(TRACE_ID to traceId)
        if (caller != null) bindings.add(CALLER to caller)
        if (operation != null) bindings.add(OPERATION to operation)

        return DesktopScopedContext.runWith(*bindings.toTypedArray(), block = block)
    }

    /**
     * 在指定的追踪上下文中执行挂起代码块 [block]。
     */
    suspend fun <R> withTraceSuspend(
        traceId: String = newTraceId(),
        caller: String? = null,
        operation: String? = null,
        block: suspend () -> R
    ): R {
        val bindings = mutableListOf<Pair<DesktopScopedValue<*>, Any?>>()
        bindings.add(TRACE_ID to traceId)
        if (caller != null) bindings.add(CALLER to caller)
        if (operation != null) bindings.add(OPERATION to operation)

        return DesktopScopedContext.withBindings(*bindings.toTypedArray(), block = block)
    }
}
