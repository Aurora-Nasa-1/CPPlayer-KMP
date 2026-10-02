package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.platform.shareText
import cp.player.app.ui.util.UiEvents
import cp.player.app.ui.util.formatTimeMs
import cp.player.app.ui.util.resized
import cp.player.core.music.CPMediaId
import cp.player.core.music.TrackSummary
import cp.player.core.model.LyricsInfo
import cp.player.core.playback.AudioFormatInfo
import cp.player.core.playback.PlaybackUiState
import kotlinx.coroutines.launch

@Composable
fun PlayerMoreBottomSheet(
    track: TrackSummary,
    isDownloaded: Boolean,
    formatInfo: AudioFormatInfo?,
    lyricsInfo: LyricsInfo?,
    sleepAfterTrack: Boolean,
    sleepTimerRemainingMs: Long?,
    onDismiss: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownload: () -> Unit,
    onSleepTimer: () -> Unit,
    onShare: () -> Unit,
    onShowInfo: () -> Unit,
    onDislike: () -> Unit,
) {
    LegacyModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!track.coverUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = track.coverUrl.resized(200),
                            contentDescription = null,
                            modifier = Modifier.size(72.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = track.name,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(48.dp).clickable {
                        onShare()
                        onDismiss()
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = "分享",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PlayerPillButton(
                        modifier = Modifier.weight(1f),
                        text = "加入歌单",
                        icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                        bgColor = MaterialTheme.colorScheme.primaryContainer,
                        textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        onClick = {
                            onAddToPlaylist()
                            onDismiss()
                        },
                    )
                    PlayerPillButton(
                        modifier = Modifier.weight(1f),
                        text = if (isDownloaded) "已下载" else "下载",
                        icon = if (isDownloaded) Icons.Filled.DownloadDone else Icons.Filled.Download,
                        bgColor = MaterialTheme.colorScheme.secondaryContainer,
                        textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        onClick = {
                            if (!isDownloaded) onDownload()
                            onDismiss()
                        },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PlayerPillButton(
                        modifier = Modifier.weight(1f),
                        text = when {
                            sleepAfterTrack -> "睡眠定时 · 播完本曲"
                            sleepTimerRemainingMs != null -> "睡眠定时 · ${(sleepTimerRemainingMs / 60_000L) + 1} 分钟"
                            else -> "睡眠定时"
                        },
                        icon = Icons.Filled.Timer,
                        bgColor = MaterialTheme.colorScheme.tertiaryContainer,
                        textColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        onClick = {
                            onSleepTimer()
                            onDismiss()
                        },
                    )
                    PlayerPillButton(
                        modifier = Modifier.weight(1f),
                        text = "不感兴趣",
                        icon = Icons.Filled.Block,
                        bgColor = MaterialTheme.colorScheme.errorContainer,
                        textColor = MaterialTheme.colorScheme.onErrorContainer,
                        onClick = {
                            onDislike()
                            onDismiss()
                        },
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().clickable {
                    onShowInfo()
                    onDismiss()
                },
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "歌曲信息",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    Text(
                        text = "ID: ${track.id}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    track.album?.takeIf(String::isNotBlank)?.let {
                        Text(
                            text = "Album: $it",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    lyricsInfo?.let { info ->
                        Spacer(Modifier.size(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.size(12.dp))
                        Text(
                            text = "歌词信息",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("来源: ${info.source}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("格式: ${info.format}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = "逐字歌词: ${if (info.hasWordLevel) "支持" else "不支持"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (info.hasWordLevel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (info.hasTranslation) {
                            Text("翻译: 有", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        if (info.hasPhonetic) {
                            Text("音译: 有", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    formatInfo?.let { info ->
                        Spacer(Modifier.size(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.size(12.dp))
                        Text(
                            text = "音频格式",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.size(8.dp))
                        info.codecName?.takeIf(String::isNotBlank)?.let {
                            Text("编码: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        info.sampleRate?.takeIf { it > 0 }?.let {
                            Text("采样率: $it Hz", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        info.bitDepth?.takeIf { it > 0 }?.let {
                            Text("位深: $it bit", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        info.bitrate?.takeIf { it > 0 }?.let {
                            Text("码率: ${it / 1000} kbps", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        info.channels?.takeIf { it > 0 }?.let {
                            Text("声道: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerPillButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    bgColor: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = bgColor,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(22.dp))
            Text(
                text = text,
                color = textColor,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// =====================================================================
// 「更多」弹层的共享宿主（窄屏播放页 / 桌面宽屏播放页两处挂载）
// =====================================================================

/**
 * 「更多」弹层及其全部二级弹窗的开关状态。
 *
 * 由**调用方**实例化并持有：窄屏（`PlayerScreenContent`）与桌面宽屏
 * （`DesktopPlayerScreen`）是互斥切换的两套布局，各持一份与 `showQueueSheet` 同理，
 * 不做跨布局持久 —— 切换布局时未完成的弹层自然关闭，符合直觉。
 */
@Stable
class PlayerMoreSheetState {
    var showMoreMenu by mutableStateOf(false)
    var showAddToPlaylist by mutableStateOf(false)
    var showSleepTimer by mutableStateOf(false)
    var showSongInfo by mutableStateOf(false)
}

@Composable
fun rememberPlayerMoreSheetState(): PlayerMoreSheetState = remember { PlayerMoreSheetState() }

/**
 * 「更多」弹层 + 三个二级弹窗（加入歌单 / 睡眠定时 / 歌曲信息）的宿主，
 * 以及**全部动作实现**（下载 / 分享 / 不感兴趣）的唯一一份。
 *
 * 背景：这套东西原先内联在 `PlayerScreenContent` 的 `PlayerPage` 调用点里，
 * 桌面宽屏（`DesktopPlayerScreen`）作为**桌面默认形态**反而完全够不到这组功能
 * —— 详见 docs/history/PLAYER_MORE_MENU_PORT.md 缺口 A。收进共享宿主后两套布局
 * 复用同一实现，不会再各自长出一份然后漂移。
 *
 * 注意：`PlayerMoreBottomSheet` 内部每个动作按钮都会先回调动作、再自行 `onDismiss`，
 * 所以这里「开二级弹窗」的回调不需要先关菜单。
 */
@Composable
fun PlayerMoreSheets(
    state: PlaybackUiState,
    sheets: PlayerMoreSheetState,
) {
    val track = state.currentTrack ?: return
    val scope = rememberCoroutineScope()

    // ⚠️ controller 只在弹层 / 弹窗真正打开的分支里解析，**不要**提到函数顶层：
    // AppModel.playback → MusicBackend.instance 在后端未 init 的环境（desktopTest 的
    // 离屏渲染、未来可能的 preview）会直接抛错 —— 那些环境同样会组合本组件
    // （showMoreMenu=false），急切求值等于让整个宿主变成「不可组合」。

    if (sheets.showMoreMenu) {
        val controller = AppModel.playback
        PlayerMoreBottomSheet(
            track = track,
            isDownloaded = AppModel.isDownloaded(track.id),
            formatInfo = state.formatInfo,
            lyricsInfo = state.lyricsInfo,
            sleepAfterTrack = state.sleepAfterTrack,
            sleepTimerRemainingMs = state.sleepTimerRemainingMs,
            onDismiss = { sheets.showMoreMenu = false },
            onAddToPlaylist = { sheets.showAddToPlaylist = true },
            onDownload = { AppModel.downloadTrack(track) },
            onSleepTimer = { sheets.showSleepTimer = true },
            onShare = {
                shareText("${track.name} - ${track.artist}\nhttps://music.163.com/song?id=${runCatching { CPMediaId.parse(track.id).resourceId }.getOrDefault(track.id)}")
            },
            onShowInfo = { sheets.showSongInfo = true },
            onDislike = {
                scope.launch {
                    runCatching {
                        val rawId = runCatching { CPMediaId.parse(track.id).resourceId }.getOrDefault(track.id)
                        AppModel.api.dislikeSong(rawId)
                    }
                    UiEvents.notify("已标记不感兴趣")
                    controller.skipNext()
                }
            },
        )
    }

    if (sheets.showAddToPlaylist) {
        AddToPlaylistSheet(
            trackId = track.id,
            onDismiss = { sheets.showAddToPlaylist = false },
        )
    }

    if (sheets.showSleepTimer) {
        val controller = AppModel.playback
        SleepTimerDialog(
            activeRemainingMs = state.sleepTimerRemainingMs,
            afterTrackActive = state.sleepAfterTrack,
            onSelect = controller::setSleepTimer,
            onCancelTimer = controller::cancelSleepTimer,
            onDismiss = { sheets.showSleepTimer = false },
        )
    }

    if (sheets.showSongInfo) {
        SongInfoDialog(
            track = track,
            formatInfo = state.formatInfo,
            lyricsInfo = state.lyricsInfo,
            onDismiss = { sheets.showSongInfo = false },
        )
    }
}

/** 歌曲信息弹窗（「更多」弹层信息卡的展开版，含 MIME 行）。 */
@Composable
fun SongInfoDialog(
    track: TrackSummary,
    formatInfo: AudioFormatInfo?,
    lyricsInfo: LyricsInfo?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("歌曲信息", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            val info = formatInfo
            Text(
                buildString {
                    append("歌曲：").append(track.name)
                    append("\n歌手：").append(track.artist)
                    append("\n专辑：").append(track.album ?: "未知专辑")
                    append("\n时长：").append(formatTimeMs(track.durationMs))
                    append("\n歌曲 ID：").append(track.id)
                    lyricsInfo?.let { lyric ->
                        append("\n\n歌词信息")
                        append("\n来源：").append(lyric.source)
                        append("\n格式：").append(lyric.format)
                        append("\n逐字歌词：").append(if (lyric.hasWordLevel) "支持" else "不支持")
                        if (lyric.hasTranslation) append("\n翻译：有")
                        if (lyric.hasPhonetic) append("\n音译：有")
                    }
                    if (info != null) {
                        append("\n\n音频格式")
                        info.codecName?.takeIf(String::isNotBlank)?.let { append("\n编码：").append(it) }
                        info.sampleRate?.takeIf { it > 0 }?.let { append("\n采样率：").append(it).append(" Hz") }
                        info.bitDepth?.takeIf { it > 0 }?.let { append("\n位深：").append(it).append(" bit") }
                        info.bitrate?.takeIf { it > 0 }?.let { append("\n码率：").append(it / 1000).append(" kbps") }
                        info.channels?.takeIf { it > 0 }?.let { append("\n声道：").append(it) }
                        info.mimeType?.takeIf(String::isNotBlank)?.let { append("\nMIME：").append(it) }
                    }
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
