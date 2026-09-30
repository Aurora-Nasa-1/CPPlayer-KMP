package cp.player.app.ui.screen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.auth.AccountStore
import cp.player.app.ui.component.LegacyPageScaffold
import cp.player.app.ui.component.SettingsButtonItem
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSwitchItem

/**
 * 音源隔离（「账号、缓存与设置按音源分开存储」）。
 *
 * 旧版**没有**这个页面 —— 隔离一直发生在数据层，用户看不见也没法解释。
 * 新版把它显式化：把「哪些东西是按音源分开的」讲清楚，再给两个真正可动的开关。
 *
 * 数据层事实（改动前必须先看 `ProviderManager` / `CachedMusicApiService`）：
 * - Cookie：`cookie_<providerId>`，切音源即换登录态
 * - 缓存：`ApiCache` 键含 `providerId + cookieHash`，A 音源的缓存不会串到 B
 * - 账号列表：`AccountStore` 的 `saved_accounts_<providerId>` / `active_account_<providerId>`
 */
class ProviderIsolationScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val expanded = cp.player.app.ui.component.LocalIsExpanded.current
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
                    "每个音源都有自己的登录态、缓存和账号列表，互相不共享：换一个音源，" +
                        "看到的账号、红心和歌单就是那个音源自己的。",
                )

                SettingsSection("登录态") {
                    SettingsSwitchItem(
                        title = "切音源时同步刷新账号资料",
                        subtitle = "关掉后仍会换登录态（Cookie 本来就按音源存），只是不立即重新拉取昵称与头像",
                        checked = switchAccount,
                        onCheckedChange = { AppModel.setIsolationSwitchAccount(it) },
                        index = 0,
                        total = 3,
                        icon = Icons.Filled.PrivacyTip,
                    )
                    SettingsClickItem(
                        title = "当前音源的账号",
                        subtitle = buildString {
                            append(provider?.name ?: "未知音源")
                            append(" · ")
                            append(if (cookieSaved) "登录态已保存" else "未登录")
                            if (savedAccounts > 0) append(" · $savedAccounts 个账号")
                        },
                        index = 1,
                        total = 3,
                        icon = Icons.Filled.AccountCircle,
                        onClick = { navigator.push(AccountScreen()) },
                    )
                    SettingsClickItem(
                        title = "切换音乐源",
                        subtitle = "不同音源之间不会互相污染登录态与缓存",
                        index = 2,
                        total = 3,
                        icon = Icons.Filled.Dns,
                        onClick = { navigator.push(ProviderManagementScreen()) },
                    )
                }

                SettingsSection("缓存") {
                    SettingsClickItem(
                        title = "存储与清理",
                        subtitle = "封面缓存、下载目录等按音源隔离的数据都在这里管理",
                        index = 0,
                        total = 1,
                        icon = Icons.Filled.FolderOpen,
                        onClick = { navigator.push(StorageSettingsScreen()) },
                    )
                }

                if (cookieSaved) {
                    SettingsSection("危险操作") {
                        SettingsButtonItem(
                            text = if (confirmClear) "再次点击确认清除" else "清除当前音源的登录态",
                            subtitle = "只清这一个音源；已保存的账号仍在，可一键切回",
                            index = 0,
                            total = 1,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            onClick = {
                                if (!confirmClear) {
                                    confirmClear = true
                                } else {
                                    AppModel.cookieStorage.clear(providerId)
                                    AccountStore.setActive(providerId, null)
                                    AppModel.clearUserProfile()
                                    confirmClear = false
                                }
                            },
                        )
                    }
                }

                SettingsNote(
                    "资料展示：${profile?.nickname ?: "未登录"}。清除登录态不会动本地下载的歌曲。",
                )
            }
        }

        if (expanded) body(androidx.compose.ui.Modifier.fillMaxWidth()) else LegacyPageScaffold(
            title = "音源隔离",
            navigationIcon = {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        ) { pageModifier -> body(pageModifier) }
    }
}
