package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.platform.isAggressiveStandbyActive
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsFieldGroup
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsNoteEmphasis
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.util.popOrNotify
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 「激进保活」与局域网设备（仅 Android 出现入口，见 `SettingsRegistry.androidOnly`）。
 *
 * ### 这一页现在承担两件事
 * 1. **激进保活开关** —— 持 Wi-Fi 高性能锁 + 组播锁，让熄屏后组播包不被系统丢弃；
 * 2. **局域网设备列表** —— 实时展示发现层看到了哪些设备。
 *
 * 两者放同一页是刻意的：设备发现失败最常见的原因就是 Wi-Fi 省电丢包，
 * 而开关就在这一页上 —— 用户「搜不到设备」时不需要被引导到别处找开关。
 *
 * ### 设备发现的启停策略
 * **进入本页才开启，离开即关闭**（`DisposableEffect`）。不是常驻：
 * 在配对与同步协议落地之前，持续广播信标没有任何收益，却增加耗电与网络暴露面。
 * 这也意味着设备列表只在「两台设备都打开这一页」时才会出现 —— 这正是
 * 当前阶段验证「互相能发现」的预期用法，而不是产品形态（见方案 §8.5）。
 */
class StandbySettingsScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val enabled by AppModel.aggressiveStandbyFlow.collectAsState()
        // 实际持锁状态与开关值分开：持锁可能因权限被拒 / Wi-Fi 未开启而失败。
        var active by remember { mutableStateOf(isAggressiveStandbyActive()) }

        val peers by AppModel.discoveredPeersFlow.collectAsState()
        val running by AppModel.deviceDiscoveryRunningFlow.collectAsState()
        val discoveryError by AppModel.deviceDiscoveryErrorFlow.collectAsState()

        // 「在线与否」是时间的函数（PeerState 只存 lastSeenAt），
        // 而 peers 流只在收到信标 / 遗忘设备时才变 —— 对端静默退出时列表不会自己变。
        // 用一个 2s 的本地时钟驱动重组，把超时判定变成实时的。
        var nowMs by remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) {
            while (isActive) {
                nowMs = cp.player.core.util.currentTimeMillis()
                delay(2_000)
            }
        }

        // 进入页面才开发现、离开即关 —— 见类 KDoc 的启停策略。
        DisposableEffect(Unit) {
            AppModel.startDeviceDiscovery()
            onDispose { AppModel.stopDeviceDiscovery() }
        }

        LaunchedEffect(enabled) { active = isAggressiveStandbyActive() }

        val identity = AppModel.deviceIdentity
        val online = peers.filter { it.isOnline(nowMs) }
        val offline = peers.filterNot { it.isOnline(nowMs) }

        CpRouteScaffold(
            title = "激进保活",
            onBack = { navigator.popOrNotify() },
        ) { pageModifier ->
            SettingsPage(pageModifier) {
                SettingsSection("本机") {
                    SettingsFieldGroup {
                        InfoRow("名称", identity.name)
                        InfoRow("平台", identity.platform)
                        InfoRow("设备 ID", identity.deviceId.take(8))
                        InfoRow("版本", identity.appVersion)
                        InfoRow(
                            "发现状态",
                            when {
                                discoveryError != null -> "启动失败"
                                running -> "监听中"
                                else -> "未启动"
                            },
                            highlight = discoveryError == null && running,
                        )
                    }
                }

                if (discoveryError != null) {
                    SettingsNote(
                        text = "设备发现启动失败：$discoveryError",
                        emphasis = SettingsNoteEmphasis.ERROR,
                    )
                }

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

                SettingsNote(
                    text = when {
                        !enabled -> "未启用"
                        active -> "已生效"
                        else -> "未生效（可能被系统拒绝）"
                    } + if (enabled && active) "" else "。没有它，熄屏后系统可能丢弃组播包。",
                    emphasis = if (enabled && !active) SettingsNoteEmphasis.WARNING else SettingsNoteEmphasis.INFO,
                )

                SettingsSection("局域网设备") {
                    if (online.isEmpty()) {
                        // 组内塞非分段文本会打断分段圆角，这里用说明行承载空态。
                        Text(
                            text = if (running) {
                                "正在监听，还没有发现其他设备。两台设备都要打开 CPPlayer 并停留在这一页。"
                            } else {
                                "设备发现未启动。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    } else {
                        SettingsClickItem(
                            title = "在线设备",
                            subtitle = "${online.size} 台",
                            index = 0,
                            total = online.size + offline.size,
                            enabled = false,
                        )
                    }
                }

                if (online.isNotEmpty()) {
                    SettingsSection("在线") {
                        online.forEachIndexed { index, peer ->
                            SettingsClickItem(
                                title = peer.displayName,
                                subtitle = "${peer.address}:${peer.streamPort} · ${peer.platform}" +
                                    (peer.appVersion.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                index = index,
                                total = online.size,
                                // 配对与转移尚未实现：现在点了也没有可做的事，
                                // 与其给出一个「点了没反应」的可点行，不如明确不可点。
                                enabled = false,
                            )
                        }
                    }
                }

                if (offline.isNotEmpty()) {
                    SettingsSection("已离线") {
                        offline.forEachIndexed { index, peer ->
                            SettingsClickItem(
                                title = peer.displayName,
                                subtitle = "${peer.address}:${peer.streamPort} · 刚才还在",
                                index = index,
                                total = offline.size,
                                enabled = false,
                            )
                        }
                    }
                }

                SettingsNote(
                    "搜不到设备时按顺序检查：① 两台设备都打开 CPPlayer 并停留在这一页；" +
                        "② 同一局域网（注意访客网络会把设备互相隔离）；" +
                        "③ Windows 首次监听会弹防火墙授权，拒绝过就再也收不到信标；" +
                        "④ 多网卡机器（VPN、虚拟机网卡）可能需要多试几次。",
                )

                SettingsNote(
                    "需要说明的是：本页的设备列表只回答「互相能发现」，" +
                        "配对、数据同步与换设备播放都还没有实现 —— 那是接下来的工作。",
                )

                SettingsNote(
                    "它**不能**让本应用免于被系统回收。系统按进程优先级淘汰后台进程，" +
                        "与进程大小、用什么语言实现无关。真正可靠的常驻只有前台服务，" +
                        "所以本开关只是把「系统愿意留你多久」往有利方向推。",
                )

                SettingsNote(
                    "建议同时打开「播放与音质 → 电池优化白名单」，两者配合才能挡住厂商 ROM 的后台清理。",
                )
            }
        }
    }

    /** 只读信息行。 */
    @Composable
    private fun InfoRow(label: String, value: String, highlight: Boolean = false) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (highlight) FontWeight.Medium else FontWeight.Normal,
                color = if (highlight) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}
