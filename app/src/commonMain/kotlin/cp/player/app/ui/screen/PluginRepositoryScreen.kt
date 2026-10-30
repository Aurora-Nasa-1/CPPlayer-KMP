package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import cp.player.app.repository.PluginRepository
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.SettingsButtonItem
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsLazyPage
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsNoteEmphasis
import cp.player.app.ui.component.SettingsSegmentedItem
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.component.rememberConfirmState
import cp.player.app.ui.util.popOrNotify
import cp.player.core.pluginrepo.PluginCatalogEntry
import cp.player.core.pluginrepo.PluginInstallPhase
import cp.player.core.pluginrepo.PluginRepositoryState
import cp.player.core.pluginrepo.PluginVersion
import cp.player.core.pluginrepo.RepositoryPluginKind
import cp.player.core.pluginrepo.requireRepositoryHttpsUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class PluginRepositoryScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { PluginRepositoryScreenModel() }
        LaunchedEffect(model) { model.load() }
        val state by model.repository.state.collectAsState()
        val error by model.error.collectAsState()
        CpRouteScaffold(cpStrings().pluginRepository.title, onBack = { navigator.popOrNotify() }) { modifier ->
            PluginRepositoryContent(
                state = state,
                modifier = modifier,
                error = error,
                pendingError = model.pendingError,
                actions = PluginRepositoryActions(
                    refresh = model::refresh,
                    install = model::install,
                    allowNetwork = model.repository::allowNetwork,
                    autoCheck = model.repository::autoCheck,
                    addSource = model::addSource,
                    enableSource = { url, enabled -> model.change { enableSource(url, enabled) } },
                    removeSource = { url -> model.change { removeSource(url) } },
                    cancelDownload = model.repository::cancelDownload,
                    manageProviders = { navigator.push(ProviderManagementScreen()) },
                    manageLyrics = { navigator.push(LyricsPluginSettingsScreen()) },
                ),
            )
        }
    }
}

