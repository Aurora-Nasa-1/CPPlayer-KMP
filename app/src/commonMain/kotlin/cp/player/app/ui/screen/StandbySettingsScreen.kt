package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.platform.isAggressiveStandbyActive
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.SettingsFieldGroup
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsNoteEmphasis
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.util.popOrNotify

/**
 * 「激进保活」设置页（仅 Android）。
 *
 * ### 这一页的职责是**说清代价与边界**，不只是放一个开关
 *
 * 用户对「保活」普遍有两个误解，两个都会导致「开了没用还以为坏了」：
 * 1. **以为能让进程不被杀** —— 不能。Android 的 LMK 按 `oomAdj` 淘汰进程，
 *    与进程大小、用什么语言写都无关（把待机件改写成 JNI 原生进程同样无效）。
 *    真正可靠的常驻只有前台服务。
 * 2. **以为开了就一定有收益** —— 收益取决于 Wi-Fi 是否真的被系统置入省电模式。
 *    所以这里显示的是**实际持锁状态**，而不是简单回显开关值。
 *
 * 本页刻意**不重复**「电池优化白名单」那一项：它在「播放与音质」里已经有了，
 * 同一件事放两个开关，用户会以为要开两个而只开一个。
 */
class StandbySettingsScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val enabled by AppModel.aggressiveStandbyFlow.collectAsState()
        // 实际持锁状态与开关值分开：持锁可能因权限被拒 / Wi-Fi 未开启而失败。
        var active by remember { mutableStateOf(isAggressiveStandbyActive()) }

        // 开关变化后平台侧是同步持锁的，这里跟着刷新一次实际状态。
        LaunchedEffect(enabled) { active = isAggressiveStandbyActive() }

        CpRouteScaffold(
            title = "激进保活",
            onBack = { navigator.popOrNotify() },
        ) { pageModifier ->
            SettingsPage(pageModifier) {
                SettingsSection("后台在线") {
                    SettingsSwitchItem(
                        title = "激进保活",
                        subtitle = "熄屏后维持 Wi-Fi 在线，让设备发现与换设备播放仍可能命中",
                        checked = enabled,
                        onCheckedChange = { AppModel.setAggressiveStandby(it) },
                        index = 0,
                        total = 1,
                    )
                }

                SettingsFieldGroup {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "当前状态",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = when {
                                !enabled -> "未启用"
                                active -> "已生效"
                                else -> "未生效（可能被系统拒绝）"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = when {
                                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                                active -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }

                SettingsNote(
                    "开启后会持有 Wi-Fi 高性能锁与组播锁：熄屏时系统不会让 Wi-Fi 进入省电模式，" +
                        "否则设备之间的发现包会被直接丢弃（这正是「手机就在旁边却搜不到」的原因）。",
                )

                SettingsNote(
                    text = "代价是耗电。持续持锁会让 Wi-Fi 无法休眠，因此在不需要跨设备播放时请关闭。",
                    emphasis = SettingsNoteEmphasis.WARNING,
                )

                SettingsNote(
                    "需要说明的是：它**不能**让本应用免于被系统回收。系统按进程优先级淘汰后台进程，" +
                        "与进程大小、用什么语言实现无关。真正可靠的常驻只有前台服务，" +
                        "所以本开关只是把「系统愿意留你多久」往有利方向推。",
                )

                SettingsNote(
                    "建议同时打开「播放与音质 → 电池优化白名单」，两者配合才能挡住厂商 ROM 的后台清理。",
                )
            }
        }
    }
}
