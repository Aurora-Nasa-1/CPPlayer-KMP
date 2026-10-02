package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.LegacyListItem
import cp.player.app.ui.util.formatChatTime
import cp.player.core.BackendResult
import cp.player.core.model.Contact
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 消息列表页（最近联系人）。
 *
 * 收敛前整个「消息」入口在应用里**不存在**：`MusicApiService` 上早就有一整套私信端点
 * （`msg/recentcontact` / `msg/private` / `send/text`），但没有任何页面够得到它们。
 *
 * 版式沿用旧项目 `ContactListScreen`：头像 + 昵称 + 最后一条 + 时间 + 未读红点。
 * 未登录或音源不支持时给明确空态，而不是一屏空白。
 */
class MessagesScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        MessagesContent(
            // `rememberScreenModel` 是定义在 `Screen` 上的**扩展函数**，必须在 Screen
            // 子类的成员里调用（顶层的 @Composable 里没有接收者）。
            model = rememberScreenModel { MessagesModel() },
            onBack = { navigator.popOrNotify() },
            onOpenChat = { contact -> navigator.push(ChatScreen(contact.userId, contact.nickname)) },
        )
    }
}

internal data class MessagesUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val contacts: List<Contact> = emptyList(),
)

/**
 * 会话列表的数据源。
 *
 * ⚠️ **刻意是 `ScreenModel` 但被两处共用**：`MessagesScreen`（窄屏整页）与
 * `MessagesPane`（桌面双栏左栏）各 `rememberScreenModel { }` 一份 —— 它们是**两个
 * 不同的 Screen / 不同的组合位置**，共用实例反而会在宽窄切换时把加载态搅在一起。
 * 这里提升成 `internal` 只是为了让 `MessagesPane` 够得到，不是共享实例。
 */
internal class MessagesModel : ScreenModel {
    private val _state = MutableStateFlow(MessagesUiState())
    val state: StateFlow<MessagesUiState> = _state

    fun load() {
        screenModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val result = runCatching { AppModel.socialRepository.getContacts() }
                .getOrElse { BackendResult.Error(it.message ?: "读取消息失败") }
            _state.value = when (result) {
                is BackendResult.Success -> MessagesUiState(loading = false, contacts = result.data)
                is BackendResult.Error -> MessagesUiState(loading = false, error = result.message)
                is BackendResult.Unsupported -> MessagesUiState(loading = false, error = result.message)
            }
        }
    }

    /** 把某个联系人本地标为已读（不再重新拉整页 —— 用户刚点进去，列表不该整屏闪一次）。 */
    fun markReadLocally(userId: Long) {
        _state.value = _state.value.copy(
            contacts = _state.value.contacts.map {
                if (it.userId == userId) it.copy(unreadCount = 0) else it
            },
        )
    }
}

@Composable
private fun MessagesContent(
    model: MessagesModel,
    onBack: () -> Unit,
    onOpenChat: (Contact) -> Unit,
) {
    val state by model.state.collectAsState()
    val profile by AppModel.userProfileFlow.collectAsState()
    val loggedIn = profile != null
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (loggedIn) {
            model.load()
            AppModel.refreshUnreadMessages()
        }
    }

    CpRouteScaffold(title = "消息", onBack = onBack) { pageModifier ->
        when {
            !loggedIn -> ContentState(
                title = "登录后查看私信",
                message = "消息与账号绑定，先在「账号与登录」里登录当前音源",
                modifier = pageModifier.padding(top = 32.dp),
            )
            state.loading && state.contacts.isEmpty() -> ContentState(
                title = "正在载入消息",
                message = "正在从当前音源读取最近联系人",
                loading = true,
            )
            state.contacts.isEmpty() -> ContentState(
                title = "还没有消息",
                message = state.error ?: "在歌手或用户主页点「发私信」就能开始聊天",
                error = state.error != null,
                actionLabel = if (state.error != null) "重试" else null,
                onAction = if (state.error != null) ({ model.load() }) else null,
                modifier = pageModifier.padding(top = 32.dp),
            )
            else -> LazyScrollColumn(
                modifier = pageModifier.fillMaxSize(),
                // 这是「列表页」而不是表单页：内边距取栅格页那一档，与搜索 / 曲库一致。
                contentPadding = PaddingValues(
                    start = CpSpacing.pageHorizontal,
                    end = CpSpacing.pageHorizontal,
                    bottom = CpSpacing.formBottomInset,
                ),
                verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
            ) {
                items(state.contacts.size, key = { state.contacts[it].userId }) { index ->
                    val contact = state.contacts[index]
                    ContactRow(
                        contact = contact,
                        modifier = Modifier.animateItem(),
                        onClick = {
                            // 本地标已读 + 上报服务端。服务端失败静默：用户已经进聊天页了，
                            // 为此弹一条报错只是噪音。
                            model.markReadLocally(contact.userId)
                            scope.launch { AppModel.socialRepository.markRead(contact.userId) }
                            AppModel.refreshUnreadMessages()
                            onOpenChat(contact)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 联系人行。
 *
 * 直接用 [LegacyListItem] 而不是做成 Settings 行 —— 这一行有**独立的语义结构**
 * （头像 + 主标题行内右侧时间 + 副标题 + 尾部未读角标），设置行没有这种形状。
 *
 * [containerColor] 与两个内容色**必须由调用方成组传入**：只换底色不换文字色
 * 会在浅色主题下掉对比度（`SettingsClickItem` 的 `selected` 分支就是为这件事存在的）。
 * 整页（窄屏）模式不传，走默认的 `surfaceContainerHigh`。
 */
@Composable
internal fun ContactRow(
    contact: Contact,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    secondaryContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    LegacyListItem(
        index = 0,
        total = 1,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        containerColor = containerColor,
        leadingContent = { ArtistAvatar(url = contact.avatarUrl, size = 52.dp) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    contact.nickname.ifBlank { "未知用户" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val timeStr = contact.lastMessageTime?.let(::formatChatTime).orEmpty()
                if (timeStr.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        timeStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryContentColor,
                    )
                }
            }
        },
        supportingContent = {
            Text(
                contact.lastMessage.orEmpty().ifBlank { "（没有消息内容）" },
                style = MaterialTheme.typography.bodyMedium,
                color = secondaryContentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            if (contact.unreadCount > 0) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ) {
                    Text(
                        if (contact.unreadCount > 99) "99+" else contact.unreadCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
    )
}
