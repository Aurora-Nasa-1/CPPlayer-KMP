package cp.player.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.platform.rememberZipPicker
import cp.player.app.ui.components.HeroBlock
import cp.player.kmp.BackendState
import cp.player.kmp.ImportResult
import cp.player.kmp.provider.BackendProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SetupScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { SetupScreenModel() }
        val providers by model.providers.collectAsState()
        val isImporting by model.isImporting.collectAsState()
        val message by model.message.collectAsState()
        val backendState by AppModel.backendState.collectAsState()

        val pick = rememberZipPicker(onPicked = { model.importModule(it) })

        SetupScreenContent(
            providers = providers,
            activeProvider = AppModel.activeProvider(),
            isReady = backendState is BackendState.Ready,
            isImporting = isImporting,
            message = message,
            onPick = { pick() },
            onSelect = { AppModel.switchOrReport(it) },
            onStart = { navigator.replaceAll(MainScreen()) },
        )
    }
}

class SetupScreenModel : ScreenModel {
    val providers: StateFlow<List<BackendProvider>> = AppModel.backend.providersFlow
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
}

@Composable
private fun SetupScreenContent(
    providers: List<BackendProvider>,
    activeProvider: BackendProvider?,
    isReady: Boolean,
    isImporting: Boolean,
    message: String?,
    onPick: () -> Unit,
    onSelect: (BackendProvider) -> Unit,
    onStart: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(32.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.size(120.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.FolderZip, null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(60.dp),
                        )
                    }
                }
                HeroBlock(
                    title = "欢迎使用 CPPlayer",
                    description = "CPPlayer 采用插件化架构，请先导入音源 Provider 模块 (.zip)。\n导入后可在设置中随时切换或管理。",
                )

                if (isImporting) {
                    Spacer(Modifier.height(24.dp))
                    CircularProgressIndicator(modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("正在导入模块…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }

                message?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (providers.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
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
                                "已加载的音源",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            providers.forEach { provider ->
                                val isActive = provider.id == activeProvider?.id
                                ListItem(
                                    headlineContent = { Text(provider.name, fontWeight = FontWeight.Medium) },
                                    supportingContent = { Text("${provider.type.name} · v${provider.version}") },
                                    trailingContent = {
                                        if (isActive) Icon(Icons.Filled.FolderZip, "当前活跃", tint = MaterialTheme.colorScheme.primary)
                                    },
                                    colors = ListItemDefaults.colors(
                                        containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                        else MaterialTheme.colorScheme.surface,
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .clickable { onSelect(provider) },
                                )
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (providers.isNotEmpty()) {
                    Button(
                        onClick = {
                            if (!isReady && providers.isNotEmpty())
                                onSelect(providers.first())
                            onStart()
                        },
                        enabled = providers.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Text(
                            "开始使用",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                    }
                    OutlinedButton(
                        onClick = onPick,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Icon(Icons.Filled.FolderZip, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("导入新模块", style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    Button(
                        onClick = onPick,
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Icon(Icons.Filled.FolderZip, null, Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("导入音源模块", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}