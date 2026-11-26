package cp.player.core.provider.typed

import cp.player.core.provider.BackendProvider
import cp.player.core.provider.HostTrafficPolicy
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
internal expect fun createScopedSdkRuntime(allowedOrigins: Set<String>, cacheScopeKey: String): ManagedProviderRuntime

private class SdkRuntimeFactory(
    val customCreate: ((Set<String>) -> ManagedProviderRuntime)? = null,
) {
    fun create(allowedOrigins: Set<String>, cacheScopeKey: String?): ManagedProviderRuntime =
        customCreate?.invoke(allowedOrigins) ?: if (cacheScopeKey == null) {
            createSdkRuntime(allowedOrigins)
        } else {
            createScopedSdkRuntime(allowedOrigins, cacheScopeKey)
        }
}

/**
 * The existing registry owns metadata; this owns typed account-session instances.
 * No typed response enters the legacy JSON cache.
 */
class ProviderSdkSessions private constructor(
    private val active: () -> BackendProvider?,
    private val cookie: (String, String) -> String?,
    private val credentialVersion: (String, String) -> Long,
    private val runtimeFactory: SdkRuntimeFactory,
) {
    constructor(
        active: () -> BackendProvider?,
        cookie: (String, String) -> String?,
        credentialVersion: (String, String) -> Long = { _, _ -> 0 },
    ) : this(active, cookie, credentialVersion, SdkRuntimeFactory())

    internal constructor(
        active: () -> BackendProvider?,
        cookie: (String, String) -> String?,
        credentialVersion: (String, String) -> Long,
        runtimeFactory: (Set<String>) -> ManagedProviderRuntime,
    ) : this(active, cookie, credentialVersion, SdkRuntimeFactory(runtimeFactory))

    private val mutex = Mutex()
    private val retirement = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ ->
        println("[ProviderHost] Retired session disposal failed")
    })
    private var host: ProviderHost? = null
    private var credentialProbe: ProviderHost? = null
    private var owner: BackendProvider? = null
    private var revision = 0L
    private val accounts = mutableMapOf<String, String>()
    private var closed = false
    private val _changes = MutableStateFlow(0L)
    val changes = _changes.asStateFlow()

    /** Immediately fences old results; resource disposal drains asynchronously. */
    fun invalidate() = invalidateAndApplyPolicy {}

    /** Atomically fences old results, updates host image policy under session lock, and retires the old host. */
    internal fun invalidateAndApplyPolicy(applyPolicy: () -> Unit) {
        synchronized(this) {
            revision++
            credentialProbe?.invalidate()
            val previous = host.also { host = null }
            previous?.invalidate()
            if (previous != null) retirement.launch { previous.close() }
            applyPolicy()
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

    suspend fun current(): ProviderHost? = withContext(Dispatchers.IO) {
        // A private credential check holds the creation lock, but callers can keep using
        // the published host while its credential and settings snapshots are still current.
        val published = synchronized(this@ProviderSdkSessions) { owner to host }
        val publishedProvider = published.first
        val publishedHost = published.second
        if (publishedProvider != null && publishedHost != null && publishedHost.isActive &&
            isCurrent(publishedProvider, publishedHost.context.session, publishedHost.context.credentialVersion) &&
            publishedHost.context.credential == cookie(publishedProvider.id, publishedHost.context.session.accountId) &&
            publishedHost.context.settings == publishedProvider.getCustomSettings().orEmpty()) {
            return@withContext publishedHost
        }
        mutex.withLock {
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

            val generation = synchronized(this) {
                if (closed || revision != generationBefore || active() !== provider) {
                    throw CancellationException("Session changed during creation")
                }
                host?.let { retired ->
                    retired.invalidate()
                    retirement.launch { retired.close() }
                }
                host = null
                ++revision
            }
            synchronized(this) { owner = provider }
            if (provider == null || provider.contract != "kmp-sdk-v1") return@withLock null
            if (provider.requiresExclusiveNativeLibrary) {
                // A cancelled native call still owns the old DLL until the host and all lanes drain.
                retirement.coroutineContext.job.children.toList().joinAll()
                synchronized(this) {
                    if (closed || revision != generation || active() !== provider) {
                        throw CancellationException("Session changed during native provider retirement")
                    }
                }
            }
            val key = ProviderSessionKey(provider.id, account, generation)
            val credentialHash = if (credential.isNullOrEmpty()) "anon" else cp.player.core.util.stableHash64(credential)
            val settingsHash = if (settings.isEmpty()) "0" else cp.player.core.util.stableHash64(
                settings.entries.sortedBy { it.key }.joinToString("&") { (k, v) -> "${k.length}:$k=${v.length}:$v" },
            )
            val context = ProviderCallContext(key, settings, credential, credentialEpoch)
            val (created, sessionProvider) = openHost(provider, context, "${provider.id}:$account:$credentialHash:$settingsHash") {
                isCurrent(provider, key, credentialEpoch)
            }
            val accepted = synchronized(this) {
                if (!created.isActive || !isCurrent(provider, key, credentialEpoch)) false
                else {
                    host = created
                    HostTrafficPolicy.setActiveProviderImagePolicy(provider.imageResizeRule) { raw, px ->
                        sessionProvider.resolveCoverUrl(raw, px)
                    }
                    true
                }
            }
            if (!accepted) {
                val failure = CancellationException("Session changed during creation")
                try { created.close() } catch (cleanupError: Throwable) { failure.addSuppressed(cleanupError) }
                throw failure
            }
            created
        }
    }

    /** Inspect a candidate without publishing it as the active host or changing the credential store. */
    internal suspend fun verifyCredential(providerId: String, credential: String): AccountSession =
        withContext(Dispatchers.IO) {
            // Keep the probe's lifetime within the lock so a new native library cannot load
            // while a probe for the previous provider still holds a lease.
            mutex.withLock {
                val provider = active() ?: throw CancellationException("No active provider")
                if (provider.id != providerId) throw CancellationException("Provider changed before credential check")
                val key = synchronized(this@ProviderSdkSessions) {
                    check(!closed)
                    ProviderSessionKey(providerId, accounts[providerId] ?: "default", revision)
                }
                val epoch = credentialVersion(providerId, key.accountId)
                if (provider.requiresExclusiveNativeLibrary) {
                    retirement.coroutineContext.job.children.toList().joinAll()
                }
                if (!isCurrent(provider, key, epoch)) throw CancellationException("Session changed before credential check")
                val context = ProviderCallContext(key, provider.getCustomSettings().orEmpty(), credential, epoch)
                val (probe) = openHost(provider, context, cacheScopeKey = null) { isCurrent(provider, key, epoch) }
                try {
                    synchronized(this@ProviderSdkSessions) {
                        if (!isCurrent(provider, key, epoch)) throw CancellationException("Session changed during credential check")
                        credentialProbe = probe
                    }
                    probe.getCurrentAccount()
                } finally {
                    try {
                        probe.close()
                    } finally {
                        synchronized(this@ProviderSdkSessions) { credentialProbe = null }
                    }
                }
            }
        }

    private fun isCurrent(provider: BackendProvider, key: ProviderSessionKey, epoch: Long): Boolean =
        synchronized(this) {
            !closed && active() === provider && provider.isReady() && revision == key.revision &&
                (accounts[provider.id] ?: "default") == key.accountId &&
                credentialVersion(provider.id, key.accountId) == epoch
        }

    private suspend fun openHost(
        provider: BackendProvider,
        context: ProviderCallContext,
        cacheScopeKey: String?,
        isCurrent: () -> Boolean,
    ): Pair<ProviderHost, MusicProvider> {
        val runtime = runtimeFactory.create(provider.sdkAllowedOrigins, cacheScopeKey)
        val resourceLock = Any()
        var resourcesClosed = false
        var implementation: MusicProvider? = null
        var unsubscribe: () -> Unit = {}
        suspend fun closeResources() {
            val shouldClose = synchronized(resourceLock) {
                if (resourcesClosed) false else { resourcesClosed = true; true }
            }
            if (!shouldClose) return

            var failure: Throwable? = null
            fun record(error: Throwable) {
                val first = failure
                if (first == null) failure = error
                else if (first !== error) first.addSuppressed(error)
            }
            try { implementation?.close() } catch (error: Throwable) { record(error) }
            try { unsubscribe() } catch (error: Throwable) { record(error) }
            try { runtime.close() } catch (error: Throwable) { record(error) }
            failure?.let { throw it }
        }
        try {
            val sdkContext = ProviderContext(provider.id, context.session.accountId, context.settings, context.credential, runtime)
            val sessionProvider = provider.createSdkProvider(sdkContext)
                ?: error("SDK provider has no session factory")
            implementation = sessionProvider
            val adapter = object : MusicProvider by sessionProvider {
                override suspend fun close() = closeResources()
            }
            val created = ProviderHost(context, adapter, isCurrent = isCurrent)
            unsubscribe = provider.onSdkSessionRetired {
                created.invalidate()
                retirement.launch { created.close() }
            }
            return created to sessionProvider
        } catch (error: Throwable) {
            try { closeResources() } catch (cleanupError: Throwable) {
                if (cleanupError !== error) error.addSuppressed(cleanupError)
            }
            throw error
        }
    }

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

    fun trimHostCaches() {
        synchronized(this) { host }?.trimCache()
    }

    val selectedAccountId: String get() = synchronized(this) { accounts[active()?.id] ?: "default" }
}
