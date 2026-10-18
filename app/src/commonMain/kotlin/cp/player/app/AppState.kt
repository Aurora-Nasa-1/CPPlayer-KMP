package cp.player.app

import cp.player.core.BackendState

sealed interface AppStartDestination {
    data object Loading : AppStartDestination

    /**
     * 首次使用引导：`onboarding_done` 未置位时才从它进。
     *
     * ⚠️ **不再**在「无音源」时强制进引导：音源导入是可选的，用户完全可以先只用
     * 本地音乐（无音源也能扫描 / 导入 / 播放本机文件）。引导跑完一次之后，
     * 无音源直接进主界面 —— 否则「跳过了音源导入」的用户会被反复弹回引导页，
     * 永远进不了主壳（这就是 2026-10-18 之前无音源用户的死局）。
     */
    data object Onboarding : AppStartDestination
    data object Main : AppStartDestination
    data class Error(val message: String) : AppStartDestination
}

object AppState {
    fun startDestination(
        initialized: Boolean,
        backendState: BackendState,
        onboardingDone: Boolean,
    ): AppStartDestination = when {
        !initialized || backendState is BackendState.Uninitialized || backendState is BackendState.Initializing -> AppStartDestination.Loading
        backendState is BackendState.Ready ->
            if (onboardingDone) AppStartDestination.Main else AppStartDestination.Onboarding
        // 无音源 = 本地模式：引导跑过一次就放行，主界面照常可用（首页 / 搜索都退化到本地）。
        // 没跑过引导仍然先进引导 —— 它是「导入音源」的唯一显式入口，只是不再强制。
        backendState is BackendState.NoProvider ->
            if (onboardingDone) AppStartDestination.Main else AppStartDestination.Onboarding
        backendState is BackendState.Error -> AppStartDestination.Error(backendState.message)
        else -> AppStartDestination.Loading
    }
}
