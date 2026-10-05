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
import cp.player.app.i18n.cpStrings
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
import cp.player.core.sync.SYNC_HTTP_PORT
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 「激进保活」与局域网设备（仅 Android 出现入口，见 `SettingsRegistry.androidOnly`）。
 *
 * ### 这一页现在承担三件事
 * 1. **局域网设备列表** —— 实时展示发现层看到了哪些设备（桌面与 Android 都在这）；
 * 2. **自动同步开关** —— 开启后两台设备自动交换听歌记录，无需任何手动操作；
 * 3. **激进保活开关**（仅 Android 有意义）—— 持 Wi-Fi 高性能锁 + 组播锁，
 *    让熄屏后组播包不被系统丢弃。
 *
 * 两者放同一页是刻意的：设备发现失败最常见的原因就是 Wi-Fi 省电丢包，
 * 而开关就在这一页上 —— 用户「搜不到设备」时不需要被引导到别处找开关。
 *
 * ### 设备发现的启停策略
 * 由「在局域网中可见」开关驱动（默认开）：应用启动即开始发现并广播信标
 * （`AppModel.restoreLanVisibility`），关闭开关才真正停掉。进入/离开本页的
 * `DisposableEffect` 只是兜底 —— 可见性关闭时本页临时拉起发现，离开即还。
 * 设备列表因此不再要求「两台设备都停留在这页」，只要求两端都在运行 CPPlayer。
 */
class StandbySettingsScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val s = cpStrings()
        val enabled by AppModel.aggressiveStandbyFlow.collectAsState()
        // 实际持锁状态与开关值分开：持锁可能因权限被拒 / Wi-Fi 未开启而失败。
        var active by remember { mutableStateOf(isAggressiveStandbyActive()) }

        val peers by AppModel.discoveredPeersFlow.collectAsState()
        val running by AppModel.deviceDiscoveryRunningFlow.collectAsState()
        val discoveryError by AppModel.deviceDiscoveryErrorFlow.collectAsState()
        val stats by AppModel.deviceDiscoveryStatsFlow.collectAsState()
        val lanVisible by AppModel.lanVisibleFlow.collectAsState()
        val lanSyncEnabled by AppModel.lanSyncEnabledFlow.collectAsState()
        val lanSyncState by AppModel.lanSyncStateFlow.collectAsState()
        val handoffState by AppModel.handoffStateFlow.collectAsState()
        // 转移按钮的可点判据：本机真的在放一首曲子 —— 没在放就没有「转移」可言。
        val playbackState by AppModel.playback.state.collectAsState()
        val nowPlayingName = playbackState.currentTrack?.name

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

        // 兜底：可见性被用户关掉时，进入本页仍临时拉起发现，离开即还
        // （可见性开着时 start/stop 都会因 AppModel 里的幂等与 no-op 语义而无事发生）。
        DisposableEffect(Unit) {
            AppModel.startDeviceDiscovery()
            onDispose { AppModel.stopDeviceDiscovery() }
        }

        LaunchedEffect(enabled) { active = isAggressiveStandbyActive() }

        val identity = AppModel.deviceIdentity
        val online = peers.filter { it.isOnline(nowMs) }
        val offline = peers.filterNot { it.isOnline(nowMs) }

        CpRouteScaffold(
            title = s.standby.screenTitle,
            onBack = { navigator.popOrNotify() },
        ) { pageModifier ->
            SettingsPage(pageModifier) {
                SettingsSection(s.standby.sectionSelf) {
                    SettingsFieldGroup {
                        InfoRow(s.standby.deviceName, identity.name)
                        InfoRow(s.standby.devicePlatform, identity.platform)
                        InfoRow(s.standby.deviceId, identity.deviceId.take(8))
                        InfoRow(s.standby.deviceVersion, identity.appVersion)
                        InfoRow(
                            s.standby.discoveryStatus,
                            when {
                                discoveryError != null -> s.standby.discoveryFailed
                                running -> s.standby.listening
                                else -> s.standby.notStarted
                            },
                            highlight = discoveryError == null && running,
                        )
                    }
                }

                SettingsSection(s.standby.sectionVisible) {
                    SettingsSwitchItem(
                        title = s.standby.visible,
                        subtitle = s.standby.visibleNote,
                        checked = lanVisible,
                        onCheckedChange = { AppModel.setLanVisible(it) },
                        index = 0,
                        total = 1,
                    )
                }

                // 诊断计数：把「搜不到设备」的三种真因区分开 ——
                // 收到=0 → 本机收不到包（防火墙/对端没在发）；
                // 收到>0 且无效在涨 → 收到了但不认识（协议不符/别的程序占端口）；
                // 收到>0 且无效=0 → 只收到自己的回环。
                SettingsFieldGroup {
                    InfoRow(s.standby.beaconSent, s.standby.beaconSentRounds(stats.sent))
                    InfoRow(s.standby.beaconReceived, "${stats.received}")
                    if (stats.invalid > 0) {
                        InfoRow(s.standby.beaconInvalid, "${stats.invalid}", highlight = true)
                    }
                    InfoRow(
                        s.standby.lastReceived,
                        stats.lastRecvAt?.let {
                            java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault())
                                .toLocalTime().toString()
                        } ?: "—",
                    )
                }

                // ⚠️ 先落局部 val：`discoveryError` 是 `by collectAsState()` 的委托属性，
                // **不能** smart cast（编译器明确拒绝：委托属性每次读都可能有新值）。
                val discovery = discoveryError
                if (discovery != null) {
                    SettingsNote(
                        text = s.standby.discoveryStartFailed(discovery),
                        emphasis = SettingsNoteEmphasis.ERROR,
                    )
                }

                SettingsSection(s.standby.sectionAutoSync) {
                    SettingsSwitchItem(
                        title = s.standby.autoSync,
                        subtitle = s.standby.autoSyncNote,
                        checked = lanSyncEnabled,
                        onCheckedChange = { AppModel.setLanSyncEnabled(it) },
                        index = 0,
                        total = 2,
                    )
                    SettingsClickItem(
                        title = s.standby.syncNow,
                        subtitle = lanSyncState.lastSyncSummary.ifBlank { s.standby.neverSynced },
                        index = 1,
                        total = 2,
                        onClick = { AppModel.syncNow() },
                    )
                }

                SettingsFieldGroup {
                    InfoRow(
                        s.standby.syncService,
                        when {
                            lanSyncState.error != null -> s.standby.discoveryFailed
                            lanSyncState.serverRunning -> s.standby.syncListeningPort(SYNC_HTTP_PORT)
                            else -> s.standby.notStarted
                        },
                        highlight = lanSyncState.error == null && lanSyncState.serverRunning,
                    )
                    InfoRow(
                        s.standby.lastSync,
                        lanSyncState.lastSyncAt?.let {
                            java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault())
                                .toLocalTime().toString()
                        } ?: "—",
                    )
                }

                SettingsNote(
                    text = if (lanSyncEnabled) s.standby.syncWarning else s.standby.syncEnabledNote,
                    emphasis = if (lanSyncEnabled) SettingsNoteEmphasis.WARNING else SettingsNoteEmphasis.INFO,
                )

                if (cp.player.app.platform.isAndroidPlatform()) {
                    SettingsSection(s.standby.sectionKeepAlive) {
                        SettingsSwitchItem(
                            title = s.standby.aggressiveStandby,
                            subtitle = s.standby.aggressiveStandbyNote,
                            checked = enabled,
                            onCheckedChange = { AppModel.setAggressiveStandby(it) },
                            index = 0,
                            total = 1,
                        )
                    }

                    SettingsNote(
                        text = when {
                            !enabled -> s.standby.notEnabled
                            active -> s.standby.inEffect
                            else -> s.standby.notInEffect
                        } + if (enabled && active) "" else s.standby.keepAliveHint,
                        emphasis = if (enabled && !active) SettingsNoteEmphasis.WARNING else SettingsNoteEmphasis.INFO,
                    )
                }

                SettingsSection(s.standby.sectionLanDevices) {
                    if (online.isEmpty()) {
                        // 组内塞非分段文本会打断分段圆角，这里用说明行承载空态。
                        Text(
                            text = if (running) s.standby.discovering else s.standby.discoveryNotStarted,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    } else {
                        SettingsClickItem(
                            title = s.standby.onlineDevices,
                            subtitle = s.standby.onlineCount(online.size),
                            index = 0,
                            total = online.size + offline.size,
                            enabled = false,
                        )
                    }
                }

                if (online.isNotEmpty()) {
                    SettingsSection(s.standby.sectionOnline) {
                        online.forEachIndexed { index, peer ->
                            SettingsClickItem(
                                title = peer.displayName,
                                subtitle = "${peer.address}:${peer.streamPort} · ${peer.platform}" +
                                    (peer.appVersion.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") +
                                    if (nowPlayingName == null) s.standby.localNotPlaying else "",
                                index = index,
                                total = online.size,
                                enabled = nowPlayingName != null,
                                onClick = { AppModel.handoffTo(peer.address, peer.displayName) },
                            )
                        }
                    }
                }

                if (offline.isNotEmpty()) {
                    SettingsSection(s.standby.sectionOffline) {
                        offline.forEachIndexed { index, peer ->
                            SettingsClickItem(
                                title = peer.displayName,
                                subtitle = s.standby.justOnline(peer.address, peer.streamPort),
                                index = index,
                                total = offline.size,
                                enabled = false,
                            )
                        }
                    }
                }

                SettingsNote(s.standby.troubleshooting)

                // ⚠️ 先落局部 val：`handoffState` 是委托属性，不能 smart cast。
                val handoff = handoffState
                if (handoff != null) {
                    SettingsNote(
                        // 成败看 [AppModel.HandoffState.failed]，**不是**看文案 ——
                        // 旧版靠 `startsWith("转移失败")` 判断，改文案就会静默失配。
                        text = handoff.textOf(s),
                        emphasis = if (handoff.failed) {
                            SettingsNoteEmphasis.WARNING
                        } else {
                            SettingsNoteEmphasis.INFO
                        },
                    )
                }

                SettingsNote(s.standby.handoffPrereq)
                SettingsNote(s.standby.handoffSecurityNote)
                SettingsNote(s.standby.keepAliveReality)
                SettingsNote(s.standby.keepAliveRecommend)
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
