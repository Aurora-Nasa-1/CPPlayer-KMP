package cp.player.app.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import cp.player.app.ui.component.LocalIsExpanded
import kotlinx.coroutines.launch

/**
 * 第一次使用引导（旧版 `SetupScreen` 的补充，而不是替代）。
 *
 * 旧版的 `SetupScreen` 只解决「没有音源」这一个前置条件；用户进了主界面之后
 * 「先登录还是先设置」全靠自己摸。这里给首次启动一个三步引导：
 *
 * 1. 欢迎 —— 讲清楚「本地播放 + 多音源」是什么
 * 2. 导入音源 —— 通往 [ProviderManagementScreen]
 * 3. 登录账号 —— 通往 [AccountScreen]
 *
 * @param replay 从设置里「重看一遍」进入时为 true：结束时**只返回**，
 *   不重写 `onboarding_done`，也不会把首屏路由顶掉。
 */
class OnboardingScreen(private val replay: Boolean = false) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val expanded = LocalIsExpanded.current
        val coroutineScope = rememberCoroutineScope()
        val pages = OnboardingPages.entries
        val pagerState = rememberPagerState(initialPage = 0) { pages.size }
        val lastIndex = pages.lastIndex

        Column(
            modifier = Modifier.fillMaxSize()
                .padding(horizontal = if (expanded) 32.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 顶部：跳过（重看时不需要）
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (!replay) {
                    TextButton(onClick = { finish(navigator, skip = true) }) { Text("跳过") }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { page ->
                OnboardingPageContent(
                    page = pages[page],
                    onAction = {
                        when (pages[page]) {
                            OnboardingPages.Provider -> navigator.push(ProviderManagementScreen())
                            OnboardingPages.Account -> navigator.push(AccountScreen())
                            else -> {}
                        }
                    },
                )
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
                            finish(navigator, skip = true)
                        } else {
                            coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (pagerState.currentPage == 0 && replay) "返回" else if (pagerState.currentPage == 0) "跳过" else "上一步")
                }
                Button(
                    onClick = {
                        if (pagerState.currentPage == lastIndex) {
                            finish(navigator, skip = false)
                        } else {
                            coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (pagerState.currentPage == lastIndex) {
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

    private fun finish(navigator: cafe.adriel.voyager.navigator.Navigator, skip: Boolean) {
        if (replay) {
            navigator.pop()
            return
        }
        // 首次引导：打点 → 回主界面。即使「跳过」也标记完成，避免下次再弹。
        AppModel.setOnboardingDone(true)
        navigator.replaceAll(MainScreen())
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
    Provider(
        icon = Icons.Filled.Download,
        title = "导入一个音乐源",
        body = "首次使用需要先导入 Provider 模块（本地文件或网络地址）。" +
            "之后随时可以在「音源管理」里增删、切换。",
        actionLabel = "去导入音源",
    ),
    Account(
        icon = Icons.Filled.Login,
        title = "登录你的账号",
        body = "扫码、邮箱或手机号登录后可同步歌单与红心。" +
            "登录态按音源分开保存，换音源不会串号。",
        actionLabel = "去登录",
    ),
    Done(
        icon = Icons.Filled.Settings,
        title = "都准备好了",
        body = "音质、睡眠定时、外观等都在「设置」里。下面的设置项与旧版完全一致，" +
            "想改随时回来。",
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
        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = page.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (page.actionLabel != null) {
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onAction) { Text(page.actionLabel) }
        }
    }
}
