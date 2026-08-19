package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.platform.openUrl
import cp.player.app.ui.component.LegacyListItem
import cp.player.app.ui.component.LegacyPageScaffold
import cp.player.app.ui.theme.ThemeMode
import cp.player.app.ui.theme.supportsDynamicColor
import cp.player.app.ui.util.UiEvents

class AppearanceSettingsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val expanded = cp.player.app.ui.component.LocalIsExpanded.current
        var themeMode by remember { mutableStateOf(AppModel.themeMode()) }
        var dynamic by remember { mutableStateOf(AppModel.dynamicColor()) }
        var pureBlack by remember { mutableStateOf(AppModel.pureBlack()) }
        val dynamicColorSupported = supportsDynamicColor()

        val body: @Composable (Modifier) -> Unit = { pageModifier ->
            Column(
                modifier = pageModifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (expanded) 20.dp else 8.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsCard("主题") {
                    Text("主题模式", style = MaterialTheme.typography.titleMedium)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = themeMode == mode,
                                onClick = {
                                    themeMode = mode
                                    AppModel.setThemeMode(mode)
                                },
                                label = {
                                    Text(
                                        when (mode) {
                                            ThemeMode.SYSTEM -> "跟随系统"
                                            ThemeMode.LIGHT -> "浅色"
                                            ThemeMode.DARK -> "深色"
                                        }
                                    )
                                },
                            )
                        }
                    }
                }
                SettingsCard("色彩") {
                    LegacyListItem(
                        index = 0,
                        total = 2,
                        onClick = if (dynamicColorSupported) ({
                            dynamic = !dynamic
                            AppModel.setDynamicColor(dynamic)
                        }) else null,
                        headlineContent = { Text("动态取色") },
                        supportingContent = { Text(if (dynamicColorSupported) "Android 12+ 可从壁纸提取配色" else "当前平台暂不支持") },
                        trailingContent = {
                            Switch(
                                checked = dynamic && dynamicColorSupported,
                                enabled = dynamicColorSupported,
                                onCheckedChange = {
                                    dynamic = it
                                    AppModel.setDynamicColor(it)
                                },
                            )
                        },
                    )
                    LegacyListItem(
                        index = 1,
                        total = 2,
                        onClick = {
                            pureBlack = !pureBlack
                            AppModel.setPureBlack(pureBlack)
                        },
                        headlineContent = { Text("纯黑模式") },
                        supportingContent = { Text("深色主题下使用纯黑背景，适合 OLED 屏幕") },
                        trailingContent = {
                            Switch(
                                checked = pureBlack,
                                onCheckedChange = {
                                    pureBlack = it
                                    AppModel.setPureBlack(it)
                                },
                            )
                        },
                    )
                }
            }
        }

        if (expanded) body(Modifier.fillMaxWidth()) else LegacyPageScaffold(
            title = "外观",
            navigationIcon = {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        ) { pageModifier -> body(pageModifier) }
    }
}

class UiLogicSettingsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val expanded = cp.player.app.ui.component.LocalIsExpanded.current
        var playImmediately by remember { mutableStateOf(playImmediatelySetting()) }

        val body: @Composable (Modifier) -> Unit = { pageModifier ->
            Column(
                modifier = pageModifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (expanded) 20.dp else 8.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsCard("播放行为") {
                    LegacyListItem(
                        index = 0,
                        total = 1,
                        onClick = {
                            playImmediately = !playImmediately
                            setPlayImmediatelySetting(playImmediately)
                        },
                        leadingContent = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        headlineContent = { Text("立即播放") },
                        supportingContent = { Text("点击歌曲后立即开始播放，而不是仅加入队列") },
                        trailingContent = {
                            Switch(
                                checked = playImmediately,
                                onCheckedChange = {
                                    playImmediately = it
                                    setPlayImmediatelySetting(it)
                                },
                            )
                        },
                    )
                }
            }
        }

        if (expanded) body(Modifier.fillMaxWidth()) else LegacyPageScaffold(
            title = "交互逻辑",
            navigationIcon = {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        ) { pageModifier -> body(pageModifier) }
    }
}

class StorageSettingsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val expanded = cp.player.app.ui.component.LocalIsExpanded.current
        val qualityLevel by AppModel.playbackQualityFlow.collectAsState()
        val downloadDir by AppModel.downloadDirFlow.collectAsState()
        val isAndroid = cp.player.app.platform.isAndroidPlatform()
        val qualityLabel = AppModel.qualityOptions.firstOrNull { it.first == qualityLevel }?.second ?: qualityLevel
        val pickDownloadDir = cp.player.app.platform.rememberDirectoryPicker { path ->
            if (!path.isNullOrBlank()) {
                AppModel.setDownloadDir(path)
                UiEvents.notify("下载目录已更新，仅对后续下载生效")
            }
        }

        val body: @Composable (Modifier) -> Unit = { pageModifier ->
            Column(
                modifier = pageModifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (expanded) 20.dp else 8.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsCard("下载与缓存") {
                    LegacyListItem(
                        index = 0,
                        total = 4,
                        onClick = null,
                        leadingContent = { Icon(Icons.Filled.HighQuality, contentDescription = null) },
                        headlineContent = { Text("当前播放音质") },
                        supportingContent = { Text(qualityLabel) },
                    )
                    LegacyListItem(
                        index = 1,
                        total = 4,
                        onClick = null,
                        headlineContent = { Text("缓存占位") },
                        supportingContent = { Text("旧版缓存容量与网络策略入口预留，后续补齐") },
                    )
                    LegacyListItem(
                        index = 2,
                        total = 4,
                        onClick = if (isAndroid) null else ({ pickDownloadDir() }),
                        leadingContent = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                        headlineContent = { Text("下载目录") },
                        supportingContent = {
                            Column {
                                if (isAndroid) {
                                    Text(
                                        text = "Android 下载固定保存到应用私有目录",
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Text(downloadDir.ifBlank { "默认下载目录" }, maxLines = 2)
                                Text(
                                    text = "仅对后续下载生效",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        trailingContent = {
                            if (!isAndroid) {
                                Icon(
                                    Icons.Filled.FolderOpen,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                    LegacyListItem(
                        index = 3,
                        total = 4,
                        onClick = {
                            val cleared = cp.player.app.platform.clearImageCache()
                            UiEvents.notify(if (cleared) "图片缓存已清理" else "缓存清理失败")
                        },
                        leadingContent = { Icon(Icons.Filled.CleaningServices, contentDescription = null) },
                        headlineContent = { Text("清理图片缓存") },
                        supportingContent = { Text("释放封面等图片占用的缓存空间") },
                    )
                }
            }
        }

        if (expanded) body(Modifier.fillMaxWidth()) else LegacyPageScaffold(
            title = "存储与下载",
            navigationIcon = {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        ) { pageModifier -> body(pageModifier) }
    }
}

class SponsorScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        LegacyPageScaffold(
            title = "赞助",
            navigationIcon = {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        ) { pageModifier ->
            Column(
                modifier = pageModifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SettingsSubSectionHeader("支持项目")
                LegacyListItem(
                    index = 0,
                    total = 2,
                    onClick = { openUrl("https://github.com/Aurora-Nasa-1/CPPlayer-KMP") },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    headlineContent = { Text("项目主页") },
                    supportingContent = { Text("前往 GitHub 查看项目说明与支持方式") },
                )
                LegacyListItem(
                    index = 1,
                    total = 2,
                    onClick = { openUrl("https://github.com/Aurora-Nasa-1") },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    headlineContent = { Text("维护者主页") },
                    supportingContent = { Text("Aurora-Nasa-1 的 GitHub 主页") },
                )
            }
        }
    }
}

internal const val PLAY_IMMEDIATELY_SETTING_KEY = "play_immediately"

internal fun playImmediatelySetting(): Boolean =
    AppModel.settings.getString(PLAY_IMMEDIATELY_SETTING_KEY)?.toBooleanStrictOrNull() ?: true

internal fun setPlayImmediatelySetting(enabled: Boolean) {
    AppModel.settings.putString(PLAY_IMMEDIATELY_SETTING_KEY, enabled.toString())
}

@Composable
internal fun SettingsCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun SettingsSubSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
    )
}
