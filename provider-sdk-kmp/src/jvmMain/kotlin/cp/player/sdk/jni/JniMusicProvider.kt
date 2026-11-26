package cp.player.sdk.jni

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One native instance per immutable ProviderContext.
 * Implement these methods as JNI native methods in the provider's own JVM class.
 * invoke receives/returns SDK DTO objects. Throw ProviderException for business failures.
 */
interface JniProviderSession {
    /** Serialized on one owner thread. Do not retain JNIEnv or local refs after returning. */
    fun invoke(operation: Int, request: Any): Any
    /** Thread-safe, nonblocking signal to interrupt the current invoke; never free the session here. */
    fun cancel()
    /** Called once, on the owner thread, after all invoke/cancel calls have drained. */
    fun close()
}

/**
 * JNI transport shared by desktop JVM and Android; no worker process or business JSON.
 *
 * 一个 handle 只能串行调用（见 docs/dev/providers/NATIVE.md §8），但「一次只用一个 handle」
 * 不等于「整个音源一次只能跑一个请求」：首页会并发拉 7 个接口，单 handle 排队会把每个
 * 请求的耗时叠成 1+2+…+7 次上游往返（实测上游单请求 ~150ms ⇒ 平均响应破 1s）。
 * [pooled] 因此按需开最多 [MAX_CONCURRENCY] 个同上下文会话，每个会话仍严格单线程串行。
 */
class JniMusicProvider private constructor(
    openSession: (() -> JniProviderSession)?,
    firstSession: JniProviderSession?,
    maxConcurrency: Int,
) : JniProviderMethods() {
    constructor(session: JniProviderSession) : this(null, session, 1)

    private class Lane(val session: JniProviderSession) {
        var idleRetirement: Job? = null
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "provider-sdk-jni-${laneIds.incrementAndGet()}").apply { isDaemon = true }
        }
    }

    private val openSession = openSession
    private val permits = Semaphore(maxConcurrency.also { require(it in 1..MAX_CONCURRENCY) })
    private val lock = Any()
    private val idle = ArrayDeque<Lane>()
    private val lanes = mutableSetOf<Lane>()
    private val retirementScope = CoroutineScope(Dispatchers.Default)
    private val drained = CompletableDeferred<Unit>()
    private val closeMutex = Mutex()
    private var active = 0
    private var retiring = 0
    private var retirementFailure: Throwable? = null
    private var closed = false
    private var disposed = false

    init {
        require(firstSession != null || openSession != null) { "JNI provider needs a session source" }
        if (firstSession != null) {
            val lane = Lane(firstSession)
            idle.addLast(lane)
            // 预建会话也算在「待关闭」集合里，否则 close() 会遗漏它。
            lanes.add(lane)
        }
    }

    override suspend fun dispatch(operation: Int, request: Any): Any {
        check(!isClosed()) { "JNI provider closed" }
        val lane = acquireLane()
        try {
            return invokeOnLane(lane, operation, request)
        } finally {
            releaseLane(lane)
        }
    }

    private fun isClosed(): Boolean = synchronized(lock) { closed }

    private suspend fun acquireLane(): Lane {
        permits.acquire()
        return try {
            synchronized(lock) {
                check(!closed) { "JNI provider closed" }
                val lane = idle.removeLastOrNull() ?: openLaneLocked()
                lane.idleRetirement?.cancel()
                lane.idleRetirement = null
                lanes.add(lane)
                active++
                lane
            }
        } catch (error: Throwable) {
            permits.release()
            throw error
        }
    }

    private fun openLaneLocked(): Lane {
        val open = openSession ?: error("JNI provider has no session source")
        return try {
            Lane(open())
        } catch (error: Throwable) {
            throw IllegalStateException("Cannot open JNI provider session: ${error.message}", error)
        }
    }

    private fun releaseLane(lane: Lane) {
        val complete = synchronized(lock) {
            active--
            if (!closed) {
                idle.addLast(lane)
                if (openSession != null) {
                    lane.idleRetirement = retirementScope.launch {
                        delay(IDLE_LANE_TIMEOUT_MS)
                        retireIdleLane(lane, coroutineContext.job)
                    }
                }
            }
            closed && active == 0 && retiring == 0
        }
        permits.release()
        if (complete) drained.complete(Unit)
    }

    private fun retireIdleLane(lane: Lane, timer: Job) {
        synchronized(lock) {
            if (lane.idleRetirement !== timer) return
            lane.idleRetirement = null
            if (closed || idle.size <= WARM_IDLE_LANES || !permits.tryAcquire()) return
            // Keep a permit until close finishes to cap live native handles during a new burst.
            idle.remove(lane)
            lanes.remove(lane)
            retiring++
        }
        lane.executor.execute {
            try {
                lane.session.close()
            } catch (error: Throwable) {
                synchronized(lock) { if (retirementFailure == null) retirementFailure = error }
            } finally {
                lane.executor.shutdown()
                val complete = synchronized(lock) {
                    retiring--
                    closed && active == 0 && retiring == 0
                }
                permits.release()
                if (complete) drained.complete(Unit)
            }
        }
    }

    private suspend fun invokeOnLane(lane: Lane, operation: Int, request: Any): Any {
        val finished = CompletableDeferred<Unit>()
        val gate = Any()
        var running = false
        try {
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation {
                    synchronized(gate) {
                        if (running) lane.session.cancel()
                    }
                }
                lane.executor.execute {
                    try {
                        val execute = synchronized(gate) {
                            continuation.isActive.also { running = it }
                        }
                        if (execute) {
                            val result = lane.session.invoke(operation, request)
                            synchronized(gate) { running = false }
                            continuation.resume(result)
                        }
                    } catch (e: Throwable) {
                        synchronized(gate) { running = false }
                        continuation.resumeWithException(e)
                    } finally { finished.complete(Unit) }
                }
            }
        } finally {
            // Cancellation must signal native work immediately, then drain before releasing the call.
            withContext(NonCancellable) { finished.await() }
        }
    }

    override suspend fun close() {
        withContext(NonCancellable) {
            closeMutex.withLock {
                if (disposed) return@withLock
                disposed = true
                synchronized(lock) {
                    closed = true
                    if (active == 0 && retiring == 0) drained.complete(Unit)
                }
                retirementScope.cancel()
                drained.await()
                val closing = synchronized(lock) { lanes.toList().also { lanes.clear(); idle.clear() } }
                var failure = synchronized(lock) { retirementFailure }
                for (lane in closing) {
                    try {
                        lane.executor.submit { lane.session.close() }.get()
                    } catch (error: Throwable) {
                        val first = failure
                        if (first == null) failure = error
                        else if (first !== error) first.addSuppressed(error)
                    } finally {
                        lane.executor.shutdown()
                    }
                }
                failure?.let { throw it }
            }
        }
    }

    companion object {
        /** Matches the host's per-session call concurrency (`ProviderHost`). */
        const val MAX_CONCURRENCY = 8
        private const val WARM_IDLE_LANES = 2
        private const val IDLE_LANE_TIMEOUT_MS = 30_000L
        private val laneIds = AtomicInteger()

        /**
         * 同一 ProviderContext 下按需开多个会话。
         * 会话必须只由凭据/设置决定（宿主把可变状态放在自己的存储里），否则不要用本入口。
         */
        fun pooled(maxConcurrency: Int = MAX_CONCURRENCY, openSession: () -> JniProviderSession): JniMusicProvider =
            JniMusicProvider(openSession, null, maxConcurrency)
    }
}
