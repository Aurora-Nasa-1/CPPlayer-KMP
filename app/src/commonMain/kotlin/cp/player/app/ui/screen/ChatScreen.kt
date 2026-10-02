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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.ui.component.ArtistAvatar
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.util.popOrNotify
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.util.formatChatTime
import cp.player.core.BackendResult
import cp.player.core.model.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
        val navigator = LocalNavigator.currentOrThrow
        ChatContent(
            peerUid = peerUid,
            peerName = peerName,
            // `rememberScreenModel` 是定义在 `Screen` 上的**扩展函数**，只有 Screen 子类的
            // 成员里才有接收者（顶层 @Composable 里会报 `Unresolved reference`）。
            model = rememberScreenModel { ChatModel() },
            onBack = { navigator.popOrNotify() },
        )
    }
}

internal data class ChatUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val messages: List<Message> = emptyList(),
    val sending: Boolean = false,
)

internal class ChatModel : ScreenModel {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state

    private var loadedUid: Long? = null

    /**
     * 换会话时必须把上一段对话清掉再拉。
     *
     * ⚠️ 双栏模式下这个模型是**跨会话复用**的（左栏点谁就切谁），若沿用 `_state.value.copy(…)`
     * 保留旧 messages，切换瞬间右栏会先渲染**上一个会话的气泡**（还在 `messages` 里的那些），
     * 等网络回来才换成新的 —— 那一下闪现比空白更糟。所以进这里就换成一个干净的 state。
     */
    fun load(peerUid: Long, myUid: Long, force: Boolean = false) {
        if (!force && loadedUid == peerUid && _state.value.messages.isNotEmpty()) return
        val switchingPeer = loadedUid != peerUid
        loadedUid = peerUid
        screenModelScope.launch {
            _state.value = if (switchingPeer) ChatUiState(loading = true)
            else _state.value.copy(loading = true, error = null)
            val result = runCatching { AppModel.socialRepository.getMessages(peerUid, myUid) }
                .getOrElse { BackendResult.Error(it.message ?: "读取私信失败") }
            _state.value = when (result) {
                is BackendResult.Success -> ChatUiState(loading = false, messages = result.data)
                is BackendResult.Error -> _state.value.copy(loading = false, error = result.message)
                is BackendResult.Unsupported -> _state.value.copy(loading = false, error = result.message)
            }
        }
    }

    /**
     * 发送。
     *
     * 采用「**先落地再重拉**」：服务端受理后重新拉一次历史，而不是把本地气泡直接往后追加。
     * 直接追加看似更快，但服务端返回的 id / 时间与本地臆造的不一致，
     * 下次进这一页重新拉取时整条消息会「跳一下」。
     */
    fun send(peerUid: Long, myUid: Long, text: String, onResult: (Boolean) -> Unit) {
        val body = text.trim()
        if (body.isEmpty() || _state.value.sending) return
        _state.value = _state.value.copy(sending = true)
        screenModelScope.launch {
            val ok = AppModel.socialRepository.sendMessage(peerUid, body)
            _state.value = _state.value.copy(sending = false)
            if (ok) load(peerUid, myUid, force = true)
            onResult(ok)
        }
    }
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
    val state by model.state.collectAsState()
    val me by AppModel.userProfileFlow.collectAsState()
    val myUid = me?.uid ?: 0L
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(peerUid, myUid) {
        if (myUid != 0L) {
            // ⚠️ `force = true` 不能省：双栏模式下左栏点谁就切谁，`peerUid` 一变这里就要
            // 重新拉。`ChatModel` 自己只按 `loadedUid` 判重，页面这层不再兜一遍。
            model.load(peerUid, myUid, force = true)
            // 进对话即视为已读（列表页已经本地标过一次，这里补上报服务端）。
            AppModel.socialRepository.markRead(peerUid)
            if (clearGlobalUnread) AppModel.clearUnreadMessages()
            AppModel.refreshUnreadMessages()
        }
    }

    // 新消息到达后滚到底部。用 `size` 而不是 `messages` 作 key：内容变化（例如重拉后
    // 同一条消息的 id 变了）不该触发一次滚动。
    LaunchedEffect(state.messages.size, peerUid) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    // 先声明后使用：Kotlin 的局部函数不能前向引用，写成 lambda 变量最直观。
    val submit: () -> Unit = {
        val body = draft
        if (body.isNotBlank()) {
            model.send(peerUid, myUid, body) { ok ->
                if (ok) draft = "" else cp.player.app.ui.util.UiEvents.notify("发送失败，请检查登录状态")
            }
        }
    }

    val body: @Composable (Modifier) -> Unit = { boxModifier ->
        Column(boxModifier) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    myUid == 0L -> ContentState(
                        title = "登录后可发送私信",
                        message = "私信与账号绑定",
                    )
                    state.loading && state.messages.isEmpty() -> ContentState(
                        title = "正在载入对话",
                        loading = true,
                    )
                    state.messages.isEmpty() -> ContentState(
                        title = "还没有聊过",
                        message = state.error ?: "在下面输入第一句话吧",
                        error = state.error != null,
                        actionLabel = if (state.error != null) "重试" else null,
                        onAction = if (state.error != null) ({ model.load(peerUid, myUid, force = true) }) else null,
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
                        items(state.messages.size, key = { index ->
                            // ⚠️ key 里带上 index：上游同一页里出现重复 `id` 时，
                            // 纯 id 作 key 会让 Compose 直接抛「Key was already used」。
                            "${state.messages[index].id}-$index"
                        }) { index ->
                            MessageBubble(state.messages[index])
                        }
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 2.dp,
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
                        placeholder = { Text("说点什么…", style = MaterialTheme.typography.bodyMedium) },
                        shape = MaterialTheme.shapes.large,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                    )
                    FilledTonalIconButton(
                        onClick = submit,
                        enabled = draft.isNotBlank() && !state.sending && myUid != 0L,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
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

    CpRouteScaffold(title = peerName ?: "私信", onBack = onBack) { pageModifier ->
        body(pageModifier.fillMaxSize())
    }
}

/** 单条消息气泡。自己发的靠右、用 `primaryContainer`。 */
@Composable
private fun MessageBubble(message: Message) {
    val isMe = message.isMe
    // 朝向对方那一侧留直角：右上的「尾巴」指向输入框，左上的指向头像。
    val bubbleShape = if (isMe) {
        RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
    } else {
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
    }
    val bubbleColor = if (isMe) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainerHigh
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
                Text(
                    message.text.ifBlank { "（空消息）" },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = bubbleContent,
                )
            }
            val timeStr = remember(message.time) { formatChatTime(message.time) }
            if (timeStr.isNotEmpty()) {
                Text(
                    timeStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
                )
            }
        }
        if (isMe) {
            Spacer(Modifier.width(8.dp))
            ArtistAvatar(url = message.fromAvatarUrl, size = 32.dp)
        }
    }
}
