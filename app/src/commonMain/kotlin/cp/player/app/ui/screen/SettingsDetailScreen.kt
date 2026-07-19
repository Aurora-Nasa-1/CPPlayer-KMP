package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.ui.component.LegacyListItem
import cp.player.app.ui.component.LegacyPageScaffold
import cp.player.app.ui.theme.ThemeMode
import cp.player.app.ui.theme.supportsDynamicColor

class SettingsDetailScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        var themeMode by remember { mutableStateOf(AppModel.themeMode()) }
        var dynamic by remember { mutableStateOf(AppModel.dynamicColor()) }
        var pureBlack by remember { mutableStateOf(AppModel.pureBlack()) }
        val dynAvailable = supportsDynamicColor()

        LegacyPageScaffold(
            title = "账号与偏好",
            navigationIcon = {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
            },
        ) { pageModifier ->
            Column(
                pageModifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "外观",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                LegacyListItem(
                    index = 0,
                    total = 3,
                    onClick = null,
                    headlineContent = { Text("主题模式") },
                    supportingContent = {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = themeMode == mode,
                                    onClick = {
                                        themeMode = mode
                                        AppModel.setThemeMode(mode)
                                    },
                                    label = { Text(when (mode) { ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" }) },
                                )
                            }
                        }
                    },
                )
                LegacyListItem(
                    index = 1,
                    total = 3,
                    onClick = { if (dynAvailable) { dynamic = !dynamic; AppModel.setDynamicColor(dynamic) } },
                    headlineContent = { Text("动态取色") },
                    supportingContent = { Text(if (dynAvailable) "Android 12+ 取自壁纸" else "当前平台不支持") },
                    trailingContent = {
                        Switch(
                            checked = dynamic && dynAvailable,
                            enabled = dynAvailable,
                            onCheckedChange = { dynamic = it; AppModel.setDynamicColor(it) },
                        )
                    },
                )
                LegacyListItem(
                    index = 2,
                    total = 3,
                    onClick = { pureBlack = !pureBlack; AppModel.setPureBlack(pureBlack) },
                    headlineContent = { Text("纯黑模式") },
                    supportingContent = { Text("深色时使用纯 #000 背景，省电屏友好") },
                    trailingContent = {
                        Switch(checked = pureBlack, onCheckedChange = { pureBlack = it; AppModel.setPureBlack(it) })
                    },
                )

                Spacer(Modifier.height(24.dp))
                Text(
                    "提示：切换主题即时生效，无需重启。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