class PluginRepositoryScreenModel(
    val repository: PluginRepository = PluginRepository.current(),
) : ScreenModel {
    val error = MutableStateFlow<String?>(null)
    val pendingError = AppModel.backend.pendingPluginUpdateError

    fun load() = launch { repository.load() }

    private fun launch(block: suspend () -> Unit) {
        screenModelScope.launch {
            error.value = null
            try { block() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error.value = failure.message ?: failure.toString()
            }
        }
    }

    fun refresh() = launch { repository.refresh() }
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

enum class PluginRepositoryTab { BROWSE, INSTALLED, UPDATES, REPOSITORIES }

data class PluginRepositoryActions(
    val refresh: () -> Unit = {},
    val install: (PluginCatalogEntry, Boolean) -> Unit = { _, _ -> },
    val allowNetwork: (Boolean) -> Unit = {},
    val autoCheck: (Boolean) -> Unit = {},
    val addSource: (String) -> Boolean = { false },
    val enableSource: (String, Boolean) -> Unit = { _, _ -> },
    val removeSource: (String) -> Unit = {},
    val cancelDownload: () -> Unit = {},
    val manageProviders: () -> Unit = {},
    val manageLyrics: () -> Unit = {},
)

internal fun filterRepositoryEntries(
    state: PluginRepositoryState,
    tab: PluginRepositoryTab,
    query: String,
    kind: Int,
): List<PluginCatalogEntry> = state.entries.filter { entry ->
    (tab != PluginRepositoryTab.UPDATES || entry.updateAvailable) &&
        (kind == 0 || kind == 1 && entry.plugin.kind == RepositoryPluginKind.PROVIDER ||
            kind == 2 && entry.plugin.kind == RepositoryPluginKind.LYRICS) &&
        listOf(entry.plugin.name, entry.plugin.author, entry.plugin.id).any { it.contains(query.trim(), ignoreCase = true) }
}

/** Plain-state content is also used by offscreen previews without initializing the backend. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PluginRepositoryContent(
    state: PluginRepositoryState,
    modifier: Modifier = Modifier,
    error: String? = null,
    pendingError: String? = null,
    initialTab: PluginRepositoryTab = PluginRepositoryTab.BROWSE,
    actions: PluginRepositoryActions = PluginRepositoryActions(),
) {
    val t = cpStrings().pluginRepository
    var tab by remember { mutableStateOf(initialTab) }
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(0) }
    var addingSource by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<PluginCatalogEntry?>(null) }
    val confirm = rememberConfirmState()
    val busy = state.task?.phase in setOf(
        PluginInstallPhase.DOWNLOADING, PluginInstallPhase.VERIFYING, PluginInstallPhase.INSTALLING,
    )
    val entries = filterRepositoryEntries(state, tab, query, kind)
    val tabLabels = listOf(t.browse, t.installed, t.updates, t.repositories)

    fun requestInstall(entry: PluginCatalogEntry) {
        val installed = entry.installed
        val managed = entry.managed
        val local = installed != null && (managed == null || managed.version != installed.version)
        details = null
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

    SettingsLazyPage(
        pageModifier = modifier,
        header = {
            SecondaryTabRow(selectedTabIndex = tab.ordinal) {
                PluginRepositoryTab.entries.forEach { value ->
                    Tab(
                        selected = tab == value,
                        onClick = { tab = value },
                        text = { CpText(tabLabels[value.ordinal], style = MaterialTheme.typography.labelMedium, maxLines = 2) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CpText(
                    if (state.refreshing) t.refreshing else t.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = Int.MAX_VALUE,
                )
                TextButton(
                    onClick = actions.refresh,
                    enabled = state.networkEnabled && !state.refreshing && state.sources.any { it.enabled },
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    CpText(t.refresh, maxLines = 2)
                }
            }
            if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (tab == PluginRepositoryTab.BROWSE || tab == PluginRepositoryTab.UPDATES) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { CpText(t.search) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                )
                SettingsSegmentedItem(
                    title = t.browse, options = listOf(t.all, t.providers, t.lyrics),
                    selectedIndex = kind, onSelect = { kind = it }, index = 0, total = 1,
                )
            }
        },
    ) {
        if (!state.networkEnabled) item {
            SettingsNote(t.offlineNote, emphasis = SettingsNoteEmphasis.WARNING)
        }
        error?.let { item { SettingsNote("${t.failed}: $it", emphasis = SettingsNoteEmphasis.ERROR) } }
        pendingError?.let { item { SettingsNote(t.pendingFailed(it), emphasis = SettingsNoteEmphasis.ERROR) } }
        state.sourceErrors.forEach { (url, message) -> item(key = "error:$url") {
            SettingsNote(t.sourceFailed(state.sources.find { it.url == url }?.name ?: url, message), emphasis = SettingsNoteEmphasis.ERROR)
        } }
        state.task?.let { task -> item {
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
                        task.phase == PluginInstallPhase.RESTART_REQUIRED || task.message != null -> SettingsNoteEmphasis.WARNING
                        else -> SettingsNoteEmphasis.INFO
                    },
                )
                if (task.phase == PluginInstallPhase.DOWNLOADING) {
                    LinearProgressIndicator(
                        progress = { if (task.totalBytes > 0) (task.downloadedBytes.toFloat() / task.totalBytes).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (task.phase == PluginInstallPhase.DOWNLOADING || task.phase == PluginInstallPhase.VERIFYING) {
                    TextButton(onClick = actions.cancelDownload) { CpText(t.cancel) }
                }
            }
        } }
        when (tab) {
            PluginRepositoryTab.REPOSITORIES -> {
                item {
                    SettingsSwitchItem(t.network, state.networkEnabled, actions.allowNetwork, 0, 2, subtitle = t.networkNote)
                }
                item {
                    SettingsSwitchItem(t.autoCheck, state.autoCheck, actions.autoCheck, 1, 2,
                        subtitle = t.autoCheckNote, enabled = state.networkEnabled)
                }
                item { SettingsNote(t.trustNote) }
                item {
                    SettingsButtonItem(t.addSource, { addingSource = true }, 0, 1, icon = Icons.Filled.Add)
                }
                state.sources.forEachIndexed { index, source -> item(key = source.url) {
                    SettingsSwitchItem(
                        title = source.name, subtitle = source.url,
                        checked = source.enabled,
                        onCheckedChange = { actions.enableSource(source.url, it) },
                        index = index, total = state.sources.size,
                        enabled = !busy || state.task?.sourceUrl != source.url,
                        leadingTrailingContent = {
                            IconButton(
                                enabled = !busy || state.task?.sourceUrl != source.url,
                                onClick = {
                                    confirm.request(
                                        t.removeSource, t.removeSourceMessage(source.url), destructive = false,
                                        onConfirm = { actions.removeSource(source.url) },
                                    )
                                },
                            ) { Icon(Icons.Filled.DeleteOutline, "${t.removeSource} ${source.name}") }
                        },
                    )
                } }
                if (state.sources.isEmpty()) item { SettingsNote(t.noSources) }
            }
            PluginRepositoryTab.INSTALLED -> {
                item { SettingsButtonItem(t.providers, actions.manageProviders, 0, 2) }
                item { SettingsButtonItem(t.lyrics, actions.manageLyrics, 1, 2) }
                if (state.installedPlugins.isEmpty()) item { SettingsNote(t.noInstalled) }
                state.installedPlugins.forEachIndexed { index, plugin -> item(key = plugin.key) {
                    val record = state.managedInstalls.find { it.key == plugin.key }
                    val entry = state.entries.find { it.plugin.key == plugin.key && it.source.url == record?.sourceUrl }
                    SettingsClickItem(
                        title = plugin.name,
                        subtitle = listOf(
                            "${plugin.key} · v${plugin.version}",
                            when {
                                record?.pendingRestart == true -> t.pendingRestart
                                plugin.bundled -> t.bundled
                                record != null -> record.sourceUrl
                                else -> t.local
                            },
                        ).joinToString("\n"),
                        icon = Icons.Filled.Extension, index = index, total = state.installedPlugins.size,
                        onClick = {
                            if (entry != null) details = entry
                            else if (plugin.key.startsWith("provider:")) actions.manageProviders() else actions.manageLyrics()
                        },
                    )
                } }
            }
            else -> {
                item { SettingsNote(t.cachedNote) }
                if (state.sources.none { it.enabled }) item {
                    SettingsButtonItem(t.repositories, { tab = PluginRepositoryTab.REPOSITORIES }, 0, 1, subtitle = t.noSources)
                }
                if (!state.networkEnabled) item {
                    SettingsButtonItem(t.network, { tab = PluginRepositoryTab.REPOSITORIES }, 0, 1)
                }
                if (entries.isEmpty()) item {
                    SettingsNote(when {
                        query.isNotBlank() || kind != 0 -> t.noMatches
                        tab == PluginRepositoryTab.UPDATES -> t.noUpdates
                        else -> t.emptyCatalog
                    })
                }
                entries.forEachIndexed { index, entry -> item(key = "${entry.source.url}:${entry.plugin.key}") {
                    val blocked = entry.blockReason(t)
                    SettingsClickItem(
                        title = "${entry.plugin.name} · ${entry.plugin.version}",
                        subtitle = listOf(
                            listOf(entry.plugin.author, entry.source.name).filter { it.isNotBlank() }.joinToString(" · "),
                            entry.plugin.description,
                            blocked.orEmpty(),
                        ).filter { it.isNotBlank() }.joinToString("\n"),
                        index = index, total = entries.size, icon = Icons.Filled.Extension,
                        mergeSemantics = false,
                        onClick = { details = entry },
                        trailingContent = {
                            IconButton(
                                onClick = { requestInstall(entry) },
                                enabled = blocked == null && state.networkEnabled && !busy,
                            ) { Icon(Icons.Filled.Download, "${if (entry.installed == null) t.install else t.update} ${entry.plugin.name}") }
                        },
                    )
                } }
            }
        }
    }
    details?.let { entry ->
        AlertDialog(
            onDismissRequest = { details = null },
            title = { CpText(entry.plugin.name, maxLines = Int.MAX_VALUE) },
            text = {
                SelectionContainer {
                    Column(
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(CpSpacing.formSectionGap),
                    ) {
                        CpText(t.identity(entry.plugin.id, entry.plugin.version, entry.source.url), maxLines = Int.MAX_VALUE)
                        CpText(entry.plugin.description, maxLines = Int.MAX_VALUE)
                        entry.artifact?.let {
                            CpText(t.packageInfo(it.platform, it.arch, it.runtime, it.bytes), maxLines = Int.MAX_VALUE)
                            CpText("SHA-256: ${it.sha256}", style = MaterialTheme.typography.bodySmall, maxLines = Int.MAX_VALUE)
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
                    onClick = { requestInstall(entry) },
                    enabled = entry.blockReason(t) == null && state.networkEnabled && !busy,
                ) { CpText(if (entry.installed == null) t.install else t.update) }
            },
            dismissButton = { TextButton(onClick = { details = null }) { CpText(t.close) } },
        )
    }
    if (addingSource) {
        var url by remember { mutableStateOf("") }
        val valid = runCatching { requireRepositoryHttpsUrl(url.trim()) }.isSuccess
        AlertDialog(
            onDismissRequest = { addingSource = false },
            title = { CpText(t.addSource) },
            text = {
                Column {
                    OutlinedTextField(
                        value = url, onValueChange = { url = it }, label = { CpText(t.sourceUrl) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        isError = url.isNotBlank() && !valid,
                    )
                    if (url.isNotBlank() && !valid) CpText(t.invalidUrl, maxLines = Int.MAX_VALUE)
                    error?.let { CpText(it, color = MaterialTheme.colorScheme.error, maxLines = Int.MAX_VALUE) }
                }
            },
            confirmButton = {
                TextButton(onClick = { if (actions.addSource(url)) addingSource = false }, enabled = valid) { CpText(t.addSource) }
            },
            dismissButton = { TextButton(onClick = { addingSource = false }) { Icon(Icons.Filled.Close, t.close) } },
        )
    }
    CpConfirmHost(confirm)
}

private fun PluginCatalogEntry.blockReason(t: PluginRepositoryStrings): String? {
    val managed = managed
    val installed = installed
    return when {
        managed?.pendingRestart == true -> t.pendingRestart
        installed?.bundled == true -> t.bundled
        incompatibility != null -> incompatibility
        installed == null -> null
        managed != null && managed.sourceUrl != source.url -> t.otherSource
        plugin.kind == RepositoryPluginKind.LYRICS &&
            (plugin.versionCode ?: 0) > (installed.versionCode ?: 0) -> null
        plugin.kind == RepositoryPluginKind.PROVIDER &&
            PluginVersion.compare(plugin.version, installed.version)?.let { it > 0 } == true -> null
        else -> t.upToDate
    }
}
