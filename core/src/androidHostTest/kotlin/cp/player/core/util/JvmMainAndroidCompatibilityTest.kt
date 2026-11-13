package cp.player.core.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], manifest = Config.NONE)
class JvmMainAndroidCompatibilityTest {

    @Test
    fun scopedValueUsesTheAndroidFallback() {
        assertFalse(DesktopScopedValue.isNativeSupported)

        val key = DesktopScopedValue.create<String>("api24")
        assertFalse(key.isBound())
        key.runWith("fallback") {
            assertTrue(key.isBound())
            assertEquals("fallback", key.get())
        }
        assertFalse(key.isBound())
    }

    @Test
    fun virtualThreadDispatcherUsesAPlatformThreadFallback() {
        assertFalse(VirtualThreadDispatcher.isSupported)

        val completed = CountDownLatch(1)
        val thread = VirtualThreadDispatcher.runOnVirtualThread("api24-fallback") {
            completed.countDown()
        }

        assertTrue(completed.await(5, TimeUnit.SECONDS))
        thread.join(5_000)
        assertFalse(thread.isAlive)
        assertFalse(VirtualThreadDispatcher.isVirtualThread(thread))
    }
}
