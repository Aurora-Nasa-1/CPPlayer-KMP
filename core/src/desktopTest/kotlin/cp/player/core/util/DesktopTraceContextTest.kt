package cp.player.core.util

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopTraceContextTest {

    @Test
    fun testDefaultUnbound() {
        assertNull(DesktopTraceContext.currentTraceId())
        assertNull(DesktopTraceContext.currentCaller())
        assertNull(DesktopTraceContext.currentOperation())
    }

    @Test
    fun testWithTraceSync() {
        val customTrace = "custom-trace-001"
        DesktopTraceContext.withTrace(
            traceId = customTrace,
            caller = "UnitTest",
            operation = "testSync",
        ) {
            assertEquals(customTrace, DesktopTraceContext.currentTraceId())
            assertEquals("UnitTest", DesktopTraceContext.currentCaller())
            assertEquals("testSync", DesktopTraceContext.currentOperation())
        }

        assertNull(DesktopTraceContext.currentTraceId())
        assertNull(DesktopTraceContext.currentCaller())
        assertNull(DesktopTraceContext.currentOperation())
    }

    @Test
    fun testWithTraceSuspend() = runBlocking {
        val traceId = DesktopTraceContext.newTraceId("async")
        assertTrue(traceId.startsWith("async-"))

        DesktopTraceContext.withTraceSuspend(
            traceId = traceId,
            caller = "AsyncCaller",
            operation = "testSuspend",
        ) {
            assertEquals(traceId, DesktopTraceContext.currentTraceId())
            assertEquals("AsyncCaller", DesktopTraceContext.currentCaller())
            assertEquals("testSuspend", DesktopTraceContext.currentOperation())
        }

        assertNull(DesktopTraceContext.currentTraceId())
    }

    @Test
    fun testVirtualThreadDispatcherFeatures() {
        val executor = VirtualThreadDispatcher.newVirtualThreadExecutor()
        assertNotNull(executor)
        try {
            val future = executor.submit<String> { "vthread-task-done" }
            assertEquals("vthread-task-done", future.get())
        } finally {
            executor.shutdown()
        }
    }
}
