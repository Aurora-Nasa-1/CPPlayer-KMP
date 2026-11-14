package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.i18n.PluginRepositoryStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.rememberZipPicker
import cp.player.app.platform.sendPlatformToast
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpConfirmState
import cp.player.app.ui.component.CpLoadingIndicator
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.CpToggleChip
import cp.player.app.ui.component.CpViewSwitcher
import cp.player.app.ui.component.CpViewSwitcherItem
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.ProviderUpdateDetailsDialog
import cp.player.app.ui.component.ProviderUpdateSourceDialog
import cp.player.app.ui.component.SettingsButtonItem
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsLazyPage
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsNoteEmphasis
import cp.player.app.ui.component.SettingsSearchBar
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.component.rememberConfirmState
import cp.player.app.ui.util.popOrNotify
import cp.player.app.update.ProviderUpdateInfo
import cp.player.app.update.ProviderUpdateState
import cp.player.core.lyrics.LyricsSourceEntry
import cp.player.core.lyrics.LyricsSourceRegistry
import cp.player.core.pluginrepo.PluginCatalogEntry
import cp.player.core.pluginrepo.PluginInstallPhase
import cp.player.core.pluginrepo.PluginRepositoryState
import cp.player.core.pluginrepo.RepositoryPluginKind
import cp.player.core.pluginrepo.requireRepositoryHttpsUrl
import cp.player.core.provider.BackendProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/**
 * 合并后的插件中心。
 *
 * 已安装首屏直接列出音源、歌词来源和仓库插件；发现 / 更新 / 仓库是同一页的三个视图，
 * 用 M3 Expressive 连通切换器切换，不再保留 `TabRow`。列表统一走 [SettingsLazyPage]
 * 与 [SettingsSection]，行距 2dp，与设置页其余页面保持一致。
 */
