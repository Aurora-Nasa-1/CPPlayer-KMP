package cp.player.app.ui.screen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import cp.player.app.ui.component.LegacyPageScaffold
import cp.player.app.ui.component.LocalIsExpanded
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsDropdownItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SleepTimerDialog

/**
 * 播放与音质。
 *
 * ### 与重构前的差异
 *
 * 1. **删掉了「立即播放」开关。** 它的持久化键 `play_immediately` 全仓库只有本文件与
 *    已删除的「交互逻辑」页读写 —— **播放链路从不读它**。也就是说这个开关做什么都不影响，
 *    而它的副标题还描述了一个（未实现的）「只加入队列」行为。留着它比没有更糟：
 *    用户会以为自己关掉的东西生效了。
 * 2. **睡眠定时从「一个开关 + 一个下拉」收敛成一个入口行。** 那两者操作的是同一份
 *    运行时状态（`sleepAfterTrack` 既是开关的值，又是下拉的一个选项），
 *    而且下拉的「剩余 N 分钟」是算出来的、没法反选回原预设。
 *    现在点开的是**播放页同一个** [SleepTimerDialog] —— 单一事实源，两边状态一致。
 * 3. **音质副标题说清了降级行为**，不再只重复标题。
 */
class PlaybackSettingsScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val expanded = LocalIsExpanded.current
        val quality by AppModel.playbackQualityFlow.collectAsState()
        val playbackState by AppModel.playback.state.collectAsState()
        var showSleepTimer by remember { mutableStateOf(false) }

        val qualityIndex = AppModel.qualityOptions.indexOfFirst { it.first == quality }.coerceAtLeast(0)

        val body: @Composable (Modifier) -> Unit = { pageModifier ->
            SettingsPage(pageModifier) {
                SettingsSection("音质") {
                    SettingsDropdownItem(
                        title = "默认音质",
                        subtitle = "在线播放优先请求的音质；音源不提供时自动降级",
                        options = AppModel.qualityOptions.map { it.second },
                        selectedIndex = qualityIndex,
                        onSelect = { index ->
                            AppModel.qualityOptions.getOrNull(index)?.let { (level, _) ->
                                AppModel.setPlaybackQuality(level)
                            }
                        },
                        index = 0,
                        total = 1,
                    )
                }
                SettingsSection("睡眠定时") {
                    SettingsClickItem(
                        title = "定时关闭",
                        subtitle = when {
                            playbackState.sleepAfterTrack -> "播完当前歌曲后暂停"
                            playbackState.sleepTimerRemainingMs != null ->
                                "剩余 ${(playbackState.sleepTimerRemainingMs!! / 60_000L) + 1} 分钟"
                            else -> "未启用"
                        },
                        icon = Icons.Filled.Bedtime,
                        index = 0,
                        total = 1,
                        onClick = { showSleepTimer = true },
                    )
                }
                SettingsNote("这里与播放页的睡眠定时入口打开的是同一个对话框，状态始终一致。")
            }
        }

        if (showSleepTimer) {
            SleepTimerDialog(
                activeRemainingMs = playbackState.sleepTimerRemainingMs,
                afterTrackActive = playbackState.sleepAfterTrack,
                onSelect = AppModel.playback::setSleepTimer,
                onCancelTimer = AppModel.playback::cancelSleepTimer,
                onDismiss = { showSleepTimer = false },
            )
        }

        if (expanded) {
            body(Modifier.fillMaxWidth())
        } else {
            LegacyPageScaffold(
                title = "播放与音质",
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            ) { pageModifier -> body(pageModifier) }
        }
    }
}
