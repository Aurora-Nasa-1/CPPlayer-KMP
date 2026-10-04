package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.platform.shareText
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.SettingsButtonItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsNoteEmphasis
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsTextInputItem
import cp.player.app.ui.component.rememberConfirmState
import cp.player.app.ui.util.UiEvents
import cp.player.app.ui.util.popOrNotify
import cp.player.core.listentogether.ListenTogetherEngine
import cp.player.core.listentogether.ListenTogetherState
import cp.player.core.util.currentTimeMillis
import kotlinx.coroutines.launch

/**
 * 一起听房间页。
 *
 * ### 三态
 * 1. **音源不支持** —— 只说明原因，不给任何操作。
 * 2. **不在房间** —— 建房 / 加入。
 * 3. **在房间** —— 房间信息 + 邀请对方 + 退出。
 *
 * ### 为什么「退出房间」必须二次确认，而「加入」不弹
 * `end` **只能关不能复活**，一个账号同时只在在一个房间 —— 退出是**不可逆**的破坏性操作，
 * 走 [rememberConfirmState] / [CpConfirmHost]。加入是可重试的，弹确认框只会打断用户。
 *
 * ### 版式约定（踩过的）
 * [SettingsNote] 是**组外**说明文字，必须放在 [SettingsSection] **之外**——
 * 组内是分段卡片，中间插一行非分段文本会把圆角拼接打断。所以本页的信息行用 Note，
 * 只有成组的可点项才进 Section。
 *
 * ### 关于「实时」
 * 网易云不向 HTTP 客户端推送；房间状态的观测延迟**下限就是轮询间隔**
 * （[ListenTogetherEngine.POLL_INTERVAL_MS]，协议决定，实现优化不掉）。
 * 所以页面如实写「每 N 秒同步」，**不写「实时」**。
 */
class ListenTogetherScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val state by AppModel.listenTogetherState.collectAsState()
        val confirm = rememberConfirmState()

        // 进页面立刻刷一次，否则要等一个轮询周期，看起来像「页面是空的」。
        LaunchedEffect(Unit) {
            AppModel.listenTogether.start()
            AppModel.listenTogether.refresh()
        }
        LaunchedEffect(state.notice) {
            state.notice?.let {
                UiEvents.notify(it)
                AppModel.listenTogether.consumeNotice()
            }
        }
        LaunchedEffect(state.error) {
            state.error?.let {
                UiEvents.notify(it)
                AppModel.listenTogether.consumeError()
            }
        }

        CpRouteScaffold(title = PAGE_TITLE, onBack = { navigator.popOrNotify() }) { pageModifier ->
            SettingsPage(pageModifier) {
                val busy = state.busy
                // 显式标注 `() -> Unit`：不加的话 lambda 的返回类型会被推断成 `Job`
                // （`scope.launch {}` 的返回），再传给 `() -> Unit` 参数就类型不符。
                val refresh: () -> Unit = { scope.launch { AppModel.listenTogether.refresh() } }

                if (!state.supported) {
                    unsupportedContent()
                    return@SettingsPage
                }

                if (!state.inRoom) {
                    joinContent(
                        busy = busy,
                        onRefresh = refresh,
                        onCreate = { scope.launch { AppModel.listenTogether.createRoom() } },
                        onJoin = { text -> scope.launch { AppModel.listenTogether.joinFromText(text) } },
                    )
                    return@SettingsPage
                }

                roomContent(
                    state = state,
                    busy = busy,
                    onRefresh = refresh,
                    onShare = { link -> shareText(link) },
                    onLeave = {
                        confirm.request(
                            title = "退出一起听房间？",
                            message = "退出后房间立即结束且**无法恢复**。如果你是房主，对方也会同时断开。",
                            confirmLabel = "退出房间",
                            destructive = true,
                            onConfirm = { scope.launch { AppModel.listenTogether.endRoom() } },
                        )
                    },
                )
            }
        }

        CpConfirmHost(confirm)
    }

    private companion object {
        const val PAGE_TITLE = "一起听"
    }
}

// ======================== 状态 1：音源不支持 ========================

@Composable
private fun ColumnScope.unsupportedContent() {
    SettingsNote(
        text = "当前音源不支持一起听。",
        emphasis = SettingsNoteEmphasis.WARNING,
    )
    SettingsNote(
        text = "一起听依赖音源自身的房间协议。切换到支持该能力的音源后，本页才会出现操作入口。",
    )
}

// ======================== 状态 2：不在房间 ========================

