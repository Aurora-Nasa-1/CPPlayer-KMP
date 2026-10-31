package cp.player.app.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.platform.rememberZipPicker
import cp.player.app.repository.PluginRepository
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpLoadingIndicator
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.LocalIsExpanded
import cp.player.app.ui.component.desktopPagerMouseControl
import cp.player.app.ui.component.rememberConfirmState
import cp.player.core.ImportResult
import cp.player.core.pluginrepo.RepositoryPluginKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首次使用引导（应用唯一的「欢迎」页）。
 *
 * 旧版流程有两个欢迎页：无音源时先进 `SetupScreen`（欢迎 + 导入音源），导入成功后
 * 又被路由到本页再欢迎一遍。2026-10-02 合并为一条流程：无音源（NoProvider）与
 * `onboarding_done` 未置位**都**从本页进入（见 [AppState.startDestination]）。
 *
 * 1. 欢迎 —— 讲清楚「本地播放 + 多音源」是什么
 * 2. 配置音源 —— 融合云端推荐音源仓库一键安装、内嵌 zip 导入以及跳过本地播放
 * 3. 登录账号 —— 通往 [AccountScreen]（可游客登录跳过）
 * 4. 完成
 *
 * 版式核对见 desktopTest 的 `OnboardingPreviewTest`（离屏渲染出图）。
 *
 * @param replay 从设置里「重看一遍」进入时为 true：结束时**只返回**，
 *   不重写 `onboarding_done`，也不会把首屏路由顶掉。
 */
class OnboardingScreen(private val replay: Boolean = false) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val coroutineScope = rememberCoroutineScope()

        // 已加载音源与导入状态。无音源首启（NoProvider）时本页是导入入口之一；
        // 导入成功后 backend 转 Ready，但 startDestination 仍是 Onboarding，
        // 不会被 App.kt 的自动导航打断。
        val providers by AppModel.backend.providersFlow.collectAsState()
        val activeProvider by AppModel.activeProviderFlow.collectAsState()
        var isImporting by remember { mutableStateOf(false) }
        var importMessage by remember { mutableStateOf<String?>(null) }

        // 云端插件仓库集成
        val pluginRepo = remember { PluginRepository.current() }
        LaunchedEffect(pluginRepo) {
            pluginRepo.load()
        }
        val repoState by pluginRepo.state.collectAsState()
        val confirmState = rememberConfirmState()

        val pickZip = rememberZipPicker { zipPath ->
            if (zipPath == null) return@rememberZipPicker
            coroutineScope.launch {
                isImporting = true
                val result = withContext(Dispatchers.IO) { AppModel.importModule(zipPath) }
                isImporting = false
                importMessage = when (result) {
                    is ImportResult.Activated -> if (result.isUpdate) "已自动更新并激活 ${result.provider.name}" else "已导入并自动激活 ${result.provider.name}"
                    is ImportResult.Loaded -> if (result.isUpdate) "已自动更新 ${result.provider.name}（当前仍使用 ${activeProvider?.name ?: "无"}）" else "已导入 ${result.provider.name}（当前仍使用 ${activeProvider?.name ?: "无"}）"
                    is ImportResult.PendingRestart -> result.message
                    is ImportResult.Failed -> result.message
                }
            }
        }

        val cloudProviders = remember(repoState.entries, repoState.task) {
            repoState.entries
                .filter { it.plugin.kind == RepositoryPluginKind.PROVIDER }
                .map { entry ->
                    val isInstalling = repoState.task?.key == entry.plugin.key
                    val progress = if (isInstalling && (repoState.task?.totalBytes ?: 0L) > 0) {
                        (repoState.task!!.downloadedBytes.toFloat() / repoState.task!!.totalBytes).coerceIn(0f, 1f)
                    } else null
                    OnboardCloudProviderItem(
                        key = entry.plugin.key,
                        name = entry.plugin.name,
                        version = entry.plugin.version,
                        description = entry.plugin.description,
                        isInstalled = entry.installed != null,
                        isCompatible = entry.artifact != null,
                        incompatibilityReason = entry.incompatibility,
                        isDownloading = isInstalling,
                        downloadProgress = progress,
                    )
                }
        }

        val catalogError = remember(repoState.sourceErrors) {
            repoState.sourceErrors.values.firstOrNull()
        }

        Box(Modifier.fillMaxSize()) {
            OnboardingContent(
                replay = replay,
                hasProvider = providers.isNotEmpty(),
                isImporting = isImporting,
                importMessage = importMessage,
                loadedProviders = providers.map {
                    OnboardProviderRow(
                        name = it.name,
                        meta = "${it.type.name} · v${it.version}",
                        active = it.id == activeProvider?.id,
                    )
                },
                isNetworkAllowed = repoState.networkEnabled,
                isRefreshingCatalog = repoState.refreshing,
                cloudProviders = cloudProviders,
                catalogError = catalogError,
                onEnableNetworkAndRefresh = {
                    confirmState.request(
                        title = "开启云端音源仓库",
                        message = "CPPlayer 将请求官方音源仓库目录以获取可用音源列表。音源模块在本地运行并拥有与应用相同的权限，请确认信任来源。",
                        confirmLabel = "开启并获取",
                        destructive = false,
                    ) {
                        pluginRepo.allowNetwork(true)
                        coroutineScope.launch {
                            try {
                                pluginRepo.refresh()
                            } catch (failure: Exception) {
                                importMessage = "获取云端列表失败: ${failure.message ?: failure.toString()}"
                            }
                        }
                    }
                },
                onRefreshCatalog = {
                    coroutineScope.launch {
                        try {
                            pluginRepo.refresh()
                        } catch (failure: Exception) {
                            importMessage = "刷新失败: ${failure.message ?: failure.toString()}"
                        }
                    }
                },
                onInstallCloudProvider = { key ->
                    coroutineScope.launch {
                        val targetEntry = repoState.entries.firstOrNull { it.plugin.key == key } ?: return@launch
                        isImporting = true
                        importMessage = null
                        try {
                            pluginRepo.install(targetEntry, replaceLocal = false)
                            importMessage = "已安装并激活 ${targetEntry.plugin.name}"
                        } catch (failure: Exception) {
                            importMessage = "安装失败: ${failure.message ?: failure.toString()}"
                        } finally {
                            isImporting = false
                        }
                    }
                },
                onPickZip = { pickZip() },
                onOpenProviderManagement = { navigator.push(ProviderManagementScreen()) },
                onOpenAccount = { navigator.push(AccountScreen()) },
                onFinish = {
                    if (replay) {
                        navigator.pop()
                    } else {
                        // 即使「跳过」也标记完成，避免下次再弹。
                        AppModel.setOnboardingDone(true)
                        navigator.replaceAll(MainScreen())
                    }
                },
            )

            CpConfirmHost(confirmState)
        }
    }
}

