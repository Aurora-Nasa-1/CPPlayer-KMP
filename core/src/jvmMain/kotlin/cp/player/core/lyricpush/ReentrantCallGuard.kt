package cp.player.core.lyricpush

import java.util.concurrent.atomic.AtomicBoolean

/** Prevents synchronous callbacks from re-entering the operation that registered them. */
internal class ReentrantCallGuard {
    private val inProgress = AtomicBoolean(false)

    fun run(block: () -> Unit): Boolean {
        if (!inProgress.compareAndSet(false, true)) return false
        try {
            block()
            return true
        } finally {
            inProgress.set(false)
        }
    }
}
