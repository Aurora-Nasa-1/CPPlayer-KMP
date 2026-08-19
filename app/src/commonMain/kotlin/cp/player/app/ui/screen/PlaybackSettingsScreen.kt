package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.ui.component.LegacyListItem
import cp.player.app.ui.component.LegacyPageScaffold
import cp.player.kmp.playback.PlaybackController

class PlaybackSettingsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val expanded = cp.player.app.ui.component.LocalIsExpanded.current
        val quality by AppModel.playbackQualityFlow.collectAsState()
        val playbackState by AppModel.playback.state.collectAsState()
        var playImmediately by remember { mutableStateOf(playImmediately()) }

        val body: @Composable (Modifier) -> Unit = { pageModifier ->
            Column(
                pageModifier.verticalScroll(rememberScrollState()).padding(horizontal = if (expanded) 20.dp else 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsCard("在线播放") {
                    Text("默认音质", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppModel.qualityOptions.forEach { (level, label) ->
                            FilterChip(
                                selected = quality == level,
                                onClick = { AppModel.setPlaybackQuality(level) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                SettingsCard("播放行为") {
                    LegacyListItem(
                        index = 0,
                        total = 1,
                        onClick = { playImmediately = !playImmediately; setPlayImmediately(playImmediately) },
                        leadingContent = { Icon(Icons.Default.PlayArrow, null) },
                        headlineContent = { Text("立即播放") },
                        supportingContent = { Text("点击歌曲后立即开始播放，而不是只加入队列") },
                        trailingContent = {
                            Switch(
                                checked = playImmediately,
                                onCheckedChange = { playImmediately = it; setPlayImmediately(it) },
                            )
                        },
                    )
                }
                SettingsCard("睡眠定时") {
                    LegacyListItem(
                        index = 0,
                        total = 1,
                        onClick = { AppModel.playback.setSleepTimer(PlaybackController.SLEEP_AFTER_TRACK) },
                        leadingContent = { Icon(Icons.Default.Bedtime, null) },
                        headlineContent = { Text("播完当前歌曲后暂停") },
                        supportingContent = { Text(if (playbackState.sleepAfterTrack) "已启用" else "播放完当前歌曲后自动暂停") },
                        trailingContent = {
                            Switch(
                                checked = playbackState.sleepAfterTrack,
                                onCheckedChange = {
                                    if (it) AppModel.playback.setSleepTimer(PlaybackController.SLEEP_AFTER_TRACK)
                                    else AppModel.playback.cancelSleepTimer()
                                },
                            )
                        },
                    )
                    Text(
                        "定时播放时长可在播放页的睡眠定时入口中设置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (expanded) body(Modifier.fillMaxWidth()) else LegacyPageScaffold(
            title = "播放设置",
            navigationIcon = {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
            },
        ) { pageModifier -> body(pageModifier) }
    }
}

internal const val PLAY_IMMEDIATELY_KEY = "play_immediately"

internal fun playImmediately(): Boolean =
    AppModel.settings.getString(PLAY_IMMEDIATELY_KEY)?.toBooleanStrictOrNull() ?: true

internal fun setPlayImmediately(enabled: Boolean) {
    AppModel.settings.putString(PLAY_IMMEDIATELY_KEY, enabled.toString())
}

@Composable
private fun PlaybackSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
