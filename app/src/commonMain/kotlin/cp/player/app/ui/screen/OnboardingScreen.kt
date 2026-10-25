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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import cp.player.core.ImportResult
import cp.player.app.platform.rememberZipPicker
import cp.player.app.ui.component.CpLoadingIndicator
import cp.player.app.ui.component.LocalIsExpanded
import cp.player.app.ui.component.desktopPagerMouseControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cp.player.app.ui.component.CpText

/**
 * 首次使用引导（应用唯一的「欢迎」页）。
 *
 * 旧版流程有两个欢迎页：无音源时先进 `SetupScreen`（欢迎 + 导入音源），导入成功后
 * 又被路由到本页再欢迎一遍。2026-10-02 合并为一条流程：无音源（NoProvider）与
 * `onboarding_done` 未置位**都**从本页进入（见 [AppState.startDestination]），音源
 * 导入内嵌在第二步里，并且是**强制**的 —— 没有至少一个可用音源时，「跳过」和
 * 第二步的「下一步」都不可用，进不了主界面。
 *
 * 1. 欢迎 —— 讲清楚「本地播放 + 多音源」是什么
 * 2. 导入音源 —— 内嵌 zip 导入（必做一步），也可前往「音源管理」
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

        // 已加载音源与导入状态。无音源首启（NoProvider）时本页是唯一的导入入口；
        // 导入成功后 backend 转 Ready，但 startDestination 仍是 Onboarding，
        // 不会被 App.kt 的自动导航打断。
        val providers by AppModel.backend.providersFlow.collectAsState()
        val activeProvider by AppModel.activeProviderFlow.collectAsState()
        var isImporting by remember { mutableStateOf(false) }
        var importMessage by remember { mutableStateOf<String?>(null) }
        val pickZip = rememberZipPicker { zipPath ->
            if (zipPath == null) return@rememberZipPicker
            coroutineScope.launch {
                isImporting = true
                val result = withContext(Dispatchers.IO) { AppModel.importModule(zipPath) }
                isImporting = false
                importMessage = when (result) {
                    is ImportResult.Activated -> if (result.isUpdate) "已自动更新并激活 ${result.provider.name}" else "已导入并自动激活 ${result.provider.name}"
                    is ImportResult.Loaded -> if (result.isUpdate) "已自动更新 ${result.provider.name}（当前仍使用 ${activeProvider?.name ?: "无"}）" else "已导入 ${result.provider.name}（当前仍使用 ${activeProvider?.name ?: "无"}）"
                    is ImportResult.Failed -> result.message
                }
            }
        }

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
    }
}

/** 已加载音源的展示行（纯数据，便于离屏渲染核对，不携带 [cp.player.core.provider.BackendProvider]）。 */
internal data class OnboardProviderRow(
    val name: String,
    val meta: String,
    val active: Boolean,
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
    val onProviderPage = pages[pagerState.currentPage] == OnboardingPages.Provider

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
 * 音源导入页（引导第二步，强制步骤）。
 *
 * 与旧版 `SetupScreen` 的差异：导入能力**内嵌**在本页（zip 选择器 + 进度 + 已加载
 * 列表），用户不用被甩到音源管理页再自己摸回来；「音源管理」只作为次级入口保留。
 */
@Composable
private fun ProviderOnboardingPage(
    body: String,
    isImporting: Boolean,
    importMessage: String?,
    loadedProviders: List<OnboardProviderRow>,
    hasProvider: Boolean,
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
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(120.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(56.dp),
                )
            }
        }
        Spacer(Modifier.height(32.dp))
        CpText(
            text = "导入一个音乐源",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        CpText(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPickZip, enabled = !isImporting) {
            Icon(Icons.Filled.FolderZip, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            CpText("导入音源模块 (.zip)")
        }

        if (isImporting) {
            Spacer(Modifier.height(20.dp))
            CpLoadingIndicator(Modifier.size(40.dp))
            Spacer(Modifier.height(8.dp))
            CpText(
                "正在导入模块…",
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
        title = "导入一个音乐源",
        body = "CPPlayer 通过可插拔的 Provider 模块连接在线音乐服务。" +
            "您可以导入音源模块（.zip）体验在线曲库，也可以跳过此步直接使用纯本地播放；" +
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
