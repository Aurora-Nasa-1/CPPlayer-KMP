package cp.player.app

import cp.player.core.BackendState

sealed interface AppStartDestination {
    data object Loading : AppStartDestination
    data object Setup : AppStartDestination

    /** 首次使用引导（`onboarding_done` 未置位时的 Ready 起点）。 */
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
        backendState is BackendState.NoProvider -> AppStartDestination.Setup
        backendState is BackendState.Error -> AppStartDestination.Error(backendState.message)
        else -> AppStartDestination.Loading
    }
}
