package cp.player.app.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import cp.player.app.ui.theme.cpCardSurface
import cp.player.app.ui.theme.cpNestedSurface
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cp.player.app.AppModel
import cp.player.app.i18n.OnboardingStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.rememberZipPicker
import cp.player.app.presentation.navigation.AppRoute
import cp.player.app.repository.PluginRepository
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpLoadingIndicator
import cp.player.app.ui.component.CpText
import cp.player.app.ui.navigation.LocalAppNavigator
import cp.player.app.ui.component.LocalIsExpanded
import cp.player.app.ui.component.desktopPagerMouseControl
import cp.player.app.ui.component.rememberConfirmState
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.theme.cpCardSurface
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
 * 3. 登录账号 —— 登录面板直接内嵌在本步（可游客继续，稍后再去 [AccountScreen]）
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
        val navigator = LocalAppNavigator.current
        val coroutineScope = rememberCoroutineScope()

        // 已加载音源与导入状态。无音源首启（NoProvider）时本页是导入入口之一；
        // 导入成功后 backend 转 Ready，但 startDestination 仍是 Onboarding，
        // 不会被 App.kt 的自动导航打断。
        val providers by AppModel.backend.providersFlow.collectAsState()
        val activeProvider by AppModel.activeProviderFlow.collectAsState()
        val profile by AppModel.userProfileFlow.collectAsState()
        val s = cpStrings()
        val hasProvider = providers.isNotEmpty()
        // 有音源才创建登录模型：没有音源时既没有可登录的账号，也不该发二维码请求。
        val accountModel = if (hasProvider) {
            rememberScreenModel { AccountScreenModel(s.account) }
        } else {
            null
        }
        val accountLoggedIn = if (accountModel != null) {
            accountModel.isLogged.collectAsState().value
        } else {
            false
        }
        val accountMessage = if (accountModel != null) {
            accountModel.message.collectAsState().value
        } else {
            null
        }
        val accountVerifyUrl = if (accountModel != null) {
            accountModel.verifyUrl.collectAsState().value
        } else {
            null
        }
        val accountQrRestored = if (accountModel != null) {
            accountModel.qrRestored.collectAsState().value
        } else {
            false
        }
        @Suppress("DEPRECATION")
        val clipboardManager = LocalClipboardManager.current
        var isImporting by remember { mutableStateOf(false) }
        var importMessage by remember { mutableStateOf<String?>(null) }
        var importMessageIsError by remember { mutableStateOf(false) }

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
                importMessageIsError = result is ImportResult.Failed
                importMessage = when (result) {
                    is ImportResult.Activated -> if (result.isUpdate) {
                        s.onboarding.updatedAndActivated(result.provider.name)
                    } else {
                        s.onboarding.importedAndActivated(result.provider.name)
                    }
                    is ImportResult.Loaded -> {
                        val active = activeProvider?.name ?: s.onboarding.noActiveProvider
                        if (result.isUpdate) {
                            s.onboarding.updated(result.provider.name, active)
                        } else {
                            s.onboarding.imported(result.provider.name, active)
                        }
                    }
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
                hasProvider = hasProvider,
                isImporting = isImporting,
                importMessage = importMessage,
                importMessageIsError = importMessageIsError,
                loadedProviders = providers.map {
                    OnboardProviderRow(
                        name = it.name,
                        meta = "${it.type.name} · v${it.version}",
                        active = it.id == activeProvider?.id,
                    )
                },
                providerSummary = activeProvider?.name ?: s.onboarding.providerUnconfigured,
                accountSummary = if (accountLoggedIn) {
                    profile?.nickname?.takeIf { it.isNotBlank() } ?: s.onboarding.loggedIn
                } else {
                    s.onboarding.guestMode
                },
                isNetworkAllowed = repoState.networkEnabled,
                isRefreshingCatalog = repoState.refreshing,
                cloudProviders = cloudProviders,
                catalogError = catalogError,
                onEnableNetworkAndRefresh = {
                    confirmState.request(
                        title = s.onboarding.enableCloudTitle,
                        message = s.onboarding.enableCloudMessage,
                        confirmLabel = s.onboarding.enableCloudConfirm,
                        destructive = false,
                    ) {
                        pluginRepo.allowNetwork(true)
                        coroutineScope.launch {
                            try {
                                pluginRepo.refresh()
                            } catch (failure: Exception) {
                                importMessageIsError = true
                                importMessage = s.onboarding.catalogFetchFailed(failure.message ?: failure.toString())
                            }
                        }
                    }
                },
                onRefreshCatalog = {
                    coroutineScope.launch {
                        try {
                            pluginRepo.refresh()
                        } catch (failure: Exception) {
                            importMessageIsError = true
                            importMessage = s.onboarding.refreshFailed(failure.message ?: failure.toString())
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
                            importMessageIsError = false
                            importMessage = s.onboarding.installedAndActivated(targetEntry.plugin.name)
                        } catch (failure: Exception) {
                            importMessageIsError = true
                            importMessage = s.onboarding.installFailed(failure.message ?: failure.toString())
                        } finally {
                            isImporting = false
                        }
                    }
                },
                onPickZip = { pickZip() },
                onOpenProviderManagement = { navigator.push(AppRoute.PluginHub()) },
                accountPage = { pageModifier, goToProvider, goNext ->
                    OnboardingLoginPage(
                        modifier = pageModifier,
                        hasProvider = hasProvider,
                        providerName = activeProvider?.name,
                        isLogged = accountLoggedIn,
                        nickname = profile?.nickname,
                        message = accountMessage,
                        verifyUrl = accountVerifyUrl,
                        qrRestored = accountQrRestored,
                        onGoToProvider = goToProvider,
                        onContinue = goNext,
                        onOpenVerifyUrl = { accountModel?.openVerifyUrl() },
                        onDismissVerify = { accountModel?.clearVerifyUrl() },
                        onCopyVerifyUrl = {
                            accountVerifyUrl?.let { clipboardManager.setText(AnnotatedString(it)) }
                            accountModel?.message?.value = s.account.verifyCopied
                        },
                        loginPane = {
                            accountModel?.let { AccountLoginPane(it, wrapChannels = true) }
                        },
                    )
                },
                onFinish = {
                    if (replay) {
                        navigator.pop()
                    } else {
                        // 即使「跳过」也标记完成，避免下次再弹。
                        AppModel.setOnboardingDone(true)
                        navigator.replaceAll(AppRoute.Main)
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
    importMessageIsError: Boolean = false,
    loadedProviders: List<OnboardProviderRow>,
    providerSummary: String? = null,
    accountSummary: String? = null,
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
    accountPage: @Composable (Modifier, () -> Unit, () -> Unit) -> Unit = { modifier, _, _ ->
        OnboardingAccountPlaceholder(modifier)
    },
    onFinish: () -> Unit = {},
) {
    val s = cpStrings()
    val effectiveProviderSummary = providerSummary ?: s.onboarding.providerUnconfigured
    val effectiveAccountSummary = accountSummary ?: s.onboarding.guestMode
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
        // 顶部：左侧步骤文字、中间分段指示器、右侧跳过（重看时不需要）。
        // 指示器叠在中间而不是独占一行：原来的「圆点 + 标签」要 80dp 以上，
        // 而步骤名在每页正文标题里已经写过一次，标签是重复信息。
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CpText(
                    text = s.onboarding.stepProgress(pagerState.currentPage + 1, pages.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!replay) {
                    TextButton(onClick = onFinish) { CpText(s.onboarding.skip) }
                }
            }
            OnboardingStepIndicator(
                current = pagerState.currentPage,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 登录页内容比其它页长，滚轮必须优先滚表单；否则一滚就翻页，用户永远
        // 看不到下面的登录按钮。翻页仍可用底部按钮或横向滑动。
        val wheelPaging = pages[pagerState.currentPage] != OnboardingPages.Account
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(
                    if (wheelPaging) {
                        Modifier.desktopPagerMouseControl(
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
                        )
                    } else {
                        Modifier
                    },
                ),
        ) { page ->
            when (val onboardingPage = pages[page]) {
                OnboardingPages.Welcome -> OnboardingPageContent(
                    page = onboardingPage,
                    icon = Icons.Filled.LibraryMusic,
                )
                OnboardingPages.Provider -> ProviderOnboardingPage(
                    isImporting = isImporting,
                    importMessage = importMessage,
                    importMessageIsError = importMessageIsError,
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
                OnboardingPages.Account -> accountPage(
                    Modifier.fillMaxSize(),
                    {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(OnboardingPages.Provider.ordinal)
                        }
                    },
                    {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(OnboardingPages.Done.ordinal)
                        }
                    },
                )
                OnboardingPages.Done -> OnboardingDonePage(
                    providerSummary = effectiveProviderSummary,
                    accountSummary = effectiveAccountSummary,
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
                CpText(
                    if (pagerState.currentPage == 0 && replay) {
                        s.onboarding.back
                    } else if (pagerState.currentPage == 0) {
                        s.onboarding.skip
                    } else {
                        s.onboarding.previous
                    },
                )
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
                    if (replay) s.onboarding.done else s.onboarding.getStarted
                } else s.onboarding.next)
                if (pagerState.currentPage < lastIndex) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/**
 * 顶部分段指示器（M3 Expressive 的「胶囊轨道」写法）。
 *
 * 已完成的步骤是短胶囊，当前步骤拉长成一条，未到的步骤是浅色短胶囊；宽度用
 * spatial 弹簧过渡，前进 / 后退时整排像一条会呼吸的轨道。比编号圆点小得多，
 * 也不再重复正文标题里的步骤名。
 *
 * 指示器是纯装饰：步骤文字在左侧，读屏依赖那段文字就够，别再加一层
 * `contentDescription` 把同一件事读两遍。
 */
@Composable
private fun OnboardingStepIndicator(
    current: Int,
    modifier: Modifier = Modifier,
) {
    val activeWidth = 24.dp
    val idleWidth = 8.dp
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(OnboardingPages.entries.size) { index ->
            val active = index == current
            val width by animateDpAsState(
                targetValue = if (active) activeWidth else idleWidth,
                animationSpec = CpMotion.spatialFast(),
                label = "onboardingStepWidth",
            )
            val color by animateColorAsState(
                targetValue = if (index <= current) {
                    MaterialTheme.colorScheme.primary
                } else {
                    cpNestedSurface()
                },
                animationSpec = CpMotion.effectsFast(),
                label = "onboardingStepColor",
            )
            Box(
                modifier = Modifier
                    .size(width = width, height = 6.dp)
                    .background(color, CircleShape),
            )
        }
    }
}

/**
 * 完成页：把「音源」和「账号」两条结果收在一张摘要卡里，避免用户到主界面
 * 才发现自己其实还是游客模式。
 */
@Composable
private fun OnboardingDonePage(
    providerSummary: String,
    accountSummary: String,
) {
    val s = cpStrings()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(88.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(44.dp),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        CpText(
            text = s.onboarding.doneTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        Spacer(Modifier.height(16.dp))
        Surface(
            shape = MaterialTheme.shapes.large,
            color = cpCardSurface(),
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OnboardingSummaryRow(label = s.onboarding.doneProviderLabel, value = providerSummary)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                OnboardingSummaryRow(label = s.onboarding.doneAccountLabel, value = accountSummary)
            }
        }
        Spacer(Modifier.height(16.dp))
        CpText(
            text = s.onboarding.doneBody,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = Int.MAX_VALUE,
        )
    }
}

@Composable
private fun OnboardingSummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CpText(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        CpText(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            // 这里是数据目录 / 音源包名，换行只是把完整值显示出来，
            // 截断会让用户在「确认安装结果」这一步看不到真实路径。
            maxLines = Int.MAX_VALUE,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 纯参数预览 / 测试用的登录页占位；正式流程由 `OnboardingScreen` 注入真实登录面板。 */
@Composable
private fun OnboardingAccountPlaceholder(modifier: Modifier) {
    val s = cpStrings()
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CpText(
            text = s.onboarding.accountPlaceholderTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        CpText(
            text = s.onboarding.accountPlaceholderBody,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 音源配置页（引导第二步）。
 *
 * 融合云端音源仓库（一键获取推荐音源）与本地 .zip 导入。
 */
@Composable
private fun ProviderOnboardingPage(
    isImporting: Boolean,
    importMessage: String?,
    importMessageIsError: Boolean,
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
    val s = cpStrings()
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
                text = s.onboarding.providerTitle,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            CpText(
                text = s.onboarding.providerBody,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                maxLines = Int.MAX_VALUE,
            )
            Spacer(Modifier.height(20.dp))

            // 1. 云端音源推荐区（主推荐卡片）
            Surface(
                color = cpCardSurface(),
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
                                text = s.onboarding.cloudSection,
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
                                    contentDescription = s.onboarding.refreshCatalog,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    if (!isNetworkAllowed) {
                        CpText(
                            text = s.onboarding.cloudDisabledNote,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                            maxLines = Int.MAX_VALUE,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onEnableNetworkAndRefresh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.CloudQueue, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            CpText(s.onboarding.enableCloudAndFetch)
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
                                s.onboarding.fetchingCatalog,
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
                                                    maxLines = Int.MAX_VALUE,
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
                                                    } else s.onboarding.installing,
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
                                                    text = s.onboarding.installed,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Medium,
                                                )
                                            }
                                        } else if (!item.isCompatible) {
                                            CpText(
                                                text = item.incompatibilityReason ?: s.onboarding.incompatible,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        } else {
                                            Button(
                                                onClick = { onInstallCloudProvider(item.key) },
                                                enabled = !isImporting,
                                            ) {
                                                CpText(s.onboarding.fetch)
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
                                text = catalogError ?: s.onboarding.noRecommended,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (catalogError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = Int.MAX_VALUE,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = onRefreshCatalog, enabled = !isRefreshingCatalog) {
                                CpText(s.onboarding.retry)
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
                    text = s.onboarding.orUseLocal,
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
                CpText(s.onboarding.importLocal)
            }

            if (isImporting) {
                Spacer(Modifier.height(16.dp))
                CpLoadingIndicator(Modifier.size(36.dp))
                Spacer(Modifier.height(6.dp))
                CpText(
                    s.onboarding.processingModule,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            importMessage?.let {
                Spacer(Modifier.height(12.dp))
                CpText(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (importMessageIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    textAlign = TextAlign.Center,
                    maxLines = Int.MAX_VALUE,
                )
            }

            // 3. 已加载音源展示
            if (loadedProviders.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Surface(
                    color = cpCardSurface(),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        CpText(
                            s.onboarding.loadedProviders,
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
                                            contentDescription = s.onboarding.activeProvider,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = if (row.active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else cpNestedSurface(),
                                ),
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(20.dp))
                Surface(
                    color = cpCardSurface(),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CpText(
                            s.onboarding.localModeTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        CpText(
                            s.onboarding.localModeBody,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center,
                            maxLines = Int.MAX_VALUE,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenProviderManagement, enabled = !isImporting) {
                CpText(s.onboarding.manageProviders)
            }
        }
    }
}

private enum class OnboardingPages {
    Welcome,
    Provider,
    Account,
    Done,
}

private fun titleOf(page: OnboardingPages, s: OnboardingStrings): String = when (page) {
    OnboardingPages.Welcome -> s.welcomeTitle
    OnboardingPages.Provider -> s.providerTitle
    OnboardingPages.Account -> s.loginTitle
    OnboardingPages.Done -> s.doneTitle
}

private fun bodyOf(page: OnboardingPages, s: OnboardingStrings): String = when (page) {
    OnboardingPages.Welcome -> s.welcomeBody
    OnboardingPages.Provider -> s.providerBody
    OnboardingPages.Account -> s.loginBody
    OnboardingPages.Done -> s.doneBody
}

@Composable
private fun OnboardingPageContent(page: OnboardingPages, icon: ImageVector) {
    val s = cpStrings()
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
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(56.dp),
                )
            }
        }
        Spacer(Modifier.height(32.dp))
        CpText(
            text = titleOf(page, s.onboarding),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        Spacer(Modifier.height(12.dp))
        CpText(
            text = bodyOf(page, s.onboarding),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            maxLines = Int.MAX_VALUE,
        )
    }
}

