package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import cp.player.app.ui.navigation.RoutePage
import cp.player.app.AppModel
import cp.player.app.presentation.SocialAvailability
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.navigation.LocalAppNavigator
import cp.player.app.ui.navigation.socialNavigationState
import cp.player.app.ui.navigation.statusText
import cp.player.core.provider.ProviderCapability

/**
 * 导航入口设置。
 *
 * 管三件事，都在同一页：
 * 1. 「动态」是否占用主 tab 里「搜索」的位置（搜索变成顶栏动作）；
 * 2. 是否隐藏「动态」/「消息」入口；
 * 3. 当前音源实际支持哪些功能 —— 不支持的入口本来就不会出现，这里列出判据，
 *    让「入口怎么没了」有一个可查的答案。
 *
 * 所有开关都写 [AppModel] 的 [androidx.compose.runtime.StateFlow]，`MainScreen` 收集后即时生效，
 * 不需要重启。音源能力是 `activeProviderFlow` 的派生值，切音源后本页自动刷新。
 */
class NavigationSettingsScreen(private val targetSettingId: String? = null) : RoutePage {
    @Composable
    override fun Content() {
        val provider by AppModel.activeProviderFlow.collectAsState()
        val navigation = socialNavigationState(provider)
        // 必须在 composable 上下文里取好导航器：`onBack` 是普通 lambda，不能在里面读 CompositionLocal。
        val navigator = LocalAppNavigator.current

        val body: @Composable (Modifier) -> Unit = { pageModifier ->
            SettingsPage(pageModifier) {
                SettingsSection("主 tab") {
                    SettingsSwitchItem(
                        title = "用「动态」替代「搜索」tab",
                        subtitle = when {
                            !navigation.replaceSearch -> "关闭时保留搜索 tab"
                            navigation.dynamicAsTab -> "已生效，搜索位于顶栏"
                            navigation.hideDynamic -> "暂未生效：动态入口已隐藏"
                            else -> "暂未生效：${navigation.feed.statusText("主动态")}"
                        },
                        checked = navigation.replaceSearch,
                        onCheckedChange = AppModel::setDynamicReplacesSearch,
                        icon = Icons.Filled.Search,
                        index = 0,
                        total = 1,
                    )
                }

                SettingsSection("入口可见性") {
                    SettingsSwitchItem(
                        title = "隐藏「动态」入口",
                        subtitle = navigation.feed.statusText("主动态"),
                        checked = navigation.hideDynamic,
                        onCheckedChange = AppModel::setHideDynamicEntry,
                        icon = Icons.Filled.DynamicFeed,
                        index = 0,
                        total = 2,
                    )
                    SettingsSwitchItem(
                        title = "隐藏「消息」入口",
                        subtitle = navigation.inbox.statusText("会话列表"),
                        checked = navigation.hideMessages,
                        onCheckedChange = AppModel::setHideMessagesEntry,
                        icon = Icons.Filled.Forum,
                        index = 1,
                        total = 2,
                    )
                }
                SettingsNote(
                    "入口隐藏只影响导航，不影响账号、收藏或历史记录；关掉开关入口即回来。",
                )

                SettingsSection("当前音源的功能支持") {
                    ProviderFeatureRow(
                        icon = Icons.Filled.DynamicFeed,
                        title = "社交动态",
                        availability = navigation.feed,
                    )
                    ProviderFeatureRow(
                        icon = Icons.Filled.Forum,
                        title = "私信与消息",
                        availability = navigation.inbox,
                    )
                    ProviderFeatureRow(
                        icon = Icons.Filled.Search,
                        title = "搜索",
                        availability = when (provider?.isFeatureAvailable(ProviderCapability.SEARCH)) {
                            true -> SocialAvailability.Available
                            false -> SocialAvailability.Unsupported
                            null -> navigation.feed
                        },
                    )
                }
                SettingsNote(
                    "音源不声明某项能力时，对应入口会被物理隐藏（不占位、不显示「敬请期待」）。" +
                        "切换音源后这份清单会立即刷新。",
                )
            }
        }

        // 设置页在桌面是「内嵌面板」、在窄屏是根栈路由页；[CpRouteScaffold] 的 onBack
        // 只在窄屏真的画出返回键时才被调用，桌面由窗口标题栏承担，两处都不需要额外判断。
        CpRouteScaffold(
            title = "导航入口",
            onBack = { navigator.pop() },
        ) { pageModifier -> body(pageModifier) }
    }
}

@Composable
private fun ProviderFeatureRow(
    icon: ImageVector,
    title: String,
    availability: SocialAvailability,
) {
    val supported = availability == SocialAvailability.Available
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = CpSpacing.formRowHorizontal,
                vertical = CpSpacing.formRowVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CpSpacing.formRowGap),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f)) {
            CpText(title, style = MaterialTheme.typography.bodyLarge)
            CpText(
                text = availability.statusText(title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (supported) Icons.Filled.CheckCircle else Icons.Filled.Info,
            contentDescription = null,
            tint = if (supported) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
    }
}
