package cp.player.app.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.foundation.layout.width
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.ui.component.QueueBottomSheet
import cp.player.app.ui.model.CommentScreenModel
import cp.player.app.ui.util.formatTimeMs
import cp.player.kmp.playback.LyricsState
import cp.player.kmp.playback.RepeatMode
import androidx.compose.foundation.lazy.items
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 全屏播放页（KMP 版 — 1:1 视觉移植自原项目 `app/.../ui/screen/PlayerScreen.kt`）。
 *
 * 用 KMP 等效写法替换原版仅 Android 才有的 API：
 * - 无 `SharedTransitionScope` / `WindowCompat` / `LocalOnBackPressedDispatcherOwner` → 用普通 fade/offset、systemBars inset、Voyager `pop()`。
 * - 无 `SyncedLyrics` 第三方库 → 用 KMP `cp.player.kmp.playback.SyncedLyricLine`。
 * - 无 `WindowWidthSizeClass` → 永远走移动布局（即窄屏样式），desktop 与 mobile 同 UI。
 *
 * 三页 HorizontalPager：歌词 / 播放器 / 评论占位。播放器页可下拉关闭。
 * 评论下载/睡眠定时器/推荐相似等高端功能以"待接入"占位出现。
 */
class PlayerScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class, androidx.compose.animation.ExperimentalSharedTransitionApi::class)
    @Composable
    override fun Content() {
        val controller = AppModel.playback
        val state by controller.state.collectAsState()
        val navigator = LocalNavigator.current
        val scope = rememberCoroutineScope()

        androidx.compose.animation.SharedTransitionLayout {
            androidx.compose.animation.AnimatedVisibility(visible = true) {
                PlayerScreenContent(
                    state = state,
                    animatedVisibilityScope = this,
                    onBack = { navigator?.pop() },
                    onTogglePlay = controller::togglePlayPause,
                    onSeek = controller::seekTo,
                    onSkipNext = controller::skipNext,
                    onSkipPrev = controller::skipPrevious,
                    onRepeat = {
                        controller.setRepeatMode(
                            when (state.repeatMode) {
                                RepeatMode.OFF -> RepeatMode.ALL
                                RepeatMode.ALL -> RepeatMode.ONE
                                RepeatMode.ONE -> RepeatMode.OFF
                            }
                        )
                    },
                    onShuffle = controller::toggleShuffle,
                    onClearQueue = controller::clearQueue,
                    onPlayAt = { idx -> scope.launch { controller.playAt(idx) } },
                    onRemoveQueue = { idx -> scope.launch { controller.removeQueueItem(idx) } },
                    onMoveQueue = { from, to -> scope.launch { controller.moveQueueItem(from, to) } },
                )
            }
        }
    }
}

