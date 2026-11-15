package cp.player.app.presentation.providermanagement

import cp.player.app.AppModel
import cp.player.app.i18n.ProviderManagementStrings
import cp.player.app.update.ProviderUpdateInfo
import cp.player.app.update.ProviderUpdateManager
import cp.player.app.update.ProviderUpdateState
import cp.player.core.BackendResult
import cp.player.core.ImportResult
import cp.player.core.provider.BackendProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 音源管理状态机（框架无关）。
 *
 * 插件安装 / 更新与仓库刷新仍通过 [AppModel] 的既有入口串联，
 * 页面只订阅状态与调用动作。
 */
class ProviderManagementPresenter(
    val scope: CoroutineScope,
) {

    val providers: StateFlow<List<BackendProvider>> = AppModel.backend.providersFlow
    val active: StateFlow<BackendProvider?> get() = AppModel.activeProviderFlow

    private val updateManager: ProviderUpdateManager = ProviderUpdateManager.INSTANCE
    val updateStates: StateFlow<Map<String, ProviderUpdateState>> = updateManager.updateStates
    val autoCheckEnabled: MutableStateFlow<Boolean> = MutableStateFlow(updateManager.isAutoCheckEnabled())

    val busy = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)

    fun isAutoCheckEnabled(): Boolean = updateManager.isAutoCheckEnabled()

    fun setAutoCheckEnabled(enabled: Boolean) {
        updateManager.setAutoCheckEnabled(enabled)
        autoCheckEnabled.value = enabled
    }

    fun getCustomUpdateUrl(providerId: String): String? = updateManager.getCustomUpdateUrl(providerId)

    fun setCustomUpdateUrl(providerId: String, url: String?) {
        updateManager.setCustomUpdateUrl(providerId, url)
    }

    fun checkUpdateFor(provider: BackendProvider, strings: ProviderManagementStrings) {
        scope.launch {
            val result = updateManager.checkUpdate(provider)
            when (result) {
                is ProviderUpdateState.UpdateRequired -> {
                    message.value = strings.updateRequired(provider.name, result.info.version)
                }
                is ProviderUpdateState.UpdateAvailable -> {
                    message.value = strings.updateAvailable(provider.name, result.info.version)
                }
                is ProviderUpdateState.UpToDate -> {
                    message.value = strings.upToDate(provider.name)
                }
                is ProviderUpdateState.Error -> {
                    message.value = strings.checkFailed(provider.name, result.message)
                }
                else -> Unit
            }
        }
    }

    fun checkAllUpdates(strings: ProviderManagementStrings, silent: Boolean = false) {
        scope.launch {
            checkAllUpdatesNow(strings, silent)
        }
    }

    /**
     * 在当前协程里完成全部音源检查，供插件中心与仓库刷新串联。
     *
     * 与 [checkAllUpdates] 共用同一套忙态 / 结果提示，区别只在于完成后才返回。
     */
    suspend fun checkAllUpdatesNow(strings: ProviderManagementStrings, silent: Boolean = false) {
        try {
            if (!silent) busy.value = strings.checkingAll
            updateManager.checkAllUpdates(providers.value)
            if (!silent) {
                val availableCount = updateManager.updateStates.value.values.count {
                    it is ProviderUpdateState.UpdateAvailable || it is ProviderUpdateState.UpdateRequired
                }
                message.value = if (availableCount > 0) {
                    strings.checkAllDoneAvailable(availableCount)
                } else {
                    strings.checkAllDoneUpToDate
                }
            }
        } finally {
            if (!silent) busy.value = null
        }
    }

    fun downloadAndInstallUpdate(
        provider: BackendProvider,
        info: ProviderUpdateInfo,
        strings: ProviderManagementStrings,
    ) {
        scope.launch {
            busy.value = strings.downloading(provider.name, info.version)
            val result = runCatching {
                updateManager.downloadAndInstall(provider, info) { written, total ->
                    if (total != null && total > 0) {
                        val pct = ((written * 100) / total).coerceIn(0L, 100L).toInt()
                        busy.value = strings.downloadingPercent(provider.name, info.version, pct)
                    }
                }
            }.fold(
                onSuccess = { it },
                onFailure = { ImportResult.Failed(it.message ?: strings.downloadFailed) },
            )
            busy.value = null
            message.value = when (result) {
                is ImportResult.Activated -> strings.updatedAndActivatedVersioned(result.provider.name, info.version)
                is ImportResult.Loaded -> strings.updatedVersioned(result.provider.name, info.version)
                is ImportResult.PendingRestart -> result.message
                is ImportResult.Failed -> result.message
            }
        }
    }

    fun importModule(zipPath: String?, strings: ProviderManagementStrings) {
        if (zipPath == null) {
            message.value = strings.noFileSelected
            return
        }
        scope.launch {
            busy.value = strings.importing
            val result = withContext(Dispatchers.IO) { AppModel.importModule(zipPath) }
            busy.value = null
            message.value = when (result) {
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

    fun updateModule(provider: BackendProvider, zipPath: String?, strings: ProviderManagementStrings) {
        if (zipPath == null) {
            message.value = strings.noFileSelected
            return
        }
        scope.launch {
            busy.value = strings.updating(provider.name)
            val result = withContext(Dispatchers.IO) { AppModel.updateModule(zipPath, provider.id) }
            busy.value = null
            message.value = when (result) {
                is ImportResult.Activated -> strings.updatedAndActivated(result.provider.name)
                is ImportResult.Loaded -> strings.updated(
                    result.provider.name,
                    AppModel.activeProvider()?.name ?: strings.noActiveProvider,
                )
                is ImportResult.PendingRestart -> result.message
                is ImportResult.Failed -> result.message
            }
        }
    }

    fun exportModuleTo(
        provider: BackendProvider,
        destPath: String?,
        strings: ProviderManagementStrings,
    ): Boolean {
        if (destPath == null) return false
        busy.value = strings.exporting(provider.name)
        val result = AppModel.exportModule(provider.id, destPath)
        busy.value = null
        message.value = when (result) {
            is BackendResult.Success -> strings.exported(provider.name, fileNameOf(destPath))
            is BackendResult.Error -> result.message
            is BackendResult.Unsupported -> result.message
        }
        return result is BackendResult.Success
    }

    fun activate(provider: BackendProvider, strings: ProviderManagementStrings) {
        val ok = AppModel.switchOrReport(provider)
        message.value = if (ok) {
            strings.activated(provider.name)
        } else {
            AppModel.lastSwitchError ?: strings.switchFailed
        }
    }

    fun delete(id: String, strings: ProviderManagementStrings) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { AppModel.deleteProvider(id) }
            message.value = when (result) {
                is BackendResult.Success<*> -> strings.deleted(id)
                is BackendResult.Error -> result.message
                is BackendResult.Unsupported -> result.message
            }
        }
    }
}

private fun fileNameOf(path: String): String =
    path.replace('\\', '/').substringAfterLast('/')
