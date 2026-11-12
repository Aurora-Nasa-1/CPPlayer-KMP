package cp.player.core.provider

import cp.player.core.util.PlatformContext
import cp.player.core.util.SettingsStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 提供商管理器（KMP 版，实例化、依赖注入）。
 *
 * 负责管理所有已加载的 [BackendProvider] 实例、当前活跃 Provider 的切换，
 * 以及通过 [onProviderChanged] 通知 UI 层 Provider 变更。
 *
 * ### 设计原则
 * - 所有 Provider 加载由 [ModuleManager] 完成，[ProviderManager] 只负责管理活跃状态
 * - 切换 Provider 时同步失效旧 SDK 会话；会话层负责取消、排空和释放
 * - 通过 [StateFlow] 暴露当前 Provider 状态供 Compose 观察
 * - 自动持久化用户选择的 Provider（通过 [SettingsStorage]），重启后恢复
 *
 * @param settings 键值存储，用于持久化最近活跃 Provider ID
 * @param cookieStorage Cookie 存储回调（按 provider 前缀隔离账号）
 */
class ProviderManager(
    private val settings: SettingsStorage,
    val cookieStorage: ProviderCookieStorage,
) {
    val sdkSessions = cp.player.core.provider.typed.ProviderSdkSessions(
        active = { sessionProvider },
        cookie = { provider, account -> cookieStorage.getCookie(provider, account) },
        credentialVersion = { provider, account -> cookieStorage.revision(provider, account) },
    )
    private val KEY_LAST_PROVIDER_ID = "last_active_provider_id"
    @Volatile private var sessionProvider: BackendProvider? = null
    private var unsubscribeRetirement: () -> Unit = {}
    private val unsubscribeCredentials = cookieStorage.onChanged { provider, account ->
        sdkSessions.invalidateCredentials(provider, account)
    }

    suspend fun close() {
        unsubscribeCredentials()
        unsubscribeRetirement()
        sdkSessions.close()
    }

    private fun observeRetirement(provider: BackendProvider): () -> Unit {
        val lock = Any()
        var stopped = false
        var registering = false
        var unsubscribe: () -> Unit = {}
        lateinit var subscribe: () -> Unit
        val listener: () -> Unit = {
            synchronized(lock) {
                if (!stopped) {
                    sdkSessions.invalidate()
                    // SDK descriptors consume retirement listeners. Re-arm after each update;
                    // an immediate callback from an already disposed descriptor must not recurse.
                    if (!registering) subscribe()
                }
            }
        }
        subscribe = {
            if (!stopped && provider.isReady()) {
                registering = true
                try {
                    unsubscribe()
                    unsubscribe = provider.onSdkSessionRetired(listener)
                } finally { registering = false }
            }
        }
        synchronized(lock) { subscribe() }
        return { synchronized(lock) { stopped = true; unsubscribe() } }
    }

    /** 当前活跃的 Provider */
    var currentProvider: BackendProvider? = null
        private set

    private val _currentProviderFlow = MutableStateFlow<BackendProvider?>(null)
    val currentProviderFlow: StateFlow<BackendProvider?> = _currentProviderFlow.asStateFlow()

    private val changeListeners = mutableListOf<(BackendProvider?) -> Unit>()

    /** 添加 Provider 切换监听器 */
    fun addOnProviderChangedListener(listener: (BackendProvider?) -> Unit) {
        changeListeners.add(listener)
    }

    fun removeOnProviderChangedListener(listener: (BackendProvider?) -> Unit) {
        changeListeners.remove(listener)
    }

    /**
     * 切换当前活跃的 Provider。
     *
     * @param save 是否持久化用户选择（自动选择首模块时应传 false）
     */
    var lastSwitchError: String? = null
        private set

    @Suppress("UNUSED_PARAMETER")
    fun switchProvider(provider: BackendProvider?, context: PlatformContext? = null, save: Boolean = true): Boolean {
        if (provider != null && provider === currentProvider && provider.isReady()) {
            lastSwitchError = null
            return true
        }
        if (provider != null && provider.contract != PROVIDER_SDK_CONTRACT) {
            lastSwitchError = "Legacy Provider v1/v3 is retired; use provider-sdk-kmp"
            return false
        }
        if (provider != null && !provider.isReady()) {
            lastSwitchError = provider.loadError ?: "音源未就绪"
            return false
        }

        sdkSessions.invalidate()
        unsubscribeRetirement()
        unsubscribeRetirement = {}
        runCatching { currentProvider?.retireSessions() }
        sessionProvider = provider?.let { descriptor ->
            object : BackendProvider by descriptor {
                override fun createSdkProvider(context: cp.player.sdk.ProviderContext): cp.player.sdk.MusicProvider? {
                    // Cookie and epoch are read separately by the session owner. Reject a torn snapshot.
                    if (context.credential != cookieStorage.getCookie(descriptor.id, context.accountId))
                        throw kotlinx.coroutines.CancellationException("Credentials changed during session creation")
                    if (context.settings != descriptor.getCustomSettings())
                        throw kotlinx.coroutines.CancellationException("Settings changed during session creation")
                    return descriptor.createSdkProvider(context)?.let { cp.player.sdk.SessionMusicProvider(it) }
                }
            }
        }
        currentProvider = provider
        sdkSessions.invalidate()
        // Subscribe before any lazy session can be created: configuration changes must also fence
        // the gap between its settings snapshot and registration of the session's own listener.
        unsubscribeRetirement = provider?.let(::observeRetirement) ?: {}
        _currentProviderFlow.value = provider
        if (save && provider != null) settings.putString(KEY_LAST_PROVIDER_ID, provider.id)
        changeListeners.forEach { runCatching { it(provider) } }
        lastSwitchError = null
        return true
    }

    fun switchProviderById(providerId: String, providers: Collection<BackendProvider>, context: PlatformContext? = null): Boolean {
        val provider = providers.find { it.id == providerId } ?: return false
        return switchProvider(provider, context)
    }

    /** 尝试恢复上次保存的 Provider。 */
    fun restoreLastProvider(providers: Collection<BackendProvider>, context: PlatformContext): Boolean {
        val lastId = settings.getString(KEY_LAST_PROVIDER_ID) ?: return false
        return switchProviderById(lastId, providers, context)
    }

    /** 获取最近一次活跃的 Provider ID（若已保存）。用于启动时快速匹配快路径。 */
    fun getLastActiveProviderId(): String? = settings.getString(KEY_LAST_PROVIDER_ID)

    fun getCurrentProviderName(): String = currentProvider?.name ?: "No Provider"
    fun getCurrentProviderId(): String = currentProvider?.id ?: "default"
}

