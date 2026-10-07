package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpBreakpoints
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.CpTwoPane
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.MessageNotifyGuideSheet
import cp.player.app.ui.component.rememberMessageNotifyGuide
import cp.player.core.model.Contact
import kotlinx.coroutines.launch

/**
 * 桌面消息双栏：**左栏会话列表 + 右栏对话**。
 *
 * ## 为什么是一个独立的 composable 而不是 `MessagesScreen`
 *
 * 窗口边框上的「消息」入口在桌面走的是**右侧内嵌面板**（`MainScreen` 的
 * `DesktopPane.Messages`）—— 与「设置 / 下载管理 / 歌单详情」同一套。这一类面板的共同
 * 特征是**外壳不由自己画**：标题在窗口 chrome 上、返回由标题栏的返回键承担。所以它们
 * 都不经过 `CpRouteScaffold`。[MessagesScreen] 则是窄屏（安卓 / 手机宽度窗口）push 出去的
 * **整页**，自带顶栏和返回键 —— 两者外壳要求正好相反，硬塞进一个类里迟早要靠布尔分支打架。
 *
 * ## 宽度判据为什么要自己读 `maxWidth`
 *
 * 内嵌面板的可用宽度**小于**窗口宽度（左侧还有 248dp 的桌面侧栏），不能用
 * `LocalIsExpanded`（那是按窗口算的）。同 `PlayerScreen.Content()` /
 * `PlaylistDetailScreen` 的做法：`BoxWithConstraints` + [CpBreakpoints.isExpanded]。
 *
 * ## 窄栏回落
 *
 * 面板被挤到 < 840dp（用户把窗口拖小）时不再硬撑双栏 —— 320dp 的左栏加一个右栏
 * 会互相压住。此时回落成**单栏**：只出列表，点会话 push [ChatScreen] 整页。
 *
 * @param onChatOpenChanged 右栏是否已经打开了一个对话（= 屏幕底部多了一条固定输入栏）。
 *   宿主（`MainScreen`）据此让小播放器让位给输入栏 —— 桌面宽屏的对话在面板右栏里，
 *   `App.kt` 那个全局小播放器宿主看不到它。离开组合时会复位为 false。
 */
@Composable
fun MessagesPane(
    onChatOpenChanged: (Boolean) -> Unit = {},
) {
    val navigator = LocalNavigator.current
    val model = remember { MessagesModel() }
    val chatModel = remember { ChatModel() }
    val state by model.state.collectAsState()
    val profile by AppModel.userProfileFlow.collectAsState()
    val loggedIn = profile != null
    val scope = rememberCoroutineScope()
    val strings = cpStrings()

    // 当前选中的会话。刻意**不自动选第一个**：进消息面板先看到一句「选择左侧会话」，
    // 比替用户决定看谁的私信更合适（也和设置页宽屏一致）。
    var selected by remember { mutableStateOf<Contact?>(null) }

    // 右栏一旦有对话就给宿主发「让位」信号（见 onChatOpenChanged 的 KDoc）。
    LaunchedEffect(selected) { onChatOpenChanged(selected != null) }
    // 面板被收起 / 切到别的 tab / 窗口缩窄时这个组合位置会消失，信号必须跟着复位 ——
    // 否则离开消息面板之后小播放器会一直不出现。
    DisposableEffect(Unit) { onDispose { onChatOpenChanged(false) } }

    LaunchedEffect(loggedIn) {
        if (loggedIn) {
            model.load(strings = strings)
            AppModel.refreshUnreadMessages()
        }
    }

    // 首次进入消息页的一次性引导（与窄屏整页 [MessagesScreen] 共用同一份记忆）。
    val (guideVisible, dismissGuide) = rememberMessageNotifyGuide(
        eligible = loggedIn && state.contacts.isNotEmpty(),
    )
    MessageNotifyGuideSheet(visible = guideVisible, onDismiss = dismissGuide)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = CpBreakpoints.isExpanded(maxWidth)

        val openContact: (Contact) -> Unit = { contact ->
            // 本地标已读 + 上报服务端。服务端失败静默：用户已经在看这个会话了，
            // 为此弹一条报错只是噪音（与 [MessagesScreen] 同一取舍）。
            model.markReadLocally(contact.userId)
            scope.launch { AppModel.socialRepository.markRead(contact.userId) }
            AppModel.refreshUnreadMessages()
            if (wide) selected = contact else navigator?.push(ChatScreen(contact.userId, contact.nickname))
        }

        val list: @Composable (Modifier) -> Unit = { listModifier ->
            ContactList(
                loggedIn = loggedIn,
                state = state,
                selectedUserId = selected?.userId,
                onOpenContact = openContact,
                onSetNotify = { uid, enabled -> model.setNotifySubscribed(uid, enabled) },
                onRetry = { model.load(strings = strings) },
                modifier = listModifier,
            )
        }

        if (!wide) {
            // 窄栏：只出列表。外壳由窗口 chrome 承担（标题 = DesktopShell.pageTitle）。
            list(Modifier.fillMaxSize())
            return@BoxWithConstraints
        }

        CpTwoPane(
            rail = { railModifier ->
                list(railModifier)
            },
            detail = {
                val contact = selected
                if (contact == null) {
                    ContentState(
                        title = strings.social.messages.selectConversation,
                        message = strings.social.messages.selectConversationNote,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                    )
                } else {
                    // ⚠️ 判据是「contact == null」而不是「列表里还有没有人」：右栏是
                    // **第二个组合位置**，左栏刷新期间它不该跟着闪空态。
                    ChatContent(
                        peerUid = contact.userId,
                        peerName = contact.nickname,
                        model = chatModel,
                        onBack = { selected = null },
                        embedded = true,
                        // 左栏还列着别的会话，此刻清掉总角标等于撒谎 —— 见该参数的 KDoc。
                        clearGlobalUnread = false,
                    )
                }
            },
        )
    }
}