class PluginHubScreen(
    private val initialView: PluginHubView = PluginHubView.INSTALLED,
) : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val repoModel = rememberScreenModel { PluginRepositoryScreenModel() }
        val providerModel = rememberScreenModel { ProviderManagementModel() }
        val p = cpStrings().providerManagement
        val t = cpStrings().pluginRepository

        LaunchedEffect(repoModel) { repoModel.load() }

        val repoState by repoModel.repository.state.collectAsState()
        val repoError by repoModel.error.collectAsState()
        val providers by providerModel.providers.collectAsState()
        val active by providerModel.active.collectAsState()
        val busy by providerModel.busy.collectAsState()
        val message by providerModel.message.collectAsState()
        val updateStates by providerModel.updateStates.collectAsState()
        val providerAutoCheck by providerModel.autoCheckEnabled.collectAsState()
        var checkingAll by remember { mutableStateOf(false) }
        var checkResult by remember { mutableStateOf<String?>(null) }

        val lyricsRegistry: LyricsSourceRegistry = AppModel.lyricsSources
        var lyricsSources by remember { mutableStateOf<List<LyricsSourceEntry>>(emptyList()) }
        val refreshLyrics: suspend () -> Unit = {
            lyricsSources = runCatching { lyricsRegistry.allSources() }.getOrDefault(emptyList())
        }
        LaunchedEffect(Unit) {
            refreshLyrics()
            if (providerModel.isAutoCheckEnabled()) {
                providerModel.checkAllUpdates(p, silent = true)
            }
        }
        LaunchedEffect(repoState.installedPlugins, repoState.task?.phase) {
            refreshLyrics()
        }

        var updateTarget by remember { mutableStateOf<BackendProvider?>(null) }
        var updateDetailTarget by remember { mutableStateOf<Pair<BackendProvider, ProviderUpdateInfo>?>(null) }
        var configSourceTarget by remember { mutableStateOf<BackendProvider?>(null) }

        val pickProviderZip = rememberZipPicker(onPicked = { zipPath ->
            val target = updateTarget
            updateTarget = null
            if (zipPath == null) return@rememberZipPicker
            if (target == null) {
                providerModel.importModule(zipPath, p)
            } else {
                providerModel.updateModule(target, zipPath, p)
            }
        })

        val lyricsStrings = cpStrings().lyricsPlugin
        val pickLyricsZip = rememberZipPicker(onPicked = { zipPath ->
            if (zipPath != null) {
                scope.launch {
                    val msg = runCatching { AppModel.backend.lyricsPlugins.importPluginZip(zipPath) }
                        .fold(
                            onSuccess = { ids ->
                                ids.forEach { AppModel.backend.pluginRepositories.forgetInstall("lyrics:$it") }
                                lyricsRegistry.reload()
                                if (ids.isEmpty()) lyricsStrings.importFailed else lyricsStrings.importSuccessCount(ids.size)
                            },
                            onFailure = { lyricsStrings.importFailed },
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

        val pendingProviderUpdates = remember(providers) {
            providers.filter { AppModel.backend.hasPendingUpdate(it.id) }.map { it.id }.toSet()
        }
        val uiState = PluginHubUiState(
            repository = repoState,
            providers = providers,
            activeProviderId = active?.id,
            providerUpdates = updateStates,
            providerPendingUpdates = pendingProviderUpdates,
            lyricsSources = lyricsSources,
            providerAutoCheck = providerAutoCheck,
            busy = busy,
            message = message,
            error = repoError,
            pendingError = repoModel.pendingError,
            checkingAll = checkingAll,
            checkResult = checkResult,
        )

        val actions = PluginHubActions(
            refreshRepository = repoModel::refresh,
            install = repoModel::install,
            allowNetwork = repoModel.repository::allowNetwork,
            autoCheckRepository = repoModel.repository::autoCheck,
            addSource = repoModel::addSource,
            enableSource = { url, enabled -> repoModel.change { enableSource(url, enabled) } },
            removeSource = { url -> repoModel.change { removeSource(url) } },
            cancelDownload = repoModel.repository::cancelDownload,
            checkAllUpdates = {
                if (!checkingAll) {
                    checkResult = null
                    checkingAll = true
                    scope.launch {
                        try {
                            val catalogChecked = repoState.networkEnabled
                            // 仓库目录与音源清单都检查完再报结果，避免「检查完成」早于请求返回。
                            if (catalogChecked) {
                                try {
                                    repoModel.error.value = null
                                    repoModel.repository.refresh()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    repoModel.reportError(failure)
                                }
                                // tryLock 失败时 refresh() 会立刻返回；等前一次刷新真正结束，
                                // 否则会把旧目录当成这次检查的结果。
                                repoModel.repository.state.first { !it.refreshing }
                            }
                            providerModel.checkAllUpdatesNow(p, silent = true)
                            val repoUpdates = if (catalogChecked) {
                                repoModel.repository.state.value.entries.count { it.updateAvailable }
                            } else {
                                0
                            }
                            val providerUpdates = providerModel.updateStates.value.values.count {
                                it is ProviderUpdateState.UpdateAvailable
                            }
                            val failures = (if (catalogChecked) repoModel.repository.state.value.sourceErrors.size else 0) +
                                providerModel.updateStates.value.values.count { it is ProviderUpdateState.Error }
                            checkResult = when {
                                !catalogChecked -> t.checkAllCatalogSkipped(providerUpdates)
                                repoUpdates + providerUpdates == 0 && failures == 0 -> t.checkAllUpToDate
                                else -> t.checkAllResult(repoUpdates + providerUpdates, failures)
                            }
                        } finally {
                            checkingAll = false
                        }
                    }
                }
            },
            importProvider = {
                updateTarget = null
                pickProviderZip()
            },
            activateProvider = { id ->
                providers.firstOrNull { it.id == id }?.let { providerModel.activate(it, p) }
            },
            deleteProvider = { id -> providerModel.delete(id, p) },
            exportProvider = { id, dest ->
                providers.firstOrNull { it.id == id }?.let { providerModel.exportModuleTo(it, dest, p) } ?: false
            },
            updateProvider = { id ->
                providers.firstOrNull { it.id == id }?.let { provider ->
                    updateTarget = provider
                    pickProviderZip()
                }
            },
            checkProviderUpdate = { id ->
                providers.firstOrNull { it.id == id }?.let { providerModel.checkUpdateFor(it, p) }
            },
            showProviderUpdateDetails = { id, info ->
                providers.firstOrNull { it.id == id }?.let { updateDetailTarget = it to info }
            },
            configureProviderUpdateSource = { id ->
                providers.firstOrNull { it.id == id }?.let { configSourceTarget = it }
            },
            openProviderSettings = { id ->
                providers.firstOrNull { it.id == id }?.let { provider ->
                    val schema = provider.settingsSchema
                    if (!schema.isNullOrEmpty()) {
                        navigator.push(ProviderCustomSettingsScreen(provider.id, provider.name, schema))
                    }
                }
            },
            setProviderAutoCheck = providerModel::setAutoCheckEnabled,
            importLyrics = { pickLyricsZip() },
            setLyricsEnabled = { id, enabled ->
                scope.launch {
                    lyricsRegistry.setEnabled(id, enabled)
                    if (lyricsSources.firstOrNull { it.id == id }?.bundled == false) {
                        runCatching { AppModel.backend.lyricsPlugins.setEnabled(id, enabled) }
                    }
                    refreshLyrics()
                }
            },
            moveLyrics = ::moveLyrics,
            deleteLyrics = { id ->
                scope.launch {
                    runCatching { AppModel.backend.lyricsPlugins.deletePlugin(id) }
                    lyricsRegistry.reload()
                    refreshLyrics()
                }
            },
        )

        CpRouteScaffold(
            title = cpStrings().pluginRepository.title,
            onBack = { navigator.popOrNotify() },
        ) { modifier ->
            PluginHubContent(
                state = uiState,
                modifier = modifier,
                initialView = initialView,
                actions = actions,
            )
        }

        updateDetailTarget?.let { (provider, info) ->
            ProviderUpdateDetailsDialog(
                provider = provider,
                info = info,
                onDismiss = { updateDetailTarget = null },
                onDownloadAndUpdate = {
                    updateDetailTarget = null
                    providerModel.downloadAndInstallUpdate(provider, info, p)
                },
            )
        }

        configSourceTarget?.let { provider ->
            ProviderUpdateSourceDialog(
                provider = provider,
                currentCustomUrl = providerModel.getCustomUpdateUrl(provider.id),
                manifestDefaultUrl = provider.updateUrl,
                onDismiss = { configSourceTarget = null },
                onSave = { newUrl ->
                    providerModel.setCustomUpdateUrl(provider.id, newUrl)
                    configSourceTarget = null
                    providerModel.checkUpdateFor(provider, p)
                },
                onResetDefault = {
                    providerModel.setCustomUpdateUrl(provider.id, null)
                    configSourceTarget = null
                    providerModel.checkUpdateFor(provider, p)
                },
            )
        }
    }
}

enum class PluginHubView { INSTALLED, DISCOVER, UPDATES, REPOSITORIES }

internal data class PluginHubUiState(
    val repository: PluginRepositoryState = PluginRepositoryState(),
    val providers: List<BackendProvider> = emptyList(),
    val activeProviderId: String? = null,
    val providerUpdates: Map<String, ProviderUpdateState> = emptyMap(),
    val providerPendingUpdates: Set<String> = emptySet(),
    val lyricsSources: List<LyricsSourceEntry> = emptyList(),
    val providerAutoCheck: Boolean = true,
    val busy: String? = null,
    val message: String? = null,
    val error: String? = null,
    val pendingError: String? = null,
    val checkingAll: Boolean = false,
    val checkResult: String? = null,
)

internal data class PluginHubActions(
    val refreshRepository: () -> Unit = {},
    val install: (PluginCatalogEntry, Boolean) -> Unit = { _, _ -> },
    val allowNetwork: (Boolean) -> Unit = {},
    val autoCheckRepository: (Boolean) -> Unit = {},
    val addSource: (String) -> Boolean = { false },
    val enableSource: (String, Boolean) -> Unit = { _, _ -> },
    val removeSource: (String) -> Unit = {},
    val cancelDownload: () -> Unit = {},
    val checkAllUpdates: () -> Unit = {},
    val importProvider: () -> Unit = {},
    val activateProvider: (String) -> Unit = {},
    val deleteProvider: (String) -> Unit = {},
    val exportProvider: (String, String?) -> Boolean = { _, _ -> false },
    val updateProvider: (String) -> Unit = {},
    val checkProviderUpdate: (String) -> Unit = {},
    val showProviderUpdateDetails: (String, ProviderUpdateInfo) -> Unit = { _, _ -> },
    val configureProviderUpdateSource: (String) -> Unit = {},
    val openProviderSettings: (String) -> Unit = {},
    val setProviderAutoCheck: (Boolean) -> Unit = {},
    val importLyrics: () -> Unit = {},
    val setLyricsEnabled: (String, Boolean) -> Unit = { _, _ -> },
    val moveLyrics: (String, Int) -> Unit = { _, _ -> },
    val deleteLyrics: (String) -> Unit = {},
)

/** Plain-state content is also used by offscreen previews without initializing the backend. */
@Composable
internal fun PluginHubContent(
    state: PluginHubUiState,
    modifier: Modifier = Modifier,
    initialView: PluginHubView = PluginHubView.INSTALLED,
    actions: PluginHubActions = PluginHubActions(),
) {
    val t = cpStrings().pluginRepository
    var view by remember { mutableStateOf(initialView) }
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(0) }
    var addingSource by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<PluginCatalogEntry?>(null) }
    val confirm = rememberConfirmState()

    val updateCount = remember(state) {
        state.repository.entries.count { it.updateAvailable } +
            state.providers.count { state.providerUpdates[it.id] is ProviderUpdateState.UpdateAvailable }
    }
    val installBusy = state.busy != null || state.repository.task?.phase in setOf(
        PluginInstallPhase.DOWNLOADING,
        PluginInstallPhase.VERIFYING,
        PluginInstallPhase.INSTALLING,
    )

    SettingsLazyPage(
        pageModifier = modifier,
        header = {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                CpViewSwitcher(
                    items = listOf(
                        CpViewSwitcherItem(t.installed, Icons.Filled.CheckCircle),
                        CpViewSwitcherItem(t.browse, Icons.Filled.Search),
                        CpViewSwitcherItem(
                            label = t.updates,
                            icon = Icons.Filled.Sync,
                            badge = updateCount.takeIf { it > 0 }?.toString(),
                        ),
                        CpViewSwitcherItem(t.repositories, Icons.Filled.Dns),
                    ),
                    selectedIndex = view.ordinal,
                    onSelect = { index -> view = PluginHubView.entries[index] },
                    compact = maxWidth < 600.dp,
                )
            }
            if (view == PluginHubView.DISCOVER) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsSearchBar(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = t.search,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalIconButton(
                        onClick = actions.refreshRepository,
                        modifier = Modifier.size(48.dp),
                        enabled = state.repository.networkEnabled &&
                            !state.repository.refreshing &&
                            !state.checkingAll,
                    ) {
                        if (state.repository.refreshing || state.checkingAll) {
                            CpLoadingIndicator(Modifier.size(20.dp))
                        } else {
                            Icon(Icons.Filled.Refresh, t.refresh, Modifier.size(20.dp))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(t.all, t.providers, t.lyrics).forEachIndexed { index, label ->
                        CpToggleChip(
                            checked = kind == index,
                            onCheckedChange = { checked -> if (checked) kind = index },
                            label = label,
                        )
                    }
                }
            }
        },
    ) {
        when (view) {
            PluginHubView.INSTALLED -> installedItems(state, actions, t, confirm, { details = it }, { view = it })
            PluginHubView.DISCOVER -> discoverItems(
                state, actions, t, query, kind, confirm,
                onShowDetails = { details = it },
                onOpenView = { view = it },
                installBusy = installBusy,
            )
            PluginHubView.UPDATES -> updatesItems(
                state, actions, t, confirm,
                onShowDetails = { details = it },
                installBusy = installBusy,
            )
            PluginHubView.REPOSITORIES -> repositoryItems(
                state, actions, t, confirm,
                onAddSource = { addingSource = true },
            )
        }
    }

    details?.let { entry ->
        PluginDetailsDialog(
            entry = entry,
            t = t,
            enabled = entry.blockReason(t) == null && state.repository.networkEnabled && !installBusy,
            onDismiss = { details = null },
            onInstall = {
                details = null
                requestInstall(entry, t, confirm, actions)
            },
        )
    }

    if (addingSource) {
        AddRepositoryDialog(
            t = t,
            error = state.error,
            onDismiss = { addingSource = false },
            onAdd = { url ->
                if (actions.addSource(url)) {
                    addingSource = false
                }
            },
        )
    }

    CpConfirmHost(confirm)
}

private fun requestInstall(
    entry: PluginCatalogEntry,
    t: PluginRepositoryStrings,
    confirm: CpConfirmState,
    actions: PluginHubActions,
) {
    val installed = entry.installed
    val managed = entry.managed
    val local = installed != null && (managed == null || managed.version != installed.version)
    confirm.request(
        title = t.installTitle(entry.plugin.name),
        message = listOf(
            t.identity(entry.plugin.id, entry.plugin.version, entry.source.url),
            if (local) t.replaceLocal else "",
            if (entry.plugin.kind == RepositoryPluginKind.PROVIDER) t.providerActivationNote else t.lyricsActivationNote,
            t.trustNote,
        ).filter { it.isNotBlank() }.joinToString("\n\n"),
        confirmLabel = if (local) t.replaceLocal else if (entry.installed == null) t.install else t.update,
        destructive = false,
        onConfirm = { actions.install(entry, local) },
    )
}

// ============================================================ 已安装

private fun LazyListScope.installedItems(
    state: PluginHubUiState,
    actions: PluginHubActions,
    t: PluginRepositoryStrings,
    confirm: CpConfirmState,
    onShowDetails: (PluginCatalogEntry) -> Unit,
    onOpenView: (PluginHubView) -> Unit,
) {
    if (hasHubBanners(state)) {
        item(key = "banners") { HubBanners(state, t, actions) }
    }
    if (state.providers.isEmpty() && state.lyricsSources.isEmpty()) {
        item(key = "intro") { SettingsNote(t.hubSubtitle) }
    }

    item(key = "providers") {
        SettingsSection(t.providersSection) {
            if (state.providers.isEmpty()) {
                SettingsButtonItem(
                    text = t.importProvider,
                    subtitle = t.noProvidersHint,
                    onClick = actions.importProvider,
                    index = 0,
                    total = 2,
                    icon = Icons.Filled.Add,
                )
                SettingsButtonItem(
                    text = t.discoverPlugins,
                    subtitle = t.discoverPluginsNote,
                    onClick = { onOpenView(PluginHubView.DISCOVER) },
                    index = 1,
                    total = 2,
                    icon = Icons.Filled.Search,
                )
            } else {
                val total = state.providers.size + 2
                state.providers.forEachIndexed { index, provider ->
                    ProviderRow(
                        provider = provider,
                        index = index,
                        total = total,
                        isActive = provider.id == state.activeProviderId,
                        updateState = state.providerUpdates[provider.id],
                        hasPendingUpdate = provider.id in state.providerPendingUpdates,
                        onActivate = { actions.activateProvider(provider.id) },
                        onDelete = {
                            confirm.request(
                                title = t.deleteProviderTitle,
                                message = t.deleteProviderMessage(
                                    provider.name,
                                    provider.id,
                                    provider.id == state.activeProviderId,
                                ),
                                confirmLabel = t.deleteProvider,
                                onConfirm = { actions.deleteProvider(provider.id) },
                            )
                        },
                        onExport = { dest -> actions.exportProvider(provider.id, dest) },
                        onUpdate = { actions.updateProvider(provider.id) },
                        onCheckUpdate = { actions.checkProviderUpdate(provider.id) },
                        onShowUpdateDetails = { info -> actions.showProviderUpdateDetails(provider.id, info) },
                        onConfigureUpdateSource = { actions.configureProviderUpdateSource(provider.id) },
                        onOpenSettings = provider.settingsSchema?.takeIf { it.isNotEmpty() }?.let {
                            { actions.openProviderSettings(provider.id) }
                        },
                    )
                }
                SettingsButtonItem(
                    text = t.importProvider,
                    subtitle = t.importProviderNote,
                    onClick = actions.importProvider,
                    index = state.providers.size,
                    total = total,
                    icon = Icons.Filled.Add,
                )
                SettingsSwitchItem(
                    title = t.autoCheckProvider,
                    subtitle = t.autoCheckProviderNote,
                    checked = state.providerAutoCheck,
                    onCheckedChange = actions.setProviderAutoCheck,
                    index = state.providers.size + 1,
                    total = total,
                    icon = Icons.Filled.Sync,
                )
            }
        }
    }

    item(key = "lyrics") {
        val lyricsStrings = cpStrings().lyricsPlugin
        SettingsSection(t.lyricsSection) {
            if (state.lyricsSources.isEmpty()) {
                SettingsButtonItem(
                    text = t.importLyrics,
                    subtitle = t.noLyricsHint,
                    onClick = actions.importLyrics,
                    index = 0,
                    total = 2,
                    icon = Icons.Filled.FileUpload,
                )
                SettingsButtonItem(
                    text = t.discoverPlugins,
                    subtitle = t.discoverPluginsNote,
                    onClick = { onOpenView(PluginHubView.DISCOVER) },
                    index = 1,
                    total = 2,
                    icon = Icons.Filled.Search,
                )
            } else {
                val total = state.lyricsSources.size + 1
                state.lyricsSources.forEachIndexed { index, source ->
                    SettingsSwitchItem(
                        title = sourceTitle(source, lyricsStrings),
                        subtitle = sourceSubtitle(source, lyricsStrings),
                        checked = source.enabled,
                        onCheckedChange = { actions.setLyricsEnabled(source.id, it) },
                        index = index,
                        total = total,
                        icon = Icons.Filled.Extension,
                        leadingTrailingContent = {
                            LyricsSourceRowActions(
                                source = source,
                                isFirst = index == 0,
                                isLast = index == state.lyricsSources.lastIndex,
                                t = lyricsStrings,
                                onMoveUp = { actions.moveLyrics(source.id, -1) },
                                onMoveDown = { actions.moveLyrics(source.id, +1) },
                                onDelete = {
                                    confirm.request(
                                        title = lyricsStrings.deleteConfirmTitle,
                                        message = lyricsStrings.deleteConfirmMessage(source.name),
                                        onConfirm = { actions.deleteLyrics(source.id) },
                                    )
                                },
                            )
                        },
                    )
                }
                SettingsButtonItem(
                    text = t.importLyrics,
                    subtitle = t.importLyricsNote,
                    onClick = actions.importLyrics,
                    index = state.lyricsSources.size,
                    total = total,
                    icon = Icons.Filled.FileUpload,
                )
            }
        }
    }

    item(key = "repository-plugins") {
        val represented = buildSet {
            state.providers.forEach { add("provider:${it.id}") }
            state.lyricsSources.forEach { add("lyrics:${it.id}") }
        }
        val remaining = state.repository.installedPlugins.filterNot { it.key in represented }
        if (remaining.isNotEmpty()) {
            SettingsSection(t.repositoryPluginsSection) {
                val total = remaining.size + 1
                remaining.forEachIndexed { index, plugin ->
                    val record = state.repository.managedInstalls.find { it.key == plugin.key }
                    val entry = state.repository.entries.find {
                        it.plugin.key == plugin.key && it.source.url == record?.sourceUrl
                    }
                    SettingsClickItem(
                        title = plugin.name,
                        subtitle = listOf(
                            "${plugin.key} · v${plugin.version}",
                            when {
                                record?.pendingRestart == true -> t.pendingRestartShort
                                plugin.bundled -> t.bundled
                                record != null -> record.sourceUrl
                                else -> t.local
                            },
                        ).joinToString("\n"),
                        icon = Icons.Filled.Extension,
                        index = index,
                        total = total,
                        showChevron = entry != null,
                        onClick = entry?.let { target -> { onShowDetails(target) } },
                    )
                }
                SettingsButtonItem(
                    text = t.discoverPlugins,
                    subtitle = t.discoverPluginsNote,
                    onClick = { onOpenView(PluginHubView.DISCOVER) },
                    index = remaining.size,
                    total = total,
                    icon = Icons.Filled.Search,
                )
            }
        }
    }
}

// ============================================================ 发现

private fun LazyListScope.discoverItems(
    state: PluginHubUiState,
    actions: PluginHubActions,
    t: PluginRepositoryStrings,
    query: String,
    kind: Int,
    confirm: CpConfirmState,
    onShowDetails: (PluginCatalogEntry) -> Unit,
    onOpenView: (PluginHubView) -> Unit,
    installBusy: Boolean,
) {
    if (!state.repository.networkEnabled) {
        item {
            SettingsButtonItem(
                text = t.network,
                subtitle = t.offlineNote,
                onClick = { onOpenView(PluginHubView.REPOSITORIES) },
                index = 0,
                total = 1,
                icon = Icons.Filled.Dns,
            )
        }
    }
    if (state.repository.sources.none { it.enabled }) {
        item {
            SettingsButtonItem(
                text = t.repositories,
                subtitle = t.noSourcesHint,
                onClick = { onOpenView(PluginHubView.REPOSITORIES) },
                index = 0,
                total = 1,
                icon = Icons.Filled.Add,
            )
        }
    }

    // 放在联网/仓库引导之后：断网时先给可执行的入口，再解释当前状态。
    if (hasHubBanners(state)) item { HubBanners(state, t, actions) }

    val entries = filterRepositoryEntries(state.repository, PluginRepositoryTab.BROWSE, query, kind)
    if (entries.isEmpty()) {
        item {
            ContentState(
                title = if (query.isNotBlank() || kind != 0) t.noMatches else t.emptyCatalog,
                message = if (query.isNotBlank() || kind != 0) null else t.discoverHint,
                actionLabel = t.refresh,
                onAction = actions.refreshRepository,
            )
        }
    } else {
        itemsIndexed(entries, key = { _, entry -> "${entry.source.url}:${entry.plugin.key}" }) { index, entry ->
            val blocked = entry.blockReason(t)
            SettingsClickItem(
                title = entry.plugin.name,
                subtitle = buildString {
                    append("v${entry.plugin.version}")
                    if (entry.updateAvailable) append(" · ${t.updateAvailable}")
                    listOf(entry.plugin.author, entry.source.name)
                        .filter { it.isNotBlank() }
                        .forEach { append(" · ").append(it) }
                    if (entry.plugin.description.isNotBlank()) {
                        append("\n").append(entry.plugin.description)
                    }
                    blocked?.let { append("\n").append(it) }
                },
                icon = Icons.Filled.Extension,
                index = index,
                total = entries.size,
                mergeSemantics = false,
                onClick = { onShowDetails(entry) },
                trailingContent = {
                    TextButton(
                        onClick = { requestInstall(entry, t, confirm, actions) },
                        enabled = blocked == null && state.repository.networkEnabled && !installBusy,
                    ) {
                        CpText(
                            when {
                                entry.installed == null -> t.install
                                entry.updateAvailable -> t.update
                                else -> t.installed
                            },
                            maxLines = 1,
                        )
                    }
                },
            )
        }
    }
}

// ============================================================ 更新

private fun LazyListScope.updatesItems(
    state: PluginHubUiState,
    actions: PluginHubActions,
    t: PluginRepositoryStrings,
    confirm: CpConfirmState,
    onShowDetails: (PluginCatalogEntry) -> Unit,
    installBusy: Boolean,
) {
    if (hasHubBanners(state)) item { HubBanners(state, t, actions) }
    item { SettingsNote(t.updatesHint) }

    val pluginUpdates = state.repository.entries.filter { it.updateAvailable }
    val providerUpdates = state.providers.mapNotNull { provider ->
        val update = state.providerUpdates[provider.id] as? ProviderUpdateState.UpdateAvailable
        update?.let { provider to it.info }
    }

    if (pluginUpdates.isEmpty() && providerUpdates.isEmpty()) {
        item {
            ContentState(
                title = if (state.checkingAll || state.repository.refreshing) t.refreshing else t.noUpdates,
                message = t.noUpdatesHint,
                actionLabel = t.checkAllUpdates,
                onAction = actions.checkAllUpdates,
            )
        }
        return
    }

    if (pluginUpdates.isNotEmpty()) {
        item { SettingsSection(t.updateSectionPlugins) {} }
        itemsIndexed(pluginUpdates, key = { _, entry -> "update:${entry.source.url}:${entry.plugin.key}" }) { index, entry ->
            val blocked = entry.blockReason(t)
            SettingsClickItem(
                title = entry.plugin.name,
                subtitle = buildString {
                    append(t.updateFromTo(entry.installed?.version ?: t.local, entry.plugin.version))
                    listOf(entry.plugin.author, entry.source.name)
                        .filter { it.isNotBlank() }
                        .forEach { append(" · ").append(it) }
                    blocked?.let { append("\n").append(it) }
                },
                icon = Icons.Filled.Download,
                index = index,
                total = pluginUpdates.size,
                mergeSemantics = false,
                onClick = { onShowDetails(entry) },
                trailingContent = {
                    TextButton(
                        onClick = { requestInstall(entry, t, confirm, actions) },
                        enabled = blocked == null && state.repository.networkEnabled && !installBusy,
                    ) {
                        CpText(t.update, maxLines = 1)
                    }
                },
            )
        }
    }

    if (providerUpdates.isNotEmpty()) {
        item { SettingsSection(t.updateSectionProviders) {} }
        item { SettingsNote(t.updateSectionProvidersHint) }
        itemsIndexed(providerUpdates, key = { _, item -> "provider-update:${item.first.id}" }) { index, item ->
            val (provider, info) = item
            SettingsClickItem(
                title = provider.name,
                subtitle = t.providerUpdateSummary(provider.name, provider.version, info.version),
                icon = Icons.Filled.Download,
                index = index,
                total = providerUpdates.size,
                mergeSemantics = false,
                onClick = { actions.showProviderUpdateDetails(provider.id, info) },
                trailingContent = {
                    TextButton(onClick = { actions.showProviderUpdateDetails(provider.id, info) }) {
                        CpText(t.update, maxLines = 1)
                    }
                },
            )
        }
    }

    item {
        SettingsButtonItem(
            text = t.checkAllUpdates,
            subtitle = if (state.checkingAll || state.repository.refreshing) t.refreshing else null,
            onClick = actions.checkAllUpdates,
            index = 0,
            total = 1,
            icon = Icons.Filled.Refresh,
            enabled = !state.checkingAll && !state.repository.refreshing,
        )
    }
}

// ============================================================ 仓库

private fun LazyListScope.repositoryItems(
    state: PluginHubUiState,
    actions: PluginHubActions,
    t: PluginRepositoryStrings,
    confirm: CpConfirmState,
    onAddSource: () -> Unit,
) {
    val repository = state.repository
    if (hasHubBanners(state)) item { HubBanners(state, t, actions) }
    item { SettingsNote(t.repositoriesHint) }

    item {
        SettingsSection(t.repositories) {
            SettingsSwitchItem(
                title = t.network,
                subtitle = t.networkNote,
                checked = repository.networkEnabled,
                onCheckedChange = actions.allowNetwork,
                index = 0,
                total = 2,
            )
            SettingsSwitchItem(
                title = t.autoCheck,
                subtitle = t.autoCheckNote,
                checked = repository.autoCheck,
                onCheckedChange = actions.autoCheckRepository,
                index = 1,
                total = 2,
                enabled = repository.networkEnabled,
            )
        }
    }
    item { SettingsNote(t.trustNote) }

    if (repository.sources.isEmpty()) {
        item {
            SettingsButtonItem(
                text = t.addSource,
                subtitle = t.noSourcesHint,
                onClick = onAddSource,
                index = 0,
                total = 1,
                icon = Icons.Filled.Add,
            )
        }
    } else {
        val total = repository.sources.size + 1
        itemsIndexed(repository.sources, key = { _, source -> source.url }) { index, source ->
            SettingsSwitchItem(
                title = source.name,
                subtitle = source.url,
                checked = source.enabled,
                onCheckedChange = { actions.enableSource(source.url, it) },
                index = index,
                total = total,
                leadingTrailingContent = {
                    IconButton(
                        onClick = {
                            confirm.request(
                                title = t.removeSource,
                                message = t.removeSourceMessage(source.url),
                                destructive = false,
                                onConfirm = { actions.removeSource(source.url) },
                            )
                        },
                    ) {
                        Icon(Icons.Filled.DeleteOutline, "${t.removeSource} ${source.name}")
                    }
                },
            )
        }
        item {
            SettingsButtonItem(
                text = t.addSource,
                subtitle = t.sourceUrl,
                onClick = onAddSource,
                index = repository.sources.size,
                total = total,
                icon = Icons.Filled.Add,
            )
        }
    }
}

// ============================================================ 状态提示

private fun hasHubBanners(state: PluginHubUiState): Boolean =
    !state.repository.networkEnabled ||
        state.error != null ||
        state.pendingError != null ||
        state.repository.sourceErrors.isNotEmpty() ||
        state.repository.task != null ||
        state.message != null ||
        state.busy != null ||
        state.repository.refreshing ||
        state.checkingAll ||
        state.checkResult != null

@Composable
private fun HubBanners(
    state: PluginHubUiState,
    t: PluginRepositoryStrings,
    actions: PluginHubActions,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
    ) {
        if (!state.repository.networkEnabled) {
            SettingsNote(t.offlineNote, emphasis = SettingsNoteEmphasis.WARNING)
        }
        if (state.repository.refreshing || state.checkingAll) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CpSpacing.formHorizontal, vertical = CpSpacing.listRowGap),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CpLoadingIndicator(Modifier.size(20.dp))
                CpText(t.refreshing, style = MaterialTheme.typography.bodySmall)
            }
        }
        state.checkResult?.let { SettingsNote(it) }
        state.error?.let {
            SettingsNote("${t.failed}: $it", emphasis = SettingsNoteEmphasis.ERROR)
        }
        state.pendingError?.let {
            SettingsNote(t.pendingFailed(it), emphasis = SettingsNoteEmphasis.ERROR)
        }
        state.repository.sourceErrors.forEach { (url, message) ->
            SettingsNote(
                t.sourceFailed(state.repository.sources.find { it.url == url }?.name ?: url, message),
                emphasis = SettingsNoteEmphasis.ERROR,
            )
        }
        state.repository.task?.let { task ->
            val label = when (task.phase) {
                PluginInstallPhase.DOWNLOADING -> "${t.downloading} · ${task.downloadedBytes} / ${task.totalBytes}"
                PluginInstallPhase.VERIFYING -> t.verifying
                PluginInstallPhase.INSTALLING -> t.installing
                PluginInstallPhase.COMPLETE -> listOfNotNull(t.complete, task.message).joinToString("\n")
                PluginInstallPhase.RESTART_REQUIRED -> t.pendingRestart
                PluginInstallPhase.FAILED -> "${t.failed}: ${task.message.orEmpty()}"
            }
            Column(verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap)) {
                SettingsNote(
                    "${task.key}\n$label",
                    emphasis = when {
                        task.phase == PluginInstallPhase.FAILED -> SettingsNoteEmphasis.ERROR
                        task.phase == PluginInstallPhase.RESTART_REQUIRED || task.message != null ->
                            SettingsNoteEmphasis.WARNING
                        else -> SettingsNoteEmphasis.INFO
                    },
                )
                if (task.phase == PluginInstallPhase.DOWNLOADING) {
                    LinearProgressIndicator(
                        progress = {
                            if (task.totalBytes > 0) {
                                (task.downloadedBytes.toFloat() / task.totalBytes).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (task.phase == PluginInstallPhase.DOWNLOADING || task.phase == PluginInstallPhase.VERIFYING) {
                    TextButton(onClick = actions.cancelDownload) { CpText(t.cancel) }
                }
            }
        }
        state.message?.let { SettingsNote(it) }
        state.busy?.let { label ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CpSpacing.formHorizontal, vertical = CpSpacing.listRowGap),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CpLoadingIndicator(Modifier.size(20.dp))
                CpText(label, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ============================================================ 对话框

@Composable
private fun PluginDetailsDialog(
    entry: PluginCatalogEntry,
    t: PluginRepositoryStrings,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { CpText(entry.plugin.name, maxLines = Int.MAX_VALUE) },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(CpSpacing.formSectionGap),
                ) {
                    CpText(
                        t.identity(entry.plugin.id, entry.plugin.version, entry.source.url),
                        maxLines = Int.MAX_VALUE,
                    )
                    CpText(entry.plugin.description, maxLines = Int.MAX_VALUE)
                    entry.artifact?.let {
                        CpText(t.packageInfo(it.platform, it.arch, it.runtime, it.bytes), maxLines = Int.MAX_VALUE)
                        CpText(
                            "SHA-256: ${it.sha256}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = Int.MAX_VALUE,
                        )
                    }
                    if (entry.plugin.changelog.isNotBlank()) {
                        CpText("${t.changelog}\n${entry.plugin.changelog}", maxLines = Int.MAX_VALUE)
                    }
                    entry.blockReason(t)?.let { CpText(it, maxLines = Int.MAX_VALUE) }
                    CpText(t.trustNote, maxLines = Int.MAX_VALUE)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onInstall,
                enabled = enabled && (entry.installed == null || entry.updateAvailable),
            ) {
                CpText(
                    when {
                        entry.installed == null -> t.install
                        entry.updateAvailable -> t.update
                        else -> t.close
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { CpText(t.close) } },
    )
}

@Composable
private fun AddRepositoryDialog(
    t: PluginRepositoryStrings,
    error: String?,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    val valid = runCatching { requireRepositoryHttpsUrl(url.trim()) }.isSuccess
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { CpText(t.addSource) },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { CpText(t.sourceUrl) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = url.isNotBlank() && !valid,
                )
                if (url.isNotBlank() && !valid) CpText(t.invalidUrl, maxLines = Int.MAX_VALUE)
                error?.let { CpText(it, color = MaterialTheme.colorScheme.error, maxLines = Int.MAX_VALUE) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(url) }, enabled = valid) { CpText(t.addSource) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { CpText(t.close) } },
    )
}