/**
 * Cookie 存储：按 Provider 隔离账号 cookie。
 * 实现可包装 [SettingsStorage]，键为 `cookie_<providerId>`。
 */
class ProviderCookieStorage(private val settings: SettingsStorage) {
    private val versions = mutableMapOf<String, Long>()
    private val listeners = mutableSetOf<(String, String) -> Unit>()
    fun onChanged(listener: (String, String) -> Unit): () -> Unit {
        synchronized(versions) { listeners.add(listener) }
        return { synchronized(versions) { listeners.remove(listener); Unit } }
    }
    private fun changed(providerId: String, accountId: String) {
        // Never call a session owner while holding the storage lock.
        val snapshot = synchronized(versions) { listeners.toList() }
        snapshot.forEach { it(providerId, accountId) }
    }
    fun revision(providerId: String, accountId: String): Long = synchronized(versions) { versions[key(providerId, accountId)] ?: 0 }
    // Length prefixes prevent ambiguous pairs; the default retains existing data.
    private fun key(provider: String, account: String): String =
        if (account == "default") "cookie_$provider" else "sdk_cookie_${provider.length}:$provider${account.length}:$account"
    fun saveCookie(providerId: String, cookie: String, accountId: String = "default") {
        synchronized(versions) {
            val key = key(providerId, accountId)
            settings.putString(key, cookie)
            versions[key] = (versions[key] ?: 0) + 1
        }
        changed(providerId, accountId)
    }
    fun getCookie(providerId: String, accountId: String = "default"): String? =
        synchronized(versions) { settings.getString(key(providerId, accountId)) }
    fun clear(providerId: String, accountId: String = "default") {
        synchronized(versions) {
            val key = key(providerId, accountId)
            settings.remove(key)
            versions[key] = (versions[key] ?: 0) + 1
        }
        changed(providerId, accountId)
    }
}
