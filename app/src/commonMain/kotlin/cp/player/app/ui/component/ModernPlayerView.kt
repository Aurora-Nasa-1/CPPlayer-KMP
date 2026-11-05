package cp.player.app.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.feedback.CpHaptic
import cp.player.app.ui.feedback.LocalCpHaptics
import cp.player.app.ui.model.CommentScreenModel
import cp.player.app.ui.model.MobilePlayerCustomConfig
import cp.player.app.ui.screen.UserProfileScreen
import cp.player.app.ui.screen.VideoPlayerScreen
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.theme.LocalIsDarkTheme
import cp.player.app.ui.util.SeekAvailability
import cp.player.app.ui.util.formatTimeMs
import cp.player.app.ui.util.next
import cp.player.app.ui.util.pushOrNotify
import cp.player.core.playback.PlaybackUiState
import cp.player.core.playback.RepeatMode
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 全新单页手机端播放界面（Mobile Single-Page Player View）。
 *
 * M3 Expressive 深度优化与特性：
 * 1. 【单页整合】：取消旧版横向分页器，封面、歌曲信息、双行歌词预览、进度控制与操作栏同屏汇聚；
 * 2. 【夹心歌词】：歌词置于封面与播放控制之间，以 0.5+1+0.5 行露出，平滑融入逐字卡拉OK动效与触觉反馈；
 * 3. 【Apple Music 展开歌词】：点击歌词预览或快捷按钮，无缝展开为大屏沉浸式全量滚动歌词页；
 * 4. 【独立评论入口】：评论按钮独立于底栏，点击直接拉起高雅精致的底部评论面板，无需左右滑屏；
 * 5. 【高自定义度微调】：内置布局调节器，支持封面比例、圆角、空间景深、弥散环境光晕、封面滑动手势切歌、
 *    M3 Expressive 波形/平直进度条切换、歌词字号与对齐、按键缩放等全维度实时微调；
 * 6. 【平滑无感兼容】：随时一键切回旧版多页视图，兼顾不同用户习惯。
 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
