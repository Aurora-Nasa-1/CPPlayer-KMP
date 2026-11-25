package cp.player.core.provider

import cp.player.sdk.HostRuntimeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

/**
 * 宿主运行态与流量策略中枢，打通 UI 生命周期 → 后端源码层（`ProviderHost` / `SwrCache` / 图片管线）→ SDK `ProviderRuntime`。
 *
 * 所有策略均由宿主统一维护并兜底，SDK 插件无需强制感知即可自动受益；
 * 若插件愿意配合省流/后台降级，可通过 `ProviderRuntime.hostState()` 可选读取。
 */
object HostTrafficPolicy {

    @Volatile
    var inBackgroundTray: Boolean = false
        private set

    @Volatile
    var dataSaver: Boolean = false
        private set

    @Volatile
    var offlineMode: Boolean = false
        private set

    @Volatile
    var activeImageResizeRule: ProviderImageResizeRule? = null
        private set

    @Volatile
    var activeCustomCoverResolver: ((rawUrl: String, targetPx: Int) -> String?)? = null
        private set

    private val _stateFlow = MutableStateFlow(HostRuntimeState())
    val stateFlow: StateFlow<HostRuntimeState> = _stateFlow.asStateFlow()

    fun setInBackgroundTray(hidden: Boolean) {
        if (inBackgroundTray == hidden) return
        inBackgroundTray = hidden
        publish()
    }

    fun setDataSaver(enabled: Boolean) {
        if (dataSaver == enabled) return
        dataSaver = enabled
        publish()
    }

    fun setOfflineMode(offline: Boolean) {
        if (offlineMode == offline) return
        offlineMode = offline
        publish()
    }

    fun setActiveProviderImagePolicy(
        rule: ProviderImageResizeRule?,
        customResolver: ((rawUrl: String, targetPx: Int) -> String?)? = null,
    ) {
        activeImageResizeRule = rule
        activeCustomCoverResolver = customResolver
    }

    fun isBackgroundRefreshAllowed(): Boolean = !inBackgroundTray && !dataSaver && !offlineMode

    fun shouldDeferNetworkFallback(): Boolean = inBackgroundTray || dataSaver || offlineMode

    fun preferredImagePx(requestedPx: Int): Int = when {
        inBackgroundTray -> minOf(requestedPx.coerceAtLeast(64), 128)
        dataSaver -> minOf(requestedPx.coerceAtLeast(64), 256)
        else -> requestedPx
    }

    fun sdkHostState(): HostRuntimeState = HostRuntimeState(
        inBackgroundTray = inBackgroundTray,
        dataSaver = dataSaver,
        preferredImagePx = preferredImagePx(512),
    )

    private fun publish() {
        _stateFlow.value = sdkHostState()
    }
}
