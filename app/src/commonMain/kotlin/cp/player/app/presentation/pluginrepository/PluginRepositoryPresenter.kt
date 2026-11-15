package cp.player.app.presentation.pluginrepository

import cp.player.app.AppModel
import cp.player.app.repository.PluginRepository
import cp.player.core.pluginrepo.PluginCatalogEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 插件仓库状态机（框架无关）。
 *
 * 页面直接订阅 [repository] 的状态；[error] 只承载最近一次动作失败。
 */
class PluginRepositoryPresenter(
    val scope: CoroutineScope,
    val repository: PluginRepository = PluginRepository.current(),
) {
    val error = MutableStateFlow<String?>(null)
    val pendingError = AppModel.backend.pendingPluginUpdateError

    fun load() = launch { repository.load() }

    private fun launch(block: suspend () -> Unit) {
        scope.launch {
            error.value = null
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error.value = failure.message ?: failure.toString()
            }
        }
    }

    fun refresh() = launch { repository.refresh() }

    fun reportError(failure: Exception) {
        error.value = failure.message ?: failure.toString()
    }

    fun install(entry: PluginCatalogEntry, replaceLocal: Boolean) =
        launch { repository.install(entry, replaceLocal) }

    fun change(block: PluginRepository.() -> Unit) {
        try {
            repository.block()
            error.value = null
        } catch (failure: Exception) {
            error.value = failure.message ?: failure.toString()
        }
    }

    fun addSource(url: String): Boolean = try {
        repository.addSource(url)
        error.value = null
        true
    } catch (failure: Exception) {
        error.value = failure.message ?: failure.toString()
        false
    }
}