// ==============================================================================

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
fun androidx.compose.animation.SharedTransitionScope.PlayerScreenContent(
    state: cp.player.kmp.playback.PlaybackUiState,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
    onRepeat: () -> Unit,
    onShuffle: () -> Unit,
    onClearQueue: () -> Unit,
    onPlayAt: (Int) -> Unit,
    onRemoveQueue: (Int) -> Unit,
    onMoveQueue: (Int, Int) -> Unit,
) {
    val track = state.currentTrack
    if (track == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // 状态：sheet / 弹窗
    var showQueueSheet by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showTranslation by remember { mutableStateOf(true) }

    // Pager 三页：0=歌词，1=播放器，2=评论
    val pagerState = rememberPagerState(initialPage = 1) { 3 }
    val scope = rememberCoroutineScope()

    // 下拉关闭手势（仅播放器页启用）
    var isOnPlayerPage by remember { mutableStateOf(true) }
    val offsetY = remember { Animatable(0f) }
    val maxDrag = with(LocalDensity.current) { 400.dp.toPx() }

    LaunchedEffect(pagerState.settledPage) {
        isOnPlayerPage = pagerState.settledPage == 1
        if (pagerState.settledPage != 1 && offsetY.value > 0f) offsetY.snapTo(0f)
    }

    // 背景：surfaceVariant→surface 渐变（KMP 无 Palette 提取封面色彩，故用 M3 主题色）
    val isDark = isSystemInDarkTheme()
    val surfaceTop = MaterialTheme.colorScheme.surfaceVariant
    val surfaceBottom = MaterialTheme.colorScheme.surface
    val bgBrush = remember(isDark) {
        if (isDark) Brush.verticalGradient(listOf(Color(0xFF1B1B22), Color(0xFF0A0A0F)))
        else Brush.verticalGradient(listOf(surfaceTop, surfaceBottom))
    }

    Box(
        Modifier
            .fillMaxSize()
            .sharedBounds(
                sharedContentState = rememberSharedContentState(key = "player-container"),
                animatedVisibilityScope = animatedVisibilityScope
            )
            .background(bgBrush)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isOnPlayerPage) Modifier.pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (offsetY.value > 0) {
                                    val target = if (offsetY.value > maxDrag / 2) maxDrag * 2 else 0f
                                    if (target > 0f) onBack()
                                    scope.launch {
                                        offsetY.animateTo(target, tween(250))
                                    }
                                }
                            },
                            onDragCancel = {
                                if (offsetY.value > 0) {
                                    scope.launch { offsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                                }
                            },
                            onVerticalDrag = { change, dragAmount ->
                                if (dragAmount > 0 || offsetY.value > 0) {
                                    val newOffset = (offsetY.value + dragAmount).coerceIn(0f, maxDrag * 2)
                                    scope.launch { offsetY.snapTo(newOffset) }
                                    change.consume()
                                }
                            },
                        )
                    } else Modifier
                )
                .offset { IntOffset(0, offsetY.value.roundToInt()) },
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        title = {
                            AnimatedContent(
                                targetState = pagerState.currentPage,
                                transitionSpec = {
                                    (slideInVertically { h -> h } + fadeIn()) togetherWith
                                        (slideOutVertically { h -> -h } + fadeOut())
                                },
                                label = "TopBarTitle",
                            ) { page ->
                                if (page == 1) {
                                    // 播放器页无标题
                                    Text("", Modifier.fillMaxWidth())
                                } else {
                                    Column {
                                        Text(
                                            track.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            track.artist,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "收起",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        },
                        actions = {
                            // 歌词页：翻译开关；其它页：队列按钮
                            if (pagerState.currentPage == 0) {
                                IconButton(onClick = { showTranslation = !showTranslation }) {
                                    Icon(
                                        imageVector = Icons.Filled.Translate,
                                        contentDescription = "翻译",
                                        tint = if (showTranslation) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    )
                                }
                            }
                            IconButton(onClick = { showQueueSheet = true }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                    contentDescription = "队列",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    )
                },
            ) { inner ->
                Box(Modifier.fillMaxSize().padding(inner)) {
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                        when (page) {
                            0 -> LyricsPage(
                                state = state,
                                showTranslation = showTranslation,
                                onSeek = onSeek,
                                onRepeat = onRepeat,
                                isFavorite = state.isFavorite,
                                onLikeClick = { /* 待接入：收藏 */ },
                            )
                            1 -> PlayerPage(
                                state = state,
                                animatedVisibilityScope = animatedVisibilityScope,
                                onTogglePlay = onTogglePlay,
                                onSkipNext = onSkipNext,
                                onSkipPrev = onSkipPrev,
                                onSeek = onSeek,
                                onRepeat = onRepeat,
                                onLikeClick = { /* 待接入：收藏 */ },
                                onMoreClick = { showMoreMenu = true },
                                showMoreMenu = showMoreMenu,
                                onDismissMore = { showMoreMenu = false },
                            )
                            2 -> track?.let {
                                CommentPage(it.id, "music")
                            } ?: CommentsPagePlaceholder()
                        }
                    }
                }
            }
        }
    }

    if (showQueueSheet) {
        QueueBottomSheet(
            queue = state.queue,
            currentIndex = state.currentIndex,
            onPlayAt = onPlayAt,
            onRemove = onRemoveQueue,
            onMove = onMoveQueue,
            onClear = onClearQueue,
            onClose = { showQueueSheet = false },
        )
    }
}

// ============================== 歌词页 ==============================

