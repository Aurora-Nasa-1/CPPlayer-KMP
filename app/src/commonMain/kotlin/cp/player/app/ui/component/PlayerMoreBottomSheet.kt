package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.shareText
import cp.player.app.ui.util.UiEvents
import cp.player.app.ui.util.formatTimeMs
import cp.player.app.ui.util.pushOrNotify
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
    onOpenSimilar: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownload: () -> Unit,
    onSleepTimer: () -> Unit,
    onShare: () -> Unit,
    onShowInfo: () -> Unit,
    onDislike: () -> Unit,
    onListenTogether: () -> Unit,
    onHandoff: () -> Unit,
    onLyricOffset: () -> Unit = {},
    /** 当前全局音质档位（落盘 level，不是展示名）—— 由 [PlayerMoreSheets] 从 AppModel 传入。 */
    qualityLevel: String = "",
    onSelectQuality: (String) -> Unit = {},
) {
    LegacyModalBottomSheet(onDismissRequest = onDismiss) {
        PlayerMoreContent(
            track = track,
            isDownloaded = isDownloaded,
            formatInfo = formatInfo,
            lyricsInfo = lyricsInfo,
            sleepAfterTrack = sleepAfterTrack,
            sleepTimerRemainingMs = sleepTimerRemainingMs,
            onDismiss = onDismiss,
            onOpenSimilar = onOpenSimilar,
            onAddToPlaylist = onAddToPlaylist,
            onDownload = onDownload,
            onSleepTimer = onSleepTimer,
            onShare = onShare,
            onShowInfo = onShowInfo,
            onDislike = onDislike,
            onListenTogether = onListenTogether,
            onHandoff = onHandoff,
            onLyricOffset = onLyricOffset,
            qualityLevel = qualityLevel,
            onSelectQuality = onSelectQuality,
            onClose = onDismiss,
        )
    }
}

/**
 * 「更多」操作面板内容本体。
 *
 * 既供移动端 [PlayerMoreBottomSheet] 作为底部抽屉承载，
 * 又供桌面端作为悬浮双栏面板使用。
 */
