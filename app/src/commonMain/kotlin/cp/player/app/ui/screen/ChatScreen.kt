package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cp.player.app.AppModel
import cp.player.app.i18n.CpStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.ArtistAvatar
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.util.UiEvents
import cp.player.app.ui.navigation.LocalAppNavigator
import cp.player.app.ui.component.CpChatBubblesSkeleton
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.theme.cpCardSurface
import cp.player.app.ui.component.ScrollColumn
import cp.player.app.ui.util.formatChatTime
import cp.player.core.BackendResult
import cp.player.core.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.ProvideCpTextFullDisplay
import cp.player.app.ui.theme.cpSheetSurface

/**
 * 一对一私信页。
 *
 * 数据链路：`msg/private/history` 读 → `send/text` 写 → `msg/private/mark/read` 标记已读。
 * 三条都由 [cp.player.app.repository.SocialRepository] 收口，页面不碰原始 JSON。
 *
 * 气泡版式沿用旧项目 `ChatScreen`（自己发的靠右、`primaryContainer`；对方靠左、
 * `surfaceContainerHigh`，各带一个朝向对方的直角）。
 *
 * ⚠️ 真正的实现在 [ChatContent] —— 窄屏整页与桌面双栏右栏共用同一份，
 * 差别只在 [ChatContent] 的两个开关（`embedded` / `clearGlobalUnread`）。
 */
class ChatScreen(
    private val peerUid: Long,
    private val peerName: String? = null,
) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.current
        ChatContent(
            peerUid = peerUid,
            peerName = peerName,
            // `rememberScreenModel` 是定义在 `Screen` 上的**扩展函数**，只有 Screen 子类的
            // 成员里才有接收者（顶层 @Composable 里会报 `Unresolved reference`）。
            model = rememberScreenModel { ChatModel() },
            onBack = { navigator.pop() },
        )
    }
}

internal typealias ChatUiState = cp.player.app.presentation.chat.ChatUiState

/** 私信页的 Voyager 适配层（Thin Adapter）。 */
internal class ChatModel(
    fetchMessages: suspend (peerUid: Long, myUid: Long) -> BackendResult<List<Message>> =
        { peerUid, myUid -> AppModel.socialRepository.getMessages(peerUid, myUid) },
    sendText: suspend (peerUid: Long, text: String) -> Boolean =
        { peerUid, text -> AppModel.socialRepository.sendMessage(peerUid, text) },
    requestScope: CoroutineScope? = null,
) : ScreenModel {
    private val presenter = cp.player.app.presentation.chat.ChatPresenter(
        scope = requestScope ?: screenModelScope,
        fetchMessages = fetchMessages,
        sendText = sendText,
    )

    val state: StateFlow<ChatUiState> get() = presenter.state

    fun stateFor(session: ChatSessionIdentity, observedState: ChatUiState): ChatUiState =
        presenter.stateFor(session, observedState)

    fun load(
        peerUid: Long,
        myUid: Long,
        force: Boolean = false,
        strings: CpStrings,
        providerId: String = AppModel.activeProviderId(),
        sourceGeneration: Int = AppModel.sourceGeneration.value,
    ) = presenter.load(peerUid, myUid, force, strings, providerId, sourceGeneration)

    fun reset() = presenter.reset()

    fun send(
        peerUid: Long,
        myUid: Long,
        text: String,
        strings: CpStrings,
        providerId: String = AppModel.activeProviderId(),
        sourceGeneration: Int = AppModel.sourceGeneration.value,
        onResult: (Boolean) -> Unit,
    ) = presenter.send(peerUid, myUid, text, strings, providerId, sourceGeneration, onResult)
}

/**
 * 对话区正文。窄屏整页（[ChatScreen]）与桌面双栏右栏（`MessagesPane`）共用。
 *
 * @param embedded 为 true 时表示"我正被渲染成某个双栏布局的右栏"：外壳由宿主承担
 *   （标题在左栏选中态里、返回由窗口 chrome 管），这里只出气泡列表 + 输入框。
 * @param clearGlobalUnread 进入这个会话时是否顺手把**全局未读角标**清零。
 *   整页模式传 true（用户是从列表点进来的，此刻整个消息区都看过了）；
 *   双栏模式必须传 **false** —— 左栏还明晃晃列着其它会话，把总角标清零等于撒谎。
 */
