package cp.player.core.provider.typed

import cp.player.core.provider.BackendProvider
import cp.player.sdk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface ManagedProviderRuntime : ProviderRuntime {
    suspend fun close()
}

internal expect fun createSdkRuntime(allowedOrigins: Set<String>): ManagedProviderRuntime

/**
 * The existing registry owns metadata; this owns typed account-session instances.
 * No typed response enters the legacy JSON cache.
 */
class ProviderSdkSessions(
    private val active: () -> BackendProvider?,
    private val cookie: (String, String) -> String?,
    private val credentialVersion: (String, String) -> Long = { _, _ -> 0 },
) {
    private val mutex = Mutex()
    private val retirement = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ ->
        println("[ProviderHost] Retired session disposal failed")
    })
    private var host: ProviderHost? = null
    private var owner: BackendProvider? = null
    private var revision = 0L
    private val accounts = mutableMapOf<String, String>()
    private var closed = false
    private val _changes = MutableStateFlow(0L)
    val changes = _changes.asStateFlow()

    /** Immediately fences old results; resource disposal drains asynchronously. */
    fun invalidate() {
        synchronized(this) {
            revision++
            host?.invalidate()
            _changes.value++
        }
    }

    fun invalidateCredentials(providerId: String, accountId: String) {
        synchronized(this) {
            if (!closed && active()?.id == providerId && (accounts[providerId] ?: "default") == accountId)
                invalidate()
        }
    }

    fun selectAccount(id: String) {
        require(id.isNotBlank() && id.length <= 256)
        synchronized(this) {
            check(!closed)
            val provider = active() ?: error("No active provider")
            if (accounts[provider.id] != id) { accounts[provider.id] = id; invalidate() }
        }
    }

    suspend fun current(): ProviderHost? = withContext(Dispatchers.IO) { mutex.withLock {
        val provider = active()
        val (generationBefore, account) = synchronized(this) {
            check(!closed); revision to (accounts[provider?.id] ?: "default")
        }
        val credential = provider?.let { cookie(it.id, account) }
        val credentialEpoch = provider?.let { credentialVersion(it.id, account) } ?: 0
        val settings = provider?.getCustomSettings().orEmpty().toMap()
        val previous = synchronized(this) { host }
        if (owner === provider && previous != null && previous.isActive &&
            previous.context.session.revision == generationBefore &&
            previous.context.credentialVersion == credentialEpoch &&
            previous.context.credential == credential && previous.context.settings == settings) return@withLock previous

        previous?.invalidate()
        if (previous != null) retirement.launch { previous.close() }
        val generation = synchronized(this) { host = null; ++revision }
        owner = provider
        if (provider == null || provider.contract != "kmp-sdk-v1") return@withLock null
        val key = ProviderSessionKey(provider.id, account, generation)
        val runtime = createSdkRuntime(provider.sdkAllowedOrigins)
        try {
            val sdkContext = ProviderContext(provider.id, account, settings, credential, runtime)
            val implementation = provider.createSdkProvider(sdkContext)
                ?: error("SDK provider has no session factory")
            var unsubscribe: () -> Unit = {}
            val adapter = object : MusicProvider by implementation {
                override suspend fun close() {
                    try { implementation.close() } finally { unsubscribe(); runtime.close() }
                }
            }
            val created = ProviderHost(ProviderCallContext(key, settings, credential, credentialEpoch), adapter, isCurrent = {
                active() === provider && provider.isReady() &&
                    synchronized(this) { revision == generation && (accounts[provider.id] ?: "default") == account } &&
                    credentialVersion(provider.id, account) == credentialEpoch
            })
            unsubscribe = provider.onSdkSessionRetired {
                created.invalidate()
                retirement.launch { created.close() }
            }
            val accepted = synchronized(this) {
                if (closed || !created.isActive || revision != generation ||
                    (accounts[provider.id] ?: "default") != account || active() !== provider) false
                else { host = created; true }
            }
            if (!accepted) {
                created.close()
                throw CancellationException("Session changed during creation")
            }
            created
        } catch (e: Throwable) { runtime.close(); throw e }
    } }

    suspend fun close() {
        synchronized(this) { closed = true; invalidate() }
        mutex.withLock {
            val previous = synchronized(this) { host.also { host = null } }
            previous?.close()
            owner = null
        }
        retirement.coroutineContext.job.children.toList().joinAll()
        retirement.cancel()
    }

    val selectedAccountId: String get() = synchronized(this) { accounts[active()?.id] ?: "default" }
}