@Composable
private fun LyricsPage(
    state: cp.player.kmp.playback.PlaybackUiState,
    showTranslation: Boolean,
    onSeek: (Long) -> Unit,
    onRepeat: () -> Unit,
    isFavorite: Boolean,
    onLikeClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            cp.player.app.ui.component.LyricContent(
                state = state,
                showTranslation = showTranslation,
                onSeek = onSeek,
            )
        }
        Surface(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
IconButton(onClick = onRepeat) {
                    val icon = when (state.repeatMode) {
                        RepeatMode.ONE -> Icons.Filled.RepeatOne
                        else -> Icons.Filled.Repeat
                    }
                    Icon(
                        icon, "循环", Modifier.size(24.dp),
                        tint = if (state.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onLikeClick) {
                    AnimatedContent(targetState = state.isFavorite, label = "LikeAnimation") { fav ->
                        Icon(
                            imageVector = if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "Like",
                            modifier = Modifier.size(28.dp),
                            tint = if (fav) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ============================== 播放器页 ==============================

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun androidx.compose.animation.SharedTransitionScope.PlayerPage(
    state: cp.player.kmp.playback.PlaybackUiState,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
    onTogglePlay: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onRepeat: () -> Unit,
    onLikeClick: () -> Unit,
    onMoreClick: () -> Unit,
    showMoreMenu: Boolean,
    onDismissMore: () -> Unit,
) {
    val track = state.currentTrack ?: return

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 封面区域
        Box(
            Modifier.weight(1.2f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(32.dp),
                modifier = Modifier.aspectRatio(1f).fillMaxWidth(0.95f)
                    .sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "cover-${track.id}"),
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                    .clip(RoundedCornerShape(32.dp)),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shadowElevation = 16.dp,
            ) {
                if (!track.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = track.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.MusicNote, null, Modifier.size(128.dp))
                    }
                }
            }
        }

        // 歌曲信息行：标题 / 歌手 / 喜欢
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    track.name,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "title-${track.id}"),
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    track.artist,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "artist-${track.id}"),
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                )
            }
            IconButton(onClick = onLikeClick) {
                AnimatedContent(targetState = state.isFavorite, label = "LikeAnim") { fav ->
                    Icon(
                        imageVector = if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "Like",
                        modifier = Modifier.size(28.dp),
                        tint = if (fav) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 进度条（含中央音质 chip）
        ProgressRow(state, onSeek)

        // 主控件（prev / play-pause / next）
        cp.player.app.ui.component.PlaybackControls(
            isPlaying = state.isPlaying,
            isBuffering = state.isBuffering,
            onPlayPause = onTogglePlay,
            onSkipNext = onSkipNext,
            onSkipPrevious = onSkipPrev,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            sideButtonModifier = Modifier.weight(1f).height(72.dp),
            centerButtonModifier = Modifier.weight(1.2f).height(72.dp),
            sideIconSize = 36.dp,
            centerIconSize = 40.dp,
        )

        // 错误提示
        AnimatedVisibility(visible = !state.error.isNullOrBlank()) {
            Text(
                state.error ?: "",
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 底部工具行：repeat / like / more
        Surface(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onRepeat) {
                    val icon = when (state.repeatMode) {
                        RepeatMode.ONE -> Icons.Filled.RepeatOne
                        else -> Icons.Filled.Repeat
                    }
                    Icon(
                        icon, "循环", Modifier.size(24.dp),
                        tint = if (state.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onLikeClick) {
                    AnimatedContent(targetState = state.isFavorite, label = "LikeAnim") { fav ->
                        Icon(
                            imageVector = if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "Like",
                            modifier = Modifier.size(28.dp),
                            tint = if (fav) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Box {
                    IconButton(onClick = onMoreClick) {
                        Icon(
                            Icons.Filled.MoreVert, "更多",
                            Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = showMoreMenu, onDismissRequest = onDismissMore) {
                        DropdownMenuItem(
                            text = { Text("加入歌单") },
                            onClick = onDismissMore,
                        )
                        DropdownMenuItem(
                            text = { Text("下载") },
                            onClick = onDismissMore,
                        )
                        DropdownMenuItem(
                            text = { Text("睡眠定时") },
                            onClick = onDismissMore,
                        )
                        DropdownMenuItem(
                            text = { Text("不感兴趣") },
                            onClick = onDismissMore,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ProgressRow(
    state: cp.player.kmp.playback.PlaybackUiState,
    onSeek: (Long) -> Unit,
) {
    val duration = state.durationMs.coerceAtLeast(0)
    var seekValue by remember { androidx.compose.runtime.mutableStateOf<Float?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        androidx.compose.material3.Slider(
            value = seekValue ?: state.positionMs.toFloat(),
            onValueChange = { seekValue = it },
            onValueChangeFinished = {
                seekValue?.let { onSeek(it.toLong().coerceAtLeast(0L)); seekValue = null }
            },
            valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatTimeMs(state.positionMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            // 音质 chip
            state.formatInfo?.let { info ->
                Text(
                    "${info.qualityLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            Text(
                formatTimeMs(duration),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

// ============================== 评论页 ==============================

@Composable
private fun CommentPage(id: String, type: String) {
    val model = remember { cp.player.app.ui.model.CommentScreenModel(id, type) }
    val state by model.state.collectAsState(cp.player.app.ui.model.CommentUiState(id, type))

    Box(Modifier.fillMaxSize()) {
        when {
            state.loading && state.comments.isEmpty() -> {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            state.error != null -> {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("加载评论失败", style = MaterialTheme.typography.titleMedium)
                    Text(state.error!!, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    Button(onClick = model::loadComments, modifier = Modifier.padding(top = 16.dp)) {
                        Text("重试")
                    }
                }
            }
            state.comments.isEmpty() -> {
                Text("暂无评论", Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodyMedium)
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(state.comments) { comment ->
                        CommentItem(comment)
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentItem(comment: cp.player.app.ui.model.Comment) {
    Row(Modifier.fillMaxWidth()) {
        AsyncImage(
            model = comment.avatar,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(CircleShape),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    comment.user,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    Icons.Outlined.ThumbUp,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    comment.likedCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(comment.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(comment.content, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            androidx.compose.material3.HorizontalDivider(
                Modifier.padding(top = 12.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

// ============================== 评论页占位 ==============================

@Composable
private fun CommentsPagePlaceholder() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.AutoMirrored.Filled.QueueMusic,
                null,
                Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "评论与相似歌曲 — 待接入",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "本页将在后续版本对接 MusicApiService.getComments。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
        }
    }
}