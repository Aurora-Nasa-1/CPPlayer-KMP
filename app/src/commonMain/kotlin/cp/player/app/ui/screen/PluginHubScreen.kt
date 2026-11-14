package cp.player.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.i18n.PluginRepositoryStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.rememberZipPicker
import cp.player.app.platform.sendPlatformToast
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpLoadingIndicator
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.CpViewSwitcher
import cp.player.app.ui.component.CpViewSwitcherItem
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.ProviderUpdateDetailsDialog
import cp.player.app.ui.component.ProviderUpdateSourceDialog
import cp.player.app.ui.component.SettingsBadge
import cp.player.app.ui.component.SettingsButtonItem
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsNoteEmphasis
import cp.player.app.ui.component.SettingsSearchBar
import cp.player.app.ui.component.SettingsSegmentedItem
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.component.rememberConfirmState
import cp.player.app.ui.component.settingsRowHighlight
import cp.player.app.ui.component.settingsRowHighlightContent
import cp.player.app.ui.util.popOrNotify
import cp.player.app.update.ProviderUpdateInfo
import cp.player.app.update.ProviderUpdateState
import cp.player.core.lyrics.LyricsSourceEntry
import cp.player.core.lyrics.LyricsSourceRegistry
import cp.player.core.pluginrepo.PluginCatalogEntry
import cp.player.core.pluginrepo.PluginInstallPhase
import cp.player.core.pluginrepo.PluginRepositoryState
import cp.player.core.pluginrepo.PluginVersion
import cp.player.core.pluginrepo.RepositoryPluginKind
import cp.player.core.pluginrepo.requireRepositoryHttpsUrl
import cp.player.core.provider.BackendProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 合并后的插件中心。
 *
 * 取代原先「插件中心」与「音源与歌词管理」两个入口：
 * 已安装首屏同时呈现音源模块、歌词来源与仓库插件，发现 / 更新 / 仓库作为同一页的
 * 三个视图，用 M3 Expressive 的连通切换器切换，不再使用 `TabRow`。
 *
 * @param initialView 首屏视图。设置页搜索与「歌词来源」深链会传入目标视图。
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

        LaunchedEffect(repoModel) { repoModel.load() }

        val repoState by repoModel.repository.state.collectAsState()
        val repoError by repoModel.error.collectAsState()
        val providers by providerModel.providers.collectAsState()
        val active by providerModel.active.collectAsState()
        val busy by providerModel.busy.collectAsState()
        val message by providerModel.message.collectAsState()
        val updateStates by providerModel.updateStates.collectAsState()
        val providerAutoCheck by providerModel.autoCheckEnabled.collectAsState()

        val lyricsRegistry: LyricsSourceRegistry = AppModel.lyricsSources
        var lyricsSources by remember { mutableStateOf<List<LyricsSourceEntry>>(emptyList()) }
        val refreshLyrics: suspend () -> Unit = {
            lyricsSources = runCatching { lyricsRegistry.allSources() }.getOrDefault(emptyList())
        }
        LaunchedEffect(Unit) {
            refreshLyrics()
            if (providerModel.isAutoCheckEnabled()) {
                providerModel.checkAllUpdates(silent = true)
            }
        }

        var updateTarget by remember { mutableStateOf<BackendProvider?>(null) }
        var updateDetailTarget by remember { mutableStateOf<Pair<BackendProvider, ProviderUpdateInfo>?>(null) }
        var configSourceTarget by remember { mutableStateOf<BackendProvider?>(null) }

        val pickProviderZip = rememberZipPicker(onPicked = { zipPath ->
            val target = updateTarget
            updateTarget = null
            if (zipPath == null) return@rememberZipPicker
            if (target == null) providerModel.importModule(zipPath) else providerModel.updateModule(target, zipPath)
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
                repoModel.refresh()
                providerModel.checkAllUpdates(silent = false)
            },
            importProvider = {
                updateTarget = null
                pickProviderZip()
            },
            activateProvider = { id -> providers.firstOrNull { it.id == id }?.let(providerModel::activate) },
            deleteProvider = providerModel::delete,
            exportProvider = { id, dest ->
                providers.firstOrNull { it.id == id }?.let { providerModel.exportModuleTo(it, dest) } ?: false
            },
            updateProvider = { id ->
                providers.firstOrNull { it.id == id }?.let { provider ->
                    updateTarget = provider
                    pickProviderZip()
                }
            },
            checkProviderUpdate = { id -> providers.firstOrNull { it.id == id }?.let(providerModel::checkUpdateFor) },
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
                    providerModel.downloadAndInstallUpdate(provider, info)
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
                    providerModel.checkUpdateFor(provider)
                },
                onResetDefault = {
                    providerModel.setCustomUpdateUrl(provider.id, null)
                    configSourceTarget = null
                    providerModel.checkUpdateFor(provider)
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
    val enabledSources = state.repository.sources.count { it.enabled }
    val installedCount = state.providers.size + state.lyricsSources.size + state.repository.installedPlugins.size

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val compact = maxWidth < 600.dp
        val hubWidth = if (wide) 1040.dp else 720.dp

        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                modifier = Modifier
                    .widthIn(max = hubWidth)
                    .fillMaxWidth()
                    .padding(horizontal = CpSpacing.formHorizontal, vertical = 8.dp),
            ) {
                CpViewSwitcher(
                    items = listOf(
                        CpViewSwitcherItem(
                            label = t.installed,
                            icon = Icons.Filled.CheckCircle,
                            badge = installedCount.takeIf { it > 0 }?.toString(),
                        ),
                        CpViewSwitcherItem(t.browse, Icons.Filled.Search),
                        CpViewSwitcherItem(
                            label = t.updates,
                            icon = Icons.Filled.Sync,
                            badge = updateCount.takeIf { it > 0 }?.toString(),
                        ),
                        CpViewSwitcherItem(
                            label = t.repositories,
                            icon = Icons.Filled.Dns,
                            badge = enabledSources.takeIf { it > 0 }?.toString(),
                        ),
                    ),
                    selectedIndex = view.ordinal,
                    onSelect = { index -> view = PluginHubView.entries[index] },
                    compact = compact,
                )
            }

            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                when (view) {
                    PluginHubView.INSTALLED -> PluginHubInstalledView(
                        state = state,
                        actions = actions,
                        t = t,
                        wide = wide,
                        hubWidth = hubWidth,
                        confirm = confirm,
                        onShowDetails = { details = it },
                        onOpenView = { view = it },
                    )
                    PluginHubView.DISCOVER -> PluginHubDiscoverView(
                        state = state,
                        actions = actions,
                        t = t,
                        wide = wide,
                        hubWidth = hubWidth,
                        query = query,
                        onQueryChange = { query = it },
                        kind = kind,
                        onKindChange = { kind = it },
                        confirm = confirm,
                        onShowDetails = { details = it },
                        onOpenView = { view = it },
                    )
                    PluginHubView.UPDATES -> PluginHubUpdatesView(
                        state = state,
                        actions = actions,
                        t = t,
                        wide = wide,
                        hubWidth = hubWidth,
                        confirm = confirm,
                        onShowDetails = { details = it },
                        onOpenView = { view = it },
                    )
                    PluginHubView.REPOSITORIES -> PluginHubRepositoriesView(
                        state = state,
                        actions = actions,
                        t = t,
                        hubWidth = hubWidth,
                        confirm = confirm,
                        onAddSource = { addingSource = true },
                    )
                }
            }
        }
    }

    details?.let { entry ->
        PluginDetailsDialog(
            entry = entry,
            t = t,
            enabled = entry.blockReason(t) == null && state.repository.networkEnabled && state.busy == null,
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
    confirm: cp.player.app.ui.component.CpConfirmState,
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

@Composable
private fun PluginHubInstalledView(
    state: PluginHubUiState,
    actions: PluginHubActions,
    t: PluginRepositoryStrings,
    wide: Boolean,
    hubWidth: androidx.compose.ui.unit.Dp,
    confirm: cp.player.app.ui.component.CpConfirmState,
    onShowDetails: (PluginCatalogEntry) -> Unit,
    onOpenView: (PluginHubView) -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .widthIn(max = hubWidth)
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = CpSpacing.formHorizontal),
        verticalArrangement = Arrangement.spacedBy(CpSpacing.formSectionGap),
    ) {
        Spacer(Modifier.height(4.dp))
        PluginHubHero(state, t, wide, actions, onOpenView)
        HubBanners(state, t, actions)
        if (wide) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CpSpacing.formSectionGap),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(CpSpacing.formSectionGap),
                ) {
                    ProviderSection(state, actions, t, confirm)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(CpSpacing.formSectionGap),
                ) {
                    LyricsSection(state, actions, t, confirm)
                    RepositoryPluginsSection(state, t, onShowDetails, onOpenView)
                }
            }
        } else {
            ProviderSection(state, actions, t, confirm)
            LyricsSection(state, actions, t, confirm)
            RepositoryPluginsSection(state, t, onShowDetails, onOpenView)
        }
        Spacer(Modifier.height(CpSpacing.formBottomInset))
    }
}

