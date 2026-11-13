package cp.player.core.lyricpush

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class KeepAliveBindingControllerTest {
    @Test
    fun repeatedActivationDuringDelayedConnectionBindsOnlyOnce() = runTest {
        val backend = FakeBackend(deliverStickyCallbackSynchronously = true)
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val controller = KeepAliveBindingController(scope, backend, retryDelayMs = 100)
        try {
            controller.activate()
            controller.activate()
            controller.activate()

            assertEquals(1, backend.bindCalls, "a pending asynchronous bind must be coalesced")

            // No connection callback has arrived yet; repeated activation must still be coalesced.
            controller.activate()
            assertEquals(1, backend.bindCalls)

            controller.onServiceConnected()
            controller.activate()
            assertEquals(1, backend.bindCalls, "a connected service must not bind again")
        } finally {
            controller.deactivate()
            runCurrent()
            scope.cancel()
        }
    }

    @Test
    fun bindFailureSchedulesMultipleRetryRoundsUntilSuccess() = runTest {
        val backend = FakeBackend(failuresRemaining = 2)
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val controller = KeepAliveBindingController(scope, backend, retryDelayMs = 100)
        try {
            controller.activate()
            assertEquals(1, backend.bindCalls)

            advanceTimeBy(100)
            runCurrent()
            assertEquals(2, backend.bindCalls, "the first retry should run")

            advanceTimeBy(100)
            runCurrent()
            assertEquals(3, backend.bindCalls, "a second failure must schedule another retry")
        } finally {
            controller.deactivate()
            runCurrent()
            scope.cancel()
        }
    }

    @Test
    fun deactivateBlocksLateCallbacksAndRetriesButCanBeEnabledAgain() = runTest {
        val backend = FakeBackend(failuresRemaining = 1)
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val controller = KeepAliveBindingController(scope, backend, retryDelayMs = 100)
        try {
            controller.activate()
            assertEquals(1, backend.bindCalls)
            val staleListener = backend.registeredListener

            controller.deactivate()
            runCurrent()
            assertEquals(1, backend.removeListenerCalls)
            assertEquals(1, backend.unbindCalls)

            staleListener?.invoke()
            controller.onServiceConnected()
            controller.onServiceDisconnected()
            advanceTimeBy(500)
            runCurrent()
            assertEquals(1, backend.bindCalls, "late callbacks and canceled retries must not rebind")

            backend.failuresRemaining = 0
            controller.activate()
            assertEquals(2, backend.bindCalls, "explicit re-enable should create a new binding")
            assertEquals(2, backend.addListenerCalls, "the removed listener should be registered again")
        } finally {
            controller.deactivate()
            runCurrent()
            scope.cancel()
        }
    }

    @Test
    fun queuedDeactivationDoesNotUndoALaterActivation() = runTest {
        val backend = FakeBackend()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val controller = KeepAliveBindingController(scope, backend, retryDelayMs = 100)
        try {
            controller.activate()
            runCurrent()
            assertEquals(1, backend.bindCalls)

            // Both operations are queued to the same dispatcher; activation wins by current state.
            controller.deactivate()
            controller.activate()
            runCurrent()

            assertEquals(1, backend.bindCalls)
            assertEquals(0, backend.unbindCalls)
            assertEquals(1, backend.addListenerCalls)
        } finally {
            controller.deactivate()
            runCurrent()
            scope.cancel()
        }
    }

    private class FakeBackend(
        private val deliverStickyCallbackSynchronously: Boolean = false,
        var failuresRemaining: Int = 0,
    ) : KeepAliveBindingBackend {
        var bindCalls = 0
        var unbindCalls = 0
        var addListenerCalls = 0
        var removeListenerCalls = 0
        var registeredListener: (() -> Unit)? = null

        override fun isInstalled(): Boolean = true

        override fun hasPermission(): Boolean = true

        override fun addBinderListener(listener: () -> Unit): Boolean {
            addListenerCalls += 1
            registeredListener = listener
            if (deliverStickyCallbackSynchronously) listener()
            return true
        }

        override fun removeBinderListener(): Boolean {
            removeListenerCalls += 1
            registeredListener = null
            return true
        }

        override fun bindUserService() {
            bindCalls += 1
            if (failuresRemaining > 0) {
                failuresRemaining -= 1
                throw IllegalStateException("simulated bind failure")
            }
        }

        override fun unbindUserService() {
            unbindCalls += 1
        }
    }
}
