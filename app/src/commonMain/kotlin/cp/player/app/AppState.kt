package cp.player.app

import cp.player.core.BackendState

sealed interface AppStartDestination {
    data object Loading : AppStartDestination

    /** 首次使用引导：`onboarding_done` 未置位，或尚无任何音源（NoProvider），都从它进。 */
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
        // 无音源也进引导：音源导入内嵌在引导第二步且是强制的。
        // 旧版这里单设一个 Setup 欢迎页，导入成功后又路由到 Onboarding 再欢迎一遍
        // —— 新用户要连看两个「欢迎」，所以合并掉（2026-10-02）。
        backendState is BackendState.NoProvider -> AppStartDestination.Onboarding
        backendState is BackendState.Error -> AppStartDestination.Error(backendState.message)
        else -> AppStartDestination.Loading
    }
}
