package cp.player.app.ui.screen

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Api
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
import cp.player.app.ui.util.popOrNotify
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsFieldGroup
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsNoteEmphasis
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.component.SettingsTextInputItem
import cp.player.core.control.LocalServerConfig
import cp.player.core.control.PushResult
import cp.player.core.control.resolveAdvertisedHost

/**
 * 「外部推送与集成」—— CPPlayer 作为**推送方**（以及被第三方读取的数据源）的一面。
 *
 * 从原「本地服务器」巨石页拆出来的另一半。与 [StreamOutputSettingsScreen] 的分工：
 * 那边是「别人怎么连我」，这边是「我怎么连别人 / 我允许别人读什么」。
 *
 * ### 与重构前的差异
 *
 * 1. **删掉了「允许远程播控」。** 页面自己写着「端点尚未实现，打开它暂时不会产生
 *    任何效果」—— 一个明确无效的开关只会让用户以为出了 bug。端点落地后再放回来。
 * 2. **术语去黑话。** 「开放数据面 / 开放媒体面」→「允许第三方读取音源数据 /
 *    允许第三方拉取音频流」。用户不知道什么是「面」。
 * 3. **接收端地址改为显式提交**（旧版每敲一个字符就写盘一次），
 *    且「测试连接」的结果**就地显示**，不再甩到页尾的一行说明里。
 */
class IntegrationSettingsScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val s = cpStrings()
        val config by AppModel.localServerConfigFlow.collectAsState()
        val lastPush by AppModel.lastPushResult.collectAsState()
        var probing by remember { mutableStateOf(false) }
        var probeResult by remember { mutableStateOf<PushResult?>(null) }

        val body: @Composable (Modifier) -> Unit = { pageModifier ->
            SettingsPage(pageModifier) {
                SettingsSection(s.integration.sectionPush) {
                    SettingsTextInputItem(
                        title = s.integration.receiverAddress,
                        subtitle = s.integration.receiverAddressNote,
                        value = config.receiverBaseUrl,
                        placeholder = LocalServerConfig.DEFAULT_RECEIVER_BASE_URL,
                        keyboardType = KeyboardType.Uri,
                        validate = { text -> validateReceiverUrl(text, s) },
                        onCommit = { text -> AppModel.setReceiverBaseUrl(text.trim()) },
                        index = 0,
                        total = 5,
                    )
                    SettingsSwitchItem(
                        title = s.integration.autoPush,
                        subtitle = s.integration.autoPushNote,
                        checked = config.pushEnabled,
                        onCheckedChange = AppModel::setPushEnabled,
                        index = 1,
                        total = 5,
                    )
                    SettingsClickItem(
                        title = s.integration.testConnection,
                        subtitle = when {
                            probing -> s.integration.testProbing()
                            probeResult != null -> describePush(s, probeResult)
                            else -> s.integration.testIdle("/api/health")
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
                        title = s.integration.pushCurrentTrack,
                        subtitle = s.integration.pushCurrentTrackNote,
                        icon = Icons.AutoMirrored.Filled.Send,
                        index = 3,
                        total = 5,
                        onClick = { AppModel.pushCurrentTrack { } },
                    )
                    SettingsClickItem(
                        title = s.integration.pushCurrentQueue,
                        subtitle = s.integration.pushCurrentQueueNote,
                        icon = Icons.AutoMirrored.Filled.Send,
                        index = 4,
                        total = 5,
                        onClick = { AppModel.pushQueueToReceiver { } },
                    )
                }
                SettingsNote(describePush(s, lastPush))

                SettingsSection(s.integration.sectionThirdParty) {
                    SettingsSwitchItem(
                        title = s.integration.allowStream,
                        subtitle = s.integration.allowStreamNote,
                        checked = config.exposeStream,
                        onCheckedChange = AppModel::setExposeStream,
                        icon = Icons.Filled.SettingsEthernet,
                        index = 0,
                        total = 2,
                    )
                    SettingsSwitchItem(
                        title = s.integration.allowApi,
                        subtitle = s.integration.allowApiNote,
                        checked = config.exposeDataApi,
                        onCheckedChange = AppModel::setExposeDataApi,
                        icon = Icons.Filled.Api,
                        index = 1,
                        total = 2,
                    )
                }
                SettingsNote(s.integration.apiSwitchNote)

                SettingsSection(s.integration.sectionEndpoint) {
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
                            text = s.integration.configFileNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!config.enabled) {
                    SettingsNote(
                        text = s.integration.streamDisabledNote,
                        emphasis = SettingsNoteEmphasis.WARNING,
                    )
                }
            }
        }

        CpRouteScaffold(
            title = s.integration.screenTitle,
            onBack = { navigator.popOrNotify() },
        ) { pageModifier -> body(pageModifier) }
    }
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
    if (trimmed.isEmpty()) return s.integration.addressEmpty()
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        return s.integration.addressScheme()
    }
    val rest = trimmed.substringAfter("://")
    if (rest.isBlank() || rest.startsWith("/") || rest.startsWith(":")) {
        return s.integration.addressNoHost(LocalServerConfig.DEFAULT_RECEIVER_BASE_URL)
    }
    return null
}

/**
 * 推送结果文案。
 *
 * ⚠️ [PushResult.Failed.message] 是**接收端返回的原始串**（跨设备、可能还是对方语言），
 * 原样透出 —— 它是诊断信息，不参与本地化。
 */
private fun describePush(s: CpStrings, result: PushResult?): String = when (result) {
    null -> s.integration.pushNever
    is PushResult.Ok -> s.integration.pushOk
    is PushResult.Failed -> s.integration.pushFailed(result.message)
}
