package cp.player.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.i18n.ProviderManagementStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.rememberZipPicker
import cp.player.app.platform.rememberZipSaver
import cp.player.app.platform.sendPlatformToast
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpLoadingIndicator
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.ProviderUpdateDetailsDialog
import cp.player.app.ui.component.ProviderUpdateSourceDialog
import cp.player.app.ui.component.SettingsButtonItem
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsLazyPage
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.component.rememberConfirmState
import cp.player.app.ui.component.settingsContentWidth
import cp.player.app.ui.component.settingsRowHighlightContent
import cp.player.app.ui.util.popOrNotify
import cp.player.app.update.ProviderUpdateInfo
import cp.player.app.update.ProviderUpdateManager
import cp.player.app.update.ProviderUpdateState
import cp.player.core.BackendResult
import cp.player.core.ImportResult
import cp.player.core.lyrics.BuiltinLyricsSourceIds
import cp.player.core.lyrics.LyricsCapability
import cp.player.core.lyrics.LyricsSourceEntry
import cp.player.core.lyrics.LyricsSourceRegistry
import cp.player.core.provider.BackendProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 音源与歌词管理：导入、切换、更新、导出与删除音源模块及歌词来源插件。
 *
 * @param initialTab 0 为「音源模块」，1 为「歌词来源」。
 */
