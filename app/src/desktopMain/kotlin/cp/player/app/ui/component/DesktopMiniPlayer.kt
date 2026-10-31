package cp.player.app.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
 * 桌面 Mini 迷你悬浮播放器窗口视图 (Material 3 Expressive 风格)。
 *
 * 当用户将桌面应用缩小为 Mini 模式时呈现此界面：
 * - 紧凑小巧，支持全局置顶（始终在最前）；
 * - 遵循 M3 Expressive 设计规范：
 *   1. 封面带有播放状态驱动的圆角 Morphing（播放时 18dp，暂停时 10dp）；
 *   2. 空封面统一走 [CpCoverPlaceholder] 渐变占位块，避免生硬灰块；
 *   3. 封面角落常驻 [CpPlayingEqualizer] 动态跳动状态指示；
 *   4. 主播控采用 [CpPlayPauseButton]，拥有按压缩紧与状态形变反馈；
 *   5. 次级动作按钮（切歌、还原、置顶、关闭）全部接驳 [cpPressScale] 弹簧触感；
 *   6. 隐藏 Windows 原生三按钮，全面改用精致现代的 M3e 自绘标题栏控制组；
 *   7. 深度支持深浅色与封面动态取色微氛围渲染；
 *   8. 贴底进度条采用 M3 Expressive 规范 [CpLinearProgress]，呈现圆头与终止端点。
 */
