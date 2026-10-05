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
import cp.player.app.i18n.CpStrings
import cp.player.app.i18n.cpStrings
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
        val s = cpStrings()
        val config by AppModel.localServerConfigFlow.collectAsState()
        val status by AppModel.localServerStatus.collectAsState()

        val body: @Composable (Modifier) -> Unit = { pageModifier ->
            SettingsPage(pageModifier) {
                SettingsSection(s.streamOutput.sectionMain) {
                    SettingsSwitchItem(
                        title = s.streamOutput.enabled,
                        subtitle = s.streamOutput.enabledNote,
                        checked = config.enabled,
                        onCheckedChange = AppModel::setLocalServerEnabled,
                        icon = Icons.Filled.Dns,
                        index = 0,
                        total = 3,
                    )
                    SettingsSegmentedItem(
                        title = s.streamOutput.audioOutput,
                        subtitle = s.streamOutput.outputNote(config.silentLocalOutput),
                        options = listOf(
                            s.streamOutput.outputLocal,
                            s.streamOutput.outputRemoteOnly,
                        ),
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
                        title = s.streamOutput.bindScope,
                        subtitle = s.streamOutput.bindScopeNote,
                        options = listOf(s.streamOutput.scopeLocalhost, s.streamOutput.scopeLan),
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

                statusSummary(s, config, status)
                if (config.exposedToLan) {
                    SettingsNote(
                        text = s.streamOutput.warningLanNoToken,
                        emphasis = SettingsNoteEmphasis.WARNING,
                    )
                }
                if (!config.boundToLoopback && config.accessToken.isBlank()) {
                    SettingsNote(
                        text = s.streamOutput.warningLanNoToken,
                        emphasis = SettingsNoteEmphasis.ERROR,
                    )
                }

                SettingsSection(s.streamOutput.sectionPort) {
                    SettingsTextInputItem(
                        title = s.streamOutput.port,
                        subtitle = s.streamOutput.portNote,
                        value = config.streamPort.toString(),
                        placeholder = LocalServerConfig.DEFAULT_STREAM_PORT.toString(),
                        keyboardType = KeyboardType.Number,
                        enabled = config.enabled,
                        validate = { text -> validateStreamPort(text, s) },
                        onCommit = { text ->
                            text.trim().toIntOrNull()?.let(AppModel::setLocalServerStreamPort)
                        },
                        index = 0,
                        total = 1,
                    )
                }
                SettingsNote(s.streamOutput.portRestartNote)

                SettingsSection(s.streamOutput.sectionToken) {
                    SettingsConfirmItem(
                        title = s.streamOutput.regenerateToken,
                        subtitle = s.streamOutput.regenerateTokenNote,
                        confirmTitle = s.streamOutput.regenerateConfirmTitle,
                        confirmMessage = s.streamOutput.regenerateConfirmMessage,
                        confirmLabel = s.streamOutput.regenerateLabel,
                        icon = Icons.Filled.Lock,
                        index = 0,
                        total = 1,
                        onConfirm = {
                            AppModel.regenerateLocalServerToken()
                            UiEvents.notify(s.streamOutput.tokenRegenerated)
                        },
                    )
                }
                TokenPanel(s, config.accessToken)
                SettingsNote(s.streamOutput.tokenStorageNote)

                SettingsSection(s.streamOutput.sectionEndpoint) {
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
                            text = s.streamOutput.endpointNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        CpRouteScaffold(
            title = s.streamOutput.screenTitle,
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
 *
 * 收 [CpStrings] 而不是无参：这是**顶层函数**（不是 composable），拿不到组合里的语言。
 * 三条错误说明都是文案，调用方（组合内）把 `s` 传进来。
 */
private fun validateStreamPort(text: String, s: CpStrings): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return s.streamOutput.portEmpty()
    val value = trimmed.toIntOrNull() ?: return s.streamOutput.portNotNumber()
    if (value !in LocalServerConfig.PORT_RANGE) {
        return s.streamOutput.portOutOfRange(
            LocalServerConfig.PORT_RANGE.first,
            LocalServerConfig.PORT_RANGE.last,
        )
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
private fun TokenPanel(s: CpStrings, token: String) {
    SettingsFieldGroup {
        Text(
            text = s.streamOutput.currentToken,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (token.isBlank()) {
            Text(
                text = s.streamOutput.tokenAutoGenerated,
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
private fun statusSummary(s: CpStrings, config: LocalServerConfig, status: LocalServerStatus) {
    val error = status.error
    when {
        !config.enabled -> SettingsNote(s.streamOutput.statusDisabled)
        error != null -> SettingsNote(
            text = s.streamOutput.statusStartFailed(error),
            emphasis = SettingsNoteEmphasis.ERROR,
        )
        status.running -> SettingsNote(s.streamOutput.statusRunning(status.streamUrl))
        else -> SettingsNote(s.streamOutput.statusStarting)
    }
}
