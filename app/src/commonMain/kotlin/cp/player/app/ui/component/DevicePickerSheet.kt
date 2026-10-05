package cp.player.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 「转移到其他设备」选择弹层（播放器「更多」菜单的二级弹层）。
 *
 * ### 生命周期跟随弹层，只是兜底
 * 发现层由「在局域网中可见」开关驱动（默认开，应用启动即运行）；
 * 弹层的 `DisposableEffect` 只在可见性被关闭时临时拉起发现，关闭弹层即还。
 * [cp.player.app.AppModel.stopDeviceDiscovery] 在可见性开着时是空操作，
 * 所以这里的启停不会干扰常驻的发现循环。
 *
 * ### 为什么判据是「对端开着自动同步」
 * 转移复用同步的传输面（同一端口、同一服务）：目标端必须开着「自动同步」
 * （即同步服务在监听）才接得住。这在行副标题与空态里都如实说明，避免
 * 「明明在线却转不过去」的困惑。
 */
@Composable
fun DevicePickerSheet(
    onDismiss: () -> Unit,
) {
    val peers by AppModel.discoveredPeersFlow.collectAsState()
    val running by AppModel.deviceDiscoveryRunningFlow.collectAsState()
    val discoveryError by AppModel.deviceDiscoveryErrorFlow.collectAsState()
    val s = cpStrings()
    val handoffState by AppModel.handoffStateFlow.collectAsState()
    val nowPlayingName = AppModel.playback.state.collectAsState().value.currentTrack?.name

    // 「在线与否」是时间的函数 —— 与设备页同款 2s 本地时钟，让掉线实时生效。
    var nowMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (isActive) {
            nowMs = cp.player.core.util.currentTimeMillis()
            delay(2_000)
        }
    }

    // 兜底：可见性关闭时弹层期间临时拉起发现，关闭即还；可见性开着时两者都是空操作。
    DisposableEffect(Unit) {
        AppModel.startDeviceDiscovery()
        onDispose { AppModel.stopDeviceDiscovery() }
    }

    LegacyModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = s.standby.pickerTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = nowPlayingName?.let { s.standby.pickerNowPlaying(it) } ?: s.standby.pickerNoTrack,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            val online = peers.filter { it.isOnline(nowMs) }
            // ⚠️ `discoveryError` 是 `by collectAsState()` 的委托属性，**不能** smart cast
            // （编译器明确拒绝：委托属性每次读都可能有新值）。先落局部 val 再用。
            val discovery = discoveryError
            when {
                discovery != null -> SheetHint(s.standby.pickerDiscoveryFailed(discovery))
                online.isEmpty() -> SheetHint(
                    if (running) s.standby.pickerSearching else s.standby.discoveryNotStarted,
                )
                else -> {
                    online.forEachIndexed { index, peer ->
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                AppModel.handoffTo(peer.address, peer.displayName)
                                onDismiss()
                            },
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Icon(
                                    imageVector = if (peer.platform.contains("android", ignoreCase = true)) {
                                        Icons.Filled.PhoneAndroid
                                    } else {
                                        Icons.Filled.Computer
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(26.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = peer.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${peer.address} · ${peer.platform}" +
                                            (peer.appVersion.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Filled.Cast,
                                    contentDescription = s.standby.handoffToLabel,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (index != online.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                    SheetHint(s.standby.pickerHint)
                }
            }

            handoffState?.let { state ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = state.textOf(s),
                    style = MaterialTheme.typography.bodySmall,
                    // 成败看状态类型而非文案（旧版 `startsWith("转移失败")` 改文案就失配）。
                    color = if (state.failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun SheetHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}
