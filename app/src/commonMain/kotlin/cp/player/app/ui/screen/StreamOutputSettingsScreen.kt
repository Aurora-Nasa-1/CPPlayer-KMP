package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.util.popOrNotify
import cp.player.app.ui.component.SettingsConfirmItem
import cp.player.app.ui.component.SettingsFieldGroup
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsNoteEmphasis
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSegmentedItem
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.component.SettingsTextInputItem
import cp.player.app.ui.util.UiEvents
import cp.player.core.control.LocalServerConfig
import cp.player.core.control.LocalServerStatus
import cp.player.core.control.OutputMode
import cp.player.core.control.resolveAdvertisedHost

/**
 * 「本地流输出」—— CPPlayer 作为**服务方**的一面：按自己的契约把音频流对外提供。
 *
 * 这是从原来那个「本地服务器」巨石页拆出来的一半。原页面把两个方向相反的职责混在一起：
 * 1. **服务方（inbound）** —— 别人来连我（本页）；
 * 2. **推送方（outbound）** —— 我去连别人（见 [IntegrationSettingsScreen]）。
 *
 * 拆开后每页只回答一个问题，用户不必在 5 个分组 + 8 条说明里找自己要的那一项。
 */
class StreamOutputSettingsScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val config by AppModel.localServerConfigFlow.collectAsState()
        val status by AppModel.localServerStatus.collectAsState()

        val body: @Composable (Modifier) -> Unit = { pageModifier ->
            SettingsPage(pageModifier) {
                SettingsSection("本地流输出") {
                    SettingsSwitchItem(
                        title = "启用本地流输出",
                        subtitle = "把当前曲目以 HTTP 流对外提供，供接收端按曲目拉取",
                        checked = config.enabled,
                        onCheckedChange = AppModel::setLocalServerEnabled,
                        icon = Icons.Filled.Dns,
                        index = 0,
                        total = 3,
                    )
                    SettingsSegmentedItem(
                        title = "音频输出",
                        subtitle = if (config.silentLocalOutput) {
                            "本机不出声，音频只从接收端播放"
                        } else {
                            "本机正常出声"
                        },
                        options = listOf("本机播放", "仅对外提供"),
                        selectedIndex = if (config.outputMode == OutputMode.SERVER_ONLY) 1 else 0,
                        onSelect = { index ->
                            AppModel.setOutputMode(
                                if (index == 1) OutputMode.SERVER_ONLY else OutputMode.SPEAKER,
                            )
                        },
                        index = 1,
                        total = 3,
                        enabled = config.enabled,
                    )
                    SettingsSegmentedItem(
                        title = "绑定范围",
                        subtitle = "决定哪些设备能访问这个服务",
                        options = listOf("仅本机", "局域网"),
                        selectedIndex = if (config.bindAddress == LocalServerConfig.BIND_ALL) 1 else 0,
                        onSelect = { index ->
                            AppModel.setLocalServerBind(
                                if (index == 1) LocalServerConfig.BIND_ALL else LocalServerConfig.BIND_LOOPBACK,
                            )
                        },
                        index = 2,
                        total = 3,
                        enabled = config.enabled,
                    )
                }

                statusSummary(config, status)
                if (config.exposedToLan) {
                    SettingsNote(
                        text = "已监听 0.0.0.0，同网段的任何设备都能访问。请务必保留访问令牌。",
                        emphasis = SettingsNoteEmphasis.WARNING,
                    )
                }
                if (!config.boundToLoopback && config.accessToken.isBlank()) {
                    SettingsNote(
                        text = "当前绑定局域网却没有访问令牌：媒体面与数据面都会拒绝所有请求，" +
                            "请先在下面重新生成访问令牌。",
                        emphasis = SettingsNoteEmphasis.ERROR,
                    )
                }

                SettingsSection("端口") {
                    SettingsTextInputItem(
                        title = "流输出端口",
                        subtitle = "接收端按曲目拉流时访问的端口；改动即时生效",
                        value = config.streamPort.toString(),
                        placeholder = LocalServerConfig.DEFAULT_STREAM_PORT.toString(),
                        keyboardType = KeyboardType.Number,
                        enabled = config.enabled,
                        validate = ::validateStreamPort,
                        onCommit = { text ->
                            text.trim().toIntOrNull()?.let(AppModel::setLocalServerStreamPort)
                        },
                        index = 0,
                        total = 1,
                    )
                }
                SettingsNote("端口改动会重启监听，正在进行的拉流会短暂中断。")

                SettingsSection("访问令牌") {
                    SettingsConfirmItem(
                        title = "重新生成访问令牌",
                        subtitle = "所有已连接的设备将立即失效",
                        confirmTitle = "重新生成访问令牌？",
                        confirmMessage = "旧令牌会立即作废。所有已连接的第三方软件与接收端都需要改用新令牌，" +
                            "否则会收到 401。",
                        confirmLabel = "重新生成",
                        icon = Icons.Filled.Lock,
                        index = 0,
                        total = 1,
                        onConfirm = {
                            AppModel.regenerateLocalServerToken()
                            UiEvents.notify("访问令牌已重新生成，已连接的设备需重新配置")
                        },
                    )
                }
                TokenPanel(config.accessToken)
                SettingsNote("令牌明文保存在 ~/.cpplayer/integration.json，请勿外传。")

                SettingsSection("接收端拉流的地址") {
                    SettingsFieldGroup {
                        SelectionContainer {
                            Text(
                                text = config.streamUrlWithToken(
                                    resolveAdvertisedHost(config.bindAddress),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
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
        }

        CpRouteScaffold(
            title = "本地流输出",
            onBack = { navigator.popOrNotify() },
        ) { pageModifier -> body(pageModifier) }
    }
}

/**
 * 端口校验。
 *
 * 与旧版的关键差别：旧版在 `onValueChange` 里就 `toIntOrNull()?.let(setLocalServerStreamPort)`，
 * 输入 `8080` 会依次提交 `8 / 80 / 808 / 8080` —— 每次都是全量文件回写 + 服务器重绑。
 * 现在校验只作用于草稿，「应用」按钮在通过后才可用。
 */
private fun validateStreamPort(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return "端口不能为空"
    val value = trimmed.toIntOrNull() ?: return "端口必须是数字"
    if (value !in LocalServerConfig.PORT_RANGE) {
        return "端口需在 ${LocalServerConfig.PORT_RANGE.first}–${LocalServerConfig.PORT_RANGE.last} 之间"
    }
    return null
}

/**
 * 令牌展示。
 *
 * 旧版把令牌塞在一行 `SettingsNote` 里，**没有** `SelectionContainer` —— 同一页里
 * 拉流地址是可选的，令牌却选不中，用户没法复制，只能手抄 32 位十六进制。
 */
@Composable
private fun TokenPanel(token: String) {
    SettingsFieldGroup {
        Text(
            text = "当前令牌",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (token.isBlank()) {
            Text(
                text = "启用后自动生成",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SelectionContainer {
                Text(
                    text = token,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** 服务状态摘要：未启用 / 启动失败 / 运行中（附地址）/ 启动中。 */
@Composable
private fun statusSummary(config: LocalServerConfig, status: LocalServerStatus) {
    val error = status.error
    when {
        !config.enabled -> SettingsNote("服务未启用。")
        error != null -> SettingsNote(
            text = "启动失败：$error",
            emphasis = SettingsNoteEmphasis.ERROR,
        )
        status.running -> SettingsNote("运行中 · ${status.streamUrl}")
        else -> SettingsNote("启动中…")
    }
}
