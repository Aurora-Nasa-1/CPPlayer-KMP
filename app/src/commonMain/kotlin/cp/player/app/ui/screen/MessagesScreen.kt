package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import cp.player.app.i18n.CpStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.ui.component.ArtistAvatar
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpContextMenu
import cp.player.app.ui.component.CpContextMenuItem
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.util.popOrNotify
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.LegacyListItem
import cp.player.app.ui.component.MessageNotifyGuideSheet
import cp.player.app.ui.component.MessageNotifySheet
import cp.player.app.ui.component.NotifyBellIndicator
import cp.player.app.ui.component.rememberMessageNotifyGuide
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
    /**
     * 已开启新消息推送的 uid。
     *
     * 刻意**放进 state 而不是每行去问 AppModel**：订阅表是 `SettingsStorage` 里的
     * 普通字符串（不是 Flow），逐行查询既不会触发重组、又读不出「刚刚切换过」。
     * 放进 state 后，切换 → 更新 state → 列表重组，一条链走完。
     */
    val subscribedUids: Set<Long> = emptySet(),
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

    /**
     * @param strings 失败兜底文案按**当前语言**组 —— 协程里读不到 CompositionLocal，
     *   由组合侧传进来（见 I18N.md §5.9）。
     */
    fun load(strings: CpStrings) {
        screenModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val result = runCatching { AppModel.socialRepository.getContacts() }
                .getOrElse { BackendResult.Error(it.message ?: strings.social.messages.loadFailed) }
            _state.value = when (result) {
                is BackendResult.Success -> MessagesUiState(
                    loading = false,
                    contacts = result.data,
                    subscribedUids = AppModel.messageNotifySubscribedUids(),
                )
                is BackendResult.Error -> MessagesUiState(loading = false, error = result.message)
                is BackendResult.Unsupported -> MessagesUiState(loading = false, error = result.message)
            }
        }
    }

    /**
     * 切换某人的新消息推送。
     *
     * 真正的落盘与轮询起停都在 [AppModel.toggleMessageNotify] 里；这里只负责把
     * 结果同步回 state（否则铃铛图标不会变 —— 见 [MessagesUiState.subscribedUids]）。
     */
    fun setNotifySubscribed(uid: Long, enabled: Boolean) {
        AppModel.toggleMessageNotify(uid, enabled)
        _state.value = _state.value.copy(subscribedUids = AppModel.messageNotifySubscribedUids())
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
    val s = cpStrings()
    val messages = s.social.messages

    LaunchedEffect(Unit) {
        if (loggedIn) {
            model.load(strings = s)
            AppModel.refreshUnreadMessages()
        }
    }

    CpRouteScaffold(title = messages.title, onBack = onBack) { pageModifier ->
        when {
            !loggedIn -> ContentState(
                title = messages.loginRequired,
                message = messages.loginRequiredNote,
                modifier = pageModifier.padding(top = 32.dp),
            )
            state.loading && state.contacts.isEmpty() -> ContentState(
                title = messages.loading,
                message = messages.loadingNote,
                loading = true,
            )
            state.contacts.isEmpty() -> ContentState(
                title = messages.empty,
                message = state.error ?: messages.emptyHint,
                error = state.error != null,
                actionLabel = if (state.error != null) s.player.retry else null,
                onAction = if (state.error != null) ({ model.load(strings = s) }) else null,
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
                    ContactRowItem(
                        contact = contact,
                        notifyEnabled = contact.userId in state.subscribedUids,
                        modifier = Modifier.animateItem(),
                        onOpen = {
                            // 本地标已读 + 上报服务端。服务端失败静默：用户已经进聊天页了，
                            // 为此弹一条报错只是噪音。
                            model.markReadLocally(contact.userId)
                            scope.launch { AppModel.socialRepository.markRead(contact.userId) }
                            AppModel.refreshUnreadMessages()
                            onOpenChat(contact)
                        },
                        onSetNotify = { enabled -> model.setNotifySubscribed(contact.userId, enabled) },
                    )
                }
            }
        }

        // 首次进入的引导：只在「已登录 + 确实有联系人」时弹 —— 空态/错误态上再叠一层弹层很难看。
        val (guideVisible, dismissGuide) = rememberMessageNotifyGuide(
            eligible = loggedIn && state.contacts.isNotEmpty(),
        )
        MessageNotifyGuideSheet(visible = guideVisible, onDismiss = dismissGuide)
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
    /** 该联系人是否已开启新消息推送 —— 决定行内要不要出小铃铛。 */
    notifyEnabled: Boolean = false,
    /** 长按（安卓）。桌面端传 null：那里用右键菜单。 */
    onLongClick: (() -> Unit)? = null,
) {
    val strings = cpStrings()
    LegacyListItem(
        index = 0,
        total = 1,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth(),
        containerColor = containerColor,
        leadingContent = { ArtistAvatar(url = contact.avatarUrl, size = 52.dp) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    contact.nickname.ifBlank { strings.social.messages.unknownUser },
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
                contact.lastMessage.orEmpty().ifBlank { strings.social.messages.noPreview },
                style = MaterialTheme.typography.bodyMedium,
                color = secondaryContentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 铃铛在未读角标**之前**：让「谁开了推送」一眼可见 ——
                // 否则只能靠右键/长按逐个去翻。
                if (notifyEnabled) {
                    NotifyBellIndicator(Modifier.size(16.dp))
                }
                if (contact.unreadCount > 0) {
                    Spacer(Modifier.width(6.dp))
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
            }
        },
    )
}