fun androidx.compose.animation.SharedTransitionScope.ModernPlayerScreenContent(
    state: PlaybackUiState,
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
    val s = cpStrings()
    val track = state.currentTrack
    if (track == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (!state.error.isNullOrBlank()) {
                CpText(
                    state.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                )
            } else {
                CpLoadingIndicator(Modifier.size(40.dp))
            }
        }
        return
    }

    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val layoutConfig by AppModel.mobilePlayerCustomConfigFlow.collectAsState()
    val fluidBackground by AppModel.fluidBackgroundFlow.collectAsState()
    val isDark = LocalIsDarkTheme.current
    val haptics = LocalCpHaptics.current

    var showQueueSheet by remember { mutableStateOf(false) }
    var showCommentSheet by remember { mutableStateOf(false) }
    var showLayoutSettingsSheet by remember { mutableStateOf(false) }
    var isLyricsExpanded by remember { mutableStateOf(false) }
    var showTranslation by remember { mutableStateOf(true) }
    var showOffsetDialog by remember { mutableStateOf(false) }
    val moreSheets = rememberPlayerMoreSheetState()

    val onArtistClick: ((cp.player.core.music.ArtistSummary) -> Unit)? = remember(navigator) {
        navigator?.let { nav ->
            { artist -> nav.pushOrNotify(UserProfileScreen(artist.id, artist.name, artist.resourceId)) }
        }
    }

    val onChangeLyricsSource: (() -> Unit)? = remember(navigator) {
        navigator?.let { nav ->
            { nav.pushOrNotify(cp.player.app.ui.screen.LyricsPluginSettingsScreen()) }
        }
    }

    // 背景渐变
    val surfaceTop = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh
    else MaterialTheme.colorScheme.surfaceVariant
    val surfaceBottom = if (isDark) MaterialTheme.colorScheme.surfaceContainerLowest
    else MaterialTheme.colorScheme.surface
    val bgBrush = remember(surfaceTop, surfaceBottom) {
        Brush.verticalGradient(listOf(surfaceTop, surfaceBottom))
    }

    // 下拉关闭手势（非歌词展开态启用）
    val offsetY = remember { Animatable(0f) }
    val maxDrag = with(LocalDensity.current) { 380.dp.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            .sharedBounds(
                sharedContentState = rememberSharedContentState(key = "player-container"),
                animatedVisibilityScope = animatedVisibilityScope
            )
            .cpFluidBackground(enabled = fluidBackground, fallback = bgBrush)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (!isLyricsExpanded) Modifier.pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (offsetY.value > 0) {
                                    val target = if (offsetY.value > maxDrag / 2) maxDrag * 2 else 0f
                                    if (target > 0f) {
                                        haptics.perform(CpHaptic.Confirm)
                                        onBack()
                                    }
                                    scope.launch { offsetY.animateTo(target, tween(250)) }
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
            AnimatedContent(
                targetState = isLyricsExpanded,
                transitionSpec = {
                    (fadeIn(tween(260)) + slideInVertically(tween(300)) { it / 8 })
                        .togetherWith(fadeOut(tween(200)) + slideOutVertically(tween(240)) { -it / 8 })
                },
                label = "ModernPlayerViewTransition",
                modifier = Modifier.fillMaxSize(),
            ) { expanded ->
                if (expanded) {
                    // Apple Music 风格展开歌词全屏视界
                    AppleMusicExpandedLyricsView(
                        state = state,
                        track = track,
                        showTranslation = showTranslation,
                        onToggleTranslation = {
                            haptics.perform(CpHaptic.Tick)
                            showTranslation = !showTranslation
                        },
                        onSeek = onSeek,
                        onRepeat = onRepeat,
                        onShuffle = onShuffle,
                        onTogglePlay = onTogglePlay,
                        onSkipNext = onSkipNext,
                        onSkipPrev = onSkipPrev,
                        onLikeClick = { AppModel.toggleFavoriteOf(track.id) },
                        onCollapse = {
                            haptics.perform(CpHaptic.Tick)
                            isLyricsExpanded = false
                        },
                        onOpenComments = {
                            haptics.perform(CpHaptic.Tick)
                            showCommentSheet = true
                        },
                        onOpenQueue = {
                            haptics.perform(CpHaptic.Tick)
                            showQueueSheet = true
                        },
                        onOpenOffsetDialog = { showOffsetDialog = true },
                        onChangeSource = onChangeLyricsSource,
                    )
                } else {
                    // 单页面标准播放视界
                    ModernSinglePageLayout(
                        state = state,
                        track = track,
                        config = layoutConfig,
                        animatedVisibilityScope = animatedVisibilityScope,
                        onBack = onBack,
                        onTogglePlay = onTogglePlay,
                        onSeek = onSeek,
                        onSkipNext = onSkipNext,
                        onSkipPrev = onSkipPrev,
                        onRepeat = onRepeat,
                        onShuffle = onShuffle,
                        onLikeClick = { AppModel.toggleFavoriteOf(track.id) },
                        onArtistClick = onArtistClick,
                        onExpandLyrics = {
                            haptics.perform(CpHaptic.Tick)
                            isLyricsExpanded = true
                        },
                        onOpenComments = {
                            haptics.perform(CpHaptic.Tick)
                            showCommentSheet = true
                        },
                        onOpenQueue = {
                            haptics.perform(CpHaptic.Tick)
                            showQueueSheet = true
                        },
                        onOpenLayoutSettings = {
                            haptics.perform(CpHaptic.Tick)
                            showLayoutSettingsSheet = true
                        },
                        onOpenSongInfo = { moreSheets.showSongInfo = true },
                        onMoreClick = { moreSheets.showMoreMenu = true },
                        onSleepTimer = {
                            moreSheets.showMoreMenu = false
                            moreSheets.showSleepTimer = true
                        },
                    )
                }
            }
        }
    }

    // 独立评论弹层抽屉
    if (showCommentSheet) {
        ModernCommentBottomSheet(
            trackId = track.id,
            onClose = { showCommentSheet = false },
        )
    }

    // 播放队列弹层
    if (showQueueSheet) {
        QueueBottomSheet(
            queue = state.queue,
            currentIndex = state.currentIndex,
            onPlayAt = onPlayAt,
            onRemove = onRemoveQueue,
            onMove = onMoveQueue,
            onClear = onClearQueue,
            onClose = { showQueueSheet = false },
            onOpenSimilar = {
                showQueueSheet = false
                moreSheets.showSimilarSongs = true
            },
        )
    }

    // 界面布局自定义微调弹层
    if (showLayoutSettingsSheet) {
        PlayerLayoutCustomizationSheet(
            config = layoutConfig,
            onUpdateConfig = { transform -> AppModel.updateMobilePlayerCustomConfig(transform) },
            onResetConfig = { AppModel.resetMobilePlayerCustomConfig() },
            onDismiss = { showLayoutSettingsSheet = false },
        )
    }

    // 歌词偏移微调弹窗
    if (showOffsetDialog) {
        LyricOffsetDialog(
            track = track,
            onDismiss = { showOffsetDialog = false },
        )
    }

    // 更多菜单
    PlayerMoreSheets(state = state, sheets = moreSheets)
}

