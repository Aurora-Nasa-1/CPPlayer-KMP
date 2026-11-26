package cp.player.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/** Presentation of a route, or of a page embedded in another route. */
interface RoutePage {
    val key: String get() = checkNotNull(this::class.qualifiedName)

    @Composable
    fun Content()
}

internal val LocalDisplayedRoutePage = staticCompositionLocalOf<RoutePage?> { null }

@Composable
inline fun <reified T : ViewModel> RoutePage.pageModel(
    tag: String? = null,
    crossinline create: () -> T,
): T {
    val owner = LocalViewModelStoreOwner.current
    if (owner == null) return compositionModel(create)
    val modelKey = remember(key, tag) { "$key:${T::class.qualifiedName}:${tag.orEmpty()}" }
    return viewModel(viewModelStoreOwner = owner, key = modelKey) { create() }
}

/** For a widget that leaves composition independently of its containing route. */
@Composable
inline fun <reified T : ViewModel> compositionModel(crossinline create: () -> T): T {
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    return viewModel(viewModelStoreOwner = owner) { create() }
}