@Composable
internal fun ChatContent(
    peerUid: Long,
    peerName: String?,
    model: ChatModel,
    onBack: () -> Unit,
    embedded: Boolean = false,
    clearGlobalUnread: Boolean = !embedded,
) {
    val observedState by model.state.collectAsState()
    val me by AppModel.userProfileFlow.collectAsState()
    val myUid = me?.uid ?: 0L
    val activeProvider by AppModel.activeProviderFlow.collectAsState()
    val providerId = activeProvider?.id ?: AppModel.activeProviderId()
    val sourceGeneration by AppModel.sourceGeneration.collectAsState()
    val session = ChatSessionIdentity(providerId, sourceGeneration, myUid, peerUid)
    val state = model.stateFor(session, observedState)
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val s = cpStrings()
    val chat = s.social.chat
    val messageKeys = remember(state.messages) { stableChatMessageKeys(state.messages) }
    val previousMessageKeys = remember(peerUid, myUid, providerId, sourceGeneration) {
        mutableStateOf(emptyList<String>())
    }

    LaunchedEffect(peerUid, myUid, providerId, sourceGeneration) {
        if (myUid != 0L) {
            // ⚠️ `force = true` 不能省：双栏模式下左栏点谁就切谁，`peerUid` 一变这里就要
            // 重新拉。`ChatModel` 自己只按 `loadedUid` 判重，页面这层不再兜一遍。
            model.load(
                peerUid,
                myUid,
                force = true,
                strings = s,
                providerId = providerId,
                sourceGeneration = sourceGeneration,
            )
            // 进对话即视为已读（列表页已经本地标过一次，这里补上报服务端）。
            AppModel.socialRepository.markRead(peerUid)
            if (clearGlobalUnread) AppModel.clearUnreadMessages()
            AppModel.refreshUnreadMessages()
            // 通知栏里这一条该收走了 —— 用户正在看这个会话。
            cp.player.app.platform.cancelMessageNotification(
                cp.player.app.notify.MessageNotification.keyOf(providerId, peerUid)
            )
        } else {
            model.reset()
        }
    }

    // 告诉轮询「用户此刻正开着谁」：开着的时候不再弹这个人的通知（他已经看见了）。
    // 必须 DisposableEffect 而不是 LaunchedEffect —— 后者不会在离开组合时复位，
    // 用户退出会话之后那个人的消息会**永久静音**。
    DisposableEffect(peerUid) {
        AppModel.setActiveChatPeer(peerUid)
        onDispose { AppModel.setActiveChatPeer(null) }
    }

    // 桌面端打开对话时隐藏根层 MiniPlayer，避免遮挡底部的消息输入框与发送按钮。
    // 无论是桌面双栏右栏（MessagesPane）、内容区内嵌路由（从主页点发私信），还是整页路由，
    // 只要有对话与固定输入栏在屏幕上，底层的 MiniPlayer 就必须让位。
    DisposableEffect(Unit) {
        cp.player.app.ui.util.DesktopShell.miniPlayerBlockedByChat = true
        onDispose {
            cp.player.app.ui.util.DesktopShell.miniPlayerBlockedByChat = false
        }
    }

    // 列表只在纯追加时做滚动动画；历史被替换、删除或重排时直接定位，避免动画继续
    // 驱动已从 LazyColumn 移除的行节点。消息字段刷新但条数不变时仍不触发滚动。
    LaunchedEffect(messageKeys, peerUid, myUid, providerId, sourceGeneration) {
        val previous = previousMessageKeys.value
        if (messageKeys.isNotEmpty() && messageKeys.size != previous.size) {
            if (isStrictlyAppendedChatList(previous, messageKeys)) {
                listState.animateScrollToItem(messageKeys.lastIndex)
            } else {
                listState.scrollToItem(messageKeys.lastIndex)
            }
        }
        previousMessageKeys.value = messageKeys
    }

    // 先声明后使用：Kotlin 的局部函数不能前向引用，写成 lambda 变量最直观。
    val submit: () -> Unit = {
        val body = draft
        if (body.isNotBlank()) {
            model.send(
                peerUid,
                myUid,
                body,
                strings = s,
                providerId = providerId,
                sourceGeneration = sourceGeneration,
            ) { ok ->
                if (ok) draft = "" else UiEvents.notify(chat.sendFailed)
            }
        }
    }

    val body: @Composable (Modifier) -> Unit = { boxModifier ->
        Column(boxModifier) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    myUid == 0L -> ContentState(
                        title = chat.loginRequired,
                        message = chat.accountBound,
                    )
                    state.loading && state.messages.isEmpty() -> ScrollColumn(
                        modifier = Modifier.fillMaxSize(),
                        // 与消息列表同一边距与气泡间距：骨架是「这段对话的样子」，
                        // 而不是一张居中的等待卡。
                        contentPadding = PaddingValues(
                            horizontal = CpSpacing.pageHorizontal,
                            vertical = 12.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CpChatBubblesSkeleton(rows = 7)
                    }
                    state.messages.isEmpty() -> ContentState(
                        title = chat.empty,
                        message = state.error ?: chat.emptyHint,
                        error = state.error != null,
                        actionLabel = if (state.error != null) s.player.retry else null,
                        onAction = if (state.error != null) (
                            {
                                model.load(
                                    peerUid,
                                    myUid,
                                    force = true,
                                    strings = s,
                                    providerId = providerId,
                                    sourceGeneration = sourceGeneration,
                                )
                            }
                            ) else null,
                    )
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = CpSpacing.pageHorizontal,
                            vertical = 12.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(state.messages.size, key = { index -> messageKeys[index] }) { index ->
                            MessageBubble(state.messages[index], emptyText = chat.emptyMessage)
                        }
                    }
                }
            }

            Surface(
                // 输入栏是压在页面上的浮层，走 sheet 层而不是卡片层。
                color = cpSheetSurface(),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { CpText(chat.inputPlaceholder, style = MaterialTheme.typography.bodyMedium) },
                        shape = MaterialTheme.shapes.large,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                    )
                    FilledTonalIconButton(
                        onClick = submit,
                        enabled = draft.isNotBlank() && !state.sending && myUid != 0L,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = chat.send)
                    }
                }
            }
        }
    }

    // 双栏右栏：宿主已经画了标题与返回，这里只出正文（与 `CpRouteScaffold` 的
    // `embedded` 分支同一个判据：谁提供外壳谁负责标题）。
    if (embedded) {
        body(Modifier.fillMaxSize())
        return
    }

    CpRouteScaffold(title = peerName ?: chat.titleFallback, onBack = onBack) { pageModifier ->
        body(pageModifier.fillMaxSize())
    }
}

/** 单条消息气泡。自己发的靠右、用 `primaryContainer`。 */
@Composable
private fun MessageBubble(message: Message, emptyText: String) {
    val isMe = message.isMe
    // 朝向对方那一侧留直角：右上的「尾巴」指向输入框，左上的指向头像。
    val bubbleShape = if (isMe) {
        RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
    } else {
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
    }
    val bubbleColor = if (isMe) MaterialTheme.colorScheme.primaryContainer
    else cpCardSurface()
    val bubbleContent = if (isMe) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!isMe) {
            ArtistAvatar(url = message.fromAvatarUrl, size = 32.dp)
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (isMe) Alignment.End else Alignment.Start) {
            Surface(color = bubbleColor, shape = bubbleShape) {
                ProvideCpTextFullDisplay {
                    CpText(
                        message.text.ifBlank { emptyText },
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = bubbleContent,
                    )
                }
            }
            val timeStr = remember(message.time) { formatChatTime(message.time) }
            if (timeStr.isNotEmpty()) {
                CpText(
                    timeStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        if (isMe) {
            Spacer(Modifier.width(8.dp))
            ArtistAvatar(url = message.fromAvatarUrl, size = 32.dp)
        }
    }
}
