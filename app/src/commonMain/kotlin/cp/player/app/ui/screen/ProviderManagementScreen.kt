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
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
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
            if (target == null) model.importModule(zipPath) else model.updateModule(target, zipPath)
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
                model.checkAllUpdates(silent = true)
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
            title = "音源与歌词管理",
            onBack = { navigator.popOrNotify() },
            topBarActions = listOf(
                cp.player.app.ui.component.TopBarAction(
                    icon = { Icon(Icons.Filled.Sync, "检查音源更新") },
                    onClick = {
                        if (selectedTab == 0) {
                            model.checkAllUpdates(silent = false)
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
                        contentDescription = if (selectedTab == 0) "导入音源模块" else "导入歌词插件",
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
                            text = { CpText("音源模块") },
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { CpText("歌词来源") },
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
                                    title = "尚未加载任何音源模块",
                                    message = "导入 .zip 模块包即可开始使用，模块包需含 manifest.json",
                                    actionLabel = "导入音源模块",
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
                                        text = "导入音源模块",
                                        subtitle = "选择 .zip 模块包（需含 manifest.json）",
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
                                        title = "自动检查音源更新",
                                        subtitle = "进入本页时自动检测音源最新版本，支持 manifest 及自定义更新源",
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
                                        onActivate = { model.activate(provider) },
                                        onDelete = {
                                            confirm.request(
                                                title = "删除音源模块",
                                                message = buildString {
                                                    append("确定删除「").append(provider.name)
                                                    append("」（").append(provider.id).append("）吗？\n")
                                                    append("模块文件与该音源下的登录态会被移除，需要重新导入才能使用。")
                                                    if (provider.id == active?.id) {
                                                        append("\n它正是当前音源，删除后会自动切换到其他可用音源。")
                                                    }
                                                },
                                                confirmLabel = "删除",
                                                onConfirm = { model.delete(provider.id) },
                                            )
                                        },
                                        onExport = { dest -> model.exportModuleTo(provider, dest) },
                                        onUpdate = {
                                            updateTarget = provider
                                            pickProviderZip()
                                        },
                                        onCheckUpdate = { model.checkUpdateFor(provider) },
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
                    model.downloadAndInstallUpdate(provider, info)
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
                    model.checkUpdateFor(provider)
                },
                onResetDefault = {
                    model.setCustomUpdateUrl(provider.id, null)
                    configSourceTarget = null
                    model.checkUpdateFor(provider)
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

    fun checkUpdateFor(provider: BackendProvider) {
        screenModelScope.launch {
            val state = updateManager.checkUpdate(provider)
            when (state) {
                is ProviderUpdateState.UpdateAvailable -> {
                    message.value = "${provider.name} 发现新版本 v${state.info.version}"
                }
                is ProviderUpdateState.UpToDate -> {
                    message.value = "${provider.name} 已是最新版本"
                }
                is ProviderUpdateState.Error -> {
                    message.value = "${provider.name} 检查更新失败: ${state.message}"
                }
                else -> Unit
            }
        }
    }

    fun checkAllUpdates(silent: Boolean = false) {
        screenModelScope.launch {
            val list = providers.value
            if (!silent) busy.value = "正在检查音源更新…"
            updateManager.checkAllUpdates(list)
            if (!silent) {
                busy.value = null
                val availableCount = updateManager.updateStates.value.values.count { it is ProviderUpdateState.UpdateAvailable }
                message.value = if (availableCount > 0) {
                    "检查完成，发现 $availableCount 个音源有新版本"
                } else {
                    "检查完成，所有音源均为最新版本"
                }
            }
        }
    }

    fun downloadAndInstallUpdate(provider: BackendProvider, info: ProviderUpdateInfo) {
        screenModelScope.launch {
            busy.value = "正在下载 ${provider.name} v${info.version}…"
            val result = runCatching {
                updateManager.downloadAndInstall(provider, info) { written, total ->
                    if (total != null && total > 0) {
                        val pct = ((written * 100) / total).coerceIn(0, 100)
                        busy.value = "正在下载 ${provider.name} v${info.version} ($pct%)…"
                    }
                }
            }.fold(
                onSuccess = { it },
                onFailure = { ImportResult.Failed(it.message ?: "下载更新失败") },
            )
            busy.value = null
            message.value = when (result) {
                is ImportResult.Activated -> "已更新并激活 ${result.provider.name} (v${info.version})"
                is ImportResult.Loaded -> "已更新 ${result.provider.name} (v${info.version})"
                is ImportResult.PendingRestart -> result.message
                is ImportResult.Failed -> result.message
            }
        }
    }

    fun importModule(zipPath: String?) {
        if (zipPath == null) { message.value = "未选择文件"; return }
        screenModelScope.launch {
            busy.value = "正在导入…"
            val result = withContext(Dispatchers.IO) { AppModel.importModule(zipPath) }
            busy.value = null
            message.value = when (result) {
                is ImportResult.Activated -> if (result.isUpdate) "已自动更新并激活 ${result.provider.name}" else "已导入并自动激活 ${result.provider.name}"
                is ImportResult.Loaded -> if (result.isUpdate) "已自动更新 ${result.provider.name}（当前仍使用 ${AppModel.activeProvider()?.name ?: "无"}）" else "已导入 ${result.provider.name}（当前仍使用 ${AppModel.activeProvider()?.name ?: "无"}）"
                is ImportResult.PendingRestart -> result.message
                is ImportResult.Failed -> result.message
            }
        }
    }

    fun updateModule(provider: BackendProvider, zipPath: String?) {
        if (zipPath == null) { message.value = "未选择文件"; return }
        screenModelScope.launch {
            busy.value = "正在更新 ${provider.name}…"
            val result = withContext(Dispatchers.IO) { AppModel.updateModule(zipPath, provider.id) }
            busy.value = null
            message.value = when (result) {
                is ImportResult.Activated -> "已更新并激活 ${result.provider.name}"
                is ImportResult.Loaded -> "已更新 ${result.provider.name}（当前仍使用 ${AppModel.activeProvider()?.name ?: "无"}）"
                is ImportResult.PendingRestart -> result.message
                is ImportResult.Failed -> result.message
            }
        }
    }

    fun exportModuleTo(provider: BackendProvider, destPath: String?): Boolean {
        if (destPath == null) return false
        busy.value = "正在导出 ${provider.name}…"
        val result = AppModel.exportModule(provider.id, destPath)
        busy.value = null
        message.value = when (result) {
            is BackendResult.Success -> "已导出 ${provider.name} → ${fileNameOf(destPath)}"
            is BackendResult.Error -> result.message
            is BackendResult.Unsupported -> result.message
        }
        return result is BackendResult.Success
    }

    fun activate(provider: BackendProvider) {
        val ok = AppModel.switchOrReport(provider)
        message.value = if (ok) "已切换到 ${provider.name}" else AppModel.lastSwitchError ?: "切换失败"
    }

    fun delete(id: String) {
        screenModelScope.launch {
            val result = withContext(Dispatchers.IO) { AppModel.deleteProvider(id) }
            message.value = when (result) {
                is BackendResult.Success<*> -> "已删除模块 $id"
                is BackendResult.Error -> result.message
                is BackendResult.Unsupported -> result.message
            }
        }
    }
}

@Composable
private fun ProviderRow(
    provider: BackendProvider,
    index: Int,
    total: Int,
    isActive: Boolean,
    updateState: ProviderUpdateState? = null,
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
    val capabilityText = provider.capabilities.orEmpty()
        .mapNotNull { s.player.capabilityLabel(it) }
        .joinToString(" · ")
    val apiText = if (provider.apiVersion > 1) " · API v${provider.apiVersion}" else ""
    val tagText = provider.apiVersionTag?.let { " · $it" }.orEmpty()
    val hasConfig = !provider.settingsSchema.isNullOrEmpty()
    val configText = if (hasConfig) " · 支持自定义配置" else ""
    val hasPendingUpdate = remember(provider.id) { AppModel.backend.hasPendingUpdate(provider.id) }
    val pendingText = if (hasPendingUpdate) " · 待重启生效" else ""

    val availableUpdate = updateState as? ProviderUpdateState.UpdateAvailable
    val hasUpdate = availableUpdate != null
    val isChecking = updateState is ProviderUpdateState.Checking
    val updateSubtitle = if (availableUpdate != null) {
        " · 发现新版本 v${availableUpdate.info.version}"
    } else ""

    val subtitle = buildString {
        append("${provider.type.name} · v${provider.version} · ${provider.id}$tagText$apiText$configText$pendingText$updateSubtitle")
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
                    Icon(Icons.Filled.CheckCircle, "当前音源", tint = trailingTint)
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
                                text = "可更新 v${updateInfo.version}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }
                if (hasConfig && onOpenSettings != null) {
                    IconButton(
                        onClick = onOpenSettings,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Filled.Tune,
                            contentDescription = "音源配置 ${provider.name}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "更多操作 ${provider.name}",
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
                                text = { CpText("更新详情 (v${updateInfo.version})") },
                                leadingIcon = { Icon(Icons.Filled.Download, null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    menuOpen = false
                                    onShowUpdateDetails(updateInfo)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { CpText(if (isChecking) "正在检查更新…" else "检查更新") },
                            leadingIcon = { Icon(Icons.Filled.Sync, null) },
                            enabled = !isChecking,
                            onClick = {
                                menuOpen = false
                                onCheckUpdate()
                            },
                        )
                        DropdownMenuItem(
                            text = { CpText("设置更新源") },
                            leadingIcon = { Icon(Icons.Filled.Tune, null) },
                            onClick = {
                                menuOpen = false
                                onConfigureUpdateSource()
                            },
                        )
                        if (!provider.settingsSchema.isNullOrEmpty() && onOpenSettings != null) {
                            DropdownMenuItem(
                                text = { CpText("音源配置") },
                                leadingIcon = { Icon(Icons.Filled.Tune, null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenSettings()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { CpText("导出模块") },
                            leadingIcon = { Icon(Icons.Filled.FileUpload, null) },
                            onClick = {
                                menuOpen = false
                                exportZip()
                            },
                        )
                        DropdownMenuItem(
                            text = { CpText("更新模块 (本地导入)") },
                            leadingIcon = { Icon(Icons.Filled.Sync, null) },
                            onClick = {
                                menuOpen = false
                                onUpdate()
                            },
                        )
                        DropdownMenuItem(
                            text = { CpText("删除模块") },
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
private fun LyricsSourceRowActions(
    source: LyricsSourceEntry,
    isFirst: Boolean,
    isLast: Boolean,
    t: cp.player.app.i18n.LyricsPluginStrings,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    val label = { action: String -> "$action ${sourceTitle(source, t)}" }
    Row(verticalAlignment = Alignment.CenterVertically) {
        SettingsRowIconButton(
            icon = Icons.Filled.KeyboardArrowUp,
            contentDescription = label(t.moveUp),
            enabled = !isFirst,
            tint = tint,
            onClick = onMoveUp,
        )
        SettingsRowIconButton(
            icon = Icons.Filled.KeyboardArrowDown,
            contentDescription = label(t.moveDown),
            enabled = !isLast,
            tint = tint,
            onClick = onMoveDown,
        )
        if (source.removable) {
            SettingsRowIconButton(
                icon = Icons.Filled.DeleteOutline,
                contentDescription = label(t.deleteAction),
                enabled = true,
                tint = MaterialTheme.colorScheme.error,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun SettingsRowIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    tint: Color,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun sourceTitle(
    source: LyricsSourceEntry,
    t: cp.player.app.i18n.LyricsPluginStrings,
): String = if (!source.localizeName) {
    source.name
} else {
    when (source.id) {
        BuiltinLyricsSourceIds.SIDECAR -> t.builtinSidecar
        BuiltinLyricsSourceIds.EMBEDDED -> t.builtinEmbedded
        BuiltinLyricsSourceIds.AMLL -> t.builtinAmll
        BuiltinLyricsSourceIds.PROVIDER -> t.builtinProvider
        else -> source.name
    }
}

private fun sourceSubtitle(
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

private fun builtinNote(
    source: LyricsSourceEntry,
    t: cp.player.app.i18n.LyricsPluginStrings,
): String? = when (source.id) {
    BuiltinLyricsSourceIds.SIDECAR -> t.builtinSidecarNote
    BuiltinLyricsSourceIds.EMBEDDED -> t.builtinEmbeddedNote
    BuiltinLyricsSourceIds.AMLL -> t.builtinAmllNote
    BuiltinLyricsSourceIds.PROVIDER -> t.builtinProviderNote
    else -> null
}

private fun fileNameOf(path: String): String = path.replace('\\', '/').substringAfterLast('/')