@Composable
private fun ColumnScope.joinContent(
    busy: Boolean,
    onRefresh: () -> Unit,
    onCreate: () -> Unit,
    onJoin: (String) -> Unit,
) {
    var inviteText by remember { mutableStateOf("") }

    SettingsSection("创建房间") {
        SettingsButtonItem(
            text = if (busy) "处理中…" else "创建一起听房间",
            subtitle = "创建后把邀请链接发给对方",
            icon = Icons.Filled.Add,
            index = 0,
            total = 1,
            enabled = !busy,
            onClick = onCreate,
        )
    }
    SettingsNote(
        text = "一个账号同时只能在一个房间里。若你已经在一个房间中，请先退出再建房 —— " +
            "建房会顶掉当前房间，而房间一旦结束就无法恢复。",
        emphasis = SettingsNoteEmphasis.WARNING,
    )

    SettingsSection("加入房间") {
        SettingsTextInputItem(
            title = "邀请链接或房号",
            value = inviteText,
            onCommit = { inviteText = it },
            validate = { null },
            index = 0,
            total = 2,
            placeholder = "粘贴对方发来的邀请链接",
            enabled = !busy,
        )
        SettingsButtonItem(
            text = "加入",
            index = 1,
            total = 2,
            enabled = !busy && inviteText.isNotBlank(),
            onClick = { onJoin(inviteText) },
        )
    }
    SettingsNote(
        text = "邀请链接里同时带着房间号与邀请人 id，缺一不可。" +
            "若链接无效或房间已结束，服务端只返回一个笼统的错误 —— " +
            "我们**无法区分**「房间不存在」与「邀请不是给你的」，所以这里不会给出更具体的原因。",
    )

    SettingsSection("其他") {
        SettingsButtonItem(
            text = "刷新房间状态",
            icon = Icons.Filled.Refresh,
            index = 0,
            total = 1,
            enabled = !busy,
            onClick = onRefresh,
        )
    }
}

// ======================== 状态 3：在房间 ========================

@Composable
private fun ColumnScope.roomContent(
    state: ListenTogetherState,
    busy: Boolean,
    onRefresh: () -> Unit,
    onShare: (String) -> Unit,
    onLeave: () -> Unit,
) {
    val room = state.room
    val link = state.shareUrl()

    SettingsNote(text = "房间号：${room?.roomId.orEmpty()}")
    SettingsNote(
        text = buildString {
            append("成员 ${room?.members?.size ?: 0} 人")
            append(if (state.isOwner()) " · 你是房主" else "")
            append(remainingText(room?.createdAtMs, room?.effectiveDurationMs))
        },
    )
    // ⚠️ 实测 connectionStatus 恒为 NOT_CONNECTED（IM 长连接态），HTTP 客户端永远满足不了。
    // 它不是错误，所以不渲染成警告色，只说清「延迟从哪来」，免得用户以为出了问题。
    SettingsNote(
        text = "房间状态每 ${ListenTogetherEngine.POLL_INTERVAL_MS / 1000} 秒同步一次 —— " +
            "网易云不向第三方推送，这个延迟由协议决定，不是网络慢。",
    )

    SettingsSection("房间") {
        SettingsButtonItem(
            text = "刷新",
            icon = Icons.Filled.Refresh,
            index = 0,
            total = 1,
            enabled = !busy,
            onClick = onRefresh,
        )
    }

    SettingsSection("邀请对方") {
        SettingsButtonItem(
            text = if (isAndroidPlatform()) "分享邀请链接" else "复制邀请链接",
            subtitle = if (isAndroidPlatform()) {
                "用任意聊天工具发给对方"
            } else {
                "已复制到剪贴板，粘贴给朋友即可"
            },
            icon = Icons.Filled.Share,
            index = 0,
            total = 1,
            enabled = link != null,
            onClick = { link?.let(onShare) },
        )
    }

    if (link != null) {
        SettingsNote(text = "或让对方直接扫描这个二维码加入：")
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = CpSpacing.formHorizontal),
            contentAlignment = Alignment.Center,
        ) {
            QrCodeImage(link)
        }
    } else {
        SettingsNote(
            text = "暂时拿不到邀请链接：房间信息还没同步到，或账号资料未就绪。刷新一下试试。",
            emphasis = SettingsNoteEmphasis.WARNING,
        )
    }

    SettingsNote(
        text = "网易云没有「发出邀请」的接口 —— 官方客户端那条邀请走的是站内 IM，第三方调不到。" +
            "所以邀请必然是「你主动把链接给对方」这一步，做不到点一下推进对方收件箱。",
    )

    SettingsSection("退出") {
        SettingsButtonItem(
            text = "退出房间",
            subtitle = "退出后房间立即结束，无法恢复",
            icon = Icons.Filled.ExitToApp,
            index = 0,
            total = 1,
            enabled = !busy,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            onClick = onLeave,
        )
    }
}

/**
 * 剩余有效期。
 *
 * 房间是**服务端**限时的（实测 `effectiveDurationMs` = 30 分钟），到点即失效。
 * **必须提前告诉用户**，否则表现为「听着听着突然掉线」且不知道原因。
 */
private fun remainingText(createdAtMs: Long?, durationMs: Long?): String {
    if (createdAtMs == null || durationMs == null || durationMs <= 0L) return ""
    val left = createdAtMs + durationMs - currentTimeMillis()
    if (left <= 0L) return " · 已到有效期，建议重新创建"
    val minutes = left / 60_000L
    return if (minutes >= 1) " · 剩余约 $minutes 分钟" else " · 即将到期"
}
