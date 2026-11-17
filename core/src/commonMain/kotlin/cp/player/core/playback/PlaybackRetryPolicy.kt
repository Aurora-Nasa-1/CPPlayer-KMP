package cp.player.core.playback

import cp.player.core.BackendResult
import cp.player.sdk.FailureCategory
import cp.player.sdk.ProviderException
import kotlinx.coroutines.delay

/** Bounded retry policy shared by playback URL and metadata resolution. */
internal object PlaybackRetryPolicy {
    private val backoffMs = listOf(300L, 900L)

    fun retryDelayMs(retryNumber: Int): Long = backoffMs.getOrElse(retryNumber - 1) { backoffMs.last() }

    /** Executes at most three attempts; cancellation is deliberately allowed to propagate. */
    suspend fun <T> retryTransient(
        isCurrent: () -> Boolean,
        request: suspend () -> BackendResult<T>,
    ): BackendResult<T> {
        var result = request()
        for (waitMs in backoffMs) {
            if (!isCurrent() || !isTransient(result)) return result
            delay(waitMs)
            if (!isCurrent()) return result
            result = request()
        }
        return result
    }

    fun isTransient(result: BackendResult<*>): Boolean = when (result) {
        is BackendResult.Error -> isTransient(result.message, result.cause)
        is BackendResult.Success, is BackendResult.Unsupported -> false
    }

    fun isTransient(message: String?, cause: Throwable? = null): Boolean {
        var current = cause
        repeat(8) {
            val error = current ?: return@repeat
            val category = (error as? ProviderException)?.failure?.category
            if (category == FailureCategory.NETWORK ||
                category == FailureCategory.TIMEOUT ||
                category == FailureCategory.RATE_LIMIT
            ) return true
            if (matchesTransientText(error::class.simpleName) || matchesTransientText(error.message)) return true
            current = error.cause
        }
        return matchesTransientText(message)
    }

    private fun matchesTransientText(value: String?): Boolean {
        val text = value?.lowercase().orEmpty()
        if (Regex("(?:response code|http(?: status)?):?\\s*5\\d\\d").containsMatchIn(text)) return true
        return listOf(
            "network", "timeout", "timed out", "connection", "connect", "socket",
            "unknownhost", "dns", "i/o", "ioexception", "temporar", "rate limit",
            "too many requests", "http 502", "http 503", "http 504", "bad gateway",
            "service unavailable", "gateway timeout",
        ).any(text::contains)
    }
}
