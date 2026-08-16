package cp.player.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cp.player.app.AppModel
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LegacyListItem
import cp.player.app.ui.component.LocalIsExpanded
import cp.player.app.ui.component.PageHeader

/** 设置详情页标识，用于 Expanded 布局的 list-detail 模式。 */
private enum class SettingsDetail {
    ProviderManagement, Login, Preferences, Playback, Health, About,
}

class SettingsScreen : Screen {
    @Composable
    override fun Content() { SettingsScreenContent() }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreenContent() {
    val expanded = LocalIsExpanded.current
    val navigator = LocalNavigator.current
    var selectedDetail by remember { mutableStateOf<SettingsDetail?>(null) }

    cp.player.app.ui.component.AppScaffold(
        title = "设置",
        onBackPressed = { navigator?.pop() },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (expanded) {
            Row(Modifier.fillMaxSize().padding(padding)) {
                // 左侧：设置列表（固定 320dp）
                Box(
                    Modifier.width(320.dp).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 16.dp, bottom = 32.dp),
                ) {
                    SettingsList(
                        navigator = navigator,
                        selectedDetail = selectedDetail,
                        onDetailSelected = { selectedDetail = it },
                    )
                }

                // 分隔线
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 1.dp,
                )

                // 右侧：详情页（嵌入式 Navigator）
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val detail = selectedDetail
                    if (detail != null) {
                        // key(detail) 确保切换不同详情时 Navigator 重建
                        key(detail) {
                            val screen = remember { detail.toScreen() }
                            Navigator(screen) { nav ->
                                LaunchedEffect(nav.lastItemOrNull) {
                                    if (nav.lastItemOrNull == null) selectedDetail = null
                                }
                                cafe.adriel.voyager.transitions.FadeTransition(nav)
                            }
                        }
                    } else {
                        // 未选中任何详情时的占位
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "选择左侧项目查看详情",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        } else {
            // Compact：传统列表布局，点击 push 导航
            Column(
                Modifier.fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = CpSpacing.pageHorizontal,
                        end = CpSpacing.pageHorizontal,
                        top = 16.dp,
                        bottom = 32.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(CpSpacing.section),
            ) {
                SettingsList(navigator = navigator, onDetailSelected = { /* compact 下由 entry.onClick 直接 push */ })
            }
        }
    }
}

/** SettingsDetail → Voyager Screen 映射。 */
private fun SettingsDetail.toScreen(): Screen = when (this) {
    SettingsDetail.ProviderManagement -> ProviderManagementScreen()
    SettingsDetail.Login -> LoginScreen()
    SettingsDetail.Preferences -> SettingsDetailScreen()
    SettingsDetail.Playback -> PlaybackSettingsScreen()
    SettingsDetail.Health -> HealthScreen()
    SettingsDetail.About -> AboutScreen()
}

/**
 * 设置列表内容，可复用于 Compact（Column 包裹）和 Expanded（左侧面板）。
 *
 * @param navigator 外部传入的 Navigator（Compact 下用于 push 导航；Expanded 下为 null）
 * @param selectedDetail 当前选中的详情（仅 Expanded 下使用，用于高亮）
 * @param onDetailSelected 选中某项详情时回调（Expanded 下更新状态；Compact 下不使用）
 */
@Composable
private fun SettingsList(
    navigator: cafe.adriel.voyager.navigator.Navigator? = null,
    selectedDetail: SettingsDetail? = null,
    onDetailSelected: (SettingsDetail) -> Unit = {},
) {
    val expanded = LocalIsExpanded.current

    Column(
        Modifier.fillMaxWidth().padding(horizontal = CpSpacing.pageHorizontal),
        verticalArrangement = Arrangement.spacedBy(CpSpacing.section),
    ) {
        SettingsSection(
            title = "音源与账号",
            entries = listOf(
                SettingsEntry(
                    Icons.Filled.Dns,
                    "音源管理",
                    "导入、切换或移除 Provider",
                    SettingsDetail.ProviderManagement,
                ) { navigator?.push(ProviderManagementScreen()) },
                SettingsEntry(
                    Icons.AutoMirrored.Filled.Login,
                    "登录",
                    "管理当前音源的账号",
                    SettingsDetail.Login,
                ) { navigator?.push(LoginScreen()) },
                SettingsEntry(
                    Icons.Filled.ManageAccounts,
                    "偏好设置",
                    "主题、动态取色、缓存与下载目录",
                    SettingsDetail.Preferences,
                ) { navigator?.push(SettingsDetailScreen()) },
                SettingsEntry(
                    Icons.Filled.Bedtime,
                    "播放设置",
                    "音质、播放行为与睡眠定时",
                    SettingsDetail.Playback,
                ) { navigator?.push(PlaybackSettingsScreen()) },
            ),
            selectedDetail = selectedDetail,
            onDetailSelected = onDetailSelected,
        )
        SettingsSection(
            title = "诊断",
            entries = listOf(
                SettingsEntry(
                    Icons.Filled.BugReport,
                    "API 健康监控",
                    "查看调用状态、日志与回退信息",
                    SettingsDetail.Health,
                ) { navigator?.push(HealthScreen()) },
            ),
            selectedDetail = selectedDetail,
            onDetailSelected = onDetailSelected,
        )
        SettingsSection(
            title = "关于",
            entries = listOf(
                SettingsEntry(
                    Icons.Filled.Info,
                    "关于 CP Player",
                    "版本、更新与项目维护者",
                    SettingsDetail.About,
                ) { navigator?.push(AboutScreen()) },
            ),
            selectedDetail = selectedDetail,
            onDetailSelected = onDetailSelected,
        )
    }
}

private data class SettingsEntry(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val detail: SettingsDetail,
    val onClick: () -> Unit,
)

@Composable
private fun SettingsSection(
    title: String,
    entries: List<SettingsEntry>,
    selectedDetail: SettingsDetail? = null,
    onDetailSelected: (SettingsDetail) -> Unit = {},
) {
    val expanded = LocalIsExpanded.current

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        entries.forEachIndexed { index, entry ->
            SettingsRow(
                entry = entry,
                index = index,
                total = entries.size,
                isSelected = expanded && selectedDetail == entry.detail,
                onClick = {
                    if (expanded) {
                        onDetailSelected(entry.detail)
                    } else {
                        entry.onClick()
                    }
                },
            )
        }
    }
}

@Composable
private fun SettingsRow(
    entry: SettingsEntry,
    index: Int,
    total: Int,
    isSelected: Boolean = false,
    onClick: () -> Unit,
) {
    val containerColor = when {
        isSelected -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }

    LegacyListItem(
        index = index,
        total = total,
        onClick = onClick,
        containerColor = containerColor,
        leadingContent = {
            Box(
                Modifier.size(44.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.secondaryContainer
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    entry.icon,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
        headlineContent = {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                entry.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            if (!isSelected) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
