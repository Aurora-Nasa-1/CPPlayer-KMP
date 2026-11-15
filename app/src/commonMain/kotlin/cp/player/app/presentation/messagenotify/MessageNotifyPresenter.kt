package cp.player.app.presentation.messagenotify

import cp.player.app.AppModel
import cp.player.app.presentation.UiPresenter
import cp.player.core.BackendResult
import cp.player.core.model.Contact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class MessageNotifySettingsState(
    val loading: Boolean = true,
    val contacts: List<Contact> = emptyList(),
    val subscribed: Set<Long> = emptySet(),
    val master: Boolean = AppModel.messageNotifyMasterEnabled(),
)

interface MessageNotifyContract : UiPresenter<MessageNotifySettingsState> {
    fun load()
    fun setMaster(enabled: Boolean)
    fun setSubscribed(uid: Long, enabled: Boolean)
}

class MessageNotifyPresenter(
    override val scope: CoroutineScope,
) : MessageNotifyContract {
    private val _state = MutableStateFlow(MessageNotifySettingsState())
    override val state: StateFlow<MessageNotifySettingsState> = _state

    override fun load() {
        scope.launch {
            _state.value = _state.value.copy(loading = true)
            val contacts = when (val result = runCatching { AppModel.socialRepository.getContacts() }
                .getOrElse { BackendResult.Error(it.message ?: "") }) {
                is BackendResult.Success -> result.data
                else -> emptyList()
            }
            _state.value = MessageNotifySettingsState(
                loading = false,
                contacts = contacts,
                subscribed = AppModel.messageNotifySubscribedUids(),
                master = AppModel.messageNotifyMasterEnabled(),
            )
        }
    }

    override fun setMaster(enabled: Boolean) {
        AppModel.setMessageNotifyMasterEnabled(enabled)
        _state.value = _state.value.copy(master = enabled)
    }

    override fun setSubscribed(uid: Long, enabled: Boolean) {
        AppModel.toggleMessageNotify(uid, enabled)
        _state.value = _state.value.copy(
            subscribed = AppModel.messageNotifySubscribedUids(),
            master = AppModel.messageNotifyMasterEnabled(),
        )
    }
}