@Composable
fun DesktopMiniPlayer(
    windowScope: WindowScope,
    jbr: JbrWindowChrome.Controller? = null,
    onRestore: () -> Unit,
    onMinimize: () -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = cpStrings()
    val isDark = LocalIsDarkTheme.current
    val density = LocalDensity.current
    val bridge = remember(jbr) { jbr?.let { JbrHitTestBridge(it) } }

    // 迷你模式下彻底隐藏 Windows 原生三按钮并同步深色模式
    LaunchedEffect(jbr, isDark) {
        jbr?.setControlsVisible(false)
        jbr?.setControlsDark(isDark)
    }

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

    // M3 Expressive: 封面随播放状态进行圆角 Morphing 动效
    val coverCorner by animateDpAsState(
        targetValue = if (playbackState.isPlaying) 18.dp else 10.dp,
        animationSpec = CpMotion.spatial(),
        label = "miniWindowCoverCornerMorph",
    )
    val coverShape = remember(coverCorner) { RoundedCornerShape(coverCorner) }

    Surface(
        modifier = modifier.fillMaxSize().jbrHitTest(bridge),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.35f else 0.5f),
        ),
    ) {
        // 动态取色微光背景：引入封面主色的微弱氛围渐变，深浅色自适应
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.08f else 0.05f),
                            Color.Transparent,
                        )
                    )
                )
                .graphicsLayer {
                    // 进场轻微微缩淡入动效在内层执行，保证外层 Surface 底色始终 100% 铺满窗口无缝隙
                    scaleX = enterScale
                    scaleY = enterScale
                    alpha = enterAlpha
                },
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // 顶栏：拖拽手柄区（左/中）与美化控制按钮组（右，彻底摆脱 Windows 原生三按钮）
                Row(
                    modifier = Modifier.fillMaxWidth().height(26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // 左侧与中间可拖拽区域：胶囊微徽标与标题
                    windowScope.WindowDraggableArea(
                        modifier = Modifier.weight(1f).height(26.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(18.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.MusicNote,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(11.dp),
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
                    // 右侧完全自绘的 M3 Expressive 控制按钮组（置顶 / 最小化 / 还原 / 关闭）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // 置顶切换按钮（采用 M3 Expressive Tonal 状态与弹簧触感）
                        val isPinned = DesktopShell.isMiniPinned
                        val pinBg by animateColorAsState(
                            targetValue = if (isPinned) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            animationSpec = CpMotion.effects(),
                            label = "miniPinBg",
                        )
                        val pinFg by animateColorAsState(
                            targetValue = if (isPinned) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            animationSpec = CpMotion.effects(),
                            label = "miniPinFg",
                        )
                        Surface(
                            onClick = { DesktopShell.isMiniPinned = !DesktopShell.isMiniPinned },
                            modifier = Modifier
                                .size(24.dp)
                                .cpPressScale()
                                .jbrClientZone(bridge, "mini_pin"),
                            shape = MaterialTheme.shapes.small,
                            color = pinBg,
                            contentColor = pinFg,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                    contentDescription = if (isPinned) s.player.miniModeUnpin else s.player.miniModePin,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }

                        // 最小化（收起到托盘 / 最小化）
                        MiniWindowActionButton(
                            icon = Icons.Filled.Remove,
                            contentDescription = "最小化",
                            onClick = onMinimize,
                            bridge = bridge,
                            key = "mini_minimize",
                        )

                        // 还原完整主窗口
                        MiniWindowActionButton(
                            icon = Icons.Filled.CropSquare,
                            contentDescription = s.player.miniModeRestore,
                            onClick = onRestore,
                            bridge = bridge,
                            key = "mini_restore",
                        )

                        // 关闭按钮（悬停 M3 危险色反馈）
                        MiniWindowActionButton(
                            icon = Icons.Filled.Close,
                            contentDescription = "关闭",
                            isDanger = true,
                            onClick = onClose,
                            bridge = bridge,
                            key = "mini_close",
                        )
                    }
                }

                // 中部主体：封面 + 歌曲信息与歌词 + 播控按钮
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 封面：支持 M3 Expressive 圆角 Morphing，占位块走全应用统一的渐变组件，并叠加常驻均衡器
                    val coverUrl = track?.coverUrl
                    val hasCover = !coverUrl.isNullOrBlank()
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(coverShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (hasCover) {
                            AsyncImage(
                                model = coverUrl.resized(240),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            CpCoverPlaceholder(
                                modifier = Modifier.fillMaxSize(),
                                corner = coverCorner,
                                animated = false,
                            )
                        }
                        // 常驻跳动/暂停三点均衡器
                        CpPlayingEqualizer(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(3.dp),
                            isPlaying = playbackState.isPlaying,
                        )
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

                    // 播控按钮组（上一曲 / M3 Expressive 主播放暂停键 / 下一曲）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        IconButton(
                            onClick = { controller.skipPrevious() },
                            modifier = Modifier
                                .size(32.dp)
                                .cpPressScale(),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipPrevious,
                                contentDescription = s.player.previousTrack,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // M3 Expressive 主播放键：圆角形变、按下回弹、交叉淡入淡出、触感震动反馈
                        CpPlayPauseButton(
                            isPlaying = playbackState.isPlaying,
                            onClick = { controller.togglePlayPause() },
                            size = 38.dp,
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            isLoading = playbackState.isBuffering,
                        )

                        IconButton(
                            onClick = { controller.skipNext() },
                            modifier = Modifier
                                .size(32.dp)
                                .cpPressScale(),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = s.player.nextTrack,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // 底部极简进度条：遵循 M3 Expressive 线性进度条规范（圆头与终止端点）
                val duration = playbackState.durationMs.coerceAtLeast(0L)
                val progress = if (duration > 0L) (playbackState.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
                CpLinearProgress(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
        }
    }
}

/**
 * 迷你窗口自绘操作按钮，支持 M3 Expressive 按压缩放与悬停色彩反馈。
 */
@Composable
private fun MiniWindowActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bridge: JbrHitTestBridge? = null,
    key: String = "",
    isDanger: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    val isPressed by interaction.collectIsPressedAsState()

    val normalBg = Color.Transparent
    val hoverBg = if (isDanger) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val targetBg = if (isHovered || isPressed) hoverBg else normalBg

    val normalFg = MaterialTheme.colorScheme.onSurfaceVariant
    val hoverFg = if (isDanger) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    val targetFg = if (isHovered || isPressed) hoverFg else normalFg

    val bg by animateColorAsState(targetBg, animationSpec = CpMotion.effects(), label = "miniBtnBg")
    val fg by animateColorAsState(targetFg, animationSpec = CpMotion.effects(), label = "miniBtnFg")

    Surface(
        onClick = onClick,
        modifier = modifier
            .size(24.dp)
            .cpPressScale()
            .then(if (key.isNotEmpty()) Modifier.jbrClientZone(bridge, key) else Modifier),
        shape = MaterialTheme.shapes.small,
        color = bg,
        contentColor = fg,
        interactionSource = interaction,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}


