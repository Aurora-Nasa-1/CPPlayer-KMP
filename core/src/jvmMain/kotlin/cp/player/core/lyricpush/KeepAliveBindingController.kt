package cp.player.core.lyricpush

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Platform boundary for Shizuku operations; production uses the Android API, tests use a fake. */
internal interface KeepAliveBindingBackend {
    fun isInstalled(): Boolean
    fun hasPermission(): Boolean
    fun addBinderListener(listener: () -> Unit): Boolean
    fun removeBinderListener(): Boolean
    fun bindUserService()
    fun unbindUserService()
}

/**
 * Serializes the lifecycle around an asynchronous user-service bind.
 *
 * All backend calls run in [scope] (the Android caller supplies Main.immediate). The enabled flag
 * changes synchronously so callbacks and pending retries cannot resurrect a disabled binding.
 */
internal class KeepAliveBindingController(
    private val scope: CoroutineScope,
    private val backend: KeepAliveBindingBackend,
    private val retryDelayMs: Long,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val ensureBoundGuard = ReentrantCallGuard()

    @Volatile
    private var enabled = false

    // The following state is confined to scope's dispatcher.
    private var binding = false
    private var bound = false
    private var listenerRegistered = false
    @Volatile
    private var registeringBinderListener = false
    private var retryJob: Job? = null

    /** Enable keepalive and request a bind. Safe to call repeatedly and from any thread. */
    fun activate() {
        enabled = true
        scope.launch { ensureBoundOnDispatcher() }
    }

    /** Called when Shizuku's binder is received or replaced. */
    fun onBinderReceived() {
        if (!enabled) return
        scope.launch {
            if (!enabled) return@launch
            if (binding || bound) runCatching(backend::unbindUserService).onFailure(onFailure)
            binding = false
            bound = false
            ensureBoundOnDispatcher()
        }
    }

    fun onServiceConnected() {
        scope.launch {
            if (!enabled) return@launch
            binding = false
            bound = true
            retryJob?.cancel()
            retryJob = null
        }
    }

    fun onServiceDisconnected() {
        scope.launch {
            if (!enabled) return@launch
            binding = false
            bound = false
            scheduleRetry()
        }
    }

    /** Disable immediately, then remove listeners and unbind on the configured dispatcher. */
    fun deactivate() {
        enabled = false
        scope.launch { deactivateOnDispatcher() }
    }

    private fun ensureBoundOnDispatcher() {
        if (!enabled) return
        ensureBoundGuard.run {
            if (!enabled) return@run
            if (!backend.isInstalled()) return@run

            if (!listenerRegistered) {
                registeringBinderListener = true
                listenerRegistered = try {
                    runCatching {
                        backend.addBinderListener {
                            if (!registeringBinderListener) onBinderReceived()
                        }
                    }.onFailure(onFailure).getOrDefault(false)
                } finally {
                    registeringBinderListener = false
                }
                if (!listenerRegistered) scheduleRetry()
            }

            if (!backend.hasPermission()) {
                scheduleRetry()
                return@run
            }
            // bindUserService is asynchronous: keep this gate set until the connection callback.
            if (bound || binding) return@run

            binding = true
            runCatching(backend::bindUserService).onFailure { error ->
                binding = false
                onFailure(error)
                scheduleRetry()
            }
        }
    }

    private fun deactivateOnDispatcher() {
        // A queued deactivation is stale if a later activate() has already re-enabled keepalive.
        if (enabled) return
        retryJob?.cancel()
        retryJob = null
        binding = false
        bound = false

        if (listenerRegistered) {
            val removed = runCatching(backend::removeBinderListener)
                .onFailure(onFailure)
                .getOrDefault(false)
            if (removed) listenerRegistered = false
        }
        runCatching(backend::unbindUserService).onFailure(onFailure)
    }

    private fun scheduleRetry() {
        if (!enabled || retryJob?.isActive == true) return
        retryJob = scope.launch {
            delay(retryDelayMs)
            // Clear before retrying; otherwise a failure during this retry sees the still-active
            // job and cannot schedule the next round.
            retryJob = null
            if (enabled) ensureBoundOnDispatcher()
        }
    }
}
