package cp.player.app.presentation.backenderror

import cp.player.app.AppModel
import cp.player.app.i18n.ProviderManagementStrings
import cp.player.core.ImportResult
import cp.player.core.provider.BackendProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BackendErrorPresenter(
    val scope: CoroutineScope,
) {
    /** providers 来自后端热流，和 busy / actionMessage 一样用单独流暴露，保持旧页面 API。 */
    val providers: StateFlow<List<BackendProvider>> = AppModel.backend.providersFlow
    val busy = MutableStateFlow<String?>(null)
    val actionMessage = MutableStateFlow<String?>(null)

    fun importModule(zipPath: String?, strings: ProviderManagementStrings) {
        if (zipPath == null) { actionMessage.value = strings.noFileSelected; return }
        scope.launch {
            busy.value = strings.importing
            val result = withContext(Dispatchers.IO) { AppModel.importModule(zipPath) }
            busy.value = null
            actionMessage.value = when (result) {
                is ImportResult.Activated -> if (result.isUpdate) {
                    strings.updatedAndActivated(result.provider.name)
                } else {
                    strings.importedAndActivated(result.provider.name)
                }
                is ImportResult.Loaded -> {
                    val active = AppModel.activeProvider()?.name ?: strings.noActiveProvider
                    if (result.isUpdate) {
                        strings.updated(result.provider.name, active)
                    } else {
                        strings.imported(result.provider.name, active)
                    }
                }
                is ImportResult.PendingRestart -> result.message
                is ImportResult.Failed -> result.message
            }
        }
    }

    fun delete(id: String, strings: ProviderManagementStrings) {
        scope.launch {
            busy.value = strings.deleting(id)
            val result = withContext(Dispatchers.IO) { AppModel.deleteProvider(id) }
            busy.value = null
            actionMessage.value = when (result) {
                is cp.player.core.BackendResult.Success -> strings.deleted(id)
                is cp.player.core.BackendResult.Error -> result.message
                is cp.player.core.BackendResult.Unsupported -> result.message
            }
        }
    }
}
