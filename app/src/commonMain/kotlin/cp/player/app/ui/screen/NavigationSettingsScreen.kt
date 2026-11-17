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
import cafe.adriel.voyager.core.screen.Screen
import cp.player.app.AppModel
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.navigation.LocalAppNavigator
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
class NavigationSettingsScreen(private val targetSettingId: String? = null) : Screen {
    @Composable
    override fun Content() {
        val dynamicReplacesSearch by AppModel.dynamicReplacesSearchFlow.collectAsState()
        val hideDynamic by AppModel.hideDynamicEntryFlow.collectAsState()
        val hideMessages by AppModel.hideMessagesEntryFlow.collectAsState()
        val provider by AppModel.activeProviderFlow.collectAsState()
        val supportsDynamic = provider?.isFeatureAvailable(ProviderCapability.SOCIAL_FEED) ?: true
        val supportsMessages = provider?.isFeatureAvailable(ProviderCapability.MESSAGES) ?: true
        val providerName = provider?.name.orEmpty()
        // 必须在 composable 上下文里取好导航器：`onBack` 是普通 lambda，不能在里面读 CompositionLocal。
        val navigator = LocalAppNavigator.current

        val body: @Composable (Modifier) -> Unit = { pageModifier ->
            SettingsPage(pageModifier) {
                SettingsSection("主 tab") {
                    SettingsSwitchItem(
                        title = "用「动态」替代「搜索」tab",
                        subtitle = "打开后第 2 个 tab 变成动态，搜索移到顶栏动作位；两种布局用的是同一个搜索页",
                        checked = dynamicReplacesSearch,
                        onCheckedChange = AppModel::setDynamicReplacesSearch,
                        icon = Icons.Filled.Search,
                        index = 0,
                        total = 1,
                    )
                }
                SettingsNote(
                    "搜索始终可用，只是有动态 tab 时不再占一个底部 / 侧栏位置。" +
                        "动态入口还需要当前音源声明社交动态能力。",
                )

                SettingsSection("入口可见性") {
                    SettingsSwitchItem(
                        title = "隐藏「动态」入口",
                        subtitle = if (supportsDynamic) {
                            "隐藏主 tab / 侧栏 / 顶栏的动态入口，数据不受影响"
                        } else {
                            "当前音源不支持社交动态，入口本来就不会出现"
                        },
                        checked = hideDynamic,
                        onCheckedChange = AppModel::setHideDynamicEntry,
                        enabled = supportsDynamic,
                        icon = Icons.Filled.DynamicFeed,
                        index = 0,
                        total = 2,
                    )
                    SettingsSwitchItem(
                        title = "隐藏「消息」入口",
                        subtitle = if (supportsMessages) {
                            "隐藏顶栏 / 侧栏 / 底栏的消息入口，未读数不再请求"
                        } else {
                            "当前音源不支持私信，入口本来就不会出现"
                        },
                        checked = hideMessages,
                        onCheckedChange = AppModel::setHideMessagesEntry,
                        enabled = supportsMessages,
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
                        capability = ProviderCapability.SOCIAL_FEED,
                        supported = supportsDynamic,
                        providerName = providerName,
                    )
                    ProviderFeatureRow(
                        icon = Icons.Filled.Forum,
                        title = "私信与消息",
                        capability = ProviderCapability.MESSAGES,
                        supported = supportsMessages,
                        providerName = providerName,
                    )
                    ProviderFeatureRow(
                        icon = Icons.Filled.Search,
                        title = "搜索",
                        capability = ProviderCapability.SEARCH,
                        supported = provider?.isFeatureAvailable(ProviderCapability.SEARCH) ?: true,
                        providerName = providerName,
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
    capability: String,
    supported: Boolean,
    providerName: String,
) {
    val container = if (supported) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
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
                text = if (supported) {
                    "支持 · $capability"
                } else {
                    "不支持 · $capability${if (providerName.isBlank()) "" else " · $providerName"}"
                },
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