class ProviderManagementScreen(
    private val initialTab: Int = 0,
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val s = cpStrings()
        val p = s.providerManagement
        val t = s.lyricsPlugin
        val scope = rememberCoroutineScope()

        var selectedTab by remember { mutableIntStateOf(initialTab.coerceIn(0, 1)) }

        val model = rememberScreenModel { ProviderManagementModel() }
        val providers by model.providers.collectAsState()
        val active by model.active.collectAsState()
        val busy by model.busy.collectAsState()
        val message by model.message.collectAsState()
        val updateStates by model.updateStates.collectAsState()
        val autoCheck by model.autoCheckEnabled.collectAsState()

        var updateTarget by remember { mutableStateOf<BackendProvider?>(null) }
        var updateDetailTarget by remember { mutableStateOf<Pair<BackendProvider, ProviderUpdateInfo>?>(null) }
        var configSourceTarget by remember { mutableStateOf<BackendProvider?>(null) }
        val confirm = rememberConfirmState()

        // 音源 zip 选择器
        val pickProviderZip = rememberZipPicker(onPicked = { zipPath ->
            val target = updateTarget
            updateTarget = null
            if (zipPath == null) return@rememberZipPicker
            if (target == null) {
                model.importModule(zipPath, p)
            } else {
                model.updateModule(target, zipPath, p)
            }
        })

        // 歌词来源体系
        val lyricsRegistry: LyricsSourceRegistry = AppModel.lyricsSources
        var lyricsSources by remember { mutableStateOf<List<LyricsSourceEntry>>(emptyList()) }
        val refreshLyrics: suspend () -> Unit = {
            lyricsSources = runCatching { lyricsRegistry.allSources() }.getOrDefault(emptyList())
        }

        LaunchedEffect(Unit) {
            refreshLyrics()
            if (model.isAutoCheckEnabled()) {
                model.checkAllUpdates(p, silent = true)
            }
        }

        // 歌词 zip 选择器
        val pickLyricsZip = rememberZipPicker(onPicked = { zipPath ->
            if (zipPath != null) {
                scope.launch {
                    val msg = runCatching { AppModel.backend.lyricsPlugins.importPluginZip(zipPath) }
                        .fold(
                            onSuccess = { ids ->
                                ids.forEach { AppModel.backend.pluginRepositories.forgetInstall("lyrics:$it") }
                                lyricsRegistry.reload()
                                if (ids.isEmpty()) t.importFailed else t.importSuccessCount(ids.size)
                            },
                            onFailure = { t.importFailed },
                        )
                    sendPlatformToast(msg)
                    refreshLyrics()
                }
            }
        })

        fun moveLyrics(id: String, delta: Int) {
            val ids = lyricsSources.map { it.id }.toMutableList()
            val from = ids.indexOf(id)
            val to = from + delta
            if (from < 0 || to !in ids.indices) return
            ids[from] = ids[to].also { ids[to] = ids[from] }
            scope.launch {
                lyricsRegistry.setOrder(ids)
                refreshLyrics()
            }
        }

        CpRouteScaffold(
            title = p.screenTitle,
            onBack = { navigator.popOrNotify() },
            topBarActions = listOf(
                cp.player.app.ui.component.TopBarAction(
                    icon = { Icon(Icons.Filled.Sync, p.checkUpdatesContentDesc) },
                    onClick = {
                        if (selectedTab == 0) {
                            model.checkAllUpdates(p, silent = false)
                        }
                    },
                ),
                cp.player.app.ui.component.TopBarAction(
                    icon = { Icon(Icons.Filled.Extension, cpStrings().pluginRepository.title) },
                    onClick = { navigator.push(PluginRepositoryScreen()) },
                ),
            ),
            floatingActionButton = {
                FloatingActionButton(onClick = {
                    if (selectedTab == 0) {
                        updateTarget = null
                        pickProviderZip()
                    } else {
                        pickLyricsZip()
                    }
                }) {
                    Icon(
                        imageVector = if (selectedTab == 0) Icons.Filled.Add else Icons.Filled.FileUpload,
                        contentDescription = if (selectedTab == 0) {
                            p.importProviderContentDesc
                        } else {
                            p.importLyricsContentDesc
                        },
                    )
                }
            },
        ) { pageModifier ->
            Column(pageModifier) {
                // Tab 分页栏
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    @Suppress("DEPRECATION")
                    TabRow(
                        selectedTabIndex = selectedTab,
                        modifier = Modifier.settingsContentWidth(),
                        containerColor = Color.Transparent,
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { CpText(p.tabProviders) },
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { CpText(p.tabLyrics) },
                        )
                    }
                }

                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (selectedTab == 0) {
                        // ---------------- 音源模块 ----------------
                        if (providers.isEmpty() && busy == null) {
                            // 空列表 = 首次使用 / 刚删光。这里必须自带导入按钮：
                            // 桌面宽屏下本页渲染在设置页右栏（`CpRouteScaffold` 的 embedded 分支），
                            // 没有任何列表项可点，只靠 FAB 就会变成「这页没有导入入口」。
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                ContentState(
                                    title = p.noProvidersTitle,
                                    message = p.noProvidersMessage,
                                    actionLabel = p.importProvider,
                                    onAction = {
                                        updateTarget = null
                                        pickProviderZip()
                                    },
                                )
                                message?.let { msg ->
                                    SettingsNote(msg, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        } else {
                            SettingsLazyPage(
                                pageModifier = Modifier.fillMaxSize(),
                                bottomInset = 88.dp,
                            ) {
                                item(key = "__import__") {
                                    SettingsButtonItem(
                                        text = p.importProvider,
                                        subtitle = p.importProviderNote,
                                        icon = Icons.Filled.Add,
                                        index = 0,
                                        total = 1,
                                        onClick = {
                                            updateTarget = null
                                            pickProviderZip()
                                        },
                                    )
                                }
                                item(key = "__auto_check__") {
                                    SettingsSwitchItem(
                                        title = p.autoCheck,
                                        subtitle = p.autoCheckNote,
                                        checked = autoCheck,
                                        onCheckedChange = { model.setAutoCheckEnabled(it) },
                                        index = 0,
                                        total = 1,
                                        icon = Icons.Filled.Sync,
                                    )
                                }
                                message?.let { msg ->
                                    item(key = "__status__") {
                                        SettingsNote(msg, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                itemsIndexed(providers, key = { _, provider -> provider.id }) { index, provider ->
                                    val schemas = provider.settingsSchema
                                    ProviderRow(
                                        provider = provider,
                                        index = index,
                                        total = providers.size,
                                        isActive = provider.id == active?.id,
                                        updateState = updateStates[provider.id],
                                        hasPendingUpdate = AppModel.backend.hasPendingUpdate(provider.id),
                                        onActivate = { model.activate(provider, p) },
                                        onDelete = {
                                            confirm.request(
                                                title = p.deleteTitle,
                                                message = p.deleteMessage(
                                                    provider.name,
                                                    provider.id,
                                                    provider.id == active?.id,
                                                ),
                                                confirmLabel = p.deleteConfirm,
                                                onConfirm = { model.delete(provider.id, p) },
                                            )
                                        },
                                        onExport = { dest -> model.exportModuleTo(provider, dest, p) },
                                        onUpdate = {
                                            updateTarget = provider
                                            pickProviderZip()
                                        },
                                        onCheckUpdate = { model.checkUpdateFor(provider, p) },
                                        onShowUpdateDetails = { info -> updateDetailTarget = provider to info },
                                        onConfigureUpdateSource = { configSourceTarget = provider },
                                        onOpenSettings = if (!schemas.isNullOrEmpty()) {
                                            { navigator.push(ProviderCustomSettingsScreen(provider.id, provider.name, schemas)) }
                                        } else null,
                                    )
                                }
                            }
                        }
                    } else {
                        // ---------------- 歌词来源 ----------------
                        SettingsLazyPage(
                            pageModifier = Modifier.fillMaxSize(),
                            bottomInset = 88.dp,
                        ) {
                            item {
                                SettingsButtonItem(
                                    text = s.pluginRepository.title,
                                    subtitle = s.pluginRepository.subtitle,
                                    onClick = { navigator.push(PluginRepositoryScreen()) },
                                    index = 0, total = 1, icon = Icons.Filled.Extension,
                                )
                            }
                            item { SettingsNote(t.note) }
                            item { SettingsNote(t.orderNote) }

                            if (lyricsSources.isEmpty()) {
                                item { SettingsNote(t.emptyMessage) }
                            } else {
                                lyricsSources.forEachIndexed { index, source ->
                                    item(key = "lyrics-src-${source.id}") {
                                        SettingsSwitchItem(
                                            title = sourceTitle(source, t),
                                            subtitle = sourceSubtitle(source, t),
                                            checked = source.enabled,
                                            onCheckedChange = { checked ->
                                                scope.launch {
                                                    lyricsRegistry.setEnabled(source.id, checked)
                                                    if (!source.bundled) {
                                                        runCatching {
                                                            AppModel.backend.lyricsPlugins.setEnabled(source.id, checked)
                                                        }
                                                    }
                                                    refreshLyrics()
                                                }
                                            },
                                            index = index,
                                            total = lyricsSources.size,
                                            icon = Icons.Filled.Extension,
                                            leadingTrailingContent = {
                                                LyricsSourceRowActions(
                                                    source = source,
                                                    isFirst = index == 0,
                                                    isLast = index == lyricsSources.lastIndex,
                                                    t = t,
                                                    onMoveUp = { moveLyrics(source.id, -1) },
                                                    onMoveDown = { moveLyrics(source.id, +1) },
                                                    onDelete = {
                                                        confirm.request(
                                                            title = t.deleteConfirmTitle,
                                                            message = t.deleteConfirmMessage(source.name),
                                                            onConfirm = {
                                                                scope.launch {
                                                                    runCatching {
                                                                        AppModel.backend.lyricsPlugins.deletePlugin(source.id)
                                                                    }
                                                                    lyricsRegistry.reload()
                                                                    refreshLyrics()
                                                                }
                                                            },
                                                        )
                                                    },
                                                )
                                            },
                                        )
                                    }
                                }
                            }

                            item {
                                SettingsButtonItem(
                                    text = t.importAction,
                                    onClick = { pickLyricsZip() },
                                    index = 0,
                                    total = 1,
                                    icon = Icons.Filled.FileUpload,
                                )
                            }
                        }
                    }

                    busy?.let { label ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CpLoadingIndicator(Modifier.size(40.dp))
                                Spacer(Modifier.height(8.dp))
                                CpText(label)
                            }
                        }
                    }
                }
            }
        }

        updateDetailTarget?.let { (provider, info) ->
            ProviderUpdateDetailsDialog(
                provider = provider,
                info = info,
                onDismiss = { updateDetailTarget = null },
                onDownloadAndUpdate = {
                    updateDetailTarget = null
                    model.downloadAndInstallUpdate(provider, info, p)
                },
            )
        }

        configSourceTarget?.let { provider ->
            ProviderUpdateSourceDialog(
                provider = provider,
                currentCustomUrl = model.getCustomUpdateUrl(provider.id),
                manifestDefaultUrl = provider.updateUrl,
                onDismiss = { configSourceTarget = null },
                onSave = { newUrl ->
                    model.setCustomUpdateUrl(provider.id, newUrl)
                    configSourceTarget = null
                    model.checkUpdateFor(provider, p)
                },
                onResetDefault = {
                    model.setCustomUpdateUrl(provider.id, null)
                    configSourceTarget = null
                    model.checkUpdateFor(provider, p)
                },
            )
        }

        CpConfirmHost(confirm)
    }
}

