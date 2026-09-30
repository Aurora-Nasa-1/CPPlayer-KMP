package cp.player.app.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import cp.player.app.platform.shareText
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.PlayerMoreBottomSheet
import cp.player.app.ui.component.QueueBottomSheet
import cp.player.app.ui.model.CommentScreenModel
import cp.player.app.ui.util.SeekAvailability
import cp.player.app.ui.util.formatTimeMs
import cp.player.core.playback.AudioFormatInfo
import cp.player.core.playback.LyricsState
import cp.player.core.playback.RepeatMode
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 全屏播放页（KMP 版 — 1:1 视觉移植自原项目 `app/.../ui/screen/PlayerScreen.kt`）。
 *
 * 用 KMP 等效写法替换原版仅 Android 才有的 API：
 * - 无 `SharedTransitionScope` / `WindowCompat` / `LocalOnBackPressedDispatcherOwner` → 用普通 fade/offset、systemBars inset、Voyager `pop()`。
 * - 无 `SyncedLyrics` 第三方库 → 用 KMP `cp.player.core.playback.SyncedLyricLine`。
 * - 无 `WindowWidthSizeClass` → 永远走移动布局（即窄屏样式），desktop 与 mobile 同 UI。
 *
 * 三页 HorizontalPager：歌词 / 播放器 / 评论。播放器页可下拉关闭。
 * 已接入：收藏（likeSong）、加入歌单、睡眠定时、不感兴趣、随机播放、评论点赞。
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
    state: cp.player.core.playback.PlaybackUiState,
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
            cp.player.app.ui.component.CpLoadingIndicator(Modifier.size(40.dp))
        }
        return
    }

    // 状态：sheet / 弹窗
    var showQueueSheet by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var showSongInfo by remember { mutableStateOf(false) }
    var showTranslation by remember { mutableStateOf(true) }
    val playerScope = rememberCoroutineScope()
    val controller = AppModel.playback

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

    // 背景：竖向渐变。
    //
    // 两个色都取自 MaterialTheme，所以「跟随封面 / 跟随系统」换色时这里会一起变。
    // 深色分支原先硬编码 `#1B1B22 → #0A0A0F` —— 那是「KMP 还没有封面取色」时期的替代品，
    // 副作用是播放页会成为全应用唯一不跟随主题的表面。现在改用主题的容器色，
    // 深色下依然是「上略亮、下近黑」的走向。
    //
    // ⚠️ `remember` 必须把两个颜色本身作为 key：只 key `isDark` 的话，换色时 Brush 不会重建，
    // 背景会停在旧配色上而其余控件已经换色 —— 看起来就像「主题只换了一半」。
    val isDark = isSystemInDarkTheme()
    val surfaceTop = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh
    else MaterialTheme.colorScheme.surfaceVariant
    val surfaceBottom = if (isDark) MaterialTheme.colorScheme.surfaceContainerLowest
    else MaterialTheme.colorScheme.surface
    val bgBrush = remember(surfaceTop, surfaceBottom) {
        Brush.verticalGradient(listOf(surfaceTop, surfaceBottom))
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
                                    Text("", Modifier.fillMaxWidth())
                                } else {
                                    Column {
                                        Text(
                                            text = track.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = track.artist,
                                            style = MaterialTheme.typography.bodySmall,
                                            // 次级文字用 onSurfaceVariant，不要 onSurface + 手写 alpha。
                                            // 手写 alpha 在「跟随封面取色」时不可控：封面色一深，
                                            // 0.6 的次级文字就掉到对比度下限以下，而同一层级的文字
                                            // 在别的页面是 onSurfaceVariant —— 同级别、不同深浅。
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
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
                            AnimatedContent(
                                targetState = pagerState.currentPage,
                                label = "TopBarActions"
                            ) { page ->
                                when (page) {
                                    0 -> {
                                        IconButton(
                                            onClick = { showTranslation = !showTranslation },
                                            modifier = Modifier.padding(end = 8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Translate,
                                                contentDescription = "翻译",
                                                // 「开/关」是状态不是禁用：用 onSurface / onSurfaceVariant 两档，
                                                // 而不是 onSurface 再压 0.4 透明度 —— 后者在深色主题下几乎看不见。
                                                tint = if (showTranslation) MaterialTheme.colorScheme.onSurface
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    else -> {
                                        // 与桌面顶栏的账号 / 设置按钮同款（FilledIconButton +
                                        // surfaceContainerHighest），而不是自己往 IconButton 上贴一层
                                        // surfaceVariant 20% 的圆形背景 —— 那个「看起来像个按钮的按钮」
                                        // 是上一轮凑出来的，圆角和内边距都跟别处对不上。
                                        FilledIconButton(
                                            onClick = { showQueueSheet = true },
                                            modifier = Modifier.padding(end = 8.dp),
                                            colors = IconButtonDefaults.filledIconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                contentColor = MaterialTheme.colorScheme.onSurface,
                                            ),
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                                contentDescription = "队列",
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent
                        )
                    )
                }
            ) { inner ->
                Column(Modifier.fillMaxSize().padding(inner)) {
                    // 三页（歌词 / 播放器 / 评论）是靠左右滑切换的，可这三页彼此毫无关联——
                    // 不给任何可见提示的话，用户根本不知道还有评论和歌词，
                    // 这两个功能等于藏起来了。这里给一条可点的分段指示器：
                    // 选中段拉长 + 变主题色，跟着 pager 一起走。
                    PagerIndicator(
                        pageCount = 3,
                        currentPage = pagerState.currentPage,
                        onSelect = { page -> scope.launch { pagerState.animateScrollToPage(page) } },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) { page ->
                        when (page) {
                            0 -> LyricsPage(
                                state = state,
                                showTranslation = showTranslation,
                                onSeek = onSeek,
                                onRepeat = onRepeat,
                                isFavorite = state.isFavorite,
                                onLikeClick = { playerScope.launch { controller.toggleFavorite() } },
                            )
                            1 -> PlayerPage(
                                state = state,
                                animatedVisibilityScope = animatedVisibilityScope,
                                onTogglePlay = onTogglePlay,
                                onSkipNext = onSkipNext,
                                onSkipPrev = onSkipPrev,
                                onSeek = onSeek,
                                onRepeat = onRepeat,
                                onShuffle = onShuffle,
                                onLikeClick = { playerScope.launch { controller.toggleFavorite() } },
                                onMoreClick = { showMoreMenu = true },
                                showMoreMenu = showMoreMenu,
                                onDismissMore = { showMoreMenu = false },
                                onAddToPlaylist = {
                                    showMoreMenu = false
                                    showAddToPlaylist = true
                                },
                                onDownload = {
                                    showMoreMenu = false
                                    AppModel.downloadTrack(track)
                                },
                                onSleepTimer = {
                                    showMoreMenu = false
                                    showSleepTimer = true
                                },
                                onShare = {
                                    showMoreMenu = false
                                    shareText("${track.name} - ${track.artist}\nhttps://music.163.com/song?id=${runCatching { cp.player.core.music.CPMediaId.parse(track.id).resourceId }.getOrDefault(track.id)}")
                                },
                                onShowInfo = {
                                    showMoreMenu = false
                                    showSongInfo = true
                                },
                                onDislike = {
                                    showMoreMenu = false
                                    playerScope.launch {
                                        runCatching {
                                            val rawId = runCatching { cp.player.core.music.CPMediaId.parse(track.id).resourceId }.getOrDefault(track.id)
                                            AppModel.api.dislikeSong(rawId)
                                        }
                                        cp.player.app.ui.util.UiEvents.notify("已标记不感兴趣")
                                        controller.skipNext()
                                    }
                                },
                            )
                            2 -> CommentPage(track.id, "music")
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

    if (showAddToPlaylist) {
        cp.player.app.ui.component.AddToPlaylistSheet(
            trackId = track.id,
            onDismiss = { showAddToPlaylist = false },
        )
    }

    if (showSleepTimer) {
        cp.player.app.ui.component.SleepTimerDialog(
            activeRemainingMs = state.sleepTimerRemainingMs,
            afterTrackActive = state.sleepAfterTrack,
            onSelect = controller::setSleepTimer,
            onCancelTimer = controller::cancelSleepTimer,
            onDismiss = { showSleepTimer = false },
        )
    }

    if (showSongInfo) {
        SongInfoDialog(
            track = track,
            formatInfo = state.formatInfo,
            lyricsInfo = state.lyricsInfo,
            onDismiss = { showSongInfo = false },
        )
    }
}

// ============================== 歌词页 ==============================

@Composable
private fun LyricsPage(
    state: cp.player.core.playback.PlaybackUiState,
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
        // 浮动胶囊：**按内容宽度**收口并居中，而不是铺满整宽。
        // 原先这枚只有两个按钮却长满一行，`SpaceEvenly` 把它们推到 1/4 与 3/4 处，
        // 中间空出一大块 —— 看起来像「少了两个按钮」。
        // 换成与播放器页同款的 CpFloatingToolbar 之后，左右滑动换页时底部不再跳。
        cp.player.app.ui.component.CpFloatingToolbar(
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
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
            cp.player.app.ui.component.ExpressiveLikeButton(
                isFavorite = state.isFavorite,
                onClick = onLikeClick,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ============================== 播放器页 ==============================

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun androidx.compose.animation.SharedTransitionScope.PlayerPage(
    state: cp.player.core.playback.PlaybackUiState,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
    onTogglePlay: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onRepeat: () -> Unit,
    onShuffle: () -> Unit,
    onLikeClick: () -> Unit,
    onMoreClick: () -> Unit,
    showMoreMenu: Boolean,
    onDismissMore: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownload: () -> Unit,
    onSleepTimer: () -> Unit,
    onShare: () -> Unit,
    onShowInfo: () -> Unit,
    onDislike: () -> Unit,
) {
    val track = state.currentTrack ?: return

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 封面区域。
        //
        // Expressive 的「呼吸」感：播放时圆角收紧 + 阴影加深，暂停时松开 —— 都走主题的
        // spatial 动效（带回弹），所以切播放/暂停时封面会「弹」一下，而不是硬切。
        val coverCorner by animateDpAsState(
            targetValue = if (state.isPlaying) 22.dp else 34.dp,
            animationSpec = cp.player.app.ui.theme.CpMotion.spatialSlow(),
            label = "coverCorner",
        )
        val coverElevation by animateDpAsState(
            targetValue = if (state.isPlaying) 28.dp else 12.dp,
            animationSpec = cp.player.app.ui.theme.CpMotion.spatialSlow(),
            label = "coverElevation",
        )
        Box(
            Modifier.weight(1.2f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(coverCorner),
                modifier = Modifier.aspectRatio(1f).fillMaxWidth(0.95f)
                    .sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "cover-${track.id}"),
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                    .clip(RoundedCornerShape(coverCorner)),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shadowElevation = coverElevation,
            ) {
                if (!track.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = track.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // 空封面：与桌面播放页、迷你播放器共用同一套 Expressive 占位
                    // （primaryContainer→tertiaryContainer 渐变 + 持续变形的形状）。
                    // 原先这里是一个 128dp 的灰「♪」，桌面版是渐变 + 变形形状 ——
                    // 同一个「没有封面」在两端是两个应用。
                    cp.player.app.ui.component.CpCoverPlaceholder(
                        modifier = Modifier.fillMaxSize(),
                        corner = coverCorner,
                    )
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
                    // 同一处曾写 `onSurfaceVariant.copy(alpha = 0.8f)`：那是「比次级再淡一点」的
                    // 自创层级，M3 里没有这一档，跟顶栏的歌手名（onSurfaceVariant 原样）也不一致。
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "artist-${track.id}"),
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                )
            }
            cp.player.app.ui.component.ExpressiveLikeButton(
                isFavorite = state.isFavorite,
                onClick = onLikeClick,
            )
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

        // 底部工具行：随机 / 循环 / 睡眠定时 / 更多
        //
        // 收藏按钮**刻意不放在这里** —— 它已经在上方歌曲信息行（歌名右侧）出现过一次，
        // 同一屏放两个红心是纯重复。腾出来的位置给了睡眠定时：这个功能原先只藏在
        // more 弹层里，开完就没人知道自己开了，而它恰恰是最需要持续可见的倒计时状态。
        //
        // 容器用 CpFloatingToolbar（按内容宽度的浮动胶囊），**不再铺满整宽**：
        // 上面那枚主控件胶囊已经是整宽的了，两枚等宽圆角条上下叠着，读起来像两条工具栏，
        // 主命令与次级工具的层级全丢。收成浮动胶囊之后「大胶囊=主命令、小胶囊=次级工具」
        // 一眼可辨，也顺带把两页底部统一成了同一个组件。
        cp.player.app.ui.component.CpFloatingToolbar(
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
        ) {
            cp.player.app.ui.component.CpModeToggle(
                active = state.shuffleEnabled,
                onClick = onShuffle,
                icon = Icons.Filled.Shuffle,
                label = "随机播放",
            )
            cp.player.app.ui.component.CpModeToggle(
                active = state.repeatMode != RepeatMode.OFF,
                onClick = onRepeat,
                icon = when (state.repeatMode) {
                    RepeatMode.ONE -> Icons.Filled.RepeatOne
                    else -> Icons.Filled.Repeat
                },
                label = "循环",
            )
            cp.player.app.ui.component.CpModeToggle(
                active = (state.sleepTimerRemainingMs ?: 0L) > 0 || state.sleepAfterTrack,
                onClick = onSleepTimer,
                icon = Icons.Filled.AccessAlarm,
                label = "睡眠定时",
                activeTint = MaterialTheme.colorScheme.primary,
            )
            Box {
                IconButton(onClick = onMoreClick) {
                    Icon(
                        Icons.Filled.MoreVert, "更多",
                        Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showMoreMenu) {
                    PlayerMoreBottomSheet(
                        track = track,
                        isDownloaded = AppModel.isDownloaded(track.id),
                        formatInfo = state.formatInfo,
                        lyricsInfo = state.lyricsInfo,
                        sleepAfterTrack = state.sleepAfterTrack,
                        sleepTimerRemainingMs = state.sleepTimerRemainingMs,
                        onDismiss = onDismissMore,
                        onAddToPlaylist = onAddToPlaylist,
                        onDownload = onDownload,
                        onSleepTimer = onSleepTimer,
                        onShare = onShare,
                        onShowInfo = onShowInfo,
                        onDislike = onDislike,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SongInfoDialog(
    track: cp.player.core.music.TrackSummary,
    formatInfo: AudioFormatInfo?,
    lyricsInfo: cp.player.core.model.LyricsInfo?,
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

@Composable
private fun PagerIndicator(
    pageCount: Int,
    currentPage: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(top = 2.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val selected = index == currentPage
            // 选中段从 7dp 拉到 22dp：长度是比颜色更强的状态信号，
            // 而且只有 spatial（带回弹）才有「弹过去」的感觉，用 tween 会像进度条在爬。
            val width by androidx.compose.animation.core.animateDpAsState(
                targetValue = if (selected) 22.dp else 7.dp,
                animationSpec = cp.player.app.ui.theme.CpMotion.spatialFast(),
                label = "pagerIndicatorWidth$index",
            )
            val color by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f),
                // 颜色必须走 **effects** 通道。以前这里是 spatialFast()，那是「位移 / 尺寸」
                // 的规格、**带回弹** —— 挂在颜色上会让小圆点在到位前先过冲再回落，
                // 看起来像颜色在抖。长度那一路（下面的 width）继续用 spatialFast，那才是对的。
                animationSpec = cp.player.app.ui.theme.CpMotion.effectsFast(),
                label = "pagerIndicatorColor$index",
            )
            // 外层是 28×24dp 的可点区域，内层才是 7dp 高的细段 ——
            // 只让细段可点的话，实际命中区小到几乎点不中。
            Surface(
                onClick = { onSelect(index) },
                shape = CircleShape,
                color = androidx.compose.ui.graphics.Color.Transparent,
                modifier = Modifier.width(28.dp).height(24.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.width(width).height(7.dp)
                            .background(color, CircleShape)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressRow(
    state: cp.player.core.playback.PlaybackUiState,
    onSeek: (Long) -> Unit,
) {
    val duration = state.durationMs.coerceAtLeast(0L)
    // 时长未知（流媒体元信息还没到、直播流）时滑条位置无法换算成绝对时间，
    // 拖出来必然是错的——保持禁用。但**必须说明原因**：静默失效会让用户
    // 分不清「我拖错了」和「这个音源拖不了」，观感就是「拖了没反应」。
    // 判定与标签统一走 SeekAvailability，不再各处各写一遍。
    // 无损曲后台落盘期间同样禁用：此时引擎放的是不可定位的流，拖了也不会动。
    val seekable = SeekAvailability.isSeekable(duration, state.isLocalizing)
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        // 波形进度条（M3 Expressive 的标志性元素）。内部是「波形 + 透明 Slider 叠层」，
        // 拖动期间由 CpSeekBar 自己接管视觉，松手才回调 onSeek —— 这里不用再管拖拽状态。
        cp.player.app.ui.component.CpSeekBar(
            positionMs = state.positionMs,
            durationMs = duration,
            onSeek = onSeek,
            enabled = seekable,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 时间戳用 onSurfaceVariant，不再写 `onSurface.copy(alpha = 0.6f)`。
            // 手写 alpha 有两个代价：①「跟随封面取色」时对比度不可控 —— 封面色一深，
            // 次级文字就掉到可读性下限以下；② 同一层级的文字在不同页面深浅不一
            // （这里 0.6、顶栏 0.6、歌词页 0.6、评论区 0.7、迷你播放器 0.8），
            // 这正是「浅色主题看着不精致」最主要的来源。
            val formattedPosition = remember(state.positionMs / 1000) { formatTimeMs(state.positionMs) }
            Text(
                formattedPosition,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 中间槽位：睡眠定时倒计时优先，没开定时时回落到音质标签。
            // 原先这里只有一条 50% 透明的 labelSmall 音质文字 —— 那个位置等于不存在。
            // 睡眠定时是**持续状态**（可能跨好几首歌），必须常驻可见，
            // 否则用户根本想不起来自己设过，只能去 more 弹层里翻。
            val sleepRemainingMs = state.sleepTimerRemainingMs ?: 0L
            val sleepActive = sleepRemainingMs > 0 || state.sleepAfterTrack
            if (sleepActive) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.AccessAlarm,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (sleepRemainingMs > 0) "剩余 ${formatTimeMs(sleepRemainingMs)}"
                        else "本曲结束",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                state.formatInfo?.let { info ->
                    Text(
                        info.qualityLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                SeekAvailability.durationLabel(duration),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 禁用滑条必须给出原因：静默失效的观感就是「拖了没反应」。
        // 两种原因互斥呈现（落盘优先），由 SeekAvailability 统一决定。
        SeekAvailability.disabledReason(duration, state.isLocalizing)?.let { reason ->
            Text(
                reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
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
                cp.player.app.ui.component.CpLoadingIndicator(
                    Modifier.align(Alignment.Center).size(40.dp)
                )
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
                LazyScrollColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(state.comments) { comment ->
                        CommentItem(comment, onLike = { model.toggleLike(comment) })
                    }
                }
            }
        }
    }
}

@Composable
fun DesktopLyricsContent(
    state: cp.player.core.playback.PlaybackUiState,
    onSeek: (Long) -> Unit,
    onRepeat: () -> Unit,
    onLikeClick: () -> Unit,
) {
    LyricsPage(
        state = state,
        showTranslation = true,
        onSeek = onSeek,
        onRepeat = onRepeat,
        isFavorite = state.isFavorite,
        onLikeClick = onLikeClick,
    )
}

@Composable
fun DesktopCommentContent(trackId: String) {
    CommentPage(trackId, "music")
}

@Composable
private fun CommentItem(comment: cp.player.app.ui.model.Comment, onLike: () -> Unit) {
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
                androidx.compose.material3.IconButton(onClick = onLike, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (comment.liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                        contentDescription = if (comment.liked) "取消点赞" else "点赞",
                        modifier = Modifier.size(14.dp),
                        tint = if (comment.liked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
