package cp.player.app.ui.screen

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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Translate
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import cp.player.app.i18n.cpStrings
import cp.player.app.AppModel
import cp.player.app.ui.anim.LocalNavAnimatedVisibilityScope
import cp.player.app.ui.anim.LocalSharedTransitionScope
import cp.player.app.ui.component.CpBreakpoints
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.LegacyModalBottomSheet
import cp.player.app.ui.component.cpFluidBackground
import cp.player.app.ui.component.desktopPagerMouseControl
import cp.player.app.ui.component.PlayerMoreSheets
import cp.player.app.ui.component.QueueBottomSheet
import cp.player.app.ui.component.SimilarSongsPanel
import cp.player.app.ui.component.rememberPlayerMoreSheetState
import cp.player.app.ui.model.CommentScreenModel
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.util.SeekAvailability
import cp.player.app.ui.theme.LocalIsDarkTheme
import cp.player.app.ui.util.formatTimeMs
import cp.player.app.ui.util.next
import cp.player.app.ui.util.popOrNotify
import cp.player.app.ui.util.pushOrNotify
import cp.player.core.music.Comment
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
 * - 无 `WindowWidthSizeClass` → 自己用 `BoxWithConstraints` + [CpBreakpoints] 判宽屏：
 *   宽屏走 `DesktopPlayerScreen`，窄屏走移动布局。**不能**读 `LocalIsExpanded` ——
 *   它只在 `MainScreen` 内部被 provide，本页作为路由页与之是兄弟节点，永远拿到默认 false。
 *
 * 四页 HorizontalPager：歌词 / 播放器 / 评论 / 相似歌曲。播放器页可下拉关闭。
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
        val onRepeat = { controller.setRepeatMode(state.repeatMode.next()) }

        // 桌面窗口标题栏的标题：播放页是全屏沉浸页，窗口 chrome 仍要能显示"现在在哪"。
        // 本页的「收起」是折叠语义（↓ / ✕），与「返回上一页」不是一回事，所以不参与返回键统一。
        state.currentTrack?.let { cp.player.app.ui.util.DesktopRouteTitle(it.name) }

        // 宽屏 / 窄屏两套播放页的分叉点。
        //
        // ⚠️ 判据必须是**本页自身的可用宽度**，不能读 `LocalIsExpanded` —— 那个 local 只在
        // `MainScreen` 内部被 provide，而本页是 push 出去的路由页、与 `MainScreen` 在
        // Navigator 里是兄弟节点，读到的永远是默认值 false。后果就是：从「每日推荐」
        // 这类子页里点 MiniPlayer 进播放页时，宽窗口上照样给手机布局。
        // 路由页自己占满窗口，按自身 `maxWidth` 判定与按窗口宽度判定等价，
        // 与 `PlaylistDetailScreen` 里已有的 `isWide` 判据同源。
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 桌面播放页开头就 `state.currentTrack ?: return`（无曲目时什么都不画），
            // 所以这里要显式排除空曲目，否则会先闪一帧空白。
            if (CpBreakpoints.isExpanded(maxWidth) && state.currentTrack != null) {
                DesktopPlayerScreen(
                    state = state,
                    onBack = { navigator.popOrNotify() },
                    onTogglePlay = controller::togglePlayPause,
                    onSeek = controller::seekTo,
                    onSkipNext = controller::skipNext,
                    onSkipPrev = controller::skipPrevious,
                    onRepeat = onRepeat,
                    onShuffle = controller::toggleShuffle,
                    onLike = { scope.launch { controller.toggleFavorite() } },
                    onPlayAt = { idx -> scope.launch { controller.playAt(idx) } },
                )
            } else {
                // 共享转场走**复用**而不是自建：App 根部有一个罩住整棵根 Navigator 的
                // SharedTransitionLayout（含全局 MiniPlayer），经两个 local 下发 scope 与
                // 本次页面转场的 AnimatedVisibilityScope。本页的 sharedBounds
                // （player-container / cover / title / artist）只有挂到那对 scope 上，
                // 才能和 MiniPlayer 的同名 sharedBounds 配对 —— 此前本页自建
                // SharedTransitionLayout，两个 scope 永远配不上对，「从歌单等其它页面
                // 点 MiniPlayer 展开播放页没有动画」就是它造成的。
                // 回退分支：脱离 App 树使用（读不到 local）时才自建一对自洽的 scope。
                val sharedScope = LocalSharedTransitionScope.current
                val navAnimScope = LocalNavAnimatedVisibilityScope.current
                // 显式标注 Unit：否则 val 推断成 (…) -> Job/Boolean，与参数类型不符。
                val onBack: () -> Unit = { navigator.popOrNotify() }
                val onPlayAt: (Int) -> Unit = { idx -> scope.launch { controller.playAt(idx) } }
                val onRemoveQueue: (Int) -> Unit = { idx -> scope.launch { controller.removeQueueItem(idx) } }
                val onMoveQueue: (Int, Int) -> Unit = { from, to -> scope.launch { controller.moveQueueItem(from, to) } }
                if (sharedScope != null && navAnimScope != null) {
                    with(sharedScope) {
                        PlayerScreenContent(
                            state = state,
                            animatedVisibilityScope = navAnimScope,
                            onBack = onBack,
                            onTogglePlay = controller::togglePlayPause,
                            onSeek = controller::seekTo,
                            onSkipNext = controller::skipNext,
                            onSkipPrev = controller::skipPrevious,
                            onRepeat = onRepeat,
                            onShuffle = controller::toggleShuffle,
                            onClearQueue = controller::clearQueue,
                            onPlayAt = onPlayAt,
                            onRemoveQueue = onRemoveQueue,
                            onMoveQueue = onMoveQueue,
                        )
                    }
                } else {
                    androidx.compose.animation.SharedTransitionLayout {
                        androidx.compose.animation.AnimatedVisibility(visible = true) {
                            PlayerScreenContent(
                                state = state,
                                animatedVisibilityScope = this,
                                onBack = onBack,
                                onTogglePlay = controller::togglePlayPause,
                                onSeek = controller::seekTo,
                                onSkipNext = controller::skipNext,
                                onSkipPrev = controller::skipPrevious,
                                onRepeat = onRepeat,
                                onShuffle = controller::toggleShuffle,
                                onClearQueue = controller::clearQueue,
                                onPlayAt = onPlayAt,
                                onRemoveQueue = onRemoveQueue,
                                onMoveQueue = onMoveQueue,
                            )
                        }
                    }
                }
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
    val s = cpStrings()
    val track = state.currentTrack
    if (track == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            cp.player.app.ui.component.CpLoadingIndicator(Modifier.size(40.dp))
        }
        return
    }

    // 状态：sheet / 弹窗。「更多」弹层的开关状态收在 PlayerMoreSheetState 里，
    // 宿主与动作实现是共享组件 PlayerMoreSheets（本页末尾挂载；桌面播放页
    // DesktopPlayerScreen 同款复用）—— 两套布局互斥切换，各持一份状态。
    var showQueueSheet by remember { mutableStateOf(false) }
    var showSimilarSheet by remember { mutableStateOf(false) }
    var showTranslation by remember { mutableStateOf(true) }
    val moreSheets = rememberPlayerMoreSheetState()
    val playerScope = rememberCoroutineScope()
    val controller = AppModel.playback

    // 点歌手 → 歌手主页。多歌手逐个分段可点（见 TrackArtistText 的 KDoc）。
    // ⚠️ 拿不到 Navigator 时**不挂点击区**（传 null 走纯文本分支）：与其点了只弹一句
    // 「打不开」，不如一开始就不长得像能点。
    val navigator = LocalNavigator.current
    val onArtistClick: ((cp.player.core.music.ArtistSummary) -> Unit)? = remember(navigator) {
        navigator?.let { nav ->
            { artist -> nav.pushOrNotify(UserProfileScreen(artist.id, artist.name)) }
        }
    }

    // 「没歌词 → 换源」。与点歌手同一契约：拿不到 Navigator 就不显示入口
    // （宁可没有按钮，也不要一个点了没反应的按钮）。
    val onChangeLyricsSource: (() -> Unit)? = remember(navigator) {
        navigator?.let { nav ->
            { nav.pushOrNotify(cp.player.app.ui.screen.LyricsPluginSettingsScreen()) }
        }
    }

    // 手机端三页 Pager：0=歌词，1=播放器/歌曲，2=评论（相似歌曲由顶栏独立按钮与队列入口呼出）
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

    // 背景：流体网格渐变（仿 Apple Music），低版本安卓 / 用户关掉时回退到竖向渐变。
    //
    // 两个色都取自 MaterialTheme，所以「跟随封面 / 跟随系统」换色时这里会一起变。
    // 深色分支原先硬编码 `#1B1B22 → #0A0A0F` —— 那是「KMP 还没有封面取色」时期的替代品，
    // 副作用是播放页会成为全应用唯一不跟随主题的表面。现在改用主题的容器色，
    // 深色下依然是「上略亮、下近黑」的走向。
    //
    // ⚠️ `remember` 必须把两个颜色本身作为 key：只 key `isDark` 的话，换色时 Brush 不会重建，
    // 背景会停在旧配色上而其余控件已经换色 —— 看起来就像「主题只换了一半」。
    // ⚠️ 读 LocalIsDarkTheme（已解析的明暗），**不要**读 isSystemInDarkTheme()：
    // 播放页允许用户显式选浅色/深色，用系统状态会在「应用深色 + 系统浅色」时取错色板。
    //
    // ⚠️ 这支渐变现在有**两个身份**，改色前先想清楚动的是哪一个：
    //   1. `enabled = false`（用户在「外观」里关了流体背景）时的**唯一背景**；
    //   2. 流体背景在 Android 12 及以下（没有 RuntimeShader）的**实际外观** —— 那边
    //      不是「降级到一帧纯色」，而是整段播放都长这样。
    // 所以它不是可以随便糊弄的兜底，观感必须能单独站住。
    val isDark = LocalIsDarkTheme.current
    val surfaceTop = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh
    else MaterialTheme.colorScheme.surfaceVariant
    val surfaceBottom = if (isDark) MaterialTheme.colorScheme.surfaceContainerLowest
    else MaterialTheme.colorScheme.surface
    val bgBrush = remember(surfaceTop, surfaceBottom) {
        Brush.verticalGradient(listOf(surfaceTop, surfaceBottom))
    }
    val fluidBackground by AppModel.fluidBackgroundFlow.collectAsState()

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
                                        // 顶栏大标题 = 焦点位（当前页 0）：溢出即跑马灯滚动，
                                        // 不再只留一个省略号。静态观感与治理前一致（CpText 默认 Tail 档）。
                                        cp.player.app.ui.component.CpText(
                                            text = track.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            emphasized = true,
                                        )
                                        // 歌手可点（多歌手各自分段）：顶栏这行是 bodySmall，
                                        // 与下方大字歌手行共用同一个组件、同一份跳转。
                                        // 次级文字用 onSurfaceVariant，不要 onSurface + 手写 alpha。
                                        // 手写 alpha 在「跟随封面取色」时不可控：封面色一深，
                                        // 0.6 的次级文字就掉到对比度下限以下，而同一层级的文字
                                        // 在别的页面是 onSurfaceVariant —— 同级别、不同深浅。
                                        cp.player.app.ui.component.TrackArtistText(
                                            track = track,
                                            onArtistClick = onArtistClick,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
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
                            AnimatedContent(
                                targetState = pagerState.currentPage,
                                label = "TopBarActions"
                            ) { page ->
                                when (page) {
                                    0 -> {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = { showTranslation = !showTranslation },
                                                modifier = Modifier.padding(end = 4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Translate,
                                                    contentDescription = s.player.translation,
                                                    tint = if (showTranslation) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
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
                                                    contentDescription = s.player.queue,
                                                )
                                            }
                                        }
                                    }
                                    else -> {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = { showSimilarSheet = true },
                                                modifier = Modifier.padding(end = 4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.AutoAwesome,
                                                    contentDescription = "相似歌曲",
                                                    tint = MaterialTheme.colorScheme.onSurface,
                                                )
                                            }
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
                                                    contentDescription = s.player.queue,
                                                )
                                            }
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
                    // 手机端三栏表现力切换选择器（【歌词】、【歌曲】、【评论】）
                    MobilePlayerTabSelector(
                        currentPage = pagerState.currentPage,
                        onSelect = { page -> scope.launch { pagerState.animateScrollToPage(page) } },
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp),
                    )
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            // 鼠标滚轮翻页：歌词 ↔ 播放器 ↔ 评论 三页在桌面端此前**只能用
                            // 触控板横向滑动**切（而台式机没有），用户看到的就是「滚轮没反应」。
                            // 桌面 actual 把垂直滚轮映射成翻页，安卓 actual 为空实现。
                            .desktopPagerMouseControl(
                                onScrollLeft = {
                                    val target = (pagerState.currentPage - 1).coerceAtLeast(0)
                                    if (target != pagerState.currentPage) {
                                        scope.launch { pagerState.animateScrollToPage(target) }
                                    }
                                },
                                onScrollRight = {
                                    val last = pagerState.pageCount - 1
                                    val target = (pagerState.currentPage + 1).coerceAtMost(last)
                                    if (target != pagerState.currentPage) {
                                        scope.launch { pagerState.animateScrollToPage(target) }
                                    }
                                },
                                pageCount = pagerState.pageCount,
                            ),
                    ) { page ->
                        when (page) {
                            0 -> LyricsPage(
                                state = state,
                                showTranslation = showTranslation,
                                onSeek = onSeek,
                                onRepeat = onRepeat,
                                isFavorite = state.isFavorite,
                                onLikeClick = { playerScope.launch { controller.toggleFavorite() } },
                                onChangeSource = onChangeLyricsSource,
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
                                onArtistClick = onArtistClick,
                                // 弹层本体与全部动作实现已收进共享宿主 PlayerMoreSheets
                                // （本页末尾调用），这里只传两个入口回调。
                                onMoreClick = { moreSheets.showMoreMenu = true },
                                onSleepTimer = {
                                    moreSheets.showMoreMenu = false
                                    moreSheets.showSleepTimer = true
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
            onOpenSimilar = {
                showQueueSheet = false
                showSimilarSheet = true
            },
        )
    }

    if (showSimilarSheet) {
        LegacyModalBottomSheet(
            onDismissRequest = { showSimilarSheet = false },
            skipPartiallyExpanded = true,
        ) {
            SimilarSongsPanel(
                seedTrackId = track.id,
                onBack = { showSimilarSheet = false },
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxHeight(0.85f),
            )
        }
    }

    // 「更多」弹层 + 二级弹窗：宿主与动作实现全在共享组件 PlayerMoreSheets 里，
    // 桌面播放页（DesktopPlayerScreen）以同一组件、各自的状态复用同一套行为。
    PlayerMoreSheets(state = state, sheets = moreSheets)
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
    onChangeSource: (() -> Unit)? = null,
) {
    val s = cpStrings()
    // 沉浸歌词（影院模式）：歌词自己判定「无操作够久了」，这里负责把控件收起来。
    // 状态由 LyricContent 持有并回调（它才知道什么时候算「无操作」）。
    var lyricsImmersive by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            cp.player.app.ui.component.LyricContent(
                state = state,
                showTranslation = showTranslation,
                onSeek = onSeek,
                onChangeSource = onChangeSource,
                onImmersiveChange = { lyricsImmersive = it },
            )
        }
        // 浮动胶囊：**按内容宽度**收口并居中，而不是铺满整宽。
        // 原先这枚只有两个按钮却长满一行，`SpaceEvenly` 把它们推到 1/4 与 3/4 处，
        // 中间空出一大块 —— 看起来像「少了两个按钮」。
        // 换成与播放器页同款的 CpFloatingToolbar 之后，左右滑动换页时底部不再跳。
        //
        // 沉浸态整条收起来：屏幕只剩歌词。`Modifier.align` 必须留在 AnimatedVisibility
        // **外面** —— 里面的 scope 是 AnimatedVisibilityScope，不是 ColumnScope。
        AnimatedVisibility(
            visible = !lyricsImmersive,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            cp.player.app.ui.component.CpFloatingToolbar(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                IconButton(onClick = onRepeat) {
                    val icon = when (state.repeatMode) {
                        RepeatMode.ONE -> Icons.Filled.RepeatOne
                        else -> Icons.Filled.Repeat
                    }
                    Icon(
                        icon, s.player.repeat, Modifier.size(24.dp),
                        tint = if (state.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                cp.player.app.ui.component.ExpressiveLikeButton(
                    isFavorite = state.isFavorite,
                    onClick = onLikeClick,
                )
            }
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
    onArtistClick: ((cp.player.core.music.ArtistSummary) -> Unit)?,
    onMoreClick: () -> Unit,
    onSleepTimer: () -> Unit,
) {
    val s = cpStrings()
    val track = state.currentTrack ?: return

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenHeight = maxHeight
        val isUltraCompact = screenHeight < 620.dp
        val isCompact = screenHeight in 620.dp..760.dp

        val verticalGap = when {
            isUltraCompact -> 6.dp
            isCompact -> 10.dp
            else -> 16.dp
        }
        val horizontalPadding = if (isUltraCompact) 16.dp else 24.dp
        val bottomPadding = if (isUltraCompact) 6.dp else 12.dp
        val controlsHeight = when {
            isUltraCompact -> 54.dp
            isCompact -> 62.dp
            else -> 70.dp
        }
        val sideIconSize = when {
            isUltraCompact -> 24.dp
            isCompact -> 30.dp
            else -> 34.dp
        }
        val centerIconSize = when {
            isUltraCompact -> 28.dp
            isCompact -> 34.dp
            else -> 38.dp
        }

        Column(
            Modifier.fillMaxSize().padding(horizontal = horizontalPadding).padding(bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(verticalGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 封面区域：支持根据屏幕高度自适应伸缩，避免在 16:9 或矮屏上挤压控制控件。
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
            val coverScale by animateFloatAsState(
                targetValue = if (state.isPlaying) 1f else 0.95f,
                animationSpec = tween(260),
                label = "coverScale",
            )
            Box(
                Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = if (isUltraCompact) 200.dp else if (isCompact) 280.dp else 360.dp)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(coverCorner),
                    modifier = Modifier.aspectRatio(1f)
                        .fillMaxWidth(if (isUltraCompact) 0.82f else 0.95f)
                        .sharedBounds(
                            sharedContentState = rememberSharedContentState(key = "cover-${track.id}"),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                        .graphicsLayer {
                            scaleX = coverScale
                            scaleY = coverScale
                        }
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
                        cp.player.app.ui.component.CpCoverPlaceholder(
                            modifier = Modifier.fillMaxSize(),
                            corner = coverCorner,
                        )
                    }
                }
            }

            // 歌曲信息行：标题 / 歌手 / 喜欢
            Row(
                Modifier.fillMaxWidth().padding(top = if (isUltraCompact) 0.dp else 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    cp.player.app.ui.component.CpText(
                        text = track.name,
                        style = if (isUltraCompact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface,
                        emphasized = true,
                        marqueeEnabled = state.isPlaying,
                        modifier = Modifier.sharedBounds(
                            sharedContentState = rememberSharedContentState(key = "title-${track.id}"),
                            animatedVisibilityScope = animatedVisibilityScope,
                        )
                    )
                    Spacer(Modifier.height(if (isUltraCompact) 0.dp else 2.dp))
                    cp.player.app.ui.component.TrackArtistText(
                        track = track,
                        onArtistClick = onArtistClick,
                        style = if (isUltraCompact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
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
                        IconButton(
                            onClick = {
                                nav?.push(VideoPlayerScreen("netease://mv/$mvId"))
                            },
                        ) {
                            Icon(
                                Icons.Filled.SmartDisplay,
                                contentDescription = "播放 MV",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    cp.player.app.ui.component.ExpressiveLikeButton(
                        isFavorite = state.isFavorite,
                        onClick = onLikeClick,
                    )
                }
            }

            // 进度条（含中央音质 chip）
            ProgressRow(state, onSeek)

            // 主控件（prev / play-pause / next）：自适应按键高度
            cp.player.app.ui.component.PlaybackControls(
                isPlaying = state.isPlaying,
                isBuffering = state.isBuffering,
                onPlayPause = onTogglePlay,
                onSkipNext = onSkipNext,
                onSkipPrevious = onSkipPrev,
                modifier = Modifier.fillMaxWidth().padding(vertical = if (isUltraCompact) 0.dp else 2.dp),
                sideButtonModifier = Modifier.height(controlsHeight),
                centerButtonModifier = Modifier.height(controlsHeight),
                sideWeight = 1f,
                centerWeight = 1.2f,
                sideIconSize = sideIconSize,
                centerIconSize = centerIconSize,
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
            cp.player.app.ui.component.CpFloatingToolbar(
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = if (isUltraCompact) 0.dp else 4.dp),
            ) {
                cp.player.app.ui.component.CpModeToggle(
                    active = state.shuffleEnabled,
                    onClick = onShuffle,
                    icon = Icons.Filled.Shuffle,
                    label = s.player.shuffle,
                )
                cp.player.app.ui.component.CpModeToggle(
                    active = state.repeatMode != RepeatMode.OFF,
                    onClick = onRepeat,
                    icon = when (state.repeatMode) {
                        RepeatMode.ONE -> Icons.Filled.RepeatOne
                        else -> Icons.Filled.Repeat
                    },
                    label = s.player.repeat,
                )
                cp.player.app.ui.component.CpModeToggle(
                    active = (state.sleepTimerRemainingMs ?: 0L) > 0 || state.sleepAfterTrack,
                    onClick = onSleepTimer,
                    icon = Icons.Filled.AccessAlarm,
                    label = s.player.sleepTimer,
                    activeTint = MaterialTheme.colorScheme.primary,
                )
                IconButton(onClick = onMoreClick) {
                    Icon(
                        Icons.Filled.MoreVert, s.player.more,
                        Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

// SongInfoDialog 已随「更多」弹层宿主（PlayerMoreSheets）迁往
// ui/component/PlayerMoreBottomSheet.kt —— 桌面播放页（DesktopPlayerScreen）与
// 窄屏布局共用同一宿主，SongInfoDialog 作为其二级弹窗必须同处一处。

@Composable
private fun MobilePlayerTabSelector(
    currentPage: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = LocalIsDarkTheme.current
    val tabs = remember {
        listOf(
            MobileTabItem("歌词", Icons.Filled.Lyrics),
            MobileTabItem("歌曲", Icons.Filled.MusicNote),
            MobileTabItem("评论", Icons.AutoMirrored.Filled.Comment),
        )
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraExtraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = if (isDark) 0.45f else 0.55f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.18f else 0.28f),
        ),
    ) {
        Row(
            Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = currentPage == index
                val containerColor by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else Color.Transparent,
                    animationSpec = CpMotion.effects(),
                    label = "mobileTabContainerColor$index",
                )
                val contentColor by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = CpMotion.effects(),
                    label = "mobileTabContentColor$index",
                )
                Surface(
                    onClick = { onSelect(index) },
                    shape = MaterialTheme.shapes.large,
                    color = containerColor,
                    contentColor = contentColor,
                ) {
                    Row(
                        Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = tab.title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

private data class MobileTabItem(val title: String, val icon: ImageVector)

@Composable
private fun ProgressRow(
    state: cp.player.core.playback.PlaybackUiState,
    onSeek: (Long) -> Unit,
) {
    val s = cpStrings()
    val duration = state.durationMs.coerceAtLeast(0L)
    // 时长未知（流媒体元信息还没到、直播流）时滑条位置无法换算成绝对时间，
    // 拖出来必然是错的——保持禁用。但**必须说明原因**：静默失效会让用户
    // 分不清「我拖错了」和「这个音源拖不了」，观感就是「拖了没反应」。
    // 判定与标签统一走 SeekAvailability，不再各处各写一遍。
    // 无损曲后台落盘期间同样禁用：此时引擎放的是不可定位的流，拖了也不会动。
    val seekable = SeekAvailability.isSeekable(duration, state.isLocalizing)
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        // 旧版同款**直线**进度条 —— 1:1 移植自 reference/cp-player-legacy 的 ProgressSection
        // （移动端不用波形；波形留给桌面播放页的 CpSeekBar）。视觉就是 M3 Slider 的
        // 默认 Expressive 观感。拖动期间由 CpPlainSeekBar 自己接管视觉，松手才回调 onSeek。
        cp.player.app.ui.component.CpPlainSeekBar(
            positionMs = state.positionMs,
            durationMs = duration,
            onSeek = onSeek,
            enabled = seekable,
            trackKey = state.currentTrack?.id,
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
                    val formattedSleepRemaining = remember(sleepRemainingMs / 1000) { formatTimeMs(sleepRemainingMs) }
                    Text(
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
    // 列表 / 楼层 / 回复输入条全部在共享组件 CommentPane 里（窄屏评论页与桌面弹层同一份）。
    //
    // ⚠️ `remember` 必须带 key。这里是 pager 的一页，位置不随曲目变化 —— 不带 key 时切歌
    // 会**复用同一个模型**，评论页显示的仍是上一首的评论（模型构造参数只在第一次生效）。
    val model = remember(id, type) { cp.player.app.ui.model.CommentScreenModel(id, type) }
    cp.player.app.ui.component.CommentPane(model = model, modifier = Modifier.fillMaxSize())
}

@Composable
fun DesktopLyricsContent(
    state: cp.player.core.playback.PlaybackUiState,
    onSeek: (Long) -> Unit,
    showTranslation: Boolean = true,
    onRepeat: () -> Unit = {},
    onLikeClick: () -> Unit = {},
    onChangeSource: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize()) {
        cp.player.app.ui.component.LyricContent(
            state = state,
            showTranslation = showTranslation,
            onSeek = onSeek,
            onChangeSource = onChangeSource,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 24.dp, horizontal = 12.dp),
        )
    }
}

@Composable
fun DesktopCommentContent(trackId: String) {
    CommentPage(trackId, "music")
}
