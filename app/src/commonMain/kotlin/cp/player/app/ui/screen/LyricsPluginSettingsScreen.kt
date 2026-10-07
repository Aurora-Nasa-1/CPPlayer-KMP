package cp.player.app.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.rememberZipPicker
import cp.player.app.platform.sendPlatformToast
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.SettingsButtonItem
import cp.player.app.ui.component.SettingsLazyPage
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.component.rememberConfirmState
import cp.player.app.ui.util.popOrNotify
import cp.player.core.lyricsplugin.LyricsPluginService
import cp.player.core.lyricsplugin.LyricsPluginSourceInfo
import cp.player.core.lyricsplugin.PluginCapability
import kotlinx.coroutines.launch

/**
 * 歌词源插件管理页（Lyrico Plugin API 兼容）。
 *
 * ### 为什么值得单独一页
 * 插件是**用户显式选择**的外部歌词来源：默认全部停用，只有在这里打开开关后才会参与取词，
 * 也只有打开后才会联网。把「启用」与「来源说明」放在同一屏，用户能一眼看清自己在放什么进来。
 *
 * ### 交互约定
 * - 开关即时生效并落盘（`LyricsPluginService.setEnabled`）；
 * - 删除是破坏性操作，走 `CpConfirmHost` 二次确认；
 * - 内置插件不可删除（它是随宿主分发的第一方来源）。
 */
class LyricsPluginSettingsScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val s = cpStrings()
        val t = s.lyricsPlugin
        val service: LyricsPluginService = AppModel.backend.lyricsPlugins
        val scope = rememberCoroutineScope()
        val confirm = rememberConfirmState()

        var sources by remember { mutableStateOf<List<LyricsPluginSourceInfo>>(emptyList()) }

        val refresh: suspend () -> Unit = {
            sources = runCatching { service.listSources() }.getOrDefault(emptyList())
        }

        LaunchedEffect(Unit) { refresh() }

        val pickZip = rememberZipPicker(onPicked = { zipPath ->
            if (zipPath != null) {
                scope.launch {
                    val message = runCatching { service.importPluginZip(zipPath) }.fold(
                        onSuccess = { ids ->
                            if (ids.isEmpty()) t.importFailed else t.importSuccessCount(ids.size)
                        },
                        onFailure = { t.importFailed },
                    )
                    sendPlatformToast(message)
                    refresh()
                }
            }
        })

        CpRouteScaffold(
            title = t.screenTitle,
            onBack = { navigator.popOrNotify() },
        ) { pageModifier ->
            SettingsLazyPage(pageModifier) {
                item { SettingsNote(t.note) }

                item {
                    SettingsButtonItem(
                        text = t.importAction,
                        onClick = { pickZip() },
                        index = 0,
                        total = 1,
                        icon = Icons.Filled.FileUpload,
                    )
                }

                if (sources.isEmpty()) {
                    item { SettingsNote(t.emptyMessage) }
                } else {
                    sources.forEachIndexed { index, source ->
                        item(key = "src-${source.id}") {
                            SettingsSwitchItem(
                                title = source.name,
                                subtitle = pluginSubtitle(source, t),
                                checked = source.enabled,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        service.setEnabled(source.id, checked)
                                        refresh()
                                    }
                                },
                                index = index,
                                total = sources.size,
                                icon = Icons.Filled.Extension,
                            )
                        }
                        if (!source.bundled) {
                            item(key = "del-${source.id}") {
                                SettingsButtonItem(
                                    text = t.deleteAction,
                                    subtitle = source.name,
                                    onClick = {
                                        confirm.request(
                                            title = t.deleteConfirmTitle,
                                            message = t.deleteConfirmMessage(source.name),
                                            onConfirm = {
                                                scope.launch {
                                                    service.deletePlugin(source.id)
                                                    refresh()
                                                }
                                            },
                                        )
                                    },
                                    index = index,
                                    total = sources.size,
                                    icon = Icons.Filled.DeleteOutline,
                                    contentColor = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            CpConfirmHost(confirm)
        }
    }
}

/** `作者 · 版本 · 能力` —— 一行说清这个插件是谁、能做什么。 */
private fun pluginSubtitle(
    source: LyricsPluginSourceInfo,
    t: cp.player.app.i18n.LyricsPluginStrings,
): String {
    val capabilities = source.capabilities.mapNotNull { capability ->
        when (capability) {
            PluginCapability.SEARCH_SONGS -> t.capabilitySearchSongs
            PluginCapability.GET_LYRICS -> t.capabilityGetLyrics
            PluginCapability.SEARCH_COVERS -> t.capabilitySearchCovers
        }
    }.joinToString(" / ")
    val head = listOfNotNull(
        source.author.takeIf { it.isNotBlank() },
        source.version.takeIf { it.isNotBlank() }?.let { "v$it" },
        t.bundledBadge.takeIf { source.bundled },
    ).joinToString(" · ")
    return listOfNotNull(head.takeIf { it.isNotBlank() }, capabilities.takeIf { it.isNotBlank() })
        .joinToString(" — ")
}
