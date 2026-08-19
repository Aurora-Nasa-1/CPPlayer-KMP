package cp.player.app

import cp.player.kmp.BackendState

sealed interface AppStartDestination {
    data object Loading : AppStartDestination
    data object Setup : AppStartDestination
    data object Main : AppStartDestination
    data class Error(val message: String) : AppStartDestination
}

object AppState {
    fun startDestination(initialized: Boolean, backendState: BackendState): AppStartDestination = when {
        !initialized || backendState is BackendState.Uninitialized || backendState is BackendState.Initializing -> AppStartDestination.Loading
        backendState is BackendState.Ready -> AppStartDestination.Main
        backendState is BackendState.NoProvider -> AppStartDestination.Setup
        backendState is BackendState.Error -> AppStartDestination.Error(backendState.message)
        else -> AppStartDestination.Loading
    }
}