/**
 * 联系人行 + 「新消息通知」开关入口（窄屏整页与桌面双栏左栏**共用**）。
 *
 * 两个平台各只有一种自然的呼出方式，所以分成两路：
 * - **桌面**：`CpContextMenu` 右键菜单。用默认的 Initial 消费 —— 行内菜单必须比
 *   `App.kt` 那层整窗兜底（`passive = true`，Main 阶段）**更早**拿到事件，
 *   否则右键会被兜底层先吃掉，菜单里只剩「返回上一级」。
 * - **安卓**：长按 → [MessageNotifySheet]。长按交给 `LegacyListItem` 的 `onLongClick`
 *   （它内部按该参数切换 `Surface(onClick)` / `combinedClickable`），
 *   **不要**在外层再套一个 `combinedClickable` —— 两个手势识别器会互相抢。
 *
 * @param onSetNotify 目标状态（不是「切换」）：开关要能把「当前是开的」这个事实传回来，
 *   而右键菜单只有「开启 / 关闭」一个动作、弹层里是一个 `Switch`，两边语义不同。
 */
@Composable
internal fun ContactRowItem(
    contact: Contact,
    notifyEnabled: Boolean,
    onOpen: () -> Unit,
    onSetNotify: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    secondaryContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val strings = cpStrings().messageNotify
    val desktop = !isAndroidPlatform()
    var sheetOpen by remember { mutableStateOf(false) }

    val row: @Composable () -> Unit = {
        ContactRow(
            contact = contact,
            onClick = onOpen,
            onLongClick = if (desktop) null else ({ sheetOpen = true }),
            notifyEnabled = notifyEnabled,
            modifier = Modifier.fillMaxWidth(),
            containerColor = containerColor,
            contentColor = contentColor,
            secondaryContentColor = secondaryContentColor,
        )
    }

    if (desktop) {
        CpContextMenu(
            items = listOf(
                CpContextMenuItem(
                    label = if (notifyEnabled) strings.menuDisable else strings.menuEnable,
                    icon = if (notifyEnabled) Icons.Filled.NotificationsOff else Icons.Filled.Notifications,
                    // 已开启时以主题色高亮 —— 与排序菜单的「当前项」同一套表达。
                    isSelected = notifyEnabled,
                    onClick = { onSetNotify(!notifyEnabled) },
                ),
            ),
            modifier = modifier,
        ) { row() }
    } else {
        Box(modifier) { row() }
        if (sheetOpen) {
            MessageNotifySheet(
                contactName = contact.nickname,
                enabled = notifyEnabled,
                onToggle = { onSetNotify(it) },
                onDismiss = { sheetOpen = false },
            )
        }
    }
}
