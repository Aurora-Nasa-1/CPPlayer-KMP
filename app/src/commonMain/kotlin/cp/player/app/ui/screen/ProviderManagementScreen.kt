package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import cp.player.app.platform.rememberZipPicker
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpLoadingIndicator
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsLazyPage
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.settingsRowHighlightContent
import cp.player.core.BackendResult
import cp.player.core.ImportResult
import cp.player.core.provider.BackendProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 音源管理：导入 / 切换 / 删除音源模块。
 *
 * ### 与重构前的差异（2026-10-01 统一版式时收敛）
 *
 * 1. **页边距从 8dp 收到表单页标准。** 原先自己拼 `LazyScrollColumn(...padding(8.dp))`，
 *    行距 4dp 恰好对，但页边距只有旁边 9 个设置页的一半（16dp），
 *    列表左右比别的页多出一截。现在走 [SettingsLazyPage]。
 * 2. **行底色回到 `settingsRowContainer()`。** 私有实现直接用了 `LegacyListItem` 的默认色
 *    `surfaceContainerHigh`；当前音源那行是 `primaryContainer.copy(alpha = 0.45f)` ——
 *    与账号页的 `0.5f` 纯属巧合，现在统一走 `SettingsClickItem(selected = true)`。
 * 3. **空态改用全应用统一的 [ContentState]**，不再手写图标 + 两行文字。
 * 4. **列表底部留出 FAB 的高度**（`bottomInset`）：原先没有，最后一行会被
 *    「+」按钮压住 —— 模块多的时候只能靠继续滚动碰运气。
 */
class ProviderManagementScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { ProviderManagementModel() }
        val providers by model.providers.collectAsState()
        val active by model.active.collectAsState()
        val isImporting by model.isImporting.collectAsState()
        val message by model.message.collectAsState()
        val pick = rememberZipPicker(onPicked = { model.importModule(it) })

        CpRouteScaffold(
            title = "音源管理",
            onBack = { navigator.pop() },
            floatingActionButton = {
                FloatingActionButton(onClick = { pick() }) {
                    Icon(Icons.Filled.Add, "导入模块")
                }
            },
        ) { pageModifier ->
            Box(pageModifier) {
                if (providers.isEmpty() && !isImporting) {
                    ContentState(
                        title = "尚未加载任何音源模块",
                        message = "点击右下角 + 导入 .zip 模块，模块包需含 manifest.json",
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    SettingsLazyPage(
                        pageModifier = Modifier.fillMaxSize(),
                        // FAB 56dp + 16dp 边距 ⇒ 至少留 88dp，否则最后一行被压住。
                        bottomInset = 88.dp,
                    ) {
                        message?.let { msg ->
                            item(key = "__status__") {
                                SettingsNote(msg, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        itemsIndexed(providers, key = { _, provider -> provider.id }) { index, provider ->
                            ProviderRow(
                                provider = provider,
                                index = index,
                                total = providers.size,
                                isActive = provider.id == active?.id,
                                onActivate = { model.activate(provider) },
                                onDelete = { model.delete(provider.id) },
                            )
                        }
                    }
                }
                if (isImporting) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CpLoadingIndicator(Modifier.size(40.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("正在导入…")
                        }
                    }
                }
            }
        }
    }
}

class ProviderManagementModel : ScreenModel {
    val providers: StateFlow<List<BackendProvider>> = AppModel.backend.providersFlow
    val active: StateFlow<BackendProvider?> get() = AppModel.activeProviderFlow
    val isImporting = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    fun importModule(zipPath: String?) {
        if (zipPath == null) { message.value = "未选择文件"; return }
        screenModelScope.launch {
            isImporting.value = true
            val result = withContext(Dispatchers.IO) { AppModel.importModule(zipPath) }
            isImporting.value = false
            message.value = when (result) {
                is ImportResult.Activated -> "已导入并自动激活 ${result.provider.name}"
                is ImportResult.Loaded -> "已导入 ${result.provider.name}（当前仍使用 ${AppModel.activeProvider()?.name ?: "无"}）"
                is ImportResult.Failed -> result.message
            }
        }
    }

    fun activate(provider: BackendProvider) {
        val ok = AppModel.switchOrReport(provider)
        message.value = if (ok) "已切换到 ${provider.name}" else AppModel.lastSwitchError ?: "切换失败"
    }

    fun delete(id: String) {
        screenModelScope.launch {
            val result = withContext(Dispatchers.IO) { AppModel.deleteProvider(id) }
            message.value = when (result) {
                is BackendResult.Success -> "已删除模块 $id"
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
    onActivate: () -> Unit,
    onDelete: () -> Unit,
) {
    // 当前音源那行的底色是 primaryContainer，行尾控件必须跟着换成它的前景色 ——
    // 继续用 onSurfaceVariant 会在浅色主题下掉对比度。
    val trailingTint = if (isActive) {
        settingsRowHighlightContent()
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    SettingsClickItem(
        title = provider.name,
        subtitle = "${provider.type.name} · v${provider.version} · ${provider.id}",
        icon = Icons.Filled.FolderZip,
        index = index,
        total = total,
        selected = isActive,
        // 行尾的删除按钮是**独立动作**：合并语义会把它并进整行，读屏用户就点不到删除。
        mergeSemantics = false,
        onClick = onActivate,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isActive) {
                    Icon(Icons.Filled.CheckCircle, "当前音源", tint = trailingTint)
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = "删除音源模块 ${provider.name}",
                        tint = trailingTint,
                    )
                }
            }
        },
    )
}
