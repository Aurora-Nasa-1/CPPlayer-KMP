package cp.player.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LegacyListItem
import cp.player.app.ui.component.LocalIsExpanded

@Composable
private fun SettingsCategoryRail(onCategorySelected: (Int) -> Unit) {
    Column(
        modifier = Modifier.width(160.dp).fillMaxHeight().padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp))
        listOf("通用设置", "播放设置", "外观设置", "音源与账户").forEachIndexed { index, label ->
            Surface(
                color = if (index == 0) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().clickable { onCategorySelected(index) },
            ) { Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** 设置详情页标识，用于 Expanded 布局的 list-detail 模式。 */
private enum class SettingsDetail {
    Appearance,
    Playback,
    UiLogic,
    Storage,
    Health,
    ProviderManagement,
    About,
    Sponsor,
}

class SettingsScreen(private val embedded: Boolean = false) : Screen {
    @Composable
    override fun Content() { SettingsScreenContent(embedded = embedded) }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreenContent(embedded: Boolean = false) {
    val expanded = LocalIsExpanded.current
    val navigator = LocalNavigator.current
    var selectedDetail by remember { mutableStateOf<SettingsDetail?>(null) }

    if (embedded && expanded) {
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier.width(320.dp).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp, bottom = 24.dp),
            ) {
                SettingsList(
                    navigator = null,
                    selectedDetail = selectedDetail,
                    onDetailSelected = { selectedDetail = it },
                )
            }
            VerticalDivider(modifier = Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val detail = selectedDetail
                if (detail != null) {
                    DesktopSettingsDetail(detail)
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "选择中间菜单查看设置内容",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        return
    }

    cp.player.app.ui.component.AppScaffold(
        title = "设置",
        onBackPressed = { navigator?.pop() },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (expanded) {
            Row(Modifier.fillMaxSize().padding(padding)) {
                SettingsCategoryRail(onCategorySelected = { selectedDetail = when (it) {
                    0 -> SettingsDetail.Appearance
                    1 -> SettingsDetail.Playback
                    2 -> SettingsDetail.UiLogic
                    else -> SettingsDetail.ProviderManagement
                } })
                VerticalDivider(modifier = Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
                Box(
                    Modifier.width(320.dp).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp, bottom = 24.dp),
                ) {
                    SettingsList(
                        navigator = navigator,
                        selectedDetail = selectedDetail,
                        onDetailSelected = { selectedDetail = it },
                    )
                }
                VerticalDivider(modifier = Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
                // 右侧：次级设置项
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val detail = selectedDetail
                    if (detail != null) {
                        DesktopSettingsDetail(detail)
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
                        start = 8.dp,
                        end = 8.dp,
                        top = 8.dp,
                        bottom = 24.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                SettingsList(navigator = navigator, onDetailSelected = { /* compact 下由 entry.onClick 直接 push */ })
            }
        }
    }
}

/** SettingsDetail → Voyager Screen 映射。 */
private fun SettingsDetail.toScreen(): Screen = when (this) {
    SettingsDetail.Appearance -> AppearanceSettingsScreen()
    SettingsDetail.Playback -> PlaybackSettingsScreen()
    SettingsDetail.UiLogic -> UiLogicSettingsScreen()
    SettingsDetail.Storage -> StorageSettingsScreen()
    SettingsDetail.Health -> HealthScreen()
    SettingsDetail.ProviderManagement -> ProviderManagementScreen()
    SettingsDetail.About -> AboutScreen()
    SettingsDetail.Sponsor -> SponsorScreen()
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
    val entries = remember { settingsEntries() }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        entries.forEachIndexed { index, entry ->
            SettingsRow(
                entry = entry,
                index = index,
                total = entries.size,
                isSelected = selectedDetail == entry.detail,
                onClick = {
                    onDetailSelected(entry.detail)
                    navigator?.push(entry.screen())
                },
            )
        }
    }
}

@Composable
private fun DesktopSettingsDetail(detail: SettingsDetail) {
    when (detail) {
        SettingsDetail.Appearance -> AppearanceSettingsScreen().Content()
        SettingsDetail.Playback -> PlaybackSettingsScreen().Content()
        SettingsDetail.UiLogic -> UiLogicSettingsScreen().Content()
        SettingsDetail.Storage -> StorageSettingsScreen().Content()
        SettingsDetail.Health -> HealthScreen().Content()
        SettingsDetail.ProviderManagement -> ProviderManagementScreen().Content()
        SettingsDetail.About -> AboutScreen().Content()
        SettingsDetail.Sponsor -> SponsorScreen().Content()
    }
}

private data class SettingsEntry(
    val icon: ImageVector,
    val iconContainerColor: Color,
    val iconContentColor: Color,
    val title: String,
    val subtitle: String,
    val detail: SettingsDetail,
    val screen: () -> Screen,
)

private fun settingsEntries(): List<SettingsEntry> = listOf(
    SettingsEntry(
        icon = Icons.Filled.Palette,
        iconContainerColor = Color(0xFFE8F5E9),
        iconContentColor = Color(0xFF2E7D32),
        title = "外观",
        subtitle = "主题、动态取色与纯黑模式",
        detail = SettingsDetail.Appearance,
        screen = { AppearanceSettingsScreen() },
    ),
    SettingsEntry(
        icon = Icons.Filled.Bedtime,
        iconContainerColor = Color(0xFFE3F2FD),
        iconContentColor = Color(0xFF1565C0),
        title = "播放",
        subtitle = "音质、播放行为与睡眠定时",
        detail = SettingsDetail.Playback,
        screen = { PlaybackSettingsScreen() },
    ),
    SettingsEntry(
        icon = Icons.Filled.TouchApp,
        iconContainerColor = Color(0xFFE8F5E9),
        iconContentColor = Color(0xFF388E3C),
        title = "交互逻辑",
        subtitle = "播放行为与旧版交互逻辑入口",
        detail = SettingsDetail.UiLogic,
        screen = { UiLogicSettingsScreen() },
    ),
    SettingsEntry(
        icon = Icons.Filled.Storage,
        iconContainerColor = Color(0xFFFFF3E0),
        iconContentColor = Color(0xFFEF6C00),
        title = "存储与下载",
        subtitle = "缓存、图片清理与下载目录",
        detail = SettingsDetail.Storage,
        screen = { StorageSettingsScreen() },
    ),
    SettingsEntry(
        icon = Icons.Filled.BugReport,
        iconContainerColor = Color(0xFFFCE4EC),
        iconContentColor = Color(0xFFC2185B),
        title = "调试",
        subtitle = "查看调用状态、日志与回退信息",
        detail = SettingsDetail.Health,
        screen = { HealthScreen() },
    ),
    SettingsEntry(
        icon = Icons.Filled.Dns,
        iconContainerColor = Color(0xFFFFFDE7),
        iconContentColor = Color(0xFFF57F17),
        title = "音源管理",
        subtitle = "导入、切换或移除 Provider",
        detail = SettingsDetail.ProviderManagement,
        screen = { ProviderManagementScreen() },
    ),
    SettingsEntry(
        icon = Icons.Filled.HelpOutline,
        iconContainerColor = Color(0xFFEFEBE9),
        iconContentColor = Color(0xFF4E342E),
        title = "关于",
        subtitle = "版本、更新与项目维护者",
        detail = SettingsDetail.About,
        screen = { AboutScreen() },
    ),
    SettingsEntry(
        icon = Icons.Filled.Favorite,
        iconContainerColor = Color(0xFFFCE4EC),
        iconContentColor = Color(0xFFE91E63),
        title = "赞助",
        subtitle = "独立赞助页与项目支持入口",
        detail = SettingsDetail.Sponsor,
        screen = { SponsorScreen() },
    ),
)

@Composable
private fun SettingsRow(
    entry: SettingsEntry,
    index: Int,
    total: Int,
    isSelected: Boolean = false,
    onClick: () -> Unit,
) {
    LegacyListItem(
        index = index,
        total = total,
        onClick = onClick,
        modifier = Modifier.heightIn(min = 68.dp),
        containerColor = if (isSelected) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            if (androidx.compose.foundation.isSystemInDarkTheme()) {
                MaterialTheme.colorScheme.surfaceContainerHighest
            } else {
                MaterialTheme.colorScheme.surface
            }
        },
        leadingContent = {
            MonetIcon(
                icon = entry.icon,
                containerColor = entry.iconContainerColor,
                contentColor = entry.iconContentColor,
            )
        },
        headlineContent = {
            Text(
                entry.title,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
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
    )
}

@Composable
private fun MonetIcon(
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(26.dp),
        )
    }
}