/**
 * 会话列表（左栏 / 窄屏单栏共用）。
 *
 * 与 [MessagesScreen] 的四态一一对应（未登录 / 载入中 / 空 / 有数据），差别只在
 * 未登录时的空态文案 —— 双栏模式下这一栏是窄的，长篇说明会折行成一坨。
 */
@Composable
private fun ContactList(
    loggedIn: Boolean,
    state: MessagesUiState,
    selectedUserId: Long?,
    onOpenContact: (Contact) -> Unit,
    onSetNotify: (Long, Boolean) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 双栏左栏这一栏是窄的：未登录说明走 [accountBound] 那句短的，
    // 整页那条 [loginRequiredNote] 在这里会折行成一坨。
    val s = cpStrings()
    val messages = s.social.messages
    when {
        !loggedIn -> ContentState(
            title = messages.loginRequired,
            message = messages.accountBound,
            modifier = modifier.padding(top = 24.dp),
        )
        state.loading && state.contacts.isEmpty() -> ContentState(
            title = messages.loading,
            message = messages.loadingNote,
            loading = true,
            modifier = modifier,
        )
        state.contacts.isEmpty() -> ContentState(
            title = messages.empty,
            message = state.error ?: messages.emptyHint,
            error = state.error != null,
            actionLabel = if (state.error != null) s.player.retry else null,
            onAction = if (state.error != null) onRetry else null,
            modifier = modifier.padding(top = 24.dp),
        )
        else -> LazyScrollColumn(
            modifier = modifier.fillMaxSize(),
            // 左栏是**列表**不是表单：内边距取栅格页那一档（与右栏的气泡列表同值）。
            contentPadding = PaddingValues(
                start = CpSpacing.formHorizontal,
                end = CpSpacing.formHorizontal,
                top = CpSpacing.formVertical,
                bottom = CpSpacing.formBottomInset,
            ),
            verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
        ) {
            items(state.contacts.size, key = { state.contacts[it].userId }) { index ->
                val contact = state.contacts[index]
                val isSelected = contact.userId == selectedUserId
                ContactRowItem(
                    contact = contact,
                    notifyEnabled = contact.userId in state.subscribedUids,
                    modifier = Modifier.animateItem(),
                    onOpen = { onOpenContact(contact) },
                    onSetNotify = { enabled -> onSetNotify(contact.userId, enabled) },
                    // 选中态必须**成组**换色：只换底色不换文字会在浅色主题下掉对比度
                    // （与 `SettingsClickItem` 的 `selected` 分支同一套做法）。
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    secondaryContentColor = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
