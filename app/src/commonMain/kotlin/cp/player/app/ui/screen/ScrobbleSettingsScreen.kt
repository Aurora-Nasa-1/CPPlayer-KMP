package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.openUrl
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.component.SettingsTextInputItem
import cp.player.app.ui.component.rememberConfirmState
import cp.player.app.ui.component.settingsRowContainer
import cp.player.app.ui.util.UiEvents
import cp.player.app.ui.util.popOrNotify
import kotlinx.coroutines.launch

/**
 * 听歌打卡与外部同步设置页面（Last.fm 等音乐社交与记录平台）。
 */
class ScrobbleSettingsScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val s = cpStrings()
        val strings = s.scrobble
        val hub = AppModel.scrobbleHub
        val lastFm = hub.lastFm

        val hubState by hub.state.collectAsState()
        val lastFmStatus by lastFm.statusFlow.collectAsState()
        val scope = rememberCoroutineScope()
        val confirm = rememberConfirmState()

        var authPendingToken by remember { mutableStateOf<String?>(null) }
        var isAuthorizing by remember { mutableStateOf(false) }

        var customApiKey by remember { mutableStateOf(lastFm.customApiKey.orEmpty()) }
        var customSecret by remember { mutableStateOf(lastFm.customSecret.orEmpty()) }

        CpRouteScaffold(
            title = strings.screenTitle,
            onBack = { navigator.popOrNotify() },
        ) { pageModifier ->
            SettingsPage(pageModifier) {
                // 顶部说明卡
                SettingsNote(text = strings.screenNote)

                // 错误提示（若凭据失效或发生异常）
                lastFmStatus.lastError?.let { err ->
                    SettingsNote(text = err)
                }

                // ── 分组 1：打卡平台 ──
                SettingsSection(title = strings.sectionPlatforms) {
                    // Last.fm 卡片
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = settingsRowContainer(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    // 状态指示圆点
                                    Surface(
                                        modifier = Modifier.size(10.dp),
                                        shape = CircleShape,
                                        color = if (lastFmStatus.isAuthorized) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outlineVariant,
                                    ) {}
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        CpText(
                                            text = strings.lastFmTitle,
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        CpText(
                                            text = if (lastFmStatus.isAuthorized) {
                                                "@${lastFmStatus.username.orEmpty()} · ${strings.lastFmConnected}"
                                            } else {
                                                strings.lastFmDesc
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                if (lastFmStatus.isAuthorized) {
                                    OutlinedButton(
                                        onClick = {
                                            confirm.request(
                                                title = strings.lastFmDisconnectConfirmTitle,
                                                message = strings.lastFmDisconnectConfirmMessage,
                                                confirmLabel = strings.lastFmDisconnectBtn,
                                                destructive = true,
                                                onConfirm = { lastFm.disconnect() },
                                            )
                                        },
                                    ) {
                                        CpText(strings.lastFmDisconnectBtn)
                                    }
                                } else {
                                    Button(
                                        enabled = !isAuthorizing,
                                        onClick = {
                                            isAuthorizing = true
                                            scope.launch {
                                                lastFm.startAuthWebFlow()
                                                    .onSuccess { (token, url) ->
                                                        authPendingToken = token
                                                        openUrl(url)
                                                    }
                                                    .onFailure { err ->
                                                        val msg = err.message ?: strings.lastFmAuthFailToast
                                                        UiEvents.notify("发起授权失败: $msg")
                                                    }
                                                isAuthorizing = false
                                            }
                                        },
                                    ) {
                                        if (isAuthorizing) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                            )
                                            Spacer(Modifier.width(8.dp))
                                        } else {
                                            Icon(
                                                Icons.Filled.OpenInBrowser,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        CpText(strings.lastFmConnectBtn)
                                    }
                                }
                            }
                        }
                    }

                    // 预留更多平台扩展卡片
                    SettingsClickItem(
                        title = "ListenBrainz / Libre.fm",
                        subtitle = "即将支持更多开放打卡与自由音乐档案服务",
                        index = 0,
                        total = 1,
                        onClick = {},
                        mergeSemantics = false,
                    )
                }

                // ── 分组 2：同步选项 ──
                SettingsSection(title = strings.sectionFeatures) {
                    SettingsSwitchItem(
                        title = strings.enableScrobbleTitle,
                        subtitle = strings.enableScrobbleDesc,
                        checked = lastFm.isEnabled,
                        onCheckedChange = { lastFm.setEnabled(it) },
                        index = 0,
                        total = 4,
                    )
                    SettingsSwitchItem(
                        title = strings.nowPlayingTitle,
                        subtitle = strings.nowPlayingDesc,
                        checked = lastFm.nowPlayingEnabled,
                        onCheckedChange = { lastFm.setNowPlayingEnabled(it) },
                        index = 1,
                        total = 4,
                    )
                    SettingsSwitchItem(
                        title = strings.loveSyncTitle,
                        subtitle = strings.loveSyncDesc,
                        checked = lastFm.loveSyncEnabled,
                        onCheckedChange = { lastFm.setLoveSyncEnabled(it) },
                        index = 2,
                        total = 4,
                    )
                    SettingsSwitchItem(
                        title = strings.includeLocalTitle,
                        subtitle = strings.includeLocalDesc,
                        checked = lastFm.includeLocalTracks,
                        onCheckedChange = { lastFm.setIncludeLocalTracks(it) },
                        index = 3,
                        total = 4,
                    )
                }

                // ── 分组 3：离线待办队列 ──
                SettingsSection(title = strings.sectionQueue) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = settingsRowContainer(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    CpText(
                                        text = strings.todayScrobbles(hubState.todayScrobbledCount),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    CpText(
                                        text = if (hubState.pendingQueueSize > 0) {
                                            strings.pendingQueueCount(hubState.pendingQueueSize)
                                        } else {
                                            strings.queueEmpty
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (hubState.pendingQueueSize > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (hubState.pendingQueueSize > 0) {
                                        FilledTonalButton(
                                            onClick = {
                                                hub.flushPendingQueue()
                                                UiEvents.notify("已开始同步离线打卡队列")
                                            },
                                        ) {
                                            Icon(
                                                Icons.Filled.Sync,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            CpText(strings.flushQueueBtn)
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                confirm.request(
                                                    title = strings.clearQueueConfirmTitle,
                                                    message = strings.clearQueueConfirmMessage,
                                                    confirmLabel = strings.clearQueueBtn,
                                                    destructive = true,
                                                    onConfirm = { hub.clearPendingQueue() },
                                                )
                                            },
                                        ) {
                                            Icon(
                                                Icons.Filled.DeleteOutline,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 分组 4：高级设置 (自定义 API 凭据) ──
                SettingsSection(title = strings.sectionAdvanced) {
                    SettingsTextInputItem(
                        title = strings.customApiKeyTitle,
                        subtitle = strings.customCredsDesc,
                        value = customApiKey,
                        onCommit = {
                            customApiKey = it
                            lastFm.setCustomCredentials(it, customSecret)
                        },
                        validate = { null },
                        index = 0,
                        total = 2,
                        placeholder = strings.customApiKeyPlaceholder,
                    )
                    SettingsTextInputItem(
                        title = strings.customSecretTitle,
                        value = customSecret,
                        onCommit = {
                            customSecret = it
                            lastFm.setCustomCredentials(customApiKey, it)
                        },
                        validate = { null },
                        index = 1,
                        total = 2,
                        placeholder = strings.customSecretPlaceholder,
                    )

                    if (customApiKey.isNotBlank() || customSecret.isNotBlank()) {
                        TextButton(
                            onClick = {
                                customApiKey = ""
                                customSecret = ""
                                lastFm.setCustomCredentials(null, null)
                            },
                        ) {
                            CpText(strings.resetCustomCreds)
                        }
                    }
                }
            }
        }

        // 网页授权完成后等待确认的弹窗
        authPendingToken?.let { token ->
            AlertDialog(
                onDismissRequest = { authPendingToken = null },
                title = { CpText(strings.lastFmAuthDialogTitle) },
                text = { CpText(strings.lastFmAuthDialogMessage) },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch {
                                lastFm.finishAuth(token)
                                    .onSuccess {
                                        UiEvents.notify(strings.lastFmAuthSuccessToast)
                                        authPendingToken = null
                                    }
                                    .onFailure { err ->
                                        val msg = err.message ?: strings.lastFmAuthFailToast
                                        UiEvents.notify("授权未完成: $msg")
                                    }
                            }
                        },
                    ) {
                        CpText(strings.lastFmAuthDialogConfirm)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { authPendingToken = null }) {
                        CpText(strings.lastFmAuthDialogCancel)
                    }
                },
            )
        }

        // 统一确认对话框宿主（遵守 AGENTS.md 规范）
        CpConfirmHost(confirm)
    }
}
