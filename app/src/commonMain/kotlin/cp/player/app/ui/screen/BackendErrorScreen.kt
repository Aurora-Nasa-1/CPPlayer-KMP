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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cp.player.app.AppModel
import cp.player.app.platform.rememberZipPicker
import cp.player.core.ImportResult
import cp.player.core.provider.BackendProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 后端初始化失败页。
 *
 * 除了「重试」，还提供两条自救路径（状态流驱动，无需手动导航）：
 * - **删除音源**：删掉损坏 / 起不来的模块。删的是活跃 Provider 时后端自动切到
 *   剩余第一个（切成功 → Ready → 进主界面），删光则迁移到 NoProvider → 引导页。
 * - **导入新音源**：走与引导页（[OnboardingScreen]）相同的 zip 导入链路，无活跃 Provider 时
 *   导入即自动激活，成功后 stateFlow 迁移到 Ready，[cp.player.app.App] 的
 *   LaunchedEffect 会自动 replaceAll 到主界面。
 */
class BackendErrorScreen(private val message: String) : Screen {
    @Composable
    override fun Content() {
        // rememberScreenModel 是 Screen 的扩展函数，只能在 Screen 子类成员里调用；
        // 模型作为参数传给下面的纯 composable（同 PlaylistDetailScreen 的做法）。
        val model = rememberScreenModel { BackendErrorScreenModel() }
        val providers by model.providers.collectAsState()
        val busy by model.busy.collectAsState()
        val actionMessage by model.actionMessage.collectAsState()

        val pickZip = rememberZipPicker(onPicked = { model.importModule(it) })
        val active by AppModel.activeProviderFlow.collectAsState()
        // 「删除音源」的二次确认：删掉的是模块本体（含它的登录态与本地目录），
        // 这一步不可撤回。确认框挂在 Screen.Content 这一层 —— 内部的
        // [BackendErrorContent] 是离屏渲染测试在用的纯 composable，签名不动。
        val confirm = cp.player.app.ui.component.rememberConfirmState()

        BackendErrorContent(
            message = message,
            providers = providers,
            busy = busy,
            actionMessage = actionMessage,
            onRetry = { AppModel.retryBackendBootstrap() },
            onImport = { pickZip() },
            onDelete = { id ->
                val target = providers.firstOrNull { it.id == id }
                confirm.request(
                    title = "删除音源",
                    message = buildString {
                        append("确定删除「").append(target?.name ?: id).append("」吗？\n")
                        append("模块文件与该音源下的登录态会被移除，需要重新导入才能使用。")
                        if (id == active?.id) {
                            append("\n它正是当前音源，删除后不会再有可用音源，需要重新导入。")
                        }
                    },
                    confirmLabel = "删除",
                    onConfirm = { model.delete(id) },
                )
            },
        )

        cp.player.app.ui.component.CpConfirmHost(confirm)
    }
}

class BackendErrorScreenModel : ScreenModel {
    val providers: StateFlow<List<BackendProvider>> = AppModel.backend.providersFlow

    /** 非 null 时显示「正在处理」遮罩（导入 / 删除共用，文案随操作变化）。 */
    val busy = MutableStateFlow<String?>(null)
    val actionMessage = MutableStateFlow<String?>(null)

    fun importModule(zipPath: String?) {
        if (zipPath == null) { actionMessage.value = "未选择文件"; return }
        screenModelScope.launch {
            busy.value = "正在导入…"
            val result = withContext(Dispatchers.IO) { AppModel.importModule(zipPath) }
            busy.value = null
            actionMessage.value = when (result) {
                is ImportResult.Activated -> "已导入并自动激活 ${result.provider.name}"
                is ImportResult.Loaded -> "已导入 ${result.provider.name}（当前仍使用 ${AppModel.activeProvider()?.name ?: "无"}）"
                is ImportResult.Failed -> result.message
            }
        }
    }

    fun delete(id: String) {
        screenModelScope.launch {
            busy.value = "正在删除 $id…"
            val result = withContext(Dispatchers.IO) { AppModel.deleteProvider(id) }
            busy.value = null
            actionMessage.value = when (result) {
                // 删除成功后状态流会自行迁移（切剩余 / NoProvider），多数情况下页面
                // 直接被 App.kt 换走，这条提示只在删除非活跃模块时短暂可见。
                is cp.player.core.BackendResult.Success -> "已删除模块 $id"
                is cp.player.core.BackendResult.Error -> result.message
                is cp.player.core.BackendResult.Unsupported -> result.message
            }
        }
    }
}

// internal：离屏渲染预览测试（desktopTest）需要直接组合这个纯 composable。
@Composable
internal fun BackendErrorContent(
    message: String,
    providers: List<BackendProvider>,
    busy: String?,
    actionMessage: String?,
    onRetry: () -> Unit,
    onImport: () -> Unit,
    onDelete: (String) -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "后端初始化失败",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = message,
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            if (busy != null) {
                Spacer(Modifier.height(20.dp))
                cp.player.app.ui.component.CpLoadingIndicator(Modifier.size(40.dp))
                Spacer(Modifier.height(8.dp))
                Text(busy, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            } else {
                Row(
                    modifier = Modifier.padding(top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(onClick = onRetry) {
                        Text("重试")
                    }
                    OutlinedButton(onClick = onImport) {
                        Icon(Icons.Filled.FolderZip, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("导入新音源")
                    }
                }
            }

            actionMessage?.let {
                Spacer(Modifier.height(12.dp))
                val isError = it.contains("失败") || it.contains("错误") || it.contains("异常")
                    || it.contains("不支持") || it.contains("无法") || it.contains("未就绪")
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
            }

            if (providers.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.Start) {
                        Text(
                            "已安装的音源",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "若某个音源反复导致初始化失败，可在此删除后再重试",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        providers.forEach { provider ->
                            ListItem(
                                headlineContent = { Text(provider.name, fontWeight = FontWeight.Medium) },
                                supportingContent = { Text("${provider.type.name} · v${provider.version} · ${provider.id}") },
                                trailingContent = {
                                    IconButton(onClick = { onDelete(provider.id) }) {
                                        Icon(
                                            Icons.Filled.DeleteOutline,
                                            contentDescription = "删除音源 ${provider.name}",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                ),
                                modifier = Modifier.fillMaxWidth()
                                    .padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
