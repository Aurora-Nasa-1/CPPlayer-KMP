package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.ui.component.LegacyListItem
import cp.player.app.ui.component.LegacyPageScaffold
import cp.player.core.control.LocalServerConfig
import cp.player.core.control.LocalServerStatus
import cp.player.core.control.OutputMode
import cp.player.core.control.PushResult

/**
 * 「本地服务器输出 + 外部推送」设置页。
 *
 * CPPlayer 在这里是**推送方**：
 * 1. 本机把当前曲目以 HTTP 流形式对外提供（`GET /stream`）；
 * 2. 主动把流地址与传输指令推给接收端（默认 `http://127.0.0.1:8420`）。
 *
 * 接收端只需实现 `/api/v1/...` 那套接口，不必感知 CPPlayer 的内部结构。
 */
class LocalServerSettingsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val expanded = cp.player.app.ui.component.LocalIsExpanded.current
        val config by AppModel.localServerConfigFlow.collectAsState()
        val status by AppModel.localServerStatus.collectAsState()
        val lastPush by AppModel.lastPushResult.collectAsState()

        // 端口/地址用本地字符串态：允许输入过程中的中间状态，解析成功才提交。
        var streamPortText by remember(config.streamPort) { mutableStateOf(config.streamPort.toString()) }
        var receiverText by remember { mutableStateOf(config.receiverBaseUrl) }

        val body: @Composable (Modifier) -> Unit = { pageModifier ->
            Column(
                modifier = pageModifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (expanded) 20.dp else 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsCard("本地服务器输出") {
                    LegacyListItem(
                        index = 0,
                        total = 1,
                        onClick = { AppModel.setLocalServerEnabled(!config.enabled) },
                        leadingContent = { Icon(Icons.Filled.Dns, contentDescription = null) },
                        headlineContent = { Text("启用本地服务器输出") },
                        supportingContent = { Text("把当前曲目以 HTTP 流对外提供，并推送给接收端") },
                        trailingContent = {
                            Switch(
                                checked = config.enabled,
                                onCheckedChange = { AppModel.setLocalServerEnabled(it) },
                            )
                        },
                    )
                    Text("音频输出", style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = config.outputMode == OutputMode.SPEAKER,
                            onClick = { AppModel.setOutputMode(OutputMode.SPEAKER) },
                            label = { Text("本机声卡") },
                        )
                        FilterChip(
                            selected = config.outputMode == OutputMode.SERVER_ONLY,
                            onClick = { AppModel.setOutputMode(OutputMode.SERVER_ONLY) },
                            label = { Text("只做服务器") },
                        )
                    }
                    Text(
                        text = if (config.silentLocalOutput) {
                            "本机不出声，音频只从接收端播放。进度、歌词、打卡仍由本机时间轴驱动。"
                        } else {
                            "本机正常出声；推送开启时接收端会同步播放同一首。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (config.silentLocalOutput) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = statusSummary(config, status),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.error != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                SettingsCard("流输出") {
                    OutlinedTextField(
                        value = streamPortText,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() } && input.length <= 5) {
                                streamPortText = input
                                input.toIntOrNull()?.let(AppModel::setLocalServerStreamPort)
                            }
                        },
                        label = { Text("流输出端口") },
                        supportingText = { Text("接收端按曲目拉流，默认 ${LocalServerConfig.DEFAULT_STREAM_PORT}") },
                        singleLine = true,
                        enabled = config.enabled,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        leadingIcon = { Icon(Icons.Filled.SettingsEthernet, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = config.bindAddress == LocalServerConfig.BIND_LOOPBACK,
                            onClick = { AppModel.setLocalServerBind(LocalServerConfig.BIND_LOOPBACK) },
                            label = { Text("仅本机") },
                        )
                        FilterChip(
                            selected = config.bindAddress == LocalServerConfig.BIND_ALL,
                            onClick = { AppModel.setLocalServerBind(LocalServerConfig.BIND_ALL) },
                            label = { Text("局域网") },
                        )
                    }
                    Text(
                        text = if (config.exposedToLan) {
                            "监听 0.0.0.0，同网段设备可拉流。务必保留访问令牌。"
                        } else {
                            "监听 127.0.0.1，只有本机程序可以拉流。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (config.exposedToLan) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    SelectionContainer {
                        Text(
                            text = "令牌：${config.accessToken.ifBlank { "启用后自动生成" }}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LegacyListItem(
                        index = 0,
                        total = 1,
                        onClick = { AppModel.regenerateLocalServerToken() },
                        leadingContent = { Icon(Icons.Filled.Lock, contentDescription = null) },
                        headlineContent = { Text("重新生成访问令牌") },
                        supportingContent = { Text("旧令牌立即失效，接收端需改用新地址") },
                        trailingContent = {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }

                SettingsCard("推送到接收端") {
                    OutlinedTextField(
                        value = receiverText,
                        onValueChange = {
                            receiverText = it
                            AppModel.setReceiverBaseUrl(it)
                        },
                        label = { Text("接收端地址") },
                        supportingText = { Text("例如 ${LocalServerConfig.DEFAULT_RECEIVER_BASE_URL}") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Wifi, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LegacyListItem(
                        index = 0,
                        total = 1,
                        onClick = { AppModel.setPushEnabled(!config.pushEnabled) },
                        headlineContent = { Text("曲目变化时自动推送") },
                        supportingContent = { Text("开始播放新曲目时自动替换接收端队列并播放") },
                        trailingContent = {
                            Switch(
                                checked = config.pushEnabled,
                                onCheckedChange = { AppModel.setPushEnabled(it) },
                            )
                        },
                    )
                    LegacyListItem(
                        index = 0,
                        total = 3,
                        onClick = { AppModel.probeReceiver { } },
                        leadingContent = { Icon(Icons.Filled.Wifi, contentDescription = null) },
                        headlineContent = { Text("测试连接") },
                        supportingContent = { Text("请求接收端 /api/health") },
                    )
                    LegacyListItem(
                        index = 1,
                        total = 3,
                        onClick = { AppModel.pushCurrentTrack { } },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                        headlineContent = { Text("推送当前曲目") },
                        supportingContent = { Text("替换接收端队列为这一首并立即播放") },
                    )
                    LegacyListItem(
                        index = 2,
                        total = 3,
                        onClick = { AppModel.pushQueueToReceiver { } },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                        headlineContent = { Text("推送当前队列") },
                        supportingContent = { Text("整队列交给接收端，由它负责顺序播放") },
                    )
                    Text(
                        text = lastPush.describe(),
                        style = MaterialTheme.typography.bodySmall,
                        color = when (lastPush) {
                            is PushResult.Ok -> MaterialTheme.colorScheme.primary
                            is PushResult.Failed -> MaterialTheme.colorScheme.error
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                SettingsCard("接收端会拉取的地址") {
                    SelectionContainer {
                        Text(
                            text = config.streamUrlWithToken(
                                cp.player.core.control.resolveAdvertisedHost(config.bindAddress)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "按曲目拉流时接收端会带上 ?mediaId=…，CPPlayer 据此解析并转发字节。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (expanded) body(Modifier.fillMaxWidth()) else LegacyPageScaffold(
            title = "本地服务器",
            navigationIcon = {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        ) { pageModifier -> body(pageModifier) }
    }
}

/** 状态摘要：未启用 / 运行中（附地址）/ 启动失败。 */
private fun statusSummary(config: LocalServerConfig, status: LocalServerStatus): String {
    val error = status.error
    return when {
        !config.enabled -> "未启用"
        error != null -> error
        status.running -> "运行中 · 流 ${status.streamUrl}"
        else -> "启动中…"
    }
}

/** 推送结果文案。 */
private fun PushResult?.describe(): String = when (this) {
    null -> "尚无推送记录"
    is PushResult.Ok -> "上次推送成功"
    is PushResult.Failed -> "上次推送失败：$message"
}
