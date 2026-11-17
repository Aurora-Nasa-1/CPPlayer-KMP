package cp.player.core.lyricpush

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ReentrantCallGuardTest {
    @Test
    fun synchronousStickyCallbackCannotReenterBindingAndGuardIsReleasedAfterward() {
        val guard = ReentrantCallGuard()
        var calls = 0
        var nestedCallAccepted = true
        lateinit var ensureBound: () -> Boolean

        ensureBound = {
            guard.run {
                calls++
                if (calls == 1) {
                    // Models addBinderReceivedListenerSticky invoking its callback inline.
                    nestedCallAccepted = ensureBound()
                }
            }
        }

        assertTrue(ensureBound())
        assertEquals(1, calls)
        assertFalse(nestedCallAccepted)

        // The guard is not left stuck after the outer ensureBound finishes.
        assertTrue(ensureBound())
        assertEquals(2, calls)
    }

    @Test
    fun guardIsReleasedWhenOperationThrows() {
        val guard = ReentrantCallGuard()

        assertFailsWith<IllegalStateException> {
            guard.run { error("simulated registration failure") }
        }
        assertTrue(guard.run { })
    }
}
