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
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import cp.player.app.platform.rememberZipPicker
import cp.player.app.platform.rememberZipSaver
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpLoadingIndicator
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.util.popOrNotify
import cp.player.app.ui.component.SettingsButtonItem
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
 * 音源管理：导入 / 切换 / 更新 / 导出 / 删除音源模块。
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
 *
 * ### 2026-10-02 行内「更多」弹出菜单
 *
 * 5. 行尾换成 MoreVert 圆形按钮（样式对齐歌曲列表的 [cp.player.app.ui.component.SongItem]），
 *    弹出菜单承载 **导出 / 更新 / 删除**，删除不再单独占一个裸 IconButton。
 *    - 导出：系统「保存文件」对话框（[rememberZipSaver]），把模块目录打包为 zip；
 *      文件名按 `id-v版本.zip` 生成（id 本身就是模块目录名，天然文件名安全）。
 *    - 更新：与 FAB 导入共用同一个 zip 选择器 —— 点「更新」先记下目标行，
 *      回调里按包内 manifest.id 一致性校验替换，防止把 A 模块的包覆盖到 B 上。
 *    - busy 遮罩从「正在导入」泛化成 [ProviderManagementModel.busy]（文案随操作变化），
 *      导入 / 更新 / 导出共用。
 * 6. **列表顶部加显式「导入模块」按钮行**（[SettingsButtonItem]）：FAB 只剩快捷入口，
 *    空态文案同步指向按钮。
 */
class ProviderManagementScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { ProviderManagementModel() }
        val providers by model.providers.collectAsState()
        val active by model.active.collectAsState()
        val busy by model.busy.collectAsState()
        val message by model.message.collectAsState()
        // 行菜单「更新」与 FAB「导入」共用同一个 zip 选择器：更新时先记下目标行，
        // 回调里按是否记录了目标分流 —— 免得每行都挂一个文件选择器。
        var updateTarget by remember { mutableStateOf<BackendProvider?>(null) }
        // 「删除模块」的二次确认：删掉的是模块本体（含它的登录态与本地数据目录），
        // 且它在行菜单里紧挨着「导出 / 更新」，误触代价高。
        val confirm = cp.player.app.ui.component.rememberConfirmState()
        val pickZip = rememberZipPicker(onPicked = { zipPath ->
            val target = updateTarget
            updateTarget = null
            if (zipPath == null) return@rememberZipPicker
            if (target == null) model.importModule(zipPath) else model.updateModule(target, zipPath)
        })

        CpRouteScaffold(
            title = "音源管理",
            onBack = { navigator.popOrNotify() },
            floatingActionButton = {
                FloatingActionButton(onClick = {
                    updateTarget = null
                    pickZip()
                }) {
                    Icon(Icons.Filled.Add, "导入模块")
                }
            },
        ) { pageModifier ->
            Box(pageModifier) {
                if (providers.isEmpty() && busy == null) {
                    ContentState(
                        title = "尚未加载任何音源模块",
                        message = "点击「导入模块」或右下角 + 导入 .zip 模块，模块包需含 manifest.json",
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    SettingsLazyPage(
                        pageModifier = Modifier.fillMaxSize(),
                        // FAB 56dp + 16dp 边距 ⇒ 至少留 88dp，否则最后一行被压住。
                        bottomInset = 88.dp,
                    ) {
                        // 显式导入入口：FAB 滚动时容易找不到，按钮行钉在列表顶部。
                        item(key = "__import__") {
                            SettingsButtonItem(
                                text = "导入模块",
                                subtitle = "选择 .zip 模块包（需含 manifest.json）",
                                icon = Icons.Filled.Add,
                                index = 0,
                                total = 1,
                                onClick = {
                                    updateTarget = null
                                    pickZip()
                                },
                            )
                        }
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
                                    pickZip()
                                },
                            )
                        }
                    }
                }
                busy?.let { label ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CpLoadingIndicator(Modifier.size(40.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(label)
                        }
                    }
                }
            }
        }

        cp.player.app.ui.component.CpConfirmHost(confirm)
    }
}

class ProviderManagementModel : ScreenModel {
    val providers: StateFlow<List<BackendProvider>> = AppModel.backend.providersFlow
    val active: StateFlow<BackendProvider?> get() = AppModel.activeProviderFlow

