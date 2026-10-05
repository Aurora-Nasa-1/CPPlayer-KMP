package cp.player.app.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.PlatformCloseBehaviorSetting
import cp.player.app.platform.canPostMessageNotifications
import cp.player.app.platform.messageNotificationsSupported
import cp.player.app.platform.requestMessageNotificationPermission
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.util.popOrNotify
import cp.player.core.BackendResult
import cp.player.core.model.Contact
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 消息通知设置页。
 *
 * ## 它解决什么
 *
 * 「谁开了推送」在消息页能右键/长按逐个改，但**没有地方能看到全貌**，
 * 也没有地方能一键关掉总开关。而且桌面端「关窗后还能不能收」这个前置条件
 * （必须最小化到托盘）也需要一个解释的地方 —— 否则用户关窗后收不到通知，
 * 只会认为是功能坏了。
 *
 * ## 文案里必须说清的两件事
 *
 * 1. **只在应用运行时有效**（没有服务端推送）；
 * 2. **系统通知权限**可能被用户在系统里关掉 —— 那时应用侧开关是开的但一条也收不到，
 *    所以这里要如实提示并提供「去授权」。
 */
class MessageNotifySettingsScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { MessageNotifySettingsModel() }
        val state by model.state.collectAsState()
        val strings = cpStrings().messageNotify

        LaunchedEffect(Unit) { model.load() }

        CpRouteScaffold(title = strings.settingsTitle, onBack = { navigator.popOrNotify() }) { pageModifier ->
            SettingsPage(pageModifier) {
                if (!messageNotificationsSupported()) {
                    SettingsSection(strings.settingsTitle) {
                        SettingsNote(strings.unsupportedPlatform)
                    }
                } else {
                    SettingsSection(strings.settingsTitle) {
                        SettingsSwitchItem(
                            title = strings.masterLabel,
                            subtitle = strings.masterHint,
                            checked = state.master,
                            onCheckedChange = model::setMaster,
                            index = 0,
                            total = 1,
                        )
                    }

                    // 权限被系统关掉时**必须**明说：否则用户开了开关却一条都收不到，
                    // 只会以为功能坏了（应用层看不到系统层的拦截）。
                    if (!canPostMessageNotifications()) {
                        SettingsSection(strings.permissionMissing) {
                            SettingsClickItem(
                                title = strings.grantPermission,
                                index = 0,
                                total = 1,
                                onClick = { requestMessageNotificationPermission() },
                            )
                        }
                    }

                    SettingsSection(strings.subscribedSection) {
                        val followed = state.contacts.filter { it.userId in state.subscribed }
                        if (followed.isEmpty()) {
                            SettingsNote(strings.subscribedEmpty)
                        } else {
                            followed.forEachIndexed { index, contact ->
                                SettingsSwitchItem(
                                    title = contact.nickname.ifBlank { "未知用户" },
                                    subtitle = contact.lastMessage,
                                    checked = true,
                                    onCheckedChange = { on -> model.setSubscribed(contact.userId, on) },
                                    index = index,
                                    total = followed.size,
                                )
                            }
                        }
                    }

                    // 桌面专属：关窗去向。Android 端是空实现（那里没有「关窗」这回事）。
                    PlatformCloseBehaviorSetting(index = 0, total = 1)
                }
            }
        }
    }
}

internal data class MessageNotifySettingsState(
    val loading: Boolean = true,
    val contacts: List<Contact> = emptyList(),
    val subscribed: Set<Long> = emptySet(),
    val master: Boolean = AppModel.messageNotifyMasterEnabled(),
)

internal class MessageNotifySettingsModel : ScreenModel {
    private val _state = MutableStateFlow(MessageNotifySettingsState())
    val state: StateFlow<MessageNotifySettingsState> = _state

    fun load() {
        screenModelScope.launch {
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

    fun setMaster(enabled: Boolean) {
        AppModel.setMessageNotifyMasterEnabled(enabled)
        _state.value = _state.value.copy(master = enabled)
    }

    fun setSubscribed(uid: Long, enabled: Boolean) {
        AppModel.toggleMessageNotify(uid, enabled)
        _state.value = _state.value.copy(
            subscribed = AppModel.messageNotifySubscribedUids(),
            master = AppModel.messageNotifyMasterEnabled(),
        )
    }
}
