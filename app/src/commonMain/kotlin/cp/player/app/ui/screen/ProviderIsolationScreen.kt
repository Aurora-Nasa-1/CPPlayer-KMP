package cp.player.app.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cp.player.app.ui.navigation.RoutePage
import cp.player.app.AppModel
import cp.player.app.auth.AccountStore
import cp.player.app.i18n.cpStrings
import cp.player.app.presentation.navigation.AppRoute
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.SettingsButtonItem
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.navigation.LocalAppNavigator
import kotlinx.coroutines.launch

/**
 * 音源隔离（「账号、缓存与设置按音源分开存储」）。
 *
 * 旧版**没有**这个页面 —— 隔离一直发生在数据层，用户看不见也没法解释。
 * 新版把它显式化：把「哪些东西是按音源分开的」讲清楚，再给两个真正可动的开关。
 *
 * 数据层事实（改动前必须先看 `ProviderManager` / `UnifiedMusicSourceImpl`）：
 * - Cookie：`cookie_<providerId>`，切音源即换登录态
 * - 缓存：音频落盘缓存按 `providerId + 账号 + 凭据 + 设置` 命名空间隔离，A 音源不会串到 B
 * - 账号列表：`AccountStore` 的 `saved_accounts_<providerId>` / `active_account_<providerId>`
 */
class ProviderIsolationScreen : RoutePage {
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.current
        val scope = rememberCoroutineScope()
        val s = cpStrings()
        val provider by AppModel.activeProviderFlow.collectAsState()
        val switchAccount by AppModel.isolationSwitchAccountFlow.collectAsState()
        val profile by AppModel.userProfileFlow.collectAsState()
        val providerId = AppModel.activeProviderId()
        val cookieSaved = remember(providerId) {
            !AppModel.cookieStorage.getCookie(providerId).isNullOrBlank()
        }
        val savedAccounts = remember(providerId) { AccountStore.list(providerId).size }
        var confirmClear by remember { mutableStateOf(false) }

        val body: @Composable (androidx.compose.ui.Modifier) -> Unit = { pageModifier ->
            SettingsPage(pageModifier) {
                SettingsNote(
                    s.settings.isolationIntro,
                )

                SettingsSection(s.settings.isolationSectionAccounts) {
                    SettingsSwitchItem(
                        title = s.settings.isolationRefreshProfile,
                        subtitle = s.settings.isolationRefreshProfileNote,
                        checked = switchAccount,
                        onCheckedChange = { AppModel.setIsolationSwitchAccount(it) },
                        index = 0,
                        total = 3,
                        icon = Icons.Filled.PrivacyTip,
                    )
                    SettingsClickItem(
                        title = s.settings.isolationCurrentAccounts,
                        subtitle = buildString {
                            append(provider?.name ?: s.settings.isolationUnknownProvider)
                            append(" · ")
                            append(if (cookieSaved) s.settings.isolationSaved else s.settings.isolationNotSignedIn)
                            if (savedAccounts > 0) {
                                append(" · ")
                                append(s.settings.isolationAccountCount(savedAccounts))
                            }
                        },
                        index = 1,
                        total = 3,
                        icon = Icons.Filled.AccountCircle,
                        onClick = { navigator.push(AppRoute.Account) },
                    )
                    SettingsClickItem(
                        title = s.settings.isolationSwitchProvider,
                        subtitle = s.settings.isolationSwitchProviderNote,
                        index = 2,
                        total = 3,
                        icon = Icons.Filled.Dns,
                        onClick = { navigator.push(AppRoute.PluginHub()) },
                    )
                }

                SettingsSection(s.settings.isolationSectionCache) {
                    SettingsClickItem(
                        title = s.settings.isolationStorage,
                        subtitle = s.settings.isolationStorageNote,
                        index = 0,
                        total = 1,
                        icon = Icons.Filled.FolderOpen,
                        onClick = { navigator.push(AppRoute.StorageSettings) },
                    )
                }

                if (cookieSaved) {
                    SettingsSection(s.settings.isolationSectionDanger) {
                        SettingsButtonItem(
                            text = if (confirmClear) {
                                s.settings.isolationClearConfirm
                            } else {
                                s.settings.isolationClearSession
                            },
                            subtitle = s.settings.isolationClearNote,
                            index = 0,
                            total = 1,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            onClick = {
                                if (!confirmClear) {
                                    confirmClear = true
                                } else {
                                    // 与账号页的「退出登录」保持一致：也要打一次服务端 logout，
                                    // 否则接口缓存里仍留着该账号的响应（键含 cookie 哈希，
                                    // 同一音源再登录会被读透层直接命中）。
                                    confirmClear = false
                                    scope.launch {
                                        runCatching { AppModel.authRepository.logout() }
                                        AppModel.cookieStorage.clear(providerId)
                                        AccountStore.setActive(providerId, null)
                                        AppModel.clearUserProfile()
                                    }
                                }
                            },
                        )
                    }
                }

                SettingsNote(
                    s.settings.isolationProfileNote(
                        profile?.nickname ?: s.settings.isolationNotSignedIn,
                    ),
                )
            }
        }

        CpRouteScaffold(
            title = s.settings.isolationTitle,
            onBack = { navigator.pop() },
        ) { pageModifier -> body(pageModifier) }
    }
}