/**
 * 单页式主播放界面内容排版。
 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun androidx.compose.animation.SharedTransitionScope.ModernSinglePageLayout(
    state: PlaybackUiState,
    track: cp.player.core.music.TrackSummary,
    config: MobilePlayerCustomConfig,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
    onRepeat: () -> Unit,
    onShuffle: () -> Unit,
    onLikeClick: () -> Unit,
    onArtistClick: ((cp.player.core.music.ArtistSummary) -> Unit)?,
    onExpandLyrics: () -> Unit,
    onOpenComments: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenLayoutSettings: () -> Unit,
    onOpenSongInfo: () -> Unit,
    onMoreClick: () -> Unit,
    onSleepTimer: () -> Unit,
) {
    val s = cpStrings()
    val isDark = LocalIsDarkTheme.current
    val haptics = LocalCpHaptics.current

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    // 顶栏轻量状态药丸（支持触觉与弹性按压反馈）
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = if (isDark) 0.35f else 0.45f),
                        border = BorderStroke(
                            0.5.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.15f else 0.25f)
                        ),
                        modifier = Modifier
                            .clip(CircleShape)
                            .cpPressScale(pressedScale = 0.95f)
                            .clickable(onClick = onExpandLyrics),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Filled.Lyrics,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            CpText(
                                text = "单页播放",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = s.player.collapse,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    // 布局微调入口
                    IconButton(onClick = onOpenLayoutSettings) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = "界面布局微调",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 队列
                    FilledIconButton(
                        onClick = onOpenQueue,
                        modifier = Modifier.padding(end = 8.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = if (isDark) 0.6f else 0.8f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = s.player.queue,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                )
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 18.dp)
                .padding(bottom = 12.dp)
        ) {
            val screenHeight = maxHeight
            val isUltraCompact = screenHeight < 620.dp

            // 根据整屏可用高度分配封面预算，预留出歌曲信息、双行歌词、进度条、主控制与底栏高度
            val bottomReservedBudget = when {
                isUltraCompact -> 240.dp
                screenHeight < 740.dp -> 275.dp
                else -> 310.dp
            }
            val maxCoverHeight = (screenHeight - bottomReservedBudget).coerceAtLeast(150.dp)
            val baseCoverWidth = maxWidth * config.coverScale
            val coverSize = minOf(baseCoverWidth, maxCoverHeight)

            val baseCorner = config.coverCornerDp.toFloat()
            val coverCorner by animateDpAsState(
                targetValue = if (state.isPlaying) baseCorner.dp else (baseCorner + 8).dp,
                animationSpec = CpMotion.spatialSlow(),
                label = "coverCorner",
            )
            val coverElevation by animateDpAsState(
                targetValue = if (state.isPlaying) config.coverElevationDp.dp else (config.coverElevationDp / 2).dp,
                animationSpec = CpMotion.spatialSlow(),
                label = "coverElevation",
            )
            val coverScale by animateFloatAsState(
                targetValue = if (state.isPlaying) 1f else 0.94f,
                animationSpec = tween(260),
                label = "coverScale",
            )

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // 1. 封面区域（支持弥散环境光晕 + 左右滑动切歌）
                Box(contentAlignment = Alignment.Center) {
                    if (config.ambientGlow && !track.coverUrl.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .size(coverSize * 0.95f)
                                .graphicsLayer {
                                    scaleX = coverScale * 1.10f
                                    scaleY = coverScale * 1.10f
                                    alpha = if (state.isPlaying) 0.55f else 0.28f
                                }
                                .clip(RoundedCornerShape(coverCorner + 8.dp))
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.65f else 0.45f),
                                            Color.Transparent,
                                        )
                                    )
                                )
                        )
                    }

                    var horizontalDragOffset by remember { mutableFloatStateOf(0f) }

                    Surface(
                        shape = RoundedCornerShape(coverCorner),
                        modifier = Modifier
                            .size(coverSize)
                            .aspectRatio(1f)
                            .sharedBounds(
                                sharedContentState = rememberSharedContentState(key = "cover-${track.id}"),
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                            .graphicsLayer {
                                scaleX = coverScale
                                scaleY = coverScale
                                translationX = horizontalDragOffset * 0.22f
                            }
                            .clip(RoundedCornerShape(coverCorner))
                            .then(
                                if (config.coverSwipeGesture) {
                                    Modifier.pointerInput(track.id) {
                                        detectHorizontalDragGestures(
                                            onDragEnd = {
                                                if (horizontalDragOffset < -55f) {
                                                    haptics.perform(CpHaptic.Confirm)
                                                    onSkipNext()
                                                } else if (horizontalDragOffset > 55f) {
                                                    haptics.perform(CpHaptic.Confirm)
                                                    onSkipPrev()
                                                }
                                                horizontalDragOffset = 0f
                                            },
                                            onDragCancel = { horizontalDragOffset = 0f },
                                            onHorizontalDrag = { change, dragAmount ->
                                                horizontalDragOffset = (horizontalDragOffset + dragAmount).coerceIn(-160f, 160f)
                                                change.consume()
                                            }
                                        )
                                    }
                                } else Modifier
                            ),
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
                            CpCoverPlaceholder(
                                modifier = Modifier.fillMaxSize(),
                                corner = coverCorner,
                            )
                        }
                    }
                }

                // 2. 歌曲信息行（歌名、歌手、喜欢）
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        CpText(
                            text = track.name,
                            style = if (isUltraCompact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurface,
                            emphasized = true,
                            marqueeEnabled = state.isPlaying,
                            modifier = Modifier.sharedBounds(
                                sharedContentState = rememberSharedContentState(key = "title-${track.id}"),
                                animatedVisibilityScope = animatedVisibilityScope,
                            )
                        )
                        Spacer(Modifier.height(1.dp))
                        TrackArtistText(
                            track = track,
                            onArtistClick = onArtistClick,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.sharedBounds(
                                sharedContentState = rememberSharedContentState(key = "artist-${track.id}"),
                                animatedVisibilityScope = animatedVisibilityScope,
                            )
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        track.mvId?.takeIf { it > 0 }?.let { mvId ->
                            val nav = LocalNavigator.current
                            IconButton(onClick = { nav?.push(VideoPlayerScreen("netease://mv/$mvId")) }) {
                                Icon(
                                    Icons.Filled.SmartDisplay,
                                    contentDescription = "播放 MV",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        ExpressiveLikeButton(
                            isFavorite = state.isFavorite,
                            onClick = onLikeClick,
                        )
                    }
                }

                // 3. 夹在封面与播放控制之间的两行歌词预览（0.5 + 1 + 0.5）
                InlineLyricsPreview(
                    lyricsState = state.lyrics,
                    positionMs = state.positionMs,
                    isPlaying = state.isPlaying,
                    config = config,
                    trackId = track.id,
                    onClick = onExpandLyrics,
                )

                // 4. 进度条及时间戳（支持 M3E 波形 / 经典平直进度条切换）
                ModernProgressSection(
                    state = state,
                    config = config,
                    onSeek = onSeek,
                    onOpenSongInfo = onOpenSongInfo,
                )

                // 5. 播放控制按键
                val baseControlsHeight = (64 * config.controlsScale).dp
                val sideIconSize = (32 * config.controlsScale).dp
                val centerIconSize = (38 * config.controlsScale).dp

                PlaybackControls(
                    isPlaying = state.isPlaying,
                    isBuffering = state.isBuffering,
                    onPlayPause = onTogglePlay,
                    onSkipNext = onSkipNext,
                    onSkipPrevious = onSkipPrev,
                    modifier = Modifier.fillMaxWidth(),
                    sideButtonModifier = Modifier.height(baseControlsHeight),
                    centerButtonModifier = Modifier.height(baseControlsHeight),
                    sideWeight = 1f,
                    centerWeight = 1.25f,
                    sideIconSize = sideIconSize,
                    centerIconSize = centerIconSize,
                )

                // 6. 底部工具条（独立评论按钮、随机、循环、定时、歌词展开、更多）
                CpFloatingToolbar(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    // 独立评论按钮
                    IconButton(onClick = onOpenComments) {
                        Icon(
                            Icons.AutoMirrored.Filled.Comment,
                            contentDescription = "歌曲评论",
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CpModeToggle(
                        active = state.shuffleEnabled,
                        onClick = onShuffle,
                        icon = Icons.Filled.Shuffle,
                        label = s.player.shuffle,
                    )
                    CpModeToggle(
                        active = state.repeatMode != RepeatMode.OFF,
                        onClick = onRepeat,
                        icon = when (state.repeatMode) {
                            RepeatMode.ONE -> Icons.Filled.RepeatOne
                            else -> Icons.Filled.Repeat
                        },
                        label = s.player.repeat,
                    )
                    CpModeToggle(
                        active = (state.sleepTimerRemainingMs ?: 0L) > 0 || state.sleepAfterTrack,
                        onClick = onSleepTimer,
                        icon = Icons.Filled.AccessAlarm,
                        label = s.player.sleepTimer,
                        activeTint = MaterialTheme.colorScheme.primary,
                    )
                    // 歌词展开
                    IconButton(onClick = onExpandLyrics) {
                        Icon(
                            Icons.Filled.Lyrics,
                            contentDescription = "展开歌词",
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = onMoreClick) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = s.player.more,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 进度条与时间显示部件（支持 M3 Expressive 波形进度条与平直滑块自由切换）。
 */
