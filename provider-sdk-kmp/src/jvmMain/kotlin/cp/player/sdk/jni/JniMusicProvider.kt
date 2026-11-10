package cp.player.sdk.jni

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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

/** JNI transport shared by desktop JVM and Android; no worker process or business JSON. */
class JniMusicProvider(private val session: JniProviderSession) : JniProviderMethods() {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "provider-sdk-jni").apply { isDaemon = true }
    }
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)

    override suspend fun dispatch(operation: Int, request: Any): Any = mutex.withLock {
        check(!closed.get()) { "JNI provider closed" }
        val drained = CompletableDeferred<Unit>()
        val gate = Any()
        var running = false
        try {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation {
                    synchronized(gate) {
                        if (running) session.cancel()
                    }
                }
                executor.execute {
                    try {
                        val execute = synchronized(gate) {
                            continuation.isActive.also { running = it }
                        }
                        if (execute) {
                            val result = session.invoke(operation, request)
                            synchronized(gate) { running = false }
                            continuation.resume(result)
                        }
                    } catch (e: Throwable) {
                        synchronized(gate) { running = false }
                        continuation.resumeWithException(e)
                    } finally { drained.complete(Unit) }
                }
            }
        } finally {
            // Cancellation must signal native work immediately, then drain before releasing the call.
            withContext(NonCancellable) { drained.await() }
        }
    }

    override suspend fun close() {
        withContext(NonCancellable) {
            mutex.withLock {
                if (closed.compareAndSet(false, true)) {
                    try {
                        suspendCancellableCoroutine<Unit> { continuation ->
                            executor.execute {
                                try { session.close(); continuation.resume(Unit) }
                                catch (e: Throwable) { continuation.resumeWithException(e) }
                            }
                        }
                    } finally { executor.shutdown() }
                }
            }
        }
    }
}
