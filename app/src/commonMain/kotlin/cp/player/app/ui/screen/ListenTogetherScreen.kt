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
import cp.player.app.i18n.CpStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.SettingsButtonItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsNoteEmphasis
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsTextInputItem
import cp.player.app.ui.component.ltTtl
import cp.player.app.ui.component.rememberConfirmState
import cp.player.app.ui.component.roomText
import cp.player.app.ui.util.UiEvents
import cp.player.app.ui.util.popOrNotify
import cp.player.core.listentogether.ListenTogetherEngine
import cp.player.core.listentogether.ListenTogetherState
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
        val s = cpStrings()

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

        // 页面标题复用 `player.listenTogether`：更多面板里那个入口是同一条文案，
        // 拆开写必然出现两种译法。
        CpRouteScaffold(title = s.player.listenTogether, onBack = { navigator.popOrNotify() }) { pageModifier ->
            SettingsPage(pageModifier) {
                val busy = state.busy
                // 显式标注 `() -> Unit`：不加的话 lambda 的返回类型会被推断成 `Job`
                // （`scope.launch {}` 的返回），再传给 `() -> Unit` 参数就类型不符。
                val refresh: () -> Unit = { scope.launch { AppModel.listenTogether.refresh() } }

                if (!state.supported) {
                    unsupportedContent(strings = s)
                    return@SettingsPage
                }

                if (!state.inRoom) {
                    joinContent(
                        strings = s,
                        busy = busy,
                        onRefresh = refresh,
                        onCreate = { scope.launch { AppModel.listenTogether.createRoom() } },
                        onJoin = { text -> scope.launch { AppModel.listenTogether.joinFromText(text) } },
                    )
                    return@SettingsPage
                }

                roomContent(
                    strings = s,
                    state = state,
                    busy = busy,
                    onRefresh = refresh,
                    onShare = { link -> shareText(link) },
                    onLeave = {
                        confirm.request(
                            title = s.social.together.leaveConfirmTitle,
                            message = s.social.together.leaveConfirmMessage,
                            confirmLabel = s.social.together.leaveRoom,
                            destructive = true,
                            onConfirm = { scope.launch { AppModel.listenTogether.endRoom() } },
                        )
                    },
                )
            }
        }

        CpConfirmHost(confirm)
    }
}

// ======================== 状态 1：音源不支持 ========================

@Composable
private fun ColumnScope.unsupportedContent(strings: CpStrings) {
    SettingsNote(
        text = strings.social.together.unsupported,
        emphasis = SettingsNoteEmphasis.WARNING,
    )
    SettingsNote(
        text = strings.social.together.unsupportedNote,
    )
}

// ======================== 状态 2：不在房间 ========================

@Composable
private fun ColumnScope.joinContent(
    strings: CpStrings,
    busy: Boolean,
    onRefresh: () -> Unit,
    onCreate: () -> Unit,
    onJoin: (String) -> Unit,
) {
    val together = strings.social.together
    var inviteText by remember { mutableStateOf("") }

    SettingsSection(together.sectionCreate) {
        SettingsButtonItem(
            text = if (busy) together.creating else together.createRoom,
            subtitle = together.createRoomNote,
            icon = Icons.Filled.Add,
            index = 0,
            total = 1,
            enabled = !busy,
            onClick = onCreate,
        )
    }
    SettingsNote(
        text = together.oneRoomNote,
        emphasis = SettingsNoteEmphasis.WARNING,
    )

    SettingsSection(together.sectionJoin) {
        SettingsTextInputItem(
            title = together.inviteTitle,
            value = inviteText,
            onCommit = { inviteText = it },
            validate = { null },
            index = 0,
            total = 2,
            placeholder = together.invitePlaceholder,
            enabled = !busy,
        )
        SettingsButtonItem(
            text = together.join,
            index = 1,
            total = 2,
            enabled = !busy && inviteText.isNotBlank(),
            onClick = { onJoin(inviteText) },
        )
    }
    SettingsNote(
        text = together.inviteNote,
    )

    SettingsSection(together.sectionOther) {
        SettingsButtonItem(
            text = together.refreshRoomState,
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
    strings: CpStrings,
    state: ListenTogetherState,
    busy: Boolean,
    onRefresh: () -> Unit,
    onShare: (String) -> Unit,
    onLeave: () -> Unit,
) {
    val together = strings.social.together
    val room = state.room
    val link = state.shareUrl()

    SettingsNote(text = together.roomId(room?.roomId.orEmpty()))
    SettingsNote(
        text = buildString {
            append(together.memberCount(room?.members?.size ?: 0))
            append(if (state.isOwner()) together.ownerTag else "")
            append(ltTtl(room?.createdAtMs, room?.effectiveDurationMs).roomText(strings))
        },
    )
    // ⚠️ 实测 connectionStatus 恒为 NOT_CONNECTED（IM 长连接态），HTTP 客户端永远满足不了。
    // 它不是错误，所以不渲染成警告色，只说清「延迟从哪来」，免得用户以为出了问题。
    SettingsNote(
        text = together.pollNote(ListenTogetherEngine.POLL_INTERVAL_MS / 1000),
    )

    SettingsSection(together.sectionRoom) {
        SettingsButtonItem(
            text = together.refresh,
            icon = Icons.Filled.Refresh,
            index = 0,
            total = 1,
            enabled = !busy,
            onClick = onRefresh,
        )
    }

    SettingsSection(together.sectionInvite) {
        SettingsButtonItem(
            text = if (isAndroidPlatform()) together.shareInvite else together.copyInvite,
            subtitle = if (isAndroidPlatform()) together.shareInviteNote else together.copyInviteNote,
            icon = Icons.Filled.Share,
            index = 0,
            total = 1,
            enabled = link != null,
            onClick = { link?.let(onShare) },
        )
    }

    if (link != null) {
        SettingsNote(text = together.qrHint)
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = CpSpacing.formHorizontal),
            contentAlignment = Alignment.Center,
        ) {
            QrCodeImage(link)
        }
    } else {
        SettingsNote(
            text = together.noLinkWarning,
            emphasis = SettingsNoteEmphasis.WARNING,
        )
    }

    SettingsNote(
        text = together.inviteUnavailableNote,
    )

    SettingsSection(together.sectionLeave) {
        SettingsButtonItem(
            text = together.leaveRoom,
            subtitle = together.leaveRoomNote,
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