    /** 非 null 时显示「正在处理」遮罩（导入 / 更新 / 导出共用，文案随操作变化）。 */
    val busy = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)

    fun importModule(zipPath: String?) {
        if (zipPath == null) { message.value = "未选择文件"; return }
        screenModelScope.launch {
            busy.value = "正在导入…"
            val result = withContext(Dispatchers.IO) { AppModel.importModule(zipPath) }
            busy.value = null
            message.value = when (result) {
                is ImportResult.Activated -> "已导入并自动激活 ${result.provider.name}"
                is ImportResult.Loaded -> "已导入 ${result.provider.name}（当前仍使用 ${AppModel.activeProvider()?.name ?: "无"}）"
                is ImportResult.Failed -> result.message
            }
        }
    }

    /** 行菜单「更新」：用新 zip 包替换指定模块（包内 manifest.id 必须一致）。 */
    fun updateModule(provider: BackendProvider, zipPath: String?) {
        if (zipPath == null) { message.value = "未选择文件"; return }
        screenModelScope.launch {
            busy.value = "正在更新 ${provider.name}…"
            val result = withContext(Dispatchers.IO) { AppModel.updateModule(zipPath, provider.id) }
            busy.value = null
            message.value = when (result) {
                is ImportResult.Activated -> "已更新并激活 ${result.provider.name}"
                is ImportResult.Loaded -> "已更新 ${result.provider.name}（当前仍使用 ${AppModel.activeProvider()?.name ?: "无"}）"
                is ImportResult.Failed -> result.message
            }
        }
    }

    /**
     * 行菜单「导出」：把模块目录打包为 zip 写到 [destPath]。
     *
     * **同步**版本 —— 由 [rememberZipSaver] 的回调调用，平台层保证已在 IO 线程上
     * （桌面端经 IO 协程中转）；返回是否写入成功。中途没有挂起点，协程被取消也会写完。
     */
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
                is BackendResult.Success -> "已删除模块 $id"
                is BackendResult.Error -> result.message
                is BackendResult.Unsupported -> result.message
            }
        }
    }
}

/** 取路径的最后一段做展示（导出提示里不甩一长串绝对路径）。 */
private fun fileNameOf(path: String): String =
    path.substringAfterLast('/').substringAfterLast('\\')

@Composable
private fun ProviderRow(
    provider: BackendProvider,
    index: Int,
    total: Int,
    isActive: Boolean,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
    onExport: (destPath: String?) -> Boolean,
    onUpdate: () -> Unit,
) {
    // 当前音源那行的底色是 primaryContainer，行尾控件必须跟着换成它的前景色 ——
    // 继续用 onSurfaceVariant 会在浅色主题下掉对比度。
    val trailingTint = if (isActive) {
        settingsRowHighlightContent()
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    var menuOpen by remember { mutableStateOf(false) }
    // 每行一个保存对话框：导出文件名按 id + 版本生成，id 本身就是模块目录名，天然文件名安全。
    val exportZip = rememberZipSaver(
        fileName = "${provider.id}-v${provider.version}.zip",
        onWriteTo = onExport,
    )

    // 能力标签：只展示宿主**认识**且当前语言有译名的那些（见 CpStrings.capabilityLabel）。
    // 未声明的音源（老包）这里就是空串，副标题与从前一模一样 —— 不能因为「没声明」
    // 就写一句「不支持任何能力」，那是在替音源下结论（未声明 ≠ 不支持）。
    val s = cpStrings()
    val capabilityText = provider.capabilities.orEmpty()
        .mapNotNull { s.player.capabilityLabel(it) }
        .joinToString(" · ")
    // API v1 是历史默认值，不显示（每个老包都是 v1，写出来只是噪声）；
    // 只有真正用了新契约的音源才值得占这段字。
    val apiText = if (provider.apiVersion > 1) " · API v${provider.apiVersion}" else ""
    val subtitle = buildString {
        append("${provider.type.name} · v${provider.version} · ${provider.id}$apiText")
        if (capabilityText.isNotEmpty()) append("\n$capabilityText")
    }

    SettingsClickItem(
        title = provider.name,
        subtitle = subtitle,
        icon = Icons.Filled.FolderZip,
        index = index,
        total = total,
        selected = isActive,
        // 行尾的「更多」按钮是**独立动作**：合并语义会把它并进整行，读屏用户就点不到菜单。
        mergeSemantics = false,
        onClick = onActivate,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isActive) {
                    Icon(Icons.Filled.CheckCircle, "当前音源", tint = trailingTint)
                }
                // 更多按钮样式对齐歌曲列表的 SongItem：40dp 圆形、surfaceContainerHigh 底。
                // 按钮自带头色底座，图标统一用 onSurfaceVariant，不再随行高亮变色。
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
                        DropdownMenuItem(
                            text = { Text("导出模块") },
                            leadingIcon = { Icon(Icons.Filled.FileUpload, null) },
                            onClick = {
                                menuOpen = false
                                exportZip()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("更新模块") },
                            leadingIcon = { Icon(Icons.Filled.Sync, null) },
                            onClick = {
                                menuOpen = false
                                onUpdate()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("删除模块") },
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
