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
import cp.player.app.ui.component.CpRouteScaffold
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
        val config by AppModel.localServerConfigFlow.collectAsState()
        val lastPush by AppModel.lastPushResult.collectAsState()
        var probing by remember { mutableStateOf(false) }
        var probeResult by remember { mutableStateOf<PushResult?>(null) }

        val body: @Composable (Modifier) -> Unit = { pageModifier ->
            SettingsPage(pageModifier) {
                SettingsSection("推送到接收端") {
                    SettingsTextInputItem(
                        title = "接收端地址",
                        subtitle = "接收推送的软件地址；推送方向是 CPPlayer 主动连它",
                        value = config.receiverBaseUrl,
                        placeholder = LocalServerConfig.DEFAULT_RECEIVER_BASE_URL,
                        keyboardType = KeyboardType.Uri,
                        validate = ::validateReceiverUrl,
                        onCommit = { text -> AppModel.setReceiverBaseUrl(text.trim()) },
                        index = 0,
                        total = 5,
                    )
                    SettingsSwitchItem(
                        title = "曲目变化时自动推送",
                        subtitle = "开始播放新曲目时自动替换接收端队列并播放",
                        checked = config.pushEnabled,
                        onCheckedChange = AppModel::setPushEnabled,
                        index = 1,
                        total = 5,
                    )
                    SettingsClickItem(
                        title = "测试连接",
                        subtitle = when {
                            probing -> "正在请求…"
                            probeResult != null -> describePush(probeResult)
                            else -> "请求接收端 /api/health"
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
                        title = "推送当前曲目",
                        subtitle = "替换接收端队列为这一首并立即播放",
                        icon = Icons.AutoMirrored.Filled.Send,
                        index = 3,
                        total = 5,
                        onClick = { AppModel.pushCurrentTrack { } },
                    )
                    SettingsClickItem(
                        title = "推送当前队列",
                        subtitle = "整队列交给接收端，由它负责顺序播放",
                        icon = Icons.AutoMirrored.Filled.Send,
                        index = 4,
                        total = 5,
                        onClick = { AppModel.pushQueueToReceiver { } },
                    )
                }
                SettingsNote(describePush(lastPush))

                SettingsSection("允许第三方访问") {
                    SettingsSwitchItem(
                        title = "允许第三方拉取音频流",
                        subtitle = "接收端按曲目拉流所需的 GET /stream",
                        checked = config.exposeStream,
                        onCheckedChange = AppModel::setExposeStream,
                        icon = Icons.Filled.SettingsEthernet,
                        index = 0,
                        total = 2,
                    )
                    SettingsSwitchItem(
                        title = "允许第三方读取音源数据",
                        subtitle = "搜索、曲目与播放状态（/api/v1/…）；默认关闭，因为会扩大暴露面",
                        checked = config.exposeDataApi,
                        onCheckedChange = AppModel::setExposeDataApi,
                        icon = Icons.Filled.Api,
                        index = 1,
                        total = 2,
                    )
                }
                SettingsNote("关闭「读取音源数据」后数据接口整体返回 403，但不影响接收端拉流。两个开关都即时生效。")

                SettingsSection("接口地址") {
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
                            text = "第三方软件读 ~/.cpplayer/integration.json 即可拿到地址与令牌；" +
                                "该文件含明文令牌，请勿外传。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!config.enabled) {
                    SettingsNote(
                        text = "本地流输出当前未启用，上面的地址暂时不可达。请先到「本地流输出」页开启。",
                        emphasis = SettingsNoteEmphasis.WARNING,
                    )
                }
            }
        }

        CpRouteScaffold(
            title = "外部推送与集成",
            onBack = { navigator.pop() },
        ) { pageModifier -> body(pageModifier) }
    }
}

/**
 * 接收端地址校验。
 *
 * 只做「是不是一个能连的 http(s) 地址」这一档检查 —— 比不校验强得多（旧版完全不校验，
 * 打错的地址会一路静默到推送时才失败），又不至于把用户挡在门外。
 */
private fun validateReceiverUrl(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return "接收端地址不能为空"
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        return "需要以 http:// 或 https:// 开头"
    }
    val rest = trimmed.substringAfter("://")
    if (rest.isBlank() || rest.startsWith("/") || rest.startsWith(":")) {
        return "缺少主机名，例如 ${LocalServerConfig.DEFAULT_RECEIVER_BASE_URL}"
    }
    return null
}

/** 推送结果文案。 */
private fun describePush(result: PushResult?): String = when (result) {
    null -> "尚无推送记录"
    is PushResult.Ok -> "上次推送成功"
    is PushResult.Failed -> "上次推送失败：${result.message}"
}