@Composable
private fun PluginHubHero(
    state: PluginHubUiState,
    t: PluginRepositoryStrings,
    wide: Boolean,
    actions: PluginHubActions,
    onOpenView: (PluginHubView) -> Unit,
) {
    val enabledLyrics = state.lyricsSources.count { it.enabled }
    val enabledSources = state.repository.sources.count { it.enabled }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Extension,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    CpText(
                        text = t.hubTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    CpText(
                        text = t.hubSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HubStat(
                    value = state.providers.size.toString(),
                    label = t.statProviders(state.providers.size),
                    modifier = Modifier.weight(1f),
                )
                HubStat(
                    value = "$enabledLyrics/${state.lyricsSources.size}",
                    label = t.statLyrics(enabledLyrics, state.lyricsSources.size),
                    modifier = Modifier.weight(1f),
                )
                HubStat(
                    value = "$enabledSources/${state.repository.sources.size}",
                    label = t.statRepositories(enabledSources, state.repository.sources.size),
                    modifier = Modifier.weight(1f),
                )
            }

            if (wide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HubStep(1, t.stepInstall, Modifier.weight(1f))
                    HubStep(2, t.stepEnable, Modifier.weight(1f))
                    HubStep(3, t.stepTune, Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HubStep(1, t.stepInstall)
                    HubStep(2, t.stepEnable)
                    HubStep(3, t.stepTune)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { onOpenView(PluginHubView.DISCOVER) }) {
                    Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    CpText(t.discoverPlugins)
                }
                FilledTonalButton(
                    onClick = actions.checkAllUpdates,
                    enabled = !state.repository.refreshing,
                ) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    CpText(t.checkAllUpdates, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun HubStat(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CpText(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            CpText(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun HubStep(number: Int, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = 34.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                CpText(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        CpText(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun ProviderSection(
    state: PluginHubUiState,
    actions: PluginHubActions,
    t: PluginRepositoryStrings,
    confirm: cp.player.app.ui.component.CpConfirmState,
) {
    HubSectionCard(
        icon = Icons.Filled.Dns,
        title = t.providersSection,
        hint = t.providersHint,
        action = {
            TextButton(onClick = actions.importProvider) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                CpText(t.importProvider, maxLines = 1)
            }
        },
    ) {
        if (state.providers.isEmpty()) {
            ContentState(
                title = t.noProviders,
                message = t.noProvidersHint,
                actionLabel = t.importProvider,
                onAction = actions.importProvider,
            )
        } else {
            state.providers.forEachIndexed { index, provider ->
                ProviderRow(
                    provider = provider,
                    index = index,
                    total = state.providers.size,
                    isActive = provider.id == state.activeProviderId,
                    updateState = state.providerUpdates[provider.id],
                    hasPendingUpdate = provider.id in state.providerPendingUpdates,
                    onActivate = { actions.activateProvider(provider.id) },
                    onDelete = {
                        confirm.request(
                            title = "删除音源模块",
                            message = buildString {
                                append("确定删除「").append(provider.name)
                                append("」（").append(provider.id).append("）吗？\n")
                                append("模块文件与该音源下的登录态会被移除，需要重新导入才能使用。")
                                if (provider.id == state.activeProviderId) {
                                    append("\n它正是当前音源，删除后会自动切换到其他可用音源。")
                                }
                            },
                            confirmLabel = "删除",
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
            Spacer(Modifier.height(6.dp))
            SettingsSwitchItem(
                title = "自动检查音源更新",
                subtitle = "进入插件中心时自动检测音源最新版本，支持 manifest 与自定义更新源。",
                checked = state.providerAutoCheck,
                onCheckedChange = actions.setProviderAutoCheck,
                index = 0,
                total = 1,
                icon = Icons.Filled.Sync,
            )
        }
    }
}

@Composable
private fun LyricsSection(
    state: PluginHubUiState,
    actions: PluginHubActions,
    t: PluginRepositoryStrings,
    confirm: cp.player.app.ui.component.CpConfirmState,
) {
    val lyricsStrings = cpStrings().lyricsPlugin
    HubSectionCard(
        icon = Icons.Filled.FileUpload,
        title = t.lyricsSection,
        hint = t.lyricsHint,
        action = {
            TextButton(onClick = actions.importLyrics) {
                Icon(Icons.Filled.FileUpload, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                CpText(t.importLyrics, maxLines = 1)
            }
        },
    ) {
        if (state.lyricsSources.isEmpty()) {
            ContentState(
                title = t.noLyrics,
                message = t.noLyricsHint,
                actionLabel = t.importLyrics,
                onAction = actions.importLyrics,
            )
        } else {
            state.lyricsSources.forEachIndexed { index, source ->
                SettingsSwitchItem(
                    title = sourceTitle(source, lyricsStrings),
                    subtitle = sourceSubtitle(source, lyricsStrings),
                    checked = source.enabled,
                    onCheckedChange = { actions.setLyricsEnabled(source.id, it) },
                    index = index,
                    total = state.lyricsSources.size,
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
        }
    }
}

@Composable
private fun RepositoryPluginsSection(
    state: PluginHubUiState,
    t: PluginRepositoryStrings,
    onShowDetails: (PluginCatalogEntry) -> Unit,
    onOpenView: (PluginHubView) -> Unit,
) {
    val represented = remember(state) {
        buildSet {
            state.providers.forEach { add("provider:${it.id}") }
            state.lyricsSources.forEach { add("lyrics:${it.id}") }
        }
    }
    val remaining = state.repository.installedPlugins.filterNot { it.key in represented }
    HubSectionCard(
        icon = Icons.Filled.Extension,
        title = t.repositoryPluginsSection,
        hint = t.repositoryPluginsHint,
        action = {
            TextButton(onClick = { onOpenView(PluginHubView.DISCOVER) }) {
                CpText(t.discoverPlugins, maxLines = 1)
            }
        },
    ) {
        if (remaining.isEmpty()) {
            SettingsNote(t.noRepositoryPluginsHint)
        } else {
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
                    total = remaining.size,
                    showChevron = entry != null,
                    onClick = entry?.let { target -> { onShowDetails(target) } },
                )
            }
        }
    }
}

// ============================================================ 发现 / 更新

@Composable
private fun PluginHubDiscoverView(
    state: PluginHubUiState,
    actions: PluginHubActions,
    t: PluginRepositoryStrings,
    wide: Boolean,
    hubWidth: androidx.compose.ui.unit.Dp,
    query: String,
    onQueryChange: (String) -> Unit,
    kind: Int,
    onKindChange: (Int) -> Unit,
    confirm: cp.player.app.ui.component.CpConfirmState,
    onShowDetails: (PluginCatalogEntry) -> Unit,
    onOpenView: (PluginHubView) -> Unit,
) {
    val entries = remember(state.repository, query, kind) {
        filterRepositoryEntries(state.repository, PluginRepositoryTab.BROWSE, query, kind)
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            modifier = Modifier
                .widthIn(max = hubWidth)
                .fillMaxWidth()
                .padding(horizontal = CpSpacing.formHorizontal, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsSearchBar(
                query = query,
                onQueryChange = onQueryChange,
                placeholder = t.search,
            )
            SettingsSegmentedItem(
                title = t.browse,
                options = listOf(t.all, t.providers, t.lyrics),
                selectedIndex = kind,
                onSelect = onKindChange,
                index = 0,
                total = 1,
            )
        }
        HubLazyList(hubWidth) {
            item { HubBanners(state, t, actions) }
            item {
                SettingsNote(t.discoverHint)
            }
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
            if (entries.isEmpty()) {
                item {
                    ContentState(
                        title = when {
                            query.isNotBlank() || kind != 0 -> t.noMatches
                            else -> t.emptyCatalog
                        },
                        message = if (query.isNotBlank() || kind != 0) null else t.emptyCatalog,
                        actionLabel = t.refresh,
                        onAction = actions.refreshRepository,
                    )
                }
            } else {
                item {
                    CpText(
                        text = t.cachedNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = CpSpacing.formHorizontal),
                    )
                }
                entries.chunked(if (wide) 2 else 1).forEach { rowEntries ->
                    item(key = rowEntries.joinToString("|") { "${it.source.url}:${it.plugin.key}" }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = CpSpacing.formHorizontal),
                            horizontalArrangement = Arrangement.spacedBy(CpSpacing.formSectionGap),
                            verticalAlignment = Alignment.Top,
                        ) {
                            rowEntries.forEach { entry ->
                                Box(Modifier.weight(1f)) {
                                    PluginCatalogCard(
                                        entry = entry,
                                        t = t,
                                        networkEnabled = state.repository.networkEnabled,
                                        busy = state.busy != null,
                                        onDetails = { onShowDetails(entry) },
                                        onInstall = { requestInstall(entry, t, confirm, actions) },
                                    )
                                }
                            }
                            if (rowEntries.size == 1 && wide) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginHubUpdatesView(
    state: PluginHubUiState,
    actions: PluginHubActions,
    t: PluginRepositoryStrings,
    wide: Boolean,
    hubWidth: androidx.compose.ui.unit.Dp,
    confirm: cp.player.app.ui.component.CpConfirmState,
    onShowDetails: (PluginCatalogEntry) -> Unit,
    onOpenView: (PluginHubView) -> Unit,
) {
    val pluginUpdates = remember(state.repository) {
        state.repository.entries.filter { it.updateAvailable }
    }
    val providerUpdates = remember(state.providers, state.providerUpdates) {
        state.providers.mapNotNull { provider ->
            val update = state.providerUpdates[provider.id] as? ProviderUpdateState.UpdateAvailable
            update?.let { provider to it.info }
        }
    }
    HubLazyList(hubWidth) {
        item { HubBanners(state, t, actions) }
        item { SettingsNote(t.updatesHint) }
        if (pluginUpdates.isEmpty() && providerUpdates.isEmpty()) {
            item {
                ContentState(
                    title = t.noUpdates,
                    message = t.noUpdatesHint,
                    actionLabel = t.checkAllUpdates,
                    onAction = actions.checkAllUpdates,
                )
            }
        } else {
            if (pluginUpdates.isNotEmpty()) {
                item {
                    HubSectionTitle(t.updateSectionPlugins, Icons.Filled.Extension)
                }
                pluginUpdates.chunked(if (wide) 2 else 1).forEach { rowEntries ->
                    item(key = "update:" + rowEntries.joinToString("|") { it.plugin.key }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = CpSpacing.formHorizontal),
                            horizontalArrangement = Arrangement.spacedBy(CpSpacing.formSectionGap),
                            verticalAlignment = Alignment.Top,
                        ) {
                            rowEntries.forEach { entry ->
                                Box(Modifier.weight(1f)) {
                                    PluginCatalogCard(
                                        entry = entry,
                                        t = t,
                                        networkEnabled = state.repository.networkEnabled,
                                        busy = state.busy != null,
                                        onDetails = { onShowDetails(entry) },
                                        onInstall = { requestInstall(entry, t, confirm, actions) },
                                    )
                                }
                            }
                            if (rowEntries.size == 1 && wide) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            if (providerUpdates.isNotEmpty()) {
                item {
                    HubSectionTitle(t.updateSectionProviders, Icons.Filled.Dns)
                }
                item { SettingsNote(t.updateSectionProvidersHint) }
                providerUpdates.forEachIndexed { index, (provider, info) ->
                    item(key = "provider-update:${provider.id}") {
                        SettingsClickItem(
                            title = provider.name,
                            subtitle = t.providerUpdateSummary(provider.name, provider.version, info.version),
                            icon = Icons.Filled.Download,
                            index = index,
                            total = providerUpdates.size,
                            showChevron = true,
                            onClick = { actions.showProviderUpdateDetails(provider.id, info) },
                            trailingContent = {
                                FilledTonalButton(
                                    onClick = { actions.showProviderUpdateDetails(provider.id, info) },
                                ) {
                                    CpText(t.update)
                                }
                            },
                        )
                    }
                }
            }
            item {
                SettingsButtonItem(
                    text = t.checkAllUpdates,
                    onClick = actions.checkAllUpdates,
                    index = 0,
                    total = 1,
                    icon = Icons.Filled.Refresh,
                )
            }
        }
    }
}

// ============================================================ 仓库

@Composable
private fun PluginHubRepositoriesView(
    state: PluginHubUiState,
    actions: PluginHubActions,
    t: PluginRepositoryStrings,
    hubWidth: androidx.compose.ui.unit.Dp,
    confirm: cp.player.app.ui.component.CpConfirmState,
    onAddSource: () -> Unit,
) {
    val repository = state.repository
    HubLazyList(hubWidth) {
        item { HubBanners(state, t, actions) }
        item {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CpSpacing.formHorizontal),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    CpText(
                        text = t.repositoriesHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = Int.MAX_VALUE,
                    )
                }
            }
        }
        item {
            SettingsSwitchItem(
                title = t.network,
                subtitle = t.networkNote,
                checked = repository.networkEnabled,
                onCheckedChange = actions.allowNetwork,
                index = 0,
                total = 2,
            )
        }
        item {
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
        item { SettingsNote(t.trustNote) }
        item {
            SettingsButtonItem(
                text = t.addSource,
                subtitle = t.sourceUrl,
                onClick = onAddSource,
                index = 0,
                total = 1,
                icon = Icons.Filled.Add,
            )
        }
        if (repository.sources.isEmpty()) {
            item {
                ContentState(
                    title = t.noSources,
                    message = t.noSourcesHint,
                    actionLabel = t.addSource,
                    onAction = onAddSource,
                )
            }
        } else {
            repository.sources.forEachIndexed { index, source ->
                item(key = source.url) {
                    SettingsSwitchItem(
                        title = source.name,
                        subtitle = source.url,
                        checked = source.enabled,
                        onCheckedChange = { actions.enableSource(source.url, it) },
                        index = index,
                        total = repository.sources.size,
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
            }
        }
    }
}

// ============================================================ 公共组件

@Composable
private fun HubBanners(
    state: PluginHubUiState,
    t: PluginRepositoryStrings,
    actions: PluginHubActions,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!state.repository.networkEnabled) {
            SettingsNote(t.offlineNote, emphasis = SettingsNoteEmphasis.WARNING)
        }
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    .padding(horizontal = CpSpacing.formHorizontal, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CpLoadingIndicator(Modifier.size(20.dp))
                CpText(label, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun HubSectionCard(
    icon: ImageVector,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    CpText(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    CpText(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                    )
                }
                action?.invoke()
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            content()
        }
    }
}

@Composable
private fun HubSectionTitle(title: String, icon: ImageVector) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CpSpacing.formHorizontal, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        CpText(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun HubLazyList(
    hubWidth: androidx.compose.ui.unit.Dp,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .widthIn(max = hubWidth)
            .fillMaxSize(),
        contentPadding = PaddingValues(
            top = 4.dp,
            bottom = CpSpacing.formBottomInset,
        ),
        verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
        content = content,
    )
}

@Composable
private fun PluginCatalogCard(
    entry: PluginCatalogEntry,
    t: PluginRepositoryStrings,
    networkEnabled: Boolean,
    busy: Boolean,
    onDetails: () -> Unit,
    onInstall: () -> Unit,
) {
    val blocked = entry.blockReason(t)
    val actionEnabled = blocked == null && networkEnabled && !busy
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDetails),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Extension,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CpText(
                            text = entry.plugin.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        SettingsBadge(
                            text = if (entry.plugin.kind == RepositoryPluginKind.PROVIDER) t.providers else t.lyrics,
                        )
                    }
                    CpText(
                        text = listOf(entry.plugin.author, entry.source.name)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                CpText(
                    text = "v${entry.plugin.version}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.plugin.description.isNotBlank()) {
                CpText(
                    text = entry.plugin.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when {
                    entry.updateAvailable -> SettingsBadge(
                        text = "${t.updateAvailable} v${entry.plugin.version}",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    entry.installed != null -> SettingsBadge(t.installed)
                    entry.managed != null -> SettingsBadge(t.managedByRepository)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDetails) { CpText(t.details) }
                FilledTonalButton(
                    onClick = onInstall,
                    enabled = actionEnabled,
                ) {
                    CpText(
                        if (entry.installed == null) t.install else t.update,
                        maxLines = 1,
                    )
                }
            }
            blocked?.let {
                CpText(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                )
            }
        }
    }
}

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
            androidx.compose.foundation.text.selection.SelectionContainer {
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
            Button(onClick = onInstall, enabled = enabled) {
                CpText(if (entry.installed == null) t.install else t.update)
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
