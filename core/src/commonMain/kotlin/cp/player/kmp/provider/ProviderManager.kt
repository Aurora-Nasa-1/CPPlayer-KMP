package cp.player.kmp.provider

import cp.player.kmp.util.PlatformContext
import cp.player.kmp.util.PlatformSupport
import cp.player.kmp.util.SettingsStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 提供商管理器（KMP 版，实例化、依赖注入）。
 *
 * 负责管理所有已加载的 [BackendProvider] 实例、当前活跃 Provider 的切换，
 * 以及通过 [onProviderChanged] 通知 UI 层 Provider 变更。
 *
 * ### 设计原则
 * - 所有 Provider 加载由 [ModuleManager] 完成，[ProviderManager] 只负责管理活跃状态
 * - 切换 Provider 时自动停止旧 Provider 的服务并启动新 Provider 的服务
 * - 通过 [StateFlow] 暴露当前 Provider 状态供 Compose 观察
 * - 自动持久化用户选择的 Provider（通过 [SettingsStorage]），重启后恢复
 *
 * @param settings 键值存储，用于持久化最近活跃 Provider ID
 * @param cookieStorage Cookie 存储回调（按 provider 前缀隔离账号）
 * @param defaultPort 默认起始端口
 */
class ProviderManager(
    private val settings: SettingsStorage,
    val cookieStorage: ProviderCookieStorage,
    defaultPort: Int = 3000
) {
    private val DEFAULT_PORT = defaultPort
    private val MAX_PORT_ATTEMPTS = 20
    private val KEY_LAST_PROVIDER_ID = "last_active_provider_id"

    /** 当前活跃的 Provider */
    var currentProvider: BackendProvider? = null
        private set

    /** 当前实际使用的端口 */
    var currentPort: Int = DEFAULT_PORT
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
     * 启动当前 Provider 的服务（自动检测端口可用性 + 回退）。
     */
    fun startServer(context: PlatformContext, port: Int = DEFAULT_PORT) {
        val provider = currentProvider ?: return
        val actualPort = PlatformSupport.findAvailablePort(port, MAX_PORT_ATTEMPTS) ?: run {
            println("[ProviderManager] 端口 $port~${port + MAX_PORT_ATTEMPTS - 1} 全被占用")
            return
        }
        currentPort = actualPort
        provider.startServer(context, actualPort)
    }

    /**
     * 切换当前活跃的 Provider。
     *
     * @param save 是否持久化用户选择（自动选择首模块时应传 false）
     */
    fun switchProvider(provider: BackendProvider?, context: PlatformContext? = null, port: Int = DEFAULT_PORT, save: Boolean = true): Boolean {
        if (provider?.id == currentProvider?.id) return true
        if (provider != null && !provider.isReady()) return false

        val previousProvider = currentProvider
        try { previousProvider?.stopServer() } catch (_: Throwable) {}

        if (provider != null && context != null) {
            val actualPort = PlatformSupport.findAvailablePort(port, MAX_PORT_ATTEMPTS)
            if (actualPort == null) {
                currentProvider = previousProvider
                _currentProviderFlow.value = previousProvider
                return false
            }
            currentPort = actualPort
            try {
                provider.startServer(context, actualPort)
            } catch (e: Throwable) {
                currentProvider = previousProvider
                _currentProviderFlow.value = previousProvider
                return false
            }
        }

        currentProvider = provider
        _currentProviderFlow.value = provider
        if (save && provider != null) settings.putString(KEY_LAST_PROVIDER_ID, provider.id)
        changeListeners.forEach { runCatching { it(provider) } }
        return true
    }

    fun switchProviderById(providerId: String, providers: Collection<BackendProvider>, context: PlatformContext? = null): Boolean {
        val provider = providers.find { it.id == providerId } ?: return false
        return switchProvider(provider, context)
    }

    /** 尝试恢复上次保存的 Provider。 */
    fun restoreLastProvider(providers: Collection<BackendProvider>, context: PlatformContext): Boolean {
        val lastId = settings.getString(KEY_LAST_PROVIDER_ID) ?: return false
        if (currentProvider?.id == lastId) return true
        return switchProviderById(lastId, providers, context)
    }

    /**
     * 调用当前 Provider 的 API（suspend 版本）。
     *
     * 自动在 [Dispatchers.IO] 上执行，通过 [BackendProvider.apiMap] 映射方法名；
     * 若映射为 "unsupported" 返回不支持提示。
     */
    suspend fun callApi(method: String, params: Map<String, String>): String = withContext(Dispatchers.IO) {
        val provider = currentProvider
            ?: return@withContext """{"code": 500, "msg": "No active provider"}"""
        val mappedMethod = provider.apiMap?.get(method) ?: method
        if (mappedMethod.isEmpty() || mappedMethod.equals("unsupported", ignoreCase = true)) {
            return@withContext """{"code": -1, "msg": "该提供商不支持此功能"}"""
        }
        return@withContext try {
            provider.callApi(mappedMethod, params)
        } catch (e: Exception) {
            """{"code": 500, "msg": "Provider call failed: ${e.message}"}"""
        }
    }

    fun getCurrentProviderName(): String = currentProvider?.name ?: "No Provider"
    fun getCurrentProviderId(): String = currentProvider?.id ?: "default"
}

/**
 * Cookie 存储：按 Provider 隔离账号 cookie。
 * 实现可包装 [SettingsStorage]，键为 `cookie_<providerId>`。
 */
class ProviderCookieStorage(private val settings: SettingsStorage) {
    fun saveCookie(providerId: String, cookie: String) =
        settings.putString("cookie_$providerId", cookie)
    fun getCookie(providerId: String): String? = settings.getString("cookie_$providerId")
    fun clear(providerId: String) = settings.remove("cookie_$providerId")
}