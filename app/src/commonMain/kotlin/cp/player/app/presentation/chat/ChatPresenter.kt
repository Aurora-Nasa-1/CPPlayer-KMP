package cp.player.app.presentation.chat

import cp.player.app.AppModel
import cp.player.app.i18n.CpStrings
import cp.player.core.BackendResult
import cp.player.core.model.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ChatUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val messages: List<Message> = emptyList(),
    val sending: Boolean = false,
)

/**
 * 一对一私信状态机（框架无关）。
 *
 * 采用请求门控确保切换账号 / 音源 / 会话后，旧请求不会再回写状态。
 */
class ChatPresenter(
    val scope: CoroutineScope,
    private val fetchMessages: suspend (peerUid: Long, myUid: Long) -> BackendResult<List<Message>> =
        { peerUid, myUid -> AppModel.socialRepository.getMessages(peerUid, myUid) },
    private val sendText: suspend (peerUid: Long, text: String) -> Boolean =
        { peerUid, text -> AppModel.socialRepository.sendMessage(peerUid, text) },
) {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state

    private var loadedSession: ChatSessionIdentity? = null
    private val requestGate = ChatRequestGate()
    private var loadJob: Job? = null
    private var sendJob: Job? = null

    /** Mask a previous account/provider conversation on the first composition frame after a switch. */
    fun stateFor(session: ChatSessionIdentity, observedState: ChatUiState): ChatUiState =
        if (loadedSession == session) observedState else ChatUiState()

    fun load(
        peerUid: Long,
        myUid: Long,
        force: Boolean = false,
        strings: CpStrings,
        providerId: String = AppModel.activeProviderId(),
        sourceGeneration: Int = AppModel.sourceGeneration.value,
    ) {
        val session = ChatSessionIdentity(providerId, sourceGeneration, myUid, peerUid)
        if (!force && loadedSession == session && _state.value.messages.isNotEmpty()) return
        val switchingSession = loadedSession != session
        loadedSession = session
        val requestToken = requestGate.begin(session)
        loadJob?.cancel()
        loadJob = null
        sendJob?.cancel()
        sendJob = null
        // Publish synchronously so a recomposition cannot render the previous account/provider.
        _state.value = if (switchingSession) ChatUiState(loading = true)
        else _state.value.copy(loading = true, error = null, sending = false)
        loadJob = scope.launch {
            if (!requestGate.isCurrent(session, requestToken)) return@launch
            val result = try {
                fetchMessages(peerUid, myUid)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                BackendResult.Error(error.message ?: strings.social.chat.loadFailed)
            }
            if (!requestGate.isCurrent(session, requestToken)) return@launch
            _state.value = when (result) {
                is BackendResult.Success -> ChatUiState(loading = false, messages = result.data)
                is BackendResult.Error -> _state.value.copy(loading = false, error = result.message)
                is BackendResult.Unsupported -> _state.value.copy(loading = false, error = result.message)
            }
        }
    }

    /** Drop private conversation state immediately on logout or when its host leaves. */
    fun reset() {
        loadedSession = null
        requestGate.invalidate()
        loadJob?.cancel()
        loadJob = null
        sendJob?.cancel()
        sendJob = null
        _state.value = ChatUiState(loading = false)
    }

    fun send(
        peerUid: Long,
        myUid: Long,
        text: String,
        strings: CpStrings,
        providerId: String = AppModel.activeProviderId(),
        sourceGeneration: Int = AppModel.sourceGeneration.value,
        onResult: (Boolean) -> Unit,
    ) {
        val body = text.trim()
        if (body.isEmpty() || _state.value.sending) return
        val session = ChatSessionIdentity(providerId, sourceGeneration, myUid, peerUid)
        val requestToken = requestGate.currentToken(session) ?: return
        _state.value = _state.value.copy(sending = true)
        sendJob = scope.launch {
            val ok = try {
                sendText(peerUid, body)
            } catch (cancel: CancellationException) {
                if (requestGate.isCurrent(session, requestToken)) {
                    _state.value = _state.value.copy(sending = false)
                    sendJob = null
                }
                throw cancel
            } catch (_: Exception) {
                false
            }
            if (!requestGate.isCurrent(session, requestToken)) return@launch
            _state.value = _state.value.copy(sending = false)
            sendJob = null
            if (ok) {
                load(
                    peerUid,
                    myUid,
                    force = true,
                    strings = strings,
                    providerId = providerId,
                    sourceGeneration = sourceGeneration,
                )
            }
            onResult(ok)
        }
    }
}
