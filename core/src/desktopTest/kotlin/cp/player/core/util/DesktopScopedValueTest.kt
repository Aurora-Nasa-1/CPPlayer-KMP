package cp.player.core.util

import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopScopedValueTest {

    private val KEY_STRING = DesktopScopedValue.create<String>("testKeyString")
    private val KEY_INT = DesktopScopedValue.create<Int>("testKeyInt")

    @Test
    fun testUnboundBehavior() {
        assertFalse(KEY_STRING.isBound())
        assertNull(KEY_STRING.getOrNull())
        assertEquals("default", KEY_STRING.orElse("default"))
        assertFailsWith<NoSuchElementException> {
            KEY_STRING.get()
        }
    }

    @Test
    fun testSingleBindingAndUnbind() {
        assertFalse(KEY_STRING.isBound())

        val result = KEY_STRING.runWith("hello-scoped") {
            assertTrue(KEY_STRING.isBound())
            assertEquals("hello-scoped", KEY_STRING.get())
            assertEquals("hello-scoped", KEY_STRING.getOrNull())
            "returned-val"
        }

        assertEquals("returned-val", result)
        assertFalse(KEY_STRING.isBound())
        assertNull(KEY_STRING.getOrNull())
    }

    @Test
    fun testNestedShadowing() {
        KEY_STRING.runWith("outer") {
            assertEquals("outer", KEY_STRING.get())

            KEY_STRING.runWith("inner") {
                assertEquals("inner", KEY_STRING.get())
            }

            assertEquals("outer", KEY_STRING.get())
        }
        assertFalse(KEY_STRING.isBound())
    }

    @Test
    fun testMultiValueBinding() {
        assertFalse(KEY_STRING.isBound())
        assertFalse(KEY_INT.isBound())

        DesktopScopedContext.runWith(
            KEY_STRING to "user-42",
            KEY_INT to 42,
        ) {
            assertTrue(KEY_STRING.isBound())
            assertTrue(KEY_INT.isBound())
            assertEquals("user-42", KEY_STRING.get())
            assertEquals(42, KEY_INT.get())
        }

        assertFalse(KEY_STRING.isBound())
        assertFalse(KEY_INT.isBound())
    }

    @Test
    fun testExceptionUnwrapping() {
        val ex = assertFailsWith<IllegalArgumentException> {
            KEY_STRING.runWith("faulty") {
                throw IllegalArgumentException("Expected failure inside scope")
            }
        }
        assertEquals("Expected failure inside scope", ex.message)
        assertFalse(KEY_STRING.isBound())
    }

    @Test
    fun testExecutionOnVirtualThread() {
        val executed = AtomicBoolean(false)
        val valueOnThread = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)

        KEY_STRING.runWith("vthread-bound") {
            VirtualThreadDispatcher.runOnVirtualThread("test-worker") {
                // 在虚拟线程中直接执行或者在新作用域中运行
                KEY_STRING.runWith("vthread-child") {
                    valueOnThread.set(KEY_STRING.get())
                    executed.set(true)
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertTrue(executed.get())
        assertEquals("vthread-child", valueOnThread.get())
    }

    @Test
    fun testSuspendingCoroutineContext() = runBlocking {
        assertFalse(KEY_STRING.isBound())

        KEY_STRING.withValue("suspend-value") {
            assertEquals("suspend-value", KEY_STRING.get())
        }

        assertFalse(KEY_STRING.isBound())
    }
}
