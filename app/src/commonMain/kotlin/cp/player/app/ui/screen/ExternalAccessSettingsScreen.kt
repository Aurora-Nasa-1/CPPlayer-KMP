package cp.player.app.ui.screen

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.MaterialTheme
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
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.i18n.CpStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.SettingsClickItem
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
import cp.player.app.ui.util.popOrNotify
import cp.player.core.control.LocalServerConfig
import cp.player.core.control.LocalServerStatus
import cp.player.core.control.OutputMode
import cp.player.core.control.PushResult
import cp.player.core.control.resolveAdvertisedHost

/**
 * 「外部访问」—— CPPlayer 与本机之外的软件打交道的**唯一一页**。
 *
 * ### 这一页是怎么来的
 *
 * 原先这里是两页：「本地流输出」（别人怎么连我）与「外部推送与集成」（我怎么连别人 +
 * 允许别人读什么）。它们共用**同一份** [LocalServerConfig]：端口、绑定范围、令牌既决定
 * 「第三方怎么连进来」，也决定「推给接收端的地址里写着什么」。拆成两页的直接后果是
 * **跨页依赖** —— 「允许第三方读取音源数据」在推送页，而它赖以为生的服务总开关在另一页，
 * 只能在页尾写一句「请先到『本地流输出』页开启」。
 *
 * 2026-10-10 合成一页，由顶部**一个总开关**（[LocalServerConfig.enabled]）统管整件事，
 * 其余各段都是它的下属设置。页内不再需要指向别的页面的说明。
 *
 * ### 六个段落
 *
 * 1. **对外服务** —— 总开关、音频输出目标、绑定范围（+ 运行状态与安全警告）；
 * 2. **端口**；
 * 3. **访问令牌** —— 媒体面与数据面共用同一个令牌；
 * 4. **允许第三方访问** —— 拉流 / 读数据两个能力开关；
 * 5. **推送到接收端** —— 出站方向：地址、自动推送、测试与手动推送；
 * 6. **接口地址** —— 第三方要拼的基地址。
 */
class ExternalAccessSettingsScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val s = cpStrings()
        val config by AppModel.localServerConfigFlow.collectAsState()
        val status by AppModel.localServerStatus.collectAsState()
        val lastPush by AppModel.lastPushResult.collectAsState()
        var probing by remember { mutableStateOf(false) }
        var probeResult by remember { mutableStateOf<PushResult?>(null) }

        val body: @Composable (Modifier) -> Unit = { pageModifier ->
            SettingsPage(pageModifier) {
                // ---- 1. 对外服务 ----
                SettingsSection(s.externalAccess.sectionService) {
                    SettingsSwitchItem(
                        title = s.externalAccess.enabled,
                        subtitle = s.externalAccess.enabledNote,
                        checked = config.enabled,
                        onCheckedChange = AppModel::setLocalServerEnabled,
                        icon = Icons.Filled.Dns,
                        index = 0,
                        total = 3,
                    )
                    SettingsSegmentedItem(
                        title = s.externalAccess.audioOutput,
                        subtitle = s.externalAccess.outputNote(config.silentLocalOutput),
                        options = listOf(
                            s.externalAccess.outputLocal,
                            s.externalAccess.outputRemoteOnly,
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
                        title = s.externalAccess.bindScope,
                        subtitle = s.externalAccess.bindScopeNote,
                        options = listOf(s.externalAccess.scopeLocalhost, s.externalAccess.scopeLan),
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
                // 只在这一条真正的坏状态下告警：绑定局域网且没有令牌 = 两个面都会拒绝所有请求
                // （见 isTokenSatisfied 的第三条分支）。有令牌时暴露局域网是用户的显式选择，不必再喊。
                if (!config.boundToLoopback && config.accessToken.isBlank()) {
                    SettingsNote(
                        text = s.externalAccess.warningLanNoToken,
                        emphasis = SettingsNoteEmphasis.ERROR,
                    )
                }

                // ---- 2. 端口 ----
                SettingsSection(s.externalAccess.sectionPort) {
                    SettingsTextInputItem(
                        title = s.externalAccess.port,
                        subtitle = s.externalAccess.portNote,
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
                SettingsNote(s.externalAccess.portRestartNote)

                // ---- 3. 访问令牌 ----
                SettingsSection(s.externalAccess.sectionToken) {
                    SettingsConfirmItem(
                        title = s.externalAccess.regenerateToken,
                        subtitle = s.externalAccess.regenerateTokenNote,
                        confirmTitle = s.externalAccess.regenerateConfirmTitle,
                        confirmMessage = s.externalAccess.regenerateConfirmMessage,
                        confirmLabel = s.externalAccess.regenerateLabel,
                        icon = Icons.Filled.Lock,
                        index = 0,
                        total = 1,
                        onConfirm = {
                            AppModel.regenerateLocalServerToken()
                            UiEvents.notify(s.externalAccess.tokenRegenerated)
                        },
                    )
                }
                TokenPanel(s, config.accessToken)
                SettingsNote(s.externalAccess.tokenStorageNote)

                // ---- 4. 允许第三方访问 ----
                SettingsSection(s.externalAccess.sectionThirdParty) {
                    SettingsSwitchItem(
                        title = s.externalAccess.allowStream,
                        subtitle = s.externalAccess.allowStreamNote,
                        checked = config.exposeStream,
                        onCheckedChange = AppModel::setExposeStream,
                        icon = Icons.Filled.SettingsEthernet,
                        index = 0,
                        total = 2,
                    )
                    SettingsSwitchItem(
                        title = s.externalAccess.allowApi,
                        subtitle = s.externalAccess.allowApiNote,
                        checked = config.exposeDataApi,
                        onCheckedChange = AppModel::setExposeDataApi,
                        icon = Icons.Filled.Api,
                        index = 1,
                        total = 2,
                    )
                }
                SettingsNote(s.externalAccess.apiSwitchNote)

                // ---- 5. 推送到接收端 ----
                SettingsSection(s.externalAccess.sectionPush) {
                    SettingsTextInputItem(
                        title = s.externalAccess.receiverAddress,
                        subtitle = s.externalAccess.receiverAddressNote,
                        value = config.receiverBaseUrl,
                        placeholder = LocalServerConfig.DEFAULT_RECEIVER_BASE_URL,
                        keyboardType = KeyboardType.Uri,
                        validate = { text -> validateReceiverUrl(text, s) },
                        onCommit = { text -> AppModel.setReceiverBaseUrl(text.trim()) },
                        index = 0,
                        total = 5,
                    )
                    SettingsSwitchItem(
                        title = s.externalAccess.autoPush,
                        subtitle = s.externalAccess.autoPushNote,
                        checked = config.pushEnabled,
                        onCheckedChange = AppModel::setPushEnabled,
                        index = 1,
                        total = 5,
                    )
                    SettingsClickItem(
                        title = s.externalAccess.testConnection,
                        subtitle = when {
                            probing -> s.externalAccess.testProbing()
                            probeResult != null -> describePush(s, probeResult)
                            else -> s.externalAccess.testIdle("/api/health")
                        },
                        icon = Icons.Filled.Wifi,
                        index = 2,
                        total = 5,
                        enabled = !probing,
                        onClick = {
                            probing = true
                            AppModel.probeReceiver { result ->
                                probeResult = result
                                probing = false
                            }
                        },
                    )
                    SettingsClickItem(
                        title = s.externalAccess.pushCurrentTrack,
                        subtitle = s.externalAccess.pushCurrentTrackNote,
                        icon = Icons.AutoMirrored.Filled.Send,
                        index = 3,
                        total = 5,
                        onClick = { AppModel.pushCurrentTrack { } },
                    )
                    SettingsClickItem(
                        title = s.externalAccess.pushCurrentQueue,
                        subtitle = s.externalAccess.pushCurrentQueueNote,
                        icon = Icons.AutoMirrored.Filled.Send,
                        index = 4,
                        total = 5,
                        onClick = { AppModel.pushQueueToReceiver { } },
                    )
                }
                SettingsNote(describePush(s, lastPush))

                // ---- 6. 接口地址 ----
                SettingsSection(s.externalAccess.sectionEndpoint) {
                    SettingsFieldGroup {
                        SelectionContainer {
                            Text(
                                text = config.baseUrl(resolveAdvertisedHost(config.bindAddress)),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Text(
                            text = s.externalAccess.endpointNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SettingsNote(s.externalAccess.configFileNote)
                if (!config.enabled) {
                    SettingsNote(
                        text = s.externalAccess.serviceDisabledNote,
                        emphasis = SettingsNoteEmphasis.WARNING,
                    )
                }
            }
        }

        CpRouteScaffold(
            title = s.externalAccess.screenTitle,
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
    if (trimmed.isEmpty()) return s.externalAccess.portEmpty()
    val value = trimmed.toIntOrNull() ?: return s.externalAccess.portNotNumber()
    if (value !in LocalServerConfig.PORT_RANGE) {
        return s.externalAccess.portOutOfRange(
            LocalServerConfig.PORT_RANGE.first,
            LocalServerConfig.PORT_RANGE.last,
        )
    }
    return null
}

/**
 * 接收端地址校验。
 *
 * 只做「是不是一个能连的 http(s) 地址」这一档检查 —— 比不校验强得多（旧版完全不校验，
 * 打错的地址会一路静默到推送时才失败），又不至于把用户挡在门外。
 *
 * 收 [CpStrings]：顶层函数（非 composable）拿不到组合里的语言，三条错误说明都是文案。
 */
private fun validateReceiverUrl(text: String, s: CpStrings): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return s.externalAccess.addressEmpty()
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        return s.externalAccess.addressScheme()
    }
    val rest = trimmed.substringAfter("://")
    if (rest.isBlank() || rest.startsWith("/") || rest.startsWith(":")) {
        return s.externalAccess.addressNoHost(LocalServerConfig.DEFAULT_RECEIVER_BASE_URL)
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
            text = s.externalAccess.currentToken,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (token.isBlank()) {
            Text(
                text = s.externalAccess.tokenAutoGenerated,
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
        !config.enabled -> SettingsNote(s.externalAccess.statusDisabled)
        error != null -> SettingsNote(
            text = s.externalAccess.statusStartFailed(error),
            emphasis = SettingsNoteEmphasis.ERROR,
        )
        status.running -> SettingsNote(s.externalAccess.statusRunning(status.streamUrl))
        else -> SettingsNote(s.externalAccess.statusStarting)
    }
}

/**
 * 推送结果文案。
 *
 * ⚠️ [PushResult.Failed.message] 是**接收端返回的原始串**（跨设备、可能还是对方语言），
 * 原样透出 —— 它是诊断信息，不参与本地化。
 */
private fun describePush(s: CpStrings, result: PushResult?): String = when (result) {
    null -> s.externalAccess.pushNever
    is PushResult.Ok -> s.externalAccess.pushOk
    is PushResult.Failed -> s.externalAccess.pushFailed(result.message)
}