class ProviderManagementModel : ScreenModel {
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
        screenModelScope.launch {
            val state = updateManager.checkUpdate(provider)
            when (state) {
                is ProviderUpdateState.UpdateAvailable -> {
                    message.value = strings.updateAvailable(provider.name, state.info.version)
                }
                is ProviderUpdateState.UpToDate -> {
                    message.value = strings.upToDate(provider.name)
                }
                is ProviderUpdateState.Error -> {
                    message.value = strings.checkFailed(provider.name, state.message)
                }
                else -> Unit
            }
        }
    }

    fun checkAllUpdates(strings: ProviderManagementStrings, silent: Boolean = false) {
        screenModelScope.launch {
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
                    it is ProviderUpdateState.UpdateAvailable
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
        screenModelScope.launch {
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
        if (zipPath == null) { message.value = strings.noFileSelected; return }
        screenModelScope.launch {
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
        if (zipPath == null) { message.value = strings.noFileSelected; return }
        screenModelScope.launch {
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
        screenModelScope.launch {
            val result = withContext(Dispatchers.IO) { AppModel.deleteProvider(id) }
            message.value = when (result) {
                is BackendResult.Success<*> -> strings.deleted(id)
                is BackendResult.Error -> result.message
                is BackendResult.Unsupported -> result.message
            }
        }
    }
}

@Composable
internal fun ProviderRow(
    provider: BackendProvider,
    index: Int,
    total: Int,
    isActive: Boolean,
    updateState: ProviderUpdateState? = null,
    hasPendingUpdate: Boolean = false,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
    onExport: (destPath: String?) -> Boolean,
    onUpdate: () -> Unit,
    onCheckUpdate: () -> Unit,
    onShowUpdateDetails: (ProviderUpdateInfo) -> Unit,
    onConfigureUpdateSource: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
) {
    val trailingTint = if (isActive) {
        settingsRowHighlightContent()
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    var menuOpen by remember { mutableStateOf(false) }
    val exportZip = rememberZipSaver(
        fileName = "${provider.id}-v${provider.version}.zip",
        onWriteTo = onExport,
    )

    val s = cpStrings()
    val p = s.providerManagement
    val capabilityText = provider.capabilities.orEmpty()
        .mapNotNull { s.player.capabilityLabel(it) }
        .joinToString(" · ")
    val hasConfig = !provider.settingsSchema.isNullOrEmpty()
    val pendingText = if (hasPendingUpdate) " · ${p.pendingRestartTag}" else ""

    val availableUpdate = updateState as? ProviderUpdateState.UpdateAvailable
    val isChecking = updateState is ProviderUpdateState.Checking

    val subtitle = buildString {
        append("v${provider.version} · ${provider.type.name}$pendingText")
        if (capabilityText.isNotEmpty()) append("\n$capabilityText")
    }

    SettingsClickItem(
        title = provider.name,
        subtitle = subtitle,
        icon = Icons.Filled.FolderZip,
        index = index,
        total = total,
        selected = isActive,
        mergeSemantics = false,
        onClick = onActivate,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isActive) {
                    Icon(Icons.Filled.CheckCircle, p.activeProviderContentDesc, tint = trailingTint)
                    Spacer(Modifier.width(8.dp))
                }
                if (availableUpdate != null) {
                    val updateInfo = availableUpdate.info
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onShowUpdateDetails(updateInfo) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            CpText(
                                text = p.updateAvailableBadge(updateInfo.version),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = p.moreActions(provider.name),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        if (availableUpdate != null) {
                            val updateInfo = availableUpdate.info
                            DropdownMenuItem(
                                text = { CpText(p.updateDetails(updateInfo.version)) },
                                leadingIcon = { Icon(Icons.Filled.Download, null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    menuOpen = false
                                    onShowUpdateDetails(updateInfo)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { CpText(if (isChecking) p.checkingUpdate else p.checkUpdate) },
                            leadingIcon = { Icon(Icons.Filled.Sync, null) },
                            enabled = !isChecking,
                            onClick = {
                                menuOpen = false
                                onCheckUpdate()
                            },
                        )
                        DropdownMenuItem(
                            text = { CpText(p.configureUpdateSource) },
                            leadingIcon = { Icon(Icons.Filled.Tune, null) },
                            onClick = {
                                menuOpen = false
                                onConfigureUpdateSource()
                            },
                        )
                        if (!provider.settingsSchema.isNullOrEmpty() && onOpenSettings != null) {
                            DropdownMenuItem(
                                text = { CpText(p.providerSettings) },
                                leadingIcon = { Icon(Icons.Filled.Tune, null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenSettings()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { CpText(p.exportModule) },
                            leadingIcon = { Icon(Icons.Filled.FileUpload, null) },
                            onClick = {
                                menuOpen = false
                                exportZip()
                            },
                        )
                        DropdownMenuItem(
                            text = { CpText(p.updateModuleLocal) },
                            leadingIcon = { Icon(Icons.Filled.Sync, null) },
                            onClick = {
                                menuOpen = false
                                onUpdate()
                            },
                        )
                        DropdownMenuItem(
                            text = { CpText(p.deleteModule) },
                            leadingIcon = { Icon(Icons.Filled.DeleteOutline, null) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        },
    )
}

// ---------------- 歌词来源相关辅助函数与组件 ----------------

@Composable
internal fun LyricsSourceRowActions(
    source: LyricsSourceEntry,
    isFirst: Boolean,
    isLast: Boolean,
    t: cp.player.app.i18n.LyricsPluginStrings,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    val p = cpStrings().providerManagement
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = p.moreActions(sourceTitle(source, t)),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { CpText(t.moveUp) },
                leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, null) },
                enabled = !isFirst,
                onClick = {
                    menuOpen = false
                    onMoveUp()
                },
            )
            DropdownMenuItem(
                text = { CpText(t.moveDown) },
                leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, null) },
                enabled = !isLast,
                onClick = {
                    menuOpen = false
                    onMoveDown()
                },
            )
            if (source.removable) {
                DropdownMenuItem(
                    text = { CpText(t.deleteAction) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

internal fun sourceTitle(
    source: LyricsSourceEntry,
    t: cp.player.app.i18n.LyricsPluginStrings,
): String = if (!source.localizeName) {
    source.name
} else {
    when (source.id) {
        BuiltinLyricsSourceIds.SIDECAR -> t.builtinSidecar
        BuiltinLyricsSourceIds.EMBEDDED -> t.builtinEmbedded
        BuiltinLyricsSourceIds.AMLL -> t.builtinAmll
        BuiltinLyricsSourceIds.LRCLIB -> t.builtinLrclib
        BuiltinLyricsSourceIds.PROVIDER -> t.builtinProvider
        else -> source.name
    }
}

internal fun sourceSubtitle(
    source: LyricsSourceEntry,
    t: cp.player.app.i18n.LyricsPluginStrings,
): String {
    val capabilities = source.capabilities.mapNotNull { capability ->
        when (capability) {
            LyricsCapability.SEARCH_SONGS -> t.capabilitySearchSongs
            LyricsCapability.GET_LYRICS -> t.capabilityGetLyrics
            LyricsCapability.SEARCH_COVERS -> t.capabilitySearchCovers
            LyricsCapability.LOOKUP_BY_ID -> t.capabilityLookupById
        }
    }.joinToString(" / ")

    val parts = if (source.bundled) {
        listOfNotNull(
            t.bundledBadge.takeIf { it.isNotBlank() },
            builtinNote(source, t)?.takeIf { it.isNotBlank() },
        )
    } else {
        listOfNotNull(
            listOfNotNull(
                source.author.takeIf { it.isNotBlank() },
                source.version.takeIf { it.isNotBlank() }?.let { "v$it" },
            ).joinToString(" · ").takeIf { it.isNotBlank() },
            capabilities.takeIf { it.isNotBlank() },
        )
    }
    val body = parts.joinToString(" — ")
    return if (source.enabled) body else "$body · ${t.disabledHint}"
}

internal fun builtinNote(
    source: LyricsSourceEntry,
    t: cp.player.app.i18n.LyricsPluginStrings,
): String? = when (source.id) {
    BuiltinLyricsSourceIds.SIDECAR -> t.builtinSidecarNote
    BuiltinLyricsSourceIds.EMBEDDED -> t.builtinEmbeddedNote
    BuiltinLyricsSourceIds.AMLL -> t.builtinAmllNote
    BuiltinLyricsSourceIds.LRCLIB -> t.builtinLrclibNote
    BuiltinLyricsSourceIds.PROVIDER -> t.builtinProviderNote
    else -> null
}

internal fun fileNameOf(path: String): String = path.replace('\\', '/').substringAfterLast('/')
