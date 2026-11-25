package cp.player.app.ui.screen

import cp.player.app.ui.component.CpText
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
import cp.player.app.ui.theme.cpCardSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import cp.player.app.presentation.SocialAvailability
import cp.player.app.presentation.SocialFeature
import cp.player.app.ui.navigation.statusText
import cp.player.app.ui.navigation.socialFeatureAvailability
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
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cp.player.app.AppModel
import cp.player.app.i18n.CpStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.presentation.navigation.AppRoute
import cp.player.app.ui.navigation.LocalAppNavigator
import cp.player.app.ui.component.ArtistAvatar
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpContextMenu
import cp.player.app.ui.component.CpContextMenuItem
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpContactListSkeleton
import cp.player.app.ui.component.CpSkeletonCaption
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.LegacyListItem
import cp.player.app.ui.component.MessageNotifyGuideSheet
import cp.player.app.ui.component.MessageNotifySheet
import cp.player.app.ui.component.NotifyBellIndicator
import cp.player.app.ui.component.rememberMessageNotifyGuide
import cp.player.app.ui.util.formatChatListTime
import cp.player.app.ui.util.formatChatTime
import cp.player.core.BackendResult
import cp.player.core.model.Contact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 消息与私信列表页面。
 */
class MessagesScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.current
        MessagesContent(
            // `rememberScreenModel` 是定义在 `Screen` 上的**扩展函数**，必须在 Screen
            // 子类的成员里调用（顶层的 @Composable 里没有接收者）。
            model = rememberScreenModel { MessagesModel() },
            onBack = { navigator.pop() },
            onOpenChat = { contact -> navigator.push(AppRoute.Chat(contact.userId, contact.nickname)) },
        )
    }
}

internal typealias MessagesUiState = cp.player.app.presentation.messages.MessagesUiState
internal typealias MessagesSessionKey = cp.player.app.presentation.messages.MessagesSessionKey

/**
 * 会话列表的数据源。
 *
 * ⚠️ **刻意是 `ScreenModel` 但被两处共用**：`MessagesScreen`（窄屏整页）与
 * `MessagesPane`（桌面双栏左栏）各 `rememberScreenModel { }` 一份 —— 它们是**两个
 * 不同的 Screen / 不同的组合位置**，共用实例反而会在宽窄切换时把加载态搅在一起。
 * 这里提升成 `internal` 只是为了让 `MessagesPane` 够得到，不是共享实例。
 */
internal class MessagesModel(
    fetchContacts: suspend () -> BackendResult<List<Contact>> = {
        AppModel.socialRepository.getContacts()
    },
    subscribedUids: () -> Set<Long> = { AppModel.messageNotifySubscribedUids() },
    requestScope: CoroutineScope? = null,
) : ScreenModel {
    private val presenter = cp.player.app.presentation.messages.MessagesPresenter(
        scope = requestScope ?: screenModelScope,
        fetchContacts = fetchContacts,
        subscribedUids = subscribedUids,
    )

    val state: StateFlow<MessagesUiState> get() = presenter.state

    fun isCurrentSession(sessionKey: MessagesSessionKey): Boolean =
        presenter.isCurrentSession(sessionKey)

    fun stateFor(sessionKey: MessagesSessionKey, observedState: MessagesUiState): MessagesUiState =
        presenter.stateFor(sessionKey, observedState)

    fun load(strings: CpStrings, sessionKey: MessagesSessionKey) =
        presenter.load(strings, sessionKey)

    fun reset() = presenter.reset()

    fun setNotifySubscribed(uid: Long, enabled: Boolean) =
        presenter.setNotifySubscribed(uid, enabled)

    fun markReadLocally(userId: Long) = presenter.markReadLocally(userId)
}

@Composable
private fun MessagesContent(
    model: MessagesModel,
    onBack: () -> Unit,
    onOpenChat: (Contact) -> Unit,
) {
    val observedState by model.state.collectAsState()
    val profile by AppModel.userProfileFlow.collectAsState()
    val loggedIn = profile != null
    val activeProvider by AppModel.activeProviderFlow.collectAsState()
    val availability = socialFeatureAvailability(activeProvider, SocialFeature.Inbox)
    val providerId = activeProvider?.id ?: AppModel.activeProviderId()
    val sourceGeneration by AppModel.sourceGeneration.collectAsState()
    val sessionKey = MessagesSessionKey(providerId, sourceGeneration, profile?.uid)
    val state = model.stateFor(sessionKey, observedState)
    val scope = rememberCoroutineScope()
    val s = cpStrings()
    val messages = s.social.messages

    LaunchedEffect(sessionKey, availability) {
        if (loggedIn && availability == SocialAvailability.Available) {
            model.load(strings = s, sessionKey = sessionKey)
            AppModel.refreshUnreadMessages()
        } else {
            model.reset()
        }
    }

    CpRouteScaffold(title = messages.title, onBack = onBack) { pageModifier ->
        when {
            availability != SocialAvailability.Available || state.unsupported -> ContentState(
                title = (if (state.unsupported && availability == SocialAvailability.Available)
                    SocialAvailability.Unsupported else availability).statusText("会话列表"),
                loading = availability == SocialAvailability.Loading,
                modifier = pageModifier.padding(top = 32.dp),
            )
            !loggedIn -> ContentState(
                title = messages.loginRequired,
                message = messages.loginRequiredNote,
                modifier = pageModifier.padding(top = 32.dp),
            )
            state.loading && state.contacts.isEmpty() -> LazyScrollColumn(
                modifier = pageModifier.fillMaxSize(),
                // 与已加载列表取同一组内边距：骨架的行高与 ContactRow 对齐，
                // 数据回来时只是灰块被文字替换，不会整体上移。
                contentPadding = PaddingValues(
                    start = CpSpacing.pageHorizontal,
                    end = CpSpacing.pageHorizontal,
                    top = CpSpacing.pageTop,
                    bottom = CpSpacing.formBottomInset,
                ),
                verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
            ) {
                item { CpSkeletonCaption(messages.loadingNote) }
                item { CpContactListSkeleton(rows = 8) }
            }
            state.contacts.isEmpty() -> ContentState(
                title = if (state.error != null) messages.loadFailed else messages.empty,
                message = state.error ?: messages.emptyHint,
                error = state.error != null,
                actionLabel = if (state.error != null) s.player.retry else null,
                onAction = if (state.error != null) ({
                    model.load(strings = s, sessionKey = sessionKey)
                }) else null,
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
    containerColor: Color = cpCardSurface(),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CpText(
                    contact.nickname.ifBlank { strings.social.messages.unknownUser },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = contentColor,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                val timeStr = contact.lastMessageTime?.let(::formatChatListTime).orEmpty()
                if (timeStr.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    CpText(
                        timeStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryContentColor,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        },
        supportingContent = {
            CpText(
                contact.lastMessage.orEmpty().ifBlank { strings.social.messages.noPreview },
                style = MaterialTheme.typography.bodyMedium,
                color = secondaryContentColor,
                maxLines = 1,
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
                        CpText(
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
    containerColor: Color = cpCardSurface(),
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
