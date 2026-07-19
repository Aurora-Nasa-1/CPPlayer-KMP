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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.platform.rememberZipPicker
import cp.player.app.ui.component.LegacyListItem
import cp.player.app.ui.component.LegacyPageScaffold
import cp.player.kmp.BackendResult
import cp.player.kmp.ImportResult
import cp.player.kmp.provider.BackendProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        LegacyPageScaffold(
            title = "音源管理",
            navigationIcon = {
                IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { pick() }) {
                    Icon(Icons.Filled.Add, "导入模块")
                }
            },
        ) { pageModifier ->
            Box(pageModifier) {
                if (providers.isEmpty() && !isImporting) {
                    EmptyProviderState(Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (message != null) item {
                            Text(
                                message!!,
                                Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                            )
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
                            CircularProgressIndicator()
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
    LegacyListItem(
        index = index,
        total = total,
        onClick = onActivate,
        headlineContent = { Text(provider.name, fontWeight = FontWeight.Medium) },
        supportingContent = { Text("${provider.type.name} · v${provider.version} · ${provider.id}") },
        leadingContent = { Icon(Icons.Filled.FolderZip, null) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isActive) {
                    Icon(Icons.Filled.CheckCircle, "活跃", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.DeleteOutline, "删除") }
            }
        },
        containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

@Composable
private fun EmptyProviderState(modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.FolderZip, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text("尚未加载任何音源模块", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "点击右下角 + 导入 .zip 模块，模块包需含 manifest.json。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