@Composable
fun PlayerMoreContent(
    track: TrackSummary,
    isDownloaded: Boolean,
    formatInfo: AudioFormatInfo?,
    lyricsInfo: LyricsInfo?,
    sleepAfterTrack: Boolean,
    sleepTimerRemainingMs: Long?,
    onDismiss: () -> Unit,
    onOpenSimilar: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownload: () -> Unit,
    onSleepTimer: () -> Unit,
    onShare: () -> Unit,
    onShowInfo: () -> Unit,
    onDislike: () -> Unit,
    onListenTogether: () -> Unit,
    onHandoff: () -> Unit,
    onLyricOffset: () -> Unit = {},
    qualityLevel: String = "",
    onSelectQuality: (String) -> Unit = {},
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val s = cpStrings()
    Column(
        // 可滚动：加入「音质」卡片后这一屏在 640dp 级的手机上会顶到窗口底，
        // 而 ModalBottomSheet 不会自己给内容套滚动 —— 不加就是**底部被裁掉**，
        // 用户看不到最后那张详情卡，也不知道下面还有东西。
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
            // PixelPlayer 风格顶部歌曲卡片：大圆角封面 + 歌名/艺术家 + 右侧圆形分享胶囊
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier.size(76.dp).aspectRatio(1f).clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!track.coverUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = track.coverUrl.resized(220),
                            contentDescription = null,
                            modifier = Modifier.size(76.dp).clip(RoundedCornerShape(20.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        CpCoverPlaceholder(
                            modifier = Modifier.fillMaxSize(),
                            corner = 20.dp,
                            animated = false,
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    CpText(
                        text = track.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    CpText(
                        text = track.artist + (track.album?.takeIf(String::isNotBlank)?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f),
                    modifier = Modifier.size(44.dp).clickable {
                        onShare()
                        onDismiss()
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = s.player.share,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (onClose != null) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f),
                        modifier = Modifier.size(44.dp).clickable { onClose() },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "关闭",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            // PixelPlayer 核心焦点行动点：探索相似歌曲推荐大胶囊卡片
            Surface(
                onClick = {
                    onOpenSimilar()
                    onDismiss()
                },
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        CpText(
                            "探索相似歌曲",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        CpText(
                            "基于在播曲目发现更多同风格好音乐",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // 动作胶囊按钮网格（PixelPlayer 风格流线型双列胶囊）
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PlayerPillButton(
                        modifier = Modifier.weight(1f),
                        text = s.player.addToPlaylist,
                        icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                        bgColor = MaterialTheme.colorScheme.secondaryContainer,
                        textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        onClick = {
                            onAddToPlaylist()
                            onDismiss()
                        },
                    )
                    PlayerPillButton(
                        modifier = Modifier.weight(1f),
                        text = if (isDownloaded) s.player.downloaded else s.player.download,
                        icon = if (isDownloaded) Icons.Filled.DownloadDone else Icons.Filled.Download,
                        bgColor = MaterialTheme.colorScheme.tertiaryContainer,
                        textColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        onClick = {
                            if (!isDownloaded) onDownload()
                            onDismiss()
                        },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PlayerPillButton(
                        modifier = Modifier.weight(1f),
                        text = when {
                            sleepAfterTrack -> s.player.sleepTimerAfterTrack()
                            sleepTimerRemainingMs != null ->
                                s.player.sleepTimerRemaining((sleepTimerRemainingMs / 60_000L) + 1)
                            else -> s.player.sleepTimer
                        },
                        icon = Icons.Filled.Timer,
                        bgColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        textColor = MaterialTheme.colorScheme.onSurface,
                        onClick = {
                            onSleepTimer()
                            onDismiss()
                        },
                    )
                    PlayerPillButton(
                        modifier = Modifier.weight(1f),
                        text = s.player.dislike,
                        icon = Icons.Filled.Block,
                        bgColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f),
                        textColor = MaterialTheme.colorScheme.onErrorContainer,
                        onClick = {
                            onDislike()
                            onDismiss()
                        },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PlayerPillButton(
                        modifier = Modifier.weight(1f),
                        text = s.player.listenTogether,
                        icon = Icons.Filled.Headphones,
                        bgColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        textColor = MaterialTheme.colorScheme.onSurface,
                        onClick = {
                            onListenTogether()
                            onDismiss()
                        },
                    )
                    PlayerPillButton(
                        modifier = Modifier.weight(1f),
                        text = s.player.transferDevice,
                        icon = Icons.Filled.Cast,
                        bgColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        textColor = MaterialTheme.colorScheme.onSurface,
                        onClick = {
                            onHandoff()
                            onDismiss()
                        },
                    )
                }
            }

            // 音质：档位是**全局设置**（写盘 + 同步给控制器），不是「只对这首生效」。
            // 放在动作胶囊之后、详情卡之前：它是设置而不是动作，但比「查看元数据」更常改。
            if (qualityLevel.isNotBlank()) {
                QualitySectionCard(level = qualityLevel, onSelect = onSelectQuality)
            }

            // PixelPlayer 风格详情信息分段卡片
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().clickable {
                    onShowInfo()
                    onDismiss()
                },
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            CpText(
                                text = s.player.songInfo,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        CpText(
                            text = "查看完整元数据 →",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    // 详情小胶囊分段
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DetailChip(
                            icon = Icons.Filled.AccessAlarm,
                            label = formatTimeMs(track.durationMs),
                            modifier = Modifier.weight(1f),
                        )
                        val qualityLabel = formatInfo?.qualityLabel ?: formatInfo?.codecName ?: "标准音质"
                        DetailChip(
                            icon = Icons.Filled.GraphicEq,
                            label = qualityLabel,
                            modifier = Modifier.weight(1f),
                        )
                        val lyricStatus = if (lyricsInfo?.hasWordLevel == true) "逐字歌词" else "动态歌词"
                        val currentOffset = cp.player.app.ui.util.LyricOffsetManager.getOffset(track.id)
                        val lyricLabel = if (currentOffset != 0L) {
                            val sign = if (currentOffset > 0) "+" else ""
                            "$lyricStatus (${sign}${currentOffset / 1000f}s)"
                        } else {
                            "$lyricStatus · 微调"
                        }
                        DetailChip(
                            icon = Icons.Filled.Lyrics,
                            label = lyricLabel,
                            modifier = Modifier.weight(1f).clickable {
                                onLyricOffset()
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }

@Composable
private fun DetailChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            CpText(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
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
        modifier = modifier.heightIn(min = 54.dp),
        shape = RoundedCornerShape(18.dp),
        color = bgColor,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(20.dp))
            CpText(
                text = text,
                color = textColor,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
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
    var showDevicePicker by mutableStateOf(false)
    var showSimilarSongs by mutableStateOf(false)
    var showLyricOffset by mutableStateOf(false)
}

@Composable
fun rememberPlayerMoreSheetState(): PlayerMoreSheetState = remember { PlayerMoreSheetState() }

/**
 * 「更多」弹层 + 四个二级弹窗（加入歌单 / 睡眠定时 / 歌曲信息 / 相似歌曲）的宿主，
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
    val s = cpStrings()
    // 音质是**全局设置**（落盘 + 同步给控制器），与迷你播放器那个 chip 读写同一份。
    // 订阅放在顶层而不是弹层分支里：它是纯设置流，不碰后端，随时可读。
    val qualityLevel by AppModel.playbackQualityFlow.collectAsState()

    // ⚠️ controller 只在弹层 / 弹窗真正打开的分支里解析，**不要**提到函数顶层：
    // AppModel.playback → MusicBackend.instance 在后端未 init 的环境（desktopTest 的
    // 离屏渲染、未来可能的 preview）会直接抛错 —— 那些环境同样会组合本组件
    // （showMoreMenu=false），急切求值等于让整个宿主变成「不可组合」。

    if (sheets.showMoreMenu) {
        val controller = AppModel.playback
        // CompositionLocal 只能在 @Composable 上下文里读，而 onClick 回调不是组合上下文。
        // 所以在这里（组合期）取出根 Navigator，回调里用捕获的引用。
        val rootNavigator = cp.player.app.ui.util.LocalRootNavigator.current
        PlayerMoreBottomSheet(
            track = track,
            isDownloaded = AppModel.isDownloaded(track.id),
            formatInfo = state.formatInfo,
            lyricsInfo = state.lyricsInfo,
            sleepAfterTrack = state.sleepAfterTrack,
            sleepTimerRemainingMs = state.sleepTimerRemainingMs,
            onDismiss = { sheets.showMoreMenu = false },
            onOpenSimilar = { sheets.showSimilarSongs = true },
            onAddToPlaylist = { sheets.showAddToPlaylist = true },
            onDownload = { AppModel.downloadTrack(track) },
            onSleepTimer = { sheets.showSleepTimer = true },
            onShare = {
                shareText("${track.name} - ${track.artist}\nhttps://music.163.com/song?id=${runCatching { CPMediaId.parse(track.id).resourceId }.getOrDefault(track.id)}")
            },
            onShowInfo = { sheets.showSongInfo = true },
            // 一起听是**整页**体验（要盖住窗口含侧栏），所以显式 push 到**根** Navigator，
            // 而不是 LocalNavigator —— 后者在桌面宽屏拿到的是内容区的内嵌 Navigator，
            // 会把整页塞进内容区、贴着侧栏渲染（见 LocalRootNavigator 的 KDoc）。
            onListenTogether = {
                rootNavigator.pushOrNotify(cp.player.app.ui.screen.ListenTogetherScreen())
            },
            onHandoff = { sheets.showDevicePicker = true },
            onDislike = {
                scope.launch {
                    val rawId = runCatching { CPMediaId.parse(track.id).resourceId }.getOrDefault(track.id)
                    runCatching { AppModel.musicRepository.dislikeSong(rawId) }
                    UiEvents.notify(s.player.disliked)
                    controller.skipNext()
                }
            },
            onLyricOffset = { sheets.showLyricOffset = true },
            qualityLevel = qualityLevel,
            onSelectQuality = AppModel::setPlaybackQuality,
        )
    }

    if (sheets.showSimilarSongs) {
        LegacyModalBottomSheet(
            onDismissRequest = { sheets.showSimilarSongs = false },
            skipPartiallyExpanded = true,
        ) {
            SimilarSongsPanel(
                seedTrackId = track.id,
                onBack = { sheets.showSimilarSongs = false },
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxHeight(0.85f),
            )
        }
    }

    if (sheets.showAddToPlaylist) {
        AddToPlaylistSheet(
            trackId = track.id,
            onDismiss = { sheets.showAddToPlaylist = false },
        )
    }

    if (sheets.showDevicePicker) {
        DevicePickerSheet(
            onDismiss = { sheets.showDevicePicker = false },
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

    if (sheets.showLyricOffset) {
        LyricOffsetDialog(
            track = track,
            onDismiss = { sheets.showLyricOffset = false },
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
    val s = cpStrings()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { CpText(s.player.songInfo, maxLines = 1) },
        text = {
            val info = formatInfo
            // 整段多行文本：CpText 默认 maxLines=1 会只剩第一行（歌曲名），必须放开。
            CpText(
                buildString {
                    // 整段进剪贴板，**逐行**都是文案 —— 中英的行标签与分隔符都不同
                    // （中文「歌手：」无空格，英文 "Artist: " 带空格），不能在调用方拼。
                    append(
                        s.player.shareTrack(
                            name = track.name,
                            artist = track.artist,
                            album = track.album ?: s.player.unknownAlbum,
                            duration = formatTimeMs(track.durationMs),
                            id = track.id,
                        ),
                    )
                    lyricsInfo?.let { lyric ->
                        append("\n\n")
                        append(
                            s.player.shareLyrics(
                                source = lyric.source,
                                format = lyric.format,
                                wordLevel = if (lyric.hasWordLevel) s.player.yes else s.player.no,
                                extra = buildString {
                                    if (lyric.hasTranslation) {
                                        append("\n${s.player.infoTranslation}: ${s.player.yes}")
                                    }
                                    if (lyric.hasPhonetic) {
                                        append("\n${s.player.infoPhonetic}: ${s.player.yes}")
                                    }
                                },
                            ),
                        )
                    }
                    if (info != null) {
                        append("\n\n")
                        append(
                            s.player.shareAudio(
                                codec = info.codecName?.takeIf(String::isNotBlank),
                                sampleRate = info.sampleRate?.takeIf { it > 0 }?.toString(),
                                bitDepth = info.bitDepth?.takeIf { it > 0 }?.toString(),
                                bitrate = info.bitrate?.takeIf { it > 0 }?.let { "${it / 1000}" },
                                channels = info.channels?.takeIf { it > 0 }?.toString(),
                            ),
                        )
                        // MIME 类型名不翻译（IETF 注册名，两端一致）。
                        info.mimeType?.takeIf(String::isNotBlank)?.let { append("\nMIME: ").append(it) }
                    }
                },
                maxLines = Int.MAX_VALUE,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { CpText(s.common.dismiss) }
        },
    )
}
