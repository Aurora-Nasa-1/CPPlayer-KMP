package cp.player.core.util.stable

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopStableValueTest {

    @Test
    fun testBasicUnsetValueLifecycle() {
        val stable = DesktopStableValue.of<String>()
        assertFalse(stable.isSet(), "Initial stable value must not be set")
        assertEquals("fallback", stable.orElse("fallback"))

        assertFailsWith<IllegalStateException> {
            stable.orElseThrow()
        }

        val computed = stable.orElseSet { "computed_value" }
        assertEquals("computed_value", computed)
        assertTrue(stable.isSet(), "Must be set after orElseSet")
        assertEquals("computed_value", stable.orElseThrow())
        assertEquals("computed_value", stable.orElse("different"))

        // 第二次 orElseSet 必须直接返回已绑定的值且不重新执行 lambda
        var lambdaRan = false
        val second = stable.orElseSet {
            lambdaRan = true
            "ignored"
        }
        assertEquals("computed_value", second)
        assertFalse(lambdaRan, "Second orElseSet must not evaluate factory lambda")

        // 已经设置后再 setOrThrow 必须报错
        assertFailsWith<IllegalStateException> {
            stable.setOrThrow("conflict")
        }
        assertFalse(stable.trySet("conflict"))
    }

    @Test
    fun testInitialValueBinding() {
        val stable = DesktopStableValue.of(42)
        assertTrue(stable.isSet())
        assertEquals(42, stable.orElse(0))
        assertEquals(42, stable.orElseThrow())
        assertFalse(stable.trySet(99))
    }

    @Test
    fun testConcurrentSingleAssignment() {
        val threadCount = 16
        val executor = Executors.newFixedThreadPool(threadCount)
        val stable = DesktopStableValue.of<Int>()
        val evalCount = AtomicInteger(0)

        val tasks = (0 until threadCount).map {
            Callable {
                stable.orElseSet {
                    evalCount.incrementAndGet()
                    Thread.sleep(10) // 模拟计算耗时
                    10086
                }
            }
        }

        val futures = executor.invokeAll(tasks)
        for (f in futures) {
            assertEquals(10086, f.get())
        }
        executor.shutdown()

        assertEquals(1, evalCount.get(), "Concurrent orElseSet must execute factory exactly once")
        assertTrue(stable.isSet())
        assertEquals(10086, stable.orElseThrow())
    }

    @Test
    fun testSupplierFactory() {
        var count = 0
        val supplier = DesktopStableValue.supplier {
            count++
            "result-$count"
        }

        assertEquals(0, count)
        val val1 = supplier()
        assertEquals("result-1", val1)
        assertEquals(1, count)

        val val2 = supplier()
        assertEquals("result-1", val2)
        assertEquals(1, count, "Supplier must cache value and not invoke lambda again")
    }

    @Test
    fun testStableList() {
        val list = DesktopStableValue.list(5) { it * 10 }
        assertEquals(5, list.size)
        assertEquals(0, list[0])
        assertEquals(20, list[2])
        assertEquals(40, list[4])

        assertFailsWith<IndexOutOfBoundsException> {
            list[5]
        }
    }
}
