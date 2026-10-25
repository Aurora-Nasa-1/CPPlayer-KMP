package cp.player.app.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowScope
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.JbrWindowChrome
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.theme.LocalIsDarkTheme
import cp.player.app.ui.util.DesktopShell
import cp.player.app.ui.util.LyricOffsetManager
import cp.player.app.ui.util.resized
import cp.player.core.playback.LyricsState
import cp.player.core.playback.PlaybackUiState

/**
 * 桌面 Mini 迷你悬浮播放器窗口视图。
 *
 * 当用户将桌面应用缩小为 Mini 模式时呈现此界面：
 * - 紧凑小巧，支持全局置顶（始终在最前）；
 * - 顶部为拖拽区与窗口快捷动作（置顶/还原/关闭）；
 * - 中部展示封面、曲目信息、单行实时高亮歌词与播控按钮；
 * - 底部展示极简播放进度条。
 */
@Composable
fun DesktopMiniPlayer(
    windowScope: WindowScope,
    jbr: JbrWindowChrome.Controller? = null,
    onRestore: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = cpStrings()
    val isDark = LocalIsDarkTheme.current
    val density = LocalDensity.current
    val useJbr = jbr != null
    val nativeControlsWidth = if (useJbr) jbr.rightInsetDp.dp else 0.dp
    val bridge = remember(jbr) { jbr?.let { JbrHitTestBridge(it) } }

    val playbackState by AppModel.playback.state.collectAsState()
    val controller = AppModel.playback
    val track = playbackState.currentTrack

    // 入场动画的一次性开关（见 Surface 上的 graphicsLayer）。
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val enterScale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.94f,
        animationSpec = CpMotion.spatialSlow(),
        label = "miniPlayerEnterScale",
    )
    val enterAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = CpMotion.effects(),
        label = "miniPlayerEnterAlpha",
    )

    val trackId = track?.id.orEmpty()
    val lyricOffset = if (trackId.isNotBlank()) LyricOffsetManager.getOffset(trackId) else 0L
    val effectivePos = playbackState.positionMs + lyricOffset

    // 实时计算当前处于活动态的歌词单行
    val currentLyricText = remember(playbackState.lyrics, effectivePos) {
        val success = playbackState.lyrics as? LyricsState.Success
        if (success != null && success.lines.isNotEmpty()) {
            val line = success.lines.lastOrNull { effectivePos >= it.time } ?: success.lines.first()
            line.text.trim().ifEmpty { line.translation?.trim().orEmpty() }
        } else {
            ""
        }
    }

    Surface(
        modifier = modifier.fillMaxSize().jbrHitTest(bridge).graphicsLayer {
            // 缩进迷你窗时窗口尺寸是**系统级跳变**（AWT setBounds，做不了逐帧动画而不抖），
            // 所以让内容替它接住这一步：淡入 + 一点点从 0.94 放大回 1。
            // 只有第一次进入会跑（`entered` 不随播放状态变化），不会干扰后续互动。
            scaleX = enterScale
            scaleY = enterScale
            alpha = enterAlpha
        },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.22f else 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // 顶栏：拖拽手柄区（左/中）与窗口控制按钮组（右）
            Row(
                modifier = Modifier.fillMaxWidth().height(26.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // 左侧与中间可拖拽区域：标题与图标（不包含右侧按钮组，防止抢占点击手势或误触双击最大化）
                windowScope.WindowDraggableArea(
                    modifier = Modifier.weight(1f).height(26.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(16.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.MusicNote,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(10.dp),
                                )
                            }
                        }
                        CpText(
                            text = "CPPlayer Mini",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // ⚠️ 按钮组必须在 WindowDraggableArea 外面：
                // 拖拽区会吃掉/抢占点击手势，甚至在 Windows 上将置顶切换连击误判为标题栏双击最大化
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // 置顶切换按钮
                    IconButton(
                        onClick = { DesktopShell.isMiniPinned = !DesktopShell.isMiniPinned },
                        modifier = Modifier.size(24.dp).jbrClientZone(bridge, "mini_pin"),
                    ) {
                        Icon(
                            imageVector = if (DesktopShell.isMiniPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = if (DesktopShell.isMiniPinned) s.player.miniModeUnpin else s.player.miniModePin,
                            modifier = Modifier.size(14.dp),
                            tint = if (DesktopShell.isMiniPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // 还原完整主窗口
                    IconButton(
                        onClick = onRestore,
                        modifier = Modifier.size(24.dp).jbrClientZone(bridge, "mini_restore"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CropSquare,
                            contentDescription = s.player.miniModeRestore,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // 关闭/原生控制槽：
                    // 在 JBR 模式下，系统窗口控制组（右上角）由 JBR 原生渲染并处理关闭，
                    // 这里留出 nativeControlsWidth 空间避免与原生按钮叠放产生两套控制；
                    // 在无边框自绘模式下，渲染自绘关闭按钮。
                    if (useJbr) {
                        Spacer(Modifier.width(nativeControlsWidth))
                    } else {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "关闭",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // 中部主体：封面 + 歌曲信息与歌词 + 播放控制
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 封面
                val coverUrl = track?.coverUrl
                if (!coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = coverUrl.resized(240),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(50.dp)
                            .clip(MaterialTheme.shapes.medium),
                    )
                } else {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.size(50.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }

                // 歌曲标题、歌手与实时歌词
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    CpText(
                        text = track?.name ?: "未在播放",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        // 桌面迷你播放器的标题是这一屏唯一的焦点位：溢出时横向滚动，
                        // 暂停时停住（画面跟着声音一起停），与移动端迷你播放器一致。
                        emphasized = true,
                        marqueeEnabled = playbackState.isPlaying,
                    )
                    if (currentLyricText.isNotBlank()) {
                        CpText(
                            text = currentLyricText,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    } else {
                        CpText(
                            text = track?.artist ?: "CPPlayer 音乐播放器",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }

                // 播控按钮组（上一曲 / 播放暂停 / 下一曲）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    IconButton(
                        onClick = { controller.skipPrevious() },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipPrevious,
                            contentDescription = s.player.previousTrack,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    FilledIconButton(
                        onClick = { controller.togglePlayPause() },
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            imageVector = if (playbackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = s.player.playOrPause(playbackState.isPlaying),
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    IconButton(
                        onClick = { controller.skipNext() },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = s.player.nextTrack,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // 底部极简进度条
            val duration = playbackState.durationMs.coerceAtLeast(0L)
            val progress = if (duration > 0L) (playbackState.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
            CpLinearProgress(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(MaterialTheme.shapes.extraSmall),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
}