/** 已加载音源的展示行（纯数据，便于离屏渲染核对，不携带 [cp.player.core.provider.BackendProvider]）。 */
internal data class OnboardProviderRow(
    val name: String,
    val meta: String,
    val active: Boolean,
)

/** 云端仓库推荐音源条目（纯数据，离屏渲染与预览友好）。 */
internal data class OnboardCloudProviderItem(
    val key: String,
    val name: String,
    val version: String,
    val description: String?,
    val isInstalled: Boolean,
    val isCompatible: Boolean,
    val incompatibilityReason: String? = null,
    val isDownloading: Boolean = false,
    val downloadProgress: Float? = null,
)

/**
 * 引导页的纯参数主体（不读 AppModel），版式离屏渲染核对用。
 * 跳过 / 下一步 / 完成统一走 [onFinish]；音源强制语义由 [hasProvider] 驱动。
 */
@Composable
internal fun OnboardingContent(
    replay: Boolean,
    hasProvider: Boolean,
    isImporting: Boolean,
    importMessage: String?,
    loadedProviders: List<OnboardProviderRow>,
    initialPage: Int = 0,
    isNetworkAllowed: Boolean = false,
    isRefreshingCatalog: Boolean = false,
    cloudProviders: List<OnboardCloudProviderItem> = emptyList(),
    catalogError: String? = null,
    onEnableNetworkAndRefresh: () -> Unit = {},
    onRefreshCatalog: () -> Unit = {},
    onInstallCloudProvider: (String) -> Unit = {},
    onPickZip: () -> Unit = {},
    onOpenProviderManagement: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onFinish: () -> Unit = {},
) {
    val expanded = LocalIsExpanded.current
    val coroutineScope = rememberCoroutineScope()
    val pages = OnboardingPages.entries
    val pagerState = rememberPagerState(initialPage = initialPage) { pages.size }
    val lastIndex = pages.lastIndex

    Column(
        modifier = Modifier.fillMaxSize()
            .padding(horizontal = if (expanded) 32.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 顶部：跳过（重看时不需要）。支持纯本地模式跳过。
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            if (!replay) {
                TextButton(onClick = onFinish) { CpText("跳过") }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // 鼠标滚轮翻页
                .desktopPagerMouseControl(
                    onScrollLeft = {
                        val target = (pagerState.currentPage - 1).coerceAtLeast(0)
                        if (target != pagerState.currentPage) {
                            coroutineScope.launch { pagerState.animateScrollToPage(target) }
                        }
                    },
                    onScrollRight = {
                        val target = (pagerState.currentPage + 1).coerceAtMost(pages.lastIndex)
                        if (target != pagerState.currentPage) {
                            coroutineScope.launch { pagerState.animateScrollToPage(target) }
                        }
                    },
                    pageCount = pagerState.pageCount,
                ),
        ) { page ->
            when (pages[page]) {
                OnboardingPages.Provider -> ProviderOnboardingPage(
                    body = pages[page].body,
                    isImporting = isImporting,
                    importMessage = importMessage,
                    loadedProviders = loadedProviders,
                    hasProvider = hasProvider,
                    isNetworkAllowed = isNetworkAllowed,
                    isRefreshingCatalog = isRefreshingCatalog,
                    cloudProviders = cloudProviders,
                    catalogError = catalogError,
                    onEnableNetworkAndRefresh = onEnableNetworkAndRefresh,
                    onRefreshCatalog = onRefreshCatalog,
                    onInstallCloudProvider = onInstallCloudProvider,
                    onPickZip = onPickZip,
                    onOpenProviderManagement = onOpenProviderManagement,
                )
                else -> OnboardingPageContent(
                    page = pages[page],
                    onAction = {
                        when (pages[page]) {
                            OnboardingPages.Account -> onOpenAccount()
                            else -> {}
                        }
                    },
                )
            }
        }

        // 页码指示器
        Row(
            modifier = Modifier.padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pages.indices.forEach { index ->
                val selected = pagerState.currentPage == index
                val color by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    label = "onboardDot",
                )
                Box(
                    modifier = Modifier.size(if (selected) 10.dp else 8.dp)
                        .background(color, CircleShape),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = {
                    if (pagerState.currentPage == 0) {
                        onFinish()
                    } else {
                        coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                CpText(if (pagerState.currentPage == 0 && replay) "返回" else if (pagerState.currentPage == 0) "跳过" else "上一步")
            }
            Button(
                onClick = {
                    if (pagerState.currentPage == lastIndex) {
                        onFinish()
                    } else {
                        coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                CpText(if (pagerState.currentPage == lastIndex) {
                    if (replay) "完成" else "开始使用"
                } else "下一步")
                if (pagerState.currentPage < lastIndex) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/**
 * 音源配置页（引导第二步）。
 *
 * 融合云端音源仓库（一键获取推荐音源）与本地 .zip 导入。
 */
@Composable
private fun ProviderOnboardingPage(
    body: String,
    isImporting: Boolean,
    importMessage: String?,
    loadedProviders: List<OnboardProviderRow>,
    hasProvider: Boolean,
    isNetworkAllowed: Boolean,
    isRefreshingCatalog: Boolean,
    cloudProviders: List<OnboardCloudProviderItem>,
    catalogError: String?,
    onEnableNetworkAndRefresh: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onInstallCloudProvider: (String) -> Unit,
    onPickZip: () -> Unit,
    onOpenProviderManagement: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(96.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            CpText(
                text = "配置你的音乐源",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            CpText(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(20.dp))

            // 1. 云端音源推荐区（主推荐卡片）
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CloudQueue,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            CpText(
                                text = "云端音源仓库（推荐）",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isNetworkAllowed) {
                            IconButton(
                                onClick = onRefreshCatalog,
                                enabled = !isRefreshingCatalog && !isImporting,
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = "刷新云端音源",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    if (!isNetworkAllowed) {
                        CpText(
                            text = "开启云端连接，即可浏览并一键安装官方推荐的音乐源，开箱即用。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onEnableNetworkAndRefresh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.CloudQueue, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            CpText("开启云端并获取推荐音源")
                        }
                    } else if (isRefreshingCatalog && cloudProviders.isEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CpLoadingIndicator(Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            CpText(
                                "正在获取云端音源目录…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else if (cloudProviders.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            cloudProviders.forEach { item ->
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                CpText(
                                                    text = item.name,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                                CpText(
                                                    text = "v${item.version}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            if (!item.description.isNullOrBlank()) {
                                                Spacer(Modifier.height(2.dp))
                                                CpText(
                                                    text = item.description,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }

                                        if (item.isDownloading) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                CpLoadingIndicator(Modifier.size(16.dp))
                                                CpText(
                                                    text = if (item.downloadProgress != null) {
                                                        "${(item.downloadProgress * 100).toInt()}%"
                                                    } else "安装中",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        } else if (item.isInstalled) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                Icon(
                                                    Icons.Filled.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                                CpText(
                                                    text = "已安装",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Medium,
                                                )
                                            }
                                        } else if (!item.isCompatible) {
                                            CpText(
                                                text = item.incompatibilityReason ?: "不兼容",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        } else {
                                            Button(
                                                onClick = { onInstallCloudProvider(item.key) },
                                                enabled = !isImporting,
                                            ) {
                                                CpText("获取")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CpText(
                                text = catalogError ?: "暂无可用的推荐音源",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (catalogError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = onRefreshCatalog, enabled = !isRefreshingCatalog) {
                                CpText("重试获取")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 2. 本地文件导入分隔区
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                CpText(
                    text = "或者使用本地模块",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            }

            Spacer(Modifier.height(16.dp))

            OutlinedButton(
                onClick = onPickZip,
                enabled = !isImporting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.FolderZip, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                CpText("导入本地音源模块 (.zip)")
            }

            if (isImporting) {
                Spacer(Modifier.height(16.dp))
                CpLoadingIndicator(Modifier.size(36.dp))
                Spacer(Modifier.height(6.dp))
                CpText(
                    "正在处理模块…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            importMessage?.let {
                Spacer(Modifier.height(12.dp))
                val isError = it.contains("失败") || it.contains("错误") || it.contains("异常")
                    || it.contains("不支持") || it.contains("无法") || it.contains("未就绪")
                CpText(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
            }

            // 3. 已加载音源展示
            if (loadedProviders.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        CpText(
                            "已加载的音源",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        loadedProviders.forEach { row ->
                            ListItem(
                                headlineContent = { CpText(row.name, fontWeight = FontWeight.Medium) },
                                supportingContent = { CpText(row.meta) },
                                trailingContent = {
                                    if (row.active) {
                                        Icon(
                                            Icons.Filled.FolderZip,
                                            contentDescription = "当前活跃",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = if (row.active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.surface,
                                ),
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(20.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CpText(
                            "未导入音源时将以本地音乐模式运行",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        CpText(
                            "您可以直接管理与播放本机音频，随时可在设置中导入音源模块解锁在线曲库。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenProviderManagement, enabled = !isImporting) {
                CpText("前往音源管理（增删、切换、更新）")
            }
        }
    }
}

private enum class OnboardingPages(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val actionLabel: String? = null,
) {
    Welcome(
        icon = Icons.Filled.LibraryMusic,
        title = "欢迎使用 CPPlayer",
        body = "本地优先的播放器：歌单、红心、下载都在本机，音乐源只是「从哪台服务器拿歌」。" +
            "装几个音源都行，它们之间互不干扰。",
    ),
    // 正文由 ProviderOnboardingPage 单独渲染。
    Provider(
        icon = Icons.Filled.Download,
        title = "配置你的音乐源",
        body = "CPPlayer 通过可插拔的 Provider 模块连接在线音乐服务。" +
            "您可以从云端仓库一键获取推荐音源，也可以导入本地模块 (.zip)，或者跳过此步直接使用纯本地播放；" +
            "之后随时可以在「音源管理」里增删、切换。",
    ),
    Account(
        icon = Icons.AutoMirrored.Filled.Login,
        title = "登录你的账号",
        body = "扫码、邮箱或手机号登录后可同步歌单与红心；暂时不想登录也可以用「游客登录」跳过。" +
            "登录态按音源分开保存，换音源不会串号。",
        actionLabel = "去登录",
    ),
    Done(
        icon = Icons.Filled.Settings,
        title = "都准备好了",
        body = "音质、睡眠定时、外观等都在「设置」里，想改随时回来。",
    ),
}

@Composable
private fun OnboardingPageContent(page: OnboardingPages, onAction: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(120.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = page.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(56.dp),
                )
            }
        }
        Spacer(Modifier.height(32.dp))
        CpText(
            text = page.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        CpText(
            text = page.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (page.actionLabel != null) {
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onAction) { CpText(page.actionLabel) }
        }
    }
}

