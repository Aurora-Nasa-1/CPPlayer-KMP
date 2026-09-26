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
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
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
import cp.player.app.ui.component.ScrollColumn
import cp.player.core.control.LocalServerConfig
import cp.player.core.control.LocalServerStatus
import cp.player.core.control.OutputMode
import cp.player.core.control.PushResult

/**
 * 「本地服务器输出 + 外部推送 + 跨软件集成」设置页。
 *
 * CPPlayer 在这里同时扮演两个**方向相反**的角色：
 *
 * 1. **推送方（outbound）**：主动把流地址与传输指令推给接收端（默认 `http://127.0.0.1:8420`）。
 *    接收端定义接口，CPPlayer 适配它。
 * 2. **服务方（inbound，跨软件集成）**：按**自己的**契约（`/api/v1/...`）对外提供音源数据，
 *    供第三方软件拉取。这一面**默认关闭**（[LocalServerConfig.exposeDataApi]），
 *    因为它扩大了攻击面 —— 所以这个页面的开关是它唯一的入口，没有 UI 就等于不可达。
 *
 * 两者共用绑定地址 / 端口 / 令牌，但开关互相独立：关掉数据面不影响接收端拉流。
 * 契约细节见 `docs/INTEGRATION_API.md`。
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
            ScrollColumn(
                modifier = pageModifier
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

                SettingsCard("跨软件集成（数据面）") {
                    LegacyListItem(
                        index = 0,
                        total = 1,
                        onClick = { AppModel.setExposeDataApi(!config.exposeDataApi) },
                        leadingContent = { Icon(Icons.Filled.Api, contentDescription = null) },
                        headlineContent = { Text("开放数据面") },
                        supportingContent = {
                            Text("允许第三方软件调用 /api/v1/… 读取音源数据（搜索 / 曲目 / 播放状态）")
                        },
                        trailingContent = {
                            Switch(
                                checked = config.exposeDataApi,
                                onCheckedChange = { AppModel.setExposeDataApi(it) },
                            )
                        },
                    )
                    Text(
                        text = "关闭时数据面整体返回 403，不影响接收端拉流。" +
                            "开关即时生效，不需要重启服务。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LegacyListItem(
                        index = 0,
                        total = 1,
                        onClick = { AppModel.setExposeStream(!config.exposeStream) },
                        leadingContent = { Icon(Icons.Filled.SettingsEthernet, contentDescription = null) },
                        headlineContent = { Text("开放媒体面") },
                        supportingContent = { Text("接收端按曲目拉流用的 GET /stream") },
                        trailingContent = {
                            Switch(
                                checked = config.exposeStream,
                                onCheckedChange = { AppModel.setExposeStream(it) },
                            )
                        },
                    )
                    LegacyListItem(
                        index = 0,
                        total = 1,
                        onClick = { AppModel.setAllowRemoteControl(!config.allowRemoteControl) },
                        leadingContent = { Icon(Icons.Filled.Security, contentDescription = null) },
                        headlineContent = { Text("允许远程播控") },
                        supportingContent = { Text("允许第三方软件控制播放 / 暂停 / 切歌") },
                        trailingContent = {
                            Switch(
                                checked = config.allowRemoteControl,
                                onCheckedChange = { AppModel.setAllowRemoteControl(it) },
                            )
                        },
                    )
                    Text(
                        text = "远程播控的端点尚未实现，打开它暂时不会产生任何效果。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer {
                        Text(
                            text = "数据面地址：" +
                                config.baseUrl(cp.player.core.control.resolveAdvertisedHost(config.bindAddress)),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "第三方软件读 ~/.cpplayer/integration.json 即可拿到地址与令牌，不必手抄。" +
                            "该文件含明文令牌，请勿外传。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!config.boundToLoopback && config.accessToken.isBlank()) {
                        Text(
                            text = "当前绑定局域网且没有令牌：媒体面与数据面都会拒绝所有请求，" +
                                "请先重新生成访问令牌。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
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