@Composable
private fun ModernProgressSection(
    state: PlaybackUiState,
    config: MobilePlayerCustomConfig,
    onSeek: (Long) -> Unit,
    onOpenSongInfo: () -> Unit,
) {
    val s = cpStrings()
    val duration = state.durationMs.coerceAtLeast(0L)
    val seekable = SeekAvailability.isSeekable(duration, state.isLocalizing)

    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        if (config.useWavySeekBar) {
            CpSeekBar(
                positionMs = state.positionMs,
                durationMs = duration,
                onSeek = onSeek,
                waveFlowing = state.isPlaying,
                enabled = seekable,
                trackKey = state.currentTrack?.id,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            CpPlainSeekBar(
                positionMs = state.positionMs,
                durationMs = duration,
                onSeek = onSeek,
                enabled = seekable,
                trackKey = state.currentTrack?.id,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val formattedPosition = remember(state.positionMs / 1000) { formatTimeMs(state.positionMs) }
            CpText(
                formattedPosition,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val sleepRemainingMs = state.sleepTimerRemainingMs ?: 0L
            val sleepActive = sleepRemainingMs > 0 || state.sleepAfterTrack
            if (sleepActive) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.AccessAlarm,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(3.dp))
                    val formattedSleepRemaining = remember(sleepRemainingMs / 1000) { formatTimeMs(sleepRemainingMs) }
                    CpText(
                        text = if (sleepRemainingMs > 0) {
                            s.player.sleepRemainingLabel(formattedSleepRemaining)
                        } else {
                            s.player.sleepEndsWithTrack
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                state.formatInfo?.let { info ->
                    Surface(
                        onClick = onOpenSongInfo,
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Transparent,
                    ) {
                        CpText(
                            info.qualityLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            CpText(
                SeekAvailability.durationLabel(duration),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 类 Apple Music 展开歌词全屏视界。
 *
 * 顶部带有精致微型唱片封面胶囊及翻译开关；
 * 主体承载平滑滚动的卡拉OK/注音歌词，点击任意行立即跳转播放；
 * 底部配有便携控制栏与返回收起键。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppleMusicExpandedLyricsView(
    state: PlaybackUiState,
    track: cp.player.core.music.TrackSummary,
    showTranslation: Boolean,
    onToggleTranslation: () -> Unit,
    onSeek: (Long) -> Unit,
    onRepeat: () -> Unit,
    onShuffle: () -> Unit,
    onTogglePlay: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
    onLikeClick: () -> Unit,
    onCollapse: () -> Unit,
    onOpenComments: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenOffsetDialog: () -> Unit,
    onChangeSource: (() -> Unit)? = null,
) {
    val s = cpStrings()
    val isDark = LocalIsDarkTheme.current
    val layoutConfig by AppModel.mobilePlayerCustomConfigFlow.collectAsState()

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    // 顶栏微型封面 + 歌曲标题
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .cpPressScale(pressedScale = 0.96f)
                            .clickable(onClick = onCollapse)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.size(32.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            if (!track.coverUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = track.coverUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Column {
                            CpText(
                                text = track.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            CpText(
                                text = track.artist,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onCollapse) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "收起歌词",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenOffsetDialog) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = "歌词偏移校准",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onToggleTranslation) {
                        Icon(
                            imageVector = Icons.Filled.Translate,
                            contentDescription = s.player.translation,
                            tint = if (showTranslation) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledIconButton(
                        onClick = onOpenQueue,
                        modifier = Modifier.padding(end = 8.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = if (isDark) 0.6f else 0.8f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = s.player.queue,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
        ) {
            // 歌词滚动主体
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                LyricContent(
                    state = state,
                    showTranslation = showTranslation,
                    onSeek = onSeek,
                    onChangeSource = onChangeSource,
                    contentPadding = PaddingValues(vertical = 32.dp, horizontal = 12.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            // Apple Music 风格便携走带控制器与底部工具栏
            ModernProgressSection(
                state = state,
                config = layoutConfig,
                onSeek = onSeek,
                onOpenSongInfo = { },
            )

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // 左侧快速喜欢与评论
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ExpressiveLikeButton(
                        isFavorite = state.isFavorite,
                        onClick = onLikeClick,
                    )
                    IconButton(onClick = onOpenComments) {
                        Icon(
                            Icons.AutoMirrored.Filled.Comment,
                            contentDescription = "评论",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 中间上一首 / 播放 / 下一首
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = onSkipPrev) {
                        Icon(Icons.Filled.SkipPrevious, "上一首", Modifier.size(30.dp))
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(52.dp)
                            .cpPressScale(pressedScale = 0.92f)
                            .clip(CircleShape)
                            .clickable(onClick = onTogglePlay),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (state.isPlaying) "暂停" else "播放",
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                    IconButton(onClick = onSkipNext) {
                        Icon(Icons.Filled.SkipNext, "下一首", Modifier.size(30.dp))
                    }
                }

                // 右侧循环与收起
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRepeat) {
                        val icon = when (state.repeatMode) {
                            RepeatMode.ONE -> Icons.Filled.RepeatOne
                            else -> Icons.Filled.Repeat
                        }
                        Icon(
                            icon, "循环",
                            tint = if (state.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onCollapse) {
                        Icon(
                            Icons.Filled.Lyrics,
                            contentDescription = "收起歌词",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 独立的歌曲评论底部抽屉（承载 CommentPane）。
 */
@Composable
fun ModernCommentBottomSheet(
    trackId: String,
    onClose: () -> Unit,
) {
    val model = remember(trackId) { CommentScreenModel(trackId, "music") }

    LegacyModalBottomSheet(
        onDismissRequest = onClose,
        skipPartiallyExpanded = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.84f)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Comment,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    CpText(
                        text = "歌曲评论",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, "关闭评论")
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                CommentPane(model = model, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * 手机播放界面多维微调设置抽屉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerLayoutCustomizationSheet(
    config: MobilePlayerCustomConfig,
    onUpdateConfig: ((MobilePlayerCustomConfig) -> MobilePlayerCustomConfig) -> Unit,
    onResetConfig: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalCpHaptics.current

    LegacyModalBottomSheet(
        onDismissRequest = onDismiss,
        skipPartiallyExpanded = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 20.dp),
        ) {
            // 顶栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    CpText(
                        text = "播放界面微调",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, "关闭")
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                // 1. 布局模式切换开关
                item {
                    SettingsGroupCard("布局模式") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                CpText(
                                    text = "全新单页播放布局",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                CpText(
                                    text = if (config.enabled) "已启用：封面、双行歌词与控制整合一屏" else "已停用：使用经典三页横滑布局",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = config.enabled,
                                onCheckedChange = {
                                    haptics.perform(CpHaptic.Confirm)
                                    onUpdateConfig { c -> c.copy(enabled = it) }
                                },
                            )
                        }
                    }
                }

                // 2. 封面尺寸与比例
                item {
                    SettingsGroupCard("封面比例与尺寸") {
                        val percentage = (config.coverScale * 100).roundToInt()
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            CpText("封面占屏宽比例", style = MaterialTheme.typography.bodyMedium)
                            CpText("$percentage%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = config.coverScale,
                            onValueChange = { onUpdateConfig { c -> c.copy(coverScale = it) } },
                            valueRange = 0.55f..0.92f,
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = kotlin.math.abs(config.coverScale - 0.62f) < 0.03f,
                                onClick = {
                                    haptics.perform(CpHaptic.Tick)
                                    onUpdateConfig { c -> c.copy(coverScale = 0.62f) }
                                },
                                label = { CpText("紧凑 62%") }
                            )
                            FilterChip(
                                selected = kotlin.math.abs(config.coverScale - 0.72f) < 0.03f,
                                onClick = {
                                    haptics.perform(CpHaptic.Tick)
                                    onUpdateConfig { c -> c.copy(coverScale = 0.72f) }
                                },
                                label = { CpText("标准 72%") }
                            )
                            FilterChip(
                                selected = kotlin.math.abs(config.coverScale - 0.85f) < 0.03f,
                                onClick = {
                                    haptics.perform(CpHaptic.Tick)
                                    onUpdateConfig { c -> c.copy(coverScale = 0.85f) }
                                },
                                label = { CpText("饱满 85%") }
                            )
                        }
                    }
                }

                // 3. 封面圆角、空间景深与视觉效果
                item {
                    SettingsGroupCard("封面圆角与视觉光影") {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            CpText("封面圆角大小", style = MaterialTheme.typography.bodyMedium)
                            CpText("${config.coverCornerDp} dp", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = config.coverCornerDp.toFloat(),
                            onValueChange = { onUpdateConfig { c -> c.copy(coverCornerDp = it.roundToInt()) } },
                            valueRange = 8f..38f,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            CpText("空间立体阴影", style = MaterialTheme.typography.bodyMedium)
                            CpText("${config.coverElevationDp} dp", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = config.coverElevationDp == 0,
                                onClick = {
                                    haptics.perform(CpHaptic.Tick)
                                    onUpdateConfig { c -> c.copy(coverElevationDp = 0) }
                                },
                                label = { CpText("无阴影") }
                            )
                            FilterChip(
                                selected = config.coverElevationDp == 8,
                                onClick = {
                                    haptics.perform(CpHaptic.Tick)
                                    onUpdateConfig { c -> c.copy(coverElevationDp = 8) }
                                },
                                label = { CpText("柔和 8dp") }
                            )
                            FilterChip(
                                selected = config.coverElevationDp == 16,
                                onClick = {
                                    haptics.perform(CpHaptic.Tick)
                                    onUpdateConfig { c -> c.copy(coverElevationDp = 16) }
                                },
                                label = { CpText("景深 16dp") }
                            )
                            FilterChip(
                                selected = config.coverElevationDp == 28,
                                onClick = {
                                    haptics.perform(CpHaptic.Tick)
                                    onUpdateConfig { c -> c.copy(coverElevationDp = 28) }
                                },
                                label = { CpText("沉浸 28dp") }
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                CpText("封面弥散环境光晕", style = MaterialTheme.typography.bodyMedium)
                                CpText("在封面后渲染跟随主题色温的浮动光晕", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = config.ambientGlow,
                                onCheckedChange = {
                                    haptics.perform(CpHaptic.Confirm)
                                    onUpdateConfig { c -> c.copy(ambientGlow = it) }
                                },
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                CpText("封面滑动手势切歌", style = MaterialTheme.typography.bodyMedium)
                                CpText("左右滑动封面即可切换上一首 / 下一首", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = config.coverSwipeGesture,
                                onCheckedChange = {
                                    haptics.perform(CpHaptic.Confirm)
                                    onUpdateConfig { c -> c.copy(coverSwipeGesture = it) }
                                },
                            )
                        }
                    }
                }

                // 4. 进度条形态选择
                item {
                    SettingsGroupCard("进度条交互形态") {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = !config.useWavySeekBar,
                                onClick = {
                                    haptics.perform(CpHaptic.Tick)
                                    onUpdateConfig { c -> c.copy(useWavySeekBar = false) }
                                },
                                label = { CpText("经典平直进度条") }
                            )
                            FilterChip(
                                selected = config.useWavySeekBar,
                                onClick = {
                                    haptics.perform(CpHaptic.Tick)
                                    onUpdateConfig { c -> c.copy(useWavySeekBar = true) }
                                },
                                label = { CpText("M3 Expressive 波形条") },
                                leadingIcon = { Icon(Icons.Filled.Waves, null, Modifier.size(16.dp)) }
                            )
                        }
                    }
                }

                // 5. 歌词预览微调
                item {
                    SettingsGroupCard("歌词预览微调") {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            CpText("歌词正文字号", style = MaterialTheme.typography.bodyMedium)
                            CpText("${config.lyricsFontSizeSp} sp", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = config.lyricsFontSizeSp.toFloat(),
                            onValueChange = { onUpdateConfig { c -> c.copy(lyricsFontSizeSp = it.roundToInt()) } },
                            valueRange = 14f..22f,
                            steps = 7,
                        )

                        Spacer(Modifier.height(4.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CpText("歌词对齐方式", style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(
                                    selected = !config.lyricsAlignStart,
                                    onClick = {
                                        haptics.perform(CpHaptic.Tick)
                                        onUpdateConfig { c -> c.copy(lyricsAlignStart = false) }
                                    },
                                    label = { CpText("居中") },
                                    leadingIcon = { Icon(Icons.Filled.FormatAlignCenter, null, Modifier.size(16.dp)) }
                                )
                                FilterChip(
                                    selected = config.lyricsAlignStart,
                                    onClick = {
                                        haptics.perform(CpHaptic.Tick)
                                        onUpdateConfig { c -> c.copy(lyricsAlignStart = true) }
                                    },
                                    label = { CpText("靠左") },
                                    leadingIcon = { Icon(Icons.Filled.FormatAlignLeft, null, Modifier.size(16.dp)) }
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                CpText("逐字卡拉OK动效", style = MaterialTheme.typography.bodyMedium)
                                CpText("根据音节时间平滑点亮文字", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = config.karaokeEnabled,
                                onCheckedChange = {
                                    haptics.perform(CpHaptic.Confirm)
                                    onUpdateConfig { c -> c.copy(karaokeEnabled = it) }
                                },
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                CpText("显示双语翻译", style = MaterialTheme.typography.bodyMedium)
                                CpText("在歌词预览中显示翻译副行", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = config.showTranslation,
                                onCheckedChange = {
                                    haptics.perform(CpHaptic.Confirm)
                                    onUpdateConfig { c -> c.copy(showTranslation = it) }
                                },
                            )
                        }
                    }
                }

                // 6. 走带控制按键微调
                item {
                    SettingsGroupCard("走带按键大小") {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = config.controlsScale == 0.85f,
                                onClick = {
                                    haptics.perform(CpHaptic.Tick)
                                    onUpdateConfig { c -> c.copy(controlsScale = 0.85f) }
                                },
                                label = { CpText("紧凑 (85%)") }
                            )
                            FilterChip(
                                selected = config.controlsScale == 1.0f,
                                onClick = {
                                    haptics.perform(CpHaptic.Tick)
                                    onUpdateConfig { c -> c.copy(controlsScale = 1.0f) }
                                },
                                label = { CpText("标准 (100%)") }
                            )
                            FilterChip(
                                selected = config.controlsScale == 1.15f,
                                onClick = {
                                    haptics.perform(CpHaptic.Tick)
                                    onUpdateConfig { c -> c.copy(controlsScale = 1.15f) }
                                },
                                label = { CpText("醒目 (115%)") }
                            )
                        }
                    }
                }

                // 7. 重置默认
                item {
                    OutlinedButton(
                        onClick = {
                            haptics.perform(CpHaptic.Confirm)
                            onResetConfig()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        CpText("恢复推荐默认布局")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    content: @Composable () -> Unit,
) {
    val isDark = LocalIsDarkTheme.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = if (isDark) 0.35f else 0.5f),
        border = BorderStroke(
            0.5.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.15f else 0.25f)
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CpText(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            content()
        }
    }
}
