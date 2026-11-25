package cp.player.app.presentation.messages

import cp.player.app.AppModel
import cp.player.app.i18n.CpStrings
import cp.player.core.BackendResult
import cp.player.core.model.Contact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class MessagesUiState(
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
    val unsupported: Boolean = false,
)

/** Provider and account identity for a contact-list request. */
data class MessagesSessionKey(
    val providerId: String,
    val sourceGeneration: Int,
    val accountUid: Long?,
)

/**
 * 消息列表状态机（框架无关）。
 */
class MessagesPresenter(
    val scope: CoroutineScope,
    private val fetchContacts: suspend () -> BackendResult<List<Contact>> = {
        AppModel.socialRepository.getContacts()
    },
    private val subscribedUids: () -> Set<Long> = { AppModel.messageNotifySubscribedUids() },
) {
    private val _state = MutableStateFlow(MessagesUiState())
    val state: StateFlow<MessagesUiState> = _state
    private val requestLock = Any()
    private var activeSession: MessagesSessionKey? = null
    private var requestGeneration = 0L
    private var loadJob: Job? = null

    fun isCurrentSession(sessionKey: MessagesSessionKey): Boolean =
        synchronized(requestLock) { activeSession == sessionKey }

    fun stateFor(sessionKey: MessagesSessionKey, observedState: MessagesUiState): MessagesUiState =
        if (isCurrentSession(sessionKey)) observedState else MessagesUiState()

    /**
     * @param strings 失败兜底文案按**当前语言**组 —— 协程里读不到 CompositionLocal，
     *   由调用侧传进来（见 I18N.md §5.9）。
     */
    fun load(strings: CpStrings, sessionKey: MessagesSessionKey) {
        val (token, switchingSession, previousJob) = synchronized(requestLock) {
            val changed = activeSession != sessionKey
            activeSession = sessionKey
            requestGeneration += 1L
            val previous = loadJob
            loadJob = null
            Triple(requestGeneration, changed, previous)
        }
        previousJob?.cancel()
        _state.value = if (switchingSession) MessagesUiState(loading = true)
        else _state.value.copy(loading = true, error = null, unsupported = false)

        val request = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                fetchContacts()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                BackendResult.Error(error.message ?: strings.social.messages.loadFailed)
            }
            if (!isCurrent(sessionKey, token)) return@launch
            _state.value = when (result) {
                is BackendResult.Success -> MessagesUiState(
                    loading = false,
                    contacts = result.data,
                    subscribedUids = subscribedUids(),
                )
                is BackendResult.Error -> MessagesUiState(loading = false, error = result.message)
                is BackendResult.Unsupported -> MessagesUiState(loading = false, unsupported = true)
            }
        }
        synchronized(requestLock) {
            if (activeSession == sessionKey && requestGeneration == token) loadJob = request
            else request.cancel()
        }
        request.start()
    }

    /** Remove account/provider conversation data and invalidate every in-flight response. */
    fun reset() {
        val previousJob = synchronized(requestLock) {
            activeSession = null
            requestGeneration += 1L
            val previous = loadJob
            loadJob = null
            previous
        }
        previousJob?.cancel()
        _state.value = MessagesUiState(loading = false)
    }

    private fun isCurrent(sessionKey: MessagesSessionKey, token: Long): Boolean =
        synchronized(requestLock) { activeSession == sessionKey && requestGeneration == token }

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
