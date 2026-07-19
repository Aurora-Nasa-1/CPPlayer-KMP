package cp.player.app

import cp.player.kmp.BackendResult
import cp.player.kmp.BackendState
import cp.player.kmp.ImportResult
import cp.player.kmp.MusicBackend
import cp.player.kmp.monitor.HealthMonitor
import cp.player.kmp.playback.PlaybackController
import cp.player.kmp.provider.BackendProvider
import cp.player.kmp.provider.ProviderCookieStorage
import cp.player.kmp.util.SettingsStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用顶层服务定位器。
 *
 * 唯一依赖的后端类型是 [MusicBackend]。它封装了：
 * - 状态机（[BackendState]）与自动激活
 * - Provider 管理（导入/切换/删除）
 * - 音乐数据访问（直通 + 缓存 + 健康监控）
 * - 本地音乐 / 播放引擎（占位，后续注入）
 *
 * UI 通过 [backendState] 观察瞬态；通过具体方法（如 [importModule]）执行操作，
 * 错误路径通过 [BackendResult]/[ImportResult] 类型安全返回。
 */
object AppModel {

    private val _initialized = MutableStateFlow(false)
    val initialized: StateFlow<Boolean> = _initialized.asStateFlow()

    val backend: MusicBackend get() = MusicBackend.instance

    /** 后端状态流（UI 渲染决策用——NoProvider → Setup，Ready → Main）。 */
    val backendState: StateFlow<BackendState> get() = backend.stateFlow

    /** 当前活跃 Provider 流（顶部标题/登录页等）。 */
    val activeProviderFlow: StateFlow<BackendProvider?> get() = backend.activeProviderFlow

    /** 当前状态快照。 */
    val state: BackendState get() = backend.state

    /** 是否首次运行（无已加载 Provider）。 */
    val isFirstRun: Boolean get() = backend.getAvailableProviders().isEmpty()

    val cookieStorage: ProviderCookieStorage get() = backend.cookieStorage
    val settings: SettingsStorage get() = cp.player.kmp.util.defaultSettingsStorage()

    /** 直通版音乐 API（无缓存，cookie 自动注入 + 健康记录）。 */
    val api: cp.player.kmp.api.MusicApiService get() = backend.musicApi

    /** 带缓存的音乐 API（先返回缓存，后台拉取，指纹比对，差异 Fresh）。 */
    val cachedApi: cp.player.kmp.cache.CachedMusicApiService get() = backend.cachedApi

    /** 当前活跃 Provider 唯一 ID（无活跃时返回 "default"）。 */
    fun activeProviderId(): String = backend.activeProviderId()

    val health: HealthMonitor get() = backend.health

    /** 播放控制器（前端唯一播放入口；UI 只 collect 其 state）。 */
    val playback: PlaybackController get() = backend.playbackController

    fun markInitialized() { _initialized.value = true }

    // ============ 设置（持久化） ============

    private val KEY_THEME_MODE = "theme_mode"
    private val KEY_DYNAMIC_COLOR = "dynamic_color"
    private val KEY_PURE_BLACK = "pure_black"

    private val _themeMode = MutableStateFlow(themeMode())
    val themeModeFlow: StateFlow<cp.player.app.ui.theme.ThemeMode> = _themeMode.asStateFlow()

    private val _dynamicColor = MutableStateFlow(dynamicColor())
    val dynamicColorFlow: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    private val _pureBlack = MutableStateFlow(pureBlack())
    val pureBlackFlow: StateFlow<Boolean> = _pureBlack.asStateFlow()

    fun themeMode(): cp.player.app.ui.theme.ThemeMode =
        runCatching { cp.player.app.ui.theme.ThemeMode.valueOf(settings.getString(KEY_THEME_MODE) ?: "SYSTEM") }
            .getOrDefault(cp.player.app.ui.theme.ThemeMode.SYSTEM)

    fun setThemeMode(mode: cp.player.app.ui.theme.ThemeMode) {
        settings.putString(KEY_THEME_MODE, mode.name)
        _themeMode.value = mode
    }

    fun dynamicColor(): Boolean = settings.getString(KEY_DYNAMIC_COLOR)?.toBooleanStrictOrNull() ?: false
    fun setDynamicColor(enabled: Boolean) {
        settings.putString(KEY_DYNAMIC_COLOR, enabled.toString())
        _dynamicColor.value = enabled
    }

    fun pureBlack(): Boolean = settings.getString(KEY_PURE_BLACK)?.toBooleanStrictOrNull() ?: false
    fun setPureBlack(enabled: Boolean) {
        settings.putString(KEY_PURE_BLACK, enabled.toString())
        _pureBlack.value = enabled
    }

    // ============ Provider 管理（封装 [MusicBackend] 并返回类型安全结果） ============

    fun availableProviders(): List<BackendProvider> = backend.getAvailableProviders()

    fun activeProvider(): BackendProvider? = backend.activeProvider()

    fun switchProvider(provider: BackendProvider): BackendResult<Unit> = backend.switchProvider(provider)

    /** 切换 Provider，返回是否成功（便捷版，错误信息存入 [lastSwitchError]）。 */
    var lastSwitchError: String? = null
        private set

    fun switchOrReport(provider: BackendProvider): Boolean {
        val result = backend.switchProvider(provider)
        lastSwitchError = (result as? BackendResult.Error)?.message
            ?: (result as? BackendResult.Unsupported)?.message
        return result.isSuccess
    }

    /** 导入模块，自动激活（此前无活跃时），返回 [ImportResult]。 */
    fun importModule(zipPath: String): ImportResult = backend.importModule(zipPath)

    /** 删除模块，返回 [BackendResult]。 */
    fun deleteProvider(id: String): BackendResult<Unit> = backend.deleteModule(id)

    val lastLoadError: String? get() = backend.lastLoadError
}