package cp.player.sdk

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Captures immutable capabilities once per session, not once per track/request.
 * Failures and cancellation are not cached. The host owns draining and close.
 */
class SessionMusicProvider(private val delegate: MusicProvider) : MusicProvider by delegate {
    private val mutex = Mutex()
    private var snapshot: Capabilities? = null

    override suspend fun capabilities(): Capabilities = mutex.withLock {
        snapshot ?: delegate.capabilities().let { result ->
            if (result.supported.size > MAX_CAPABILITIES ||
                result.supported.any { it.isBlank() || it.length > 128 }) {
                throw ProviderException(Failure(FailureCategory.INVALID_RESPONSE, "Invalid capabilities"))
            }
            Capabilities(result.supported.toList()).also { snapshot = it }
        }
    }

    override suspend fun getMany(request: BatchRequest): TrackBatch =
        if (Capability.BATCH in capabilities().supported) delegate.getMany(request)
        else super<MusicProvider>.getMany(request)
}
