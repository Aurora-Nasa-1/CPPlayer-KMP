package cp.player.app.ui.component.video

import cp.player.app.ui.component.CpText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import cp.player.app.ui.component.CpCircularWavyProgressIndeterminate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.platform.openUrl
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cp.player.app.ui.theme.CpMotion
import cp.player.core.media.MediaQualityOption
import cp.player.core.media.VideoScalingMode
import cp.player.core.playback.video.VideoPlaybackController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 全功能跨平台视频播放器交互视图。
 *
 * 具备功能：
 * 1. 跨平台视频画面渲染（[VideoSurface]）；
 * 2. 自动隐藏的沉浸式操作遮罩（无操作 3 秒后淡出）；
 * 3. 屏幕手势：单击显隐控件、双击播放/暂停、横向拖拽 Seek 预览、右半屏纵向拖拽音量；
 * 4. 顶部操作栏：返回/关闭、标题、作者、画幅模式切换（FIT / CROP）；
 * 5. 底部控制栏：播放/暂停、进度滑条、时间戳、多清晰度切换菜单（1080P/720P/480P等）、全屏切换。
 */
@Composable
fun VideoPlayerView(
    controller: VideoPlaybackController,
    modifier: Modifier = Modifier,
    title: String? = null,
    isFullscreen: Boolean = false,
    onBackClick: (() -> Unit)? = null,
    onToggleFullscreen: (() -> Unit)? = null,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()

    var controlsVisible by remember { mutableStateOf(true) }
    var isDraggingSlider by remember { mutableStateOf(false) }
    var draggingProgressMs by remember { mutableLongStateOf(0L) }
    var qualityMenuExpanded by remember { mutableStateOf(false) }

    // 手势提示状态：音量调节提示
    var volumeIndicatorVisible by remember { mutableStateOf(false) }
    var currentVolumeValue by remember { mutableFloatStateOf(1f) }

    // 横向拖拽 Seek 预览状态
    var isHorizontalSeeking by remember { mutableStateOf(false) }
    var seekPreviewMs by remember { mutableLongStateOf(0L) }

    // 控制栏自动隐藏倒计时
    LaunchedEffect(controlsVisible, state.isPlaying, isDraggingSlider, isHorizontalSeeking) {
        if (controlsVisible && state.isPlaying && !isDraggingSlider && !isHorizontalSeeking) {
            delay(3500)
            controlsVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        controlsVisible = !controlsVisible
                    },
                    onDoubleTap = {
                        controller.togglePlayPause()
                    },
                )
            }
            .pointerInput(state.durationMs) {
                var totalDragX = 0f
                var totalDragY = 0f
                var isVerticalDrag = false
                var isHorizontalDrag = false
                var startPos = 0L

                detectDragGestures(
                    onDragStart = { offset ->
                        totalDragX = 0f
                        totalDragY = 0f
                        isVerticalDrag = false
                        isHorizontalDrag = false
                        startPos = state.positionMs
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        totalDragX += dragAmount.x
                        totalDragY += dragAmount.y

                        if (!isVerticalDrag && !isHorizontalDrag) {
                            if (abs(totalDragX) > 20f && abs(totalDragX) > abs(totalDragY)) {
                                isHorizontalDrag = true
                                isHorizontalSeeking = true
                            } else if (abs(totalDragY) > 20f && abs(totalDragY) > abs(totalDragX)) {
                                isVerticalDrag = true
                            }
                        }

                        if (isHorizontalDrag && state.durationMs > 0L) {
                            // 水平滑动 Seek：每 100 像素微调约 10 秒
                            val deltaMs = (totalDragX * 100).toLong()
                            seekPreviewMs = (startPos + deltaMs).coerceIn(0L, state.durationMs)
                        } else if (isVerticalDrag) {
                            // 垂直滑动：调节音量
                            val deltaVol = -dragAmount.y / 300f
                            val newVol = (state.volume + deltaVol).coerceIn(0f, 1f)
                            controller.setVolume(newVol)
                            currentVolumeValue = newVol
                            volumeIndicatorVisible = true
                        }
                    },
                    onDragEnd = {
                        if (isHorizontalDrag && state.durationMs > 0L) {
                            controller.seekTo(seekPreviewMs)
                            isHorizontalSeeking = false
                        }
                        if (isVerticalDrag) {
                            isVerticalDrag = false
                            scope.launch {
                                delay(1200)
                                volumeIndicatorVisible = false
                            }
                        }
                    },
                    onDragCancel = {
                        isHorizontalSeeking = false
                        isVerticalDrag = false
                        volumeIndicatorVisible = false
                    },
                )
            },
    ) {
        // 1. 底层封面海报背景（有封面时显示，提供电影质感暗色遮罩，避免桌面端黑屏）
        state.currentMedia?.coverUrl?.let { coverUrl ->
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.65f)),
                )
            }
        }

        // 2. 原生视频画面渲染（Android 原生 SurfaceView / 桌面端挂载 Canvas）
        VideoSurface(
            player = controller.platformPlayer,
            modifier = Modifier.fillMaxSize(),
            scalingMode = state.scalingMode,
        )

        // 3. 缓冲中旋转动画
        if (state.isBuffering) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CpCircularWavyProgressIndeterminate(
                    modifier = Modifier.size(52.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // 4. 错误提示
        if (state.errorMessage != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = state.errorMessage ?: "播放失败",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (onBackClick != null) {
                                FilledTonalButton(
                                    onClick = onBackClick,
                                    shape = RoundedCornerShape(50),
                                ) {
                                    Text("返回", fontSize = 13.sp)
                                }
                            }
                            FilledTonalButton(
                                onClick = { controller.retry() },
                                shape = RoundedCornerShape(50),
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = "重试", modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("重试", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        // 4. 音量调节提示气泡
        if (volumeIndicatorVisible) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val icon = if (currentVolumeValue > 0.05f) {
                            Icons.AutoMirrored.Filled.VolumeUp
                        } else {
                            Icons.AutoMirrored.Filled.VolumeDown
                        }
                        Icon(icon, contentDescription = "音量", tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${(currentVolumeValue * 100).toInt()}%",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                    }
                }
            }
        }

        // 5. 滑动手势 Seek 进度提示
        if (isHorizontalSeeking) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "${formatVideoMs(seekPreviewMs)} / ${formatVideoMs(state.durationMs)}",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                        )
                    }
                }
            }
        }

        // 6. 交互遮罩与控制条
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(animationSpec = CpMotion.effects()),
            exit = fadeOut(animationSpec = CpMotion.effects()),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 顶部深色半透明渐变
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(84.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)
                            )
                        ),
                )

                // 底部深色半透明渐变
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                            )
                        ),
                )

                // 顶部栏：返回、标题与画幅切换
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBackClick != null) {
                        IconButton(onClick = onBackClick) {
                            val backIcon = if (isFullscreen) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack
                            Icon(backIcon, contentDescription = "返回", tint = Color.White)
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    ) {
                        CpText(
                            text = title ?: state.currentMedia?.title ?: "视频播放",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        state.currentMedia?.artist?.takeIf { it.isNotBlank() }?.let { artist ->
                            CpText(
                                text = artist,
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                    }

                    // 画幅切换按钮（FIT / CROP）
                    IconButton(
                        onClick = {
                            val nextMode = if (state.scalingMode == VideoScalingMode.FIT) {
                                VideoScalingMode.CROP
                            } else {
                                VideoScalingMode.FIT
                            }
                            controller.setScalingMode(nextMode)
                        },
                    ) {
                        Icon(
                            Icons.Filled.AspectRatio,
                            contentDescription = "画幅模式",
                            tint = if (state.scalingMode == VideoScalingMode.CROP) MaterialTheme.colorScheme.primary else Color.White,
                        )
                    }

                    // 外部播放器打开（桌面端与系统播放器关联）
                    state.currentMedia?.url?.let { mediaUrl ->
                        IconButton(
                            onClick = {
                                val openTarget = if (mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://")) {
                                    mediaUrl
                                } else {
                                    "file:///$mediaUrl".replace('\\', '/')
                                }
                                openUrl(openTarget)
                            },
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "在系统播放器中打开",
                                tint = Color.White,
                            )
                        }
                    }
                }

                // 桌面端专属：原生播放器引导卡片（在控制栏显示时呈现）
                if (!isAndroidPlatform() && state.currentMedia != null) {
                    val clipboardManager = LocalClipboardManager.current
                    var copied by remember { mutableStateOf(false) }

                    LaunchedEffect(copied) {
                        if (copied) {
                            delay(2000)
                            copied = false
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 24.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Black.copy(alpha = 0.85f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CpText(
                                text = "正在播放: ${state.currentMedia?.title ?: "视频"}",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            Text(
                                text = "已解析画质: ${state.currentQuality?.label ?: "标准"} • Windows 建议使用系统播放器全硬解",
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 12.sp,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FilledTonalButton(
                                    onClick = {
                                        state.currentMedia?.url?.let { mediaUrl ->
                                            val openTarget = if (mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://")) {
                                                mediaUrl
                                            } else {
                                                "file:///$mediaUrl".replace('\\', '/')
                                            }
                                            openUrl(openTarget)
                                        }
                                    },
                                    shape = RoundedCornerShape(50),
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("系统播放器打开 (PotPlayer/推荐)", fontSize = 13.sp)
                                }

                                FilledTonalButton(
                                    onClick = {
                                        state.currentMedia?.url?.let { mediaUrl ->
                                            clipboardManager.setText(AnnotatedString(mediaUrl))
                                            copied = true
                                        }
                                    },
                                    shape = RoundedCornerShape(50),
                                ) {
                                    Text(if (copied) "已复制直链" else "复制播放直链", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }

                // 底部控制栏：播控、进度滑条、清晰度切换与全屏
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    // 进度条
                    val displayPos = if (isDraggingSlider) draggingProgressMs else state.positionMs
                    val totalDur = state.durationMs
                    val sliderValue = if (totalDur > 0L) (displayPos.toFloat() / totalDur).coerceIn(0f, 1f) else 0f

                    Slider(
                        value = sliderValue,
                        enabled = totalDur > 0L,
                        onValueChange = { frac ->
                            isDraggingSlider = true
                            draggingProgressMs = (frac * totalDur).toLong()
                        },
                        onValueChangeFinished = {
                            controller.seekTo(draggingProgressMs)
                            isDraggingSlider = false
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // 播放/暂停
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { controller.togglePlayPause() }) {
                                val icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow
                                Icon(icon, contentDescription = "播放/暂停", tint = Color.White, modifier = Modifier.size(28.dp))
                            }

                            // 播放时间 / 总时长
                            val durText = if (state.durationMs > 0L) formatVideoMs(state.durationMs) else "--:--"
                            Text(
                                text = "${formatVideoMs(state.positionMs)} / $durText",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }

                        // 右侧：清晰度切换与全屏
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 清晰度选择按钮
                            Box {
                                val qualityLabel = state.currentQuality?.label ?: "清晰度"
                                Surface(
                                    color = Color.White.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(6.dp),
                                    onClick = { qualityMenuExpanded = true },
                                    modifier = Modifier.padding(horizontal = 6.dp),
                                ) {
                                    Text(
                                        text = qualityLabel,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }

                                DropdownMenu(
                                    expanded = qualityMenuExpanded,
                                    onDismissRequest = { qualityMenuExpanded = false },
                                ) {
                                    state.availableQualities.forEach { option ->
                                        val isSelected = option.id == state.currentQuality?.id
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = option.label,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                )
                                            },
                                            onClick = {
                                                qualityMenuExpanded = false
                                                scope.launch {
                                                    controller.switchQuality(option)
                                                }
                                            },
                                        )
                                    }
                                }
                            }

                            // 全屏切换按钮
                            if (onToggleFullscreen != null) {
                                IconButton(onClick = onToggleFullscreen) {
                                    val fsIcon = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen
                                    Icon(fsIcon, contentDescription = "全屏切换", tint = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatVideoMs(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000).toInt()
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}
