package cp.player.app.repository

import cp.player.app.AppModel
import cp.player.core.pluginrepo.PluginCatalogEntry
import cp.player.core.pluginrepo.PluginRepositoryService
import cp.player.core.pluginrepo.PluginInstallPhase
import cp.player.core.pluginrepo.RepositoryPluginKind

/** UI-facing facade; archive handling and networking stay in core. */
class PluginRepository(
    private val service: PluginRepositoryService,
    private val onProviderInstalled: (String) -> Unit = {},
) {
    val state = service.state
    suspend fun load() = service.loadCached()
    suspend fun refresh() = service.refresh()
    suspend fun install(entry: PluginCatalogEntry, replaceLocal: Boolean) {
        val previousTask = state.value.task
        try {
            service.install(entry, replaceLocal)
        } finally {
            val task = state.value.task
            if (task !== previousTask && task?.key == entry.plugin.key &&
                task.phase == PluginInstallPhase.COMPLETE && entry.plugin.kind == RepositoryPluginKind.PROVIDER
            ) onProviderInstalled(entry.plugin.id)
        }
    }
    fun allowNetwork(enabled: Boolean) = service.setNetworkEnabled(enabled)
    fun autoCheck(enabled: Boolean) = service.setAutoCheck(enabled)
    fun addSource(url: String) = service.addSource(url)
    fun enableSource(url: String, enabled: Boolean) = service.setSourceEnabled(url, enabled)
    fun removeSource(url: String) = service.removeSource(url)
    fun cancelDownload() = service.cancelDownload()

    companion object {
        fun current() = PluginRepository(AppModel.backend.pluginRepositories, AppModel::onRepositoryProviderInstalled)
    }
}
