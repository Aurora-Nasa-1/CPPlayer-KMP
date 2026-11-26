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
import cp.player.app.AppModel
import cp.player.app.presentation.SocialAvailability
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.component.settingsSearchTarget
import cp.player.app.ui.navigation.socialNavigationState
import cp.player.app.ui.navigation.statusText
import cp.player.core.provider.ProviderCapability

@Composable
internal fun NavigationSettingsSections(targetSettingId: String?) {
    val provider by AppModel.activeProviderFlow.collectAsState()
    val navigation = socialNavigationState(provider)

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
            modifier = Modifier.settingsSearchTarget("navigation_dynamic_tab", targetSettingId),
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
            modifier = Modifier.settingsSearchTarget("navigation_hide_dynamic", targetSettingId),
            icon = Icons.Filled.DynamicFeed,
            index = 0,
            total = 2,
        )
        SettingsSwitchItem(
            title = "隐藏「消息」入口",
            subtitle = navigation.inbox.statusText("会话列表"),
            checked = navigation.hideMessages,
            onCheckedChange = AppModel::setHideMessagesEntry,
            modifier = Modifier.settingsSearchTarget("navigation_hide_messages", targetSettingId),
            icon = Icons.Filled.Forum,
            index = 1,
            total = 2,
        )
    }
    SettingsNote(
        "入口隐藏只影响导航，不影响账号、收藏或历史记录；关掉开关入口即回来。",
    )

    Column(Modifier.settingsSearchTarget("navigation_provider_support", targetSettingId)) {
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
    }
    SettingsNote(
        "音源不声明某项能力时，对应入口会被物理隐藏（不占位、不显示「敬请期待」）。" +
            "切换音源后这份清单会立即刷新。",
    )
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
