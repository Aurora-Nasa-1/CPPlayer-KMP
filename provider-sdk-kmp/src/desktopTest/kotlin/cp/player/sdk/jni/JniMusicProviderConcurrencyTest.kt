package cp.player.sdk.jni

import cp.player.sdk.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 首页会并发拉多个接口。历史上所有 invoke 都排在同一会话的单线程队列上，
 * 7 个 150ms 的请求会变成 7×150ms 顺延（诊断页平均响应破 1s）。
 */
class JniMusicProviderConcurrencyTest {
    @Test
    fun `pooled sessions keep concurrent calls off the same serial queue`() = runBlocking {
        val sessions = SlowSessionFactory(150)
        val provider = JniMusicProvider.pooled { sessions.open() }
        try {
            val started = System.nanoTime()
            (0 until 7).map { async { provider.search(SearchRequest("q$it")) } }.awaitAll()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertTrue(sessions.opened.get() > 1, "并发调用应摊到多个会话上，实际只开了 ${sessions.opened.get()} 个")
            assertTrue(elapsedMs < 7 * 150L, "7 次并发请求耗时 ${elapsedMs}ms，仍在单队列上串行")
        } finally {
            provider.close()
        }
    }

    @Test
    fun `one session never runs two invokes at once`() = runBlocking {
        val sessions = SlowSessionFactory(80)
        val provider = JniMusicProvider.pooled(maxConcurrency = 4) { sessions.open() }
        try {
            (0 until 6).map { async { provider.search(SearchRequest("q$it")) } }.awaitAll()
        } finally {
            provider.close()
        }
        assertEquals(1, sessions.maxConcurrentPerSession.get(), "同一会话被并发进入")
    }

    private class SlowSessionFactory(private val delayMs: Long) {
        val opened = AtomicInteger()
        val maxConcurrentPerSession = AtomicInteger()

        fun open(): JniProviderSession {
            opened.incrementAndGet()
            val running = AtomicInteger()
            return object : JniProviderSession {
                override fun invoke(operation: Int, request: Any): Any {
                    val now = running.incrementAndGet()
                    maxConcurrentPerSession.updateAndGet { maxOf(it, now) }
                    try {
                        Thread.sleep(delayMs)
                        return when (operation) {
                            ProviderProtocol.CAPABILITIES -> Capabilities(listOf(Capability.SEARCH))
                            ProviderProtocol.SEARCH -> TrackPage()
                            else -> error("Unexpected JNI operation $operation")
                        }
                    } finally {
                        running.decrementAndGet()
                    }
                }

                override fun cancel() = Unit
                override fun close() = Unit
            }
        }
    }
}
