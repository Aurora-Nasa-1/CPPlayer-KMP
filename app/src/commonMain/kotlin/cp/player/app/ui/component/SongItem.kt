package cp.player.app.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.isAndroidPlatform
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cp.player.app.presentation.navigation.AppRoute
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.anim.coverFlightSource
import cp.player.app.ui.feedback.CpHaptic
import cp.player.app.ui.feedback.LocalCpHaptics
import cp.player.app.ui.navigation.LocalAppNavigator
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.theme.CpShapes
import cp.player.app.ui.theme.cpCardSurface
import cp.player.app.ui.theme.cpNestedSurface
import cp.player.app.ui.util.DesktopShell
import cp.player.app.ui.util.UiEvents
import cp.player.app.ui.util.rememberCoverImageRequest
import cp.player.core.music.TrackSummary
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp

private val SongSwipeThresholdDp = 76.dp
private val SongSwipeOvershootDp = 44.dp

/**
 * M3 Expressive 风格歌曲列表项。
 *
 * 封面 52dp + 歌名/歌手 + 右侧 MoreVert 圆形按钮。
 * 点击主体区域触发 [onClick]，点击 MoreVert 触发 [onOptionsClick]。
 * [selectionMode] 为 true 时以 Checkbox 替代封面、隐藏 MoreVert，用于多选场景。
 * 在 Android 与平板界面下（非桌面端）且全局开关开启时，支持向右滑动「下一首播放」、向左滑动「添加到队列」。
 */
@Composable
fun SongItem(
    track: TrackSummary,
    onClick: () -> Unit,
    onOptionsClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    showIndex: Boolean = false,
    index: Int = 0,
    total: Int = 0,
    isCurrentlyPlaying: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    /**
     * 右键菜单项（桌面端）。非空且非多选时，整行包在 [CpContextMenu] 里，
     * 右键弹出标准动作菜单；构建方法见 [songContextMenuItems]。
     * 多选模式下忽略（长按已被多选语义占用，右键同时弹菜单会打架）。
     */
    contextMenu: List<CpContextMenuItem>? = null,
    /**
     * 是否在副标题显示所属专辑。传 null 时跟随系统全局设置 [AppModel.showSongAlbumFlow]（默认关闭）。
     */
    showAlbum: Boolean? = null,
    /**
     * 自定义副标题文本（非空时覆盖默认的 "歌手 · 专辑" 格式，用于本地歌曲的时长/码率展示）。
     */
    supportingText: String? = null,
    /** 副标题末尾追加的音源名（不覆盖歌手 / 专辑的既有展示规则）。 */
    supportingSource: String? = null,
    /**
     * 副标题行尾的轻量徽标（如「其他音源」）。为 null 时不占位。
     * [supportingBadgeAlert] 为 true 时使用错误色容器，用于不可播放等需要立刻扫到的状态。
     */
    supportingBadge: String? = null,
    supportingBadgeAlert: Boolean = false,
    /** 向右滑动触发「下一首播放」；为 null 时默认将本曲插入当前曲目之后。 */
    onPlayNext: (() -> Unit)? = null,
    /** 向左滑动触发「添加到队列」；为 null 时默认将本曲追加到队列末尾。 */
    onAddToQueue: (() -> Unit)? = null,
    /** 是否允许本行响应左右滑动快捷操作（内部还会叠加平台与全局设置开关）。 */
    swipeEnabled: Boolean = true,
) {
    val s = cpStrings()
    val globalShowAlbum by AppModel.showSongAlbumFlow.collectAsState()
    val actualShowAlbum = showAlbum ?: globalShowAlbum
    // 预览和启动早期没有后端；观察初始化信号，完成后再切换到真实播放状态。
    // ⚠️ 必须通过 map + distinctUntilChanged 派生布尔状态：`AppModel.playback.state` 随位置轮询
    // 每 200ms 推送一次全新实例。若直接 collect 完整状态，列表中几十个 SongItem 每秒会重组 5 次，
    // 造成极大的无效重组开销。这里仅在「是否为当前曲」或「是否在播放」发生翻转时才触发重组。
    val initialized by AppModel.initialized.collectAsState()
    val activeState by remember(track.id, isCurrentlyPlaying, initialized) {
        if (!initialized) {
            flowOf(isCurrentlyPlaying to false)
        } else {
            AppModel.playback.state
                .map { st ->
                    val currentMediaId = st.queue.getOrNull(st.currentIndex)?.mediaId
                    val isCurr = isCurrentlyPlaying || st.currentTrack?.id == track.id ||
                        currentMediaId == track.id || currentMediaId?.substringAfterLast('/') == track.id
                    isCurr to (isCurr && st.isPlaying)
                }
                .distinctUntilChanged()
        }
    }.collectAsState(initial = isCurrentlyPlaying to false)
    val isCurrent = activeState.first
    val isActivelyPlaying = activeState.second

    val hasContextMenu = !contextMenu.isNullOrEmpty()
    val showMoreButton = !selectionMode && (onOptionsClick != null || hasContextMenu)
    // 桌面端（窗口 chrome 接管）且传入了菜单项 ⇒ MoreVert 走锚定菜单；
    // 移动端若未传入专用的 onOptionsClick 弹层，只要提供了 contextMenu，
    // 同样走具备自适应底部抽屉能力（CpContextMenuBottomSheet）的 CpAnchoredMenu。
    val useAnchoredMenu = hasContextMenu && (LocalWindowChromeActive.current || onOptionsClick == null)

    // 「勾中」与「当前播放」是**两个不同的语义**，用两个角色，不能共用一支颜色：
    // - 勾中 = 主色强调块（primaryContainer），是用户正在施加的操作；
    // - 当前播放 = 选中态（secondaryContainer），与导航栏指示器、设置页选中行同义。
    // 旧版两处都用 primaryContainer，只靠 α=0.5 区分 —— 同一行上根本看不出差别。
    val targetContainerColor = when {
        selectionMode && isSelected -> MaterialTheme.colorScheme.primaryContainer
        isCurrent -> MaterialTheme.colorScheme.secondaryContainer
        // 行是「页面上的卡片」，统一走卡片层 —— 深浅两套层级是反的，
        // 分散写角色名会在「用户显式选深色、系统是浅色」时取错色板（见 cpCardSurface）。
        else -> cpCardSurface()
    }
    val rowPrimaryTextColor = when {
        selectionMode && isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
        isCurrent -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val rowSecondaryTextColor = when {
        selectionMode && isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
        isCurrent -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val animatedContainerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = CpMotion.effects(),
        label = "songItemContainerColor",
    )

    // 正在播放时封面圆角从 12dp 平滑 morph 变形成 26dp（52dp 的正圆 Pill）
    val coverCorner by animateDpAsState(
        targetValue = if (isCurrent) 26.dp else 12.dp,
        animationSpec = CpMotion.spatial(),
        label = "songCoverCornerMorph",
    )
    val coverShape = remember(coverCorner) { RoundedCornerShape(coverCorner) }

    val row: @Composable (Modifier, Boolean) -> Unit = { rowModifier, swiping ->
        LegacyListItem(
            index = index.coerceAtLeast(0),
            total = total.coerceAtLeast(1),
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = rowModifier,
            emphasized = isCurrent,
            swiping = swiping,
            containerColor = animatedContainerColor,
            leadingContent = {
                if (selectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = null,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (showIndex) {
                            CpText(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = rowSecondaryTextColor,
                                modifier = Modifier.padding(end = 12.dp),
                            )
                        }
                        Box(
                            Modifier.size(52.dp).aspectRatio(1f).clip(coverShape)
                                .background(cpNestedSurface())
                                .coverFlightSource(CoverFlight.trackKey(track.id), coverCorner),
                            contentAlignment = Alignment.Center,
                        ) {
                            CpCoverPlaceholder(
                                modifier = Modifier.fillMaxSize(),
                                corner = coverCorner,
                                animated = false,
                            )
                            if (!track.coverUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = rememberCoverImageRequest(track.coverUrl, targetSizeDp = 52.dp),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                            // 当前曲目：封面上浮均衡器。播放中跳动，暂停时塌成三个点；
                            // 比「标题变主题色 + 加粗」更容易用余光扫到，也把播放/暂停状态区分开。
                            if (isCurrent) {
                                CpPlayingEqualizer(
                                    isPlaying = isActivelyPlaying,
                                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                                )
                            }
                        }
                    }
                }
            },
            // CpText 而不是 Text：歌名 / 歌手被截断后仍可读全（悬停浮出完整文本）。
            // 静态观感与治理前一致 —— 默认走尾部省略，且只在真的溢出时才挂额外能力。
            //
            // ⚠️ 只有**正在播放**的那一行是焦点位（emphasized）：列表里若每行都滚，
            // 一屏十几个动画同时跑既吃帧率又像屏保。isCurrent 在一次渲染里
            // 天然只有一个为真，跑马灯等于自带限流（见 CpTextReveal.Marquee）。
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CpText(
                        text = track.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        color = rowPrimaryTextColor,
                        emphasized = isCurrent,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    val mvId = track.mvId
                    if (mvId != null && mvId > 0) {
                        val nav = LocalAppNavigator.current
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.clickable {
                                nav.push(AppRoute.VideoPlayer("netease://mv/$mvId", title = "${track.name} - MV"))
                            },
                        ) {
                            CpText(
                                text = "MV",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            },
            supportingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CpText(
                        text = supportingText ?: buildString {
                            append(track.artist)
                            if (actualShowAlbum && !track.album.isNullOrBlank()) {
                                append(" · ")
                                append(track.album)
                            }
                            if (!supportingSource.isNullOrBlank()) {
                                if (isNotEmpty()) append(" · ")
                                append(supportingSource)
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = rowSecondaryTextColor,
                        maxLines = 1,
                        emphasized = isCurrent,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!supportingBadge.isNullOrBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (supportingBadgeAlert) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                cpNestedSurface()
                            },
                        ) {
                            CpText(
                                text = supportingBadge,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (supportingBadgeAlert) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            },
            trailingContent = if (showMoreButton) {{
            // 桌面端：MoreVert 点击弹出锚定菜单（与右键同一份 items，见 CpAnchoredMenu），
            // 不再走底部弹层；移动端保持 SongOptionsSheet 动线不变。
            // 移动端若未提供 onOptionsClick（如本地歌曲场景），回退到自适应底部弹层 CpContextMenuBottomSheet。
            var menuExpanded by remember { mutableStateOf(false) }
            CpAnchoredMenu(
                expanded = menuExpanded,
                onDismiss = { menuExpanded = false },
                items = contextMenu,
            ) {
                IconButton(
                    onClick = {
                        if (useAnchoredMenu) menuExpanded = true else onOptionsClick?.invoke()
                    },
                    colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors(
                        containerColor = cpNestedSurface(),
                    ),
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = s.contextMenu.moreActions,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }} else null,
        )
    }

    val swipeSettingEnabled by AppModel.songItemSwipeActionsFlow.collectAsState()
    val actualSwipeEnabled = swipeEnabled && !selectionMode &&
        (isAndroidPlatform() || DesktopShell.isMobileMode) &&
        swipeSettingEnabled

    val resolvedOnPlayNext: () -> Unit = onPlayNext ?: {
        if (AppModel.initialized.value) {
            AppModel.modelScope.launch {
                runCatching { AppModel.playback.addNextToQueue(AppModel.toMediaId(track.id)) }
            }
        }
        UiEvents.notify(s.library.playNext)
    }
    val resolvedOnAddToQueue: () -> Unit = onAddToQueue ?: {
        if (AppModel.initialized.value) {
            AppModel.modelScope.launch {
                runCatching { AppModel.playback.addToQueue(AppModel.toMediaId(track.id)) }
            }
        }
        UiEvents.notify(s.library.queuedToPlay)
    }

    val rowContent: @Composable (Modifier) -> Unit = { contentModifier ->
        if (actualSwipeEnabled) {
            SwipeableSongRow(
                trackId = track.id,
                index = index,
                total = total,
                playNextLabel = s.contextMenu.playNext,
                addToQueueLabel = s.contextMenu.addToQueue,
                onPlayNext = resolvedOnPlayNext,
                onAddToQueue = resolvedOnAddToQueue,
                modifier = contentModifier,
            ) { swiping ->
                row(Modifier, swiping)
            }
        } else {
            row(contentModifier, false)
        }
    }

    // 桌面右键菜单：非多选且传入了菜单项时才生效（见参数 KDoc）。
    if (contextMenu != null && !selectionMode) {
        CpContextMenu(items = contextMenu, modifier = modifier) {
            rowContent(Modifier)
        }
    } else {
        rowContent(modifier)
    }
}

/**
 * M3 Expressive 跟手左右滑动容器：
 * - 右滑触发「下一首播放」（`primaryContainer` / `primary`）；
 * - 左滑触发「添加到队列」（`tertiaryContainer` / `tertiary`）；
 * - 未达阈值时 C1 连续阻尼跟随并在松手后按 `CpMotion.spatial()` 回弹，不抢占点击、长按与纵向滚动。
 */
@Composable
private fun SwipeableSongRow(
    trackId: String,
    index: Int,
    total: Int,
    playNextLabel: String,
    addToQueueLabel: String,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (swiping: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalCpHaptics.current
    val coroutineScope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    var isDragging by remember { mutableStateOf(false) }
    // 锁定单次滑动方向，防止回弹 spring 微微过冲过 0 时背景颜色/图标闪切到另一侧。
    var activeDirection by remember { mutableIntStateOf(0) }

    val currentOnPlayNext by rememberUpdatedState(onPlayNext)
    val currentOnAddToQueue by rememberUpdatedState(onAddToQueue)
    val currentHaptics by rememberUpdatedState(haptics)
    val currentSpatialSpec by rememberUpdatedState(CpMotion.spatial<Float>())

    LaunchedEffect(trackId) {
        if (offsetX.value != 0f || isDragging) {
            offsetX.snapTo(0f)
            isDragging = false
            activeDirection = 0
        }
    }

    val thresholdPx = with(density) { SongSwipeThresholdDp.toPx() }
    val extraPx = with(density) { SongSwipeOvershootDp.toPx() }
    val currentOffset = offsetX.value
    val isVisualActive = isDragging || abs(currentOffset) > 0.5f
    val slotShape = remember(index, total) {
        legacySegmentShape(index.coerceAtLeast(0), total.coerceAtLeast(1))
    }

    val gestureModifier = Modifier.pointerInput(thresholdPx, extraPx) {
        val touchSlop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val pointerId = down.id
            var horizontalLocked = false
            var abortedForScroll = false
            var lastDamped = 0f
            var wasArmed = false
            var pointerUp = false

            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                    if (!change.pressed) {
                        if (horizontalLocked) {
                            change.consume()
                        }
                        pointerUp = true
                        break
                    }

                    if (!horizontalLocked) {
                        if (change.isConsumed) {
                            abortedForScroll = true
                            break
                        }
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        val absDx = abs(dx)
                        val absDy = abs(dy)

                        if (absDy > touchSlop && absDy >= absDx) {
                            abortedForScroll = true
                            break
                        }
                        if (absDx > touchSlop && absDx > absDy * 1.2f) {
                            horizontalLocked = true
                            isDragging = true
                            activeDirection = if (dx >= 0f) 1 else -1
                            change.consume()
                            val damped = applySwipeResistance(dx, thresholdPx, extraPx)
                            lastDamped = damped
                            val armedNow = abs(damped) >= thresholdPx
                            if (armedNow && !wasArmed) {
                                currentHaptics.perform(CpHaptic.Tick)
                            }
                            wasArmed = armedNow
                            coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                offsetX.snapTo(damped)
                            }
                        }
                    } else {
                        change.consume()
                        val dx = change.position.x - down.position.x
                        if (dx != 0f) {
                            activeDirection = if (dx >= 0f) 1 else -1
                        }
                        val damped = applySwipeResistance(dx, thresholdPx, extraPx)
                        lastDamped = damped
                        val armedNow = abs(damped) >= thresholdPx
                        if (armedNow != wasArmed) {
                            if (armedNow) {
                                currentHaptics.perform(CpHaptic.Tick)
                            }
                            wasArmed = armedNow
                        }
                        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            offsetX.snapTo(damped)
                        }
                    }
                }
            } finally {
                if (horizontalLocked) {
                    isDragging = false
                    val completedNormally = pointerUp && !abortedForScroll
                    if (completedNormally && lastDamped >= thresholdPx) {
                        currentHaptics.perform(CpHaptic.Confirm)
                        currentOnPlayNext()
                    } else if (completedNormally && lastDamped <= -thresholdPx) {
                        currentHaptics.perform(CpHaptic.Confirm)
                        currentOnAddToQueue()
                    }
                    coroutineScope.launch {
                        offsetX.animateTo(
                            targetValue = 0f,
                            animationSpec = currentSpatialSpec,
                        )
                        if (!isDragging) activeDirection = 0
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (isVisualActive) Modifier.clip(slotShape) else Modifier)
            .then(gestureModifier),
    ) {
        if (isVisualActive) {
            val isRight = if (isDragging) currentOffset >= 0f else activeDirection >= 0
            val directedOffsetPx = if (isDragging) {
                abs(currentOffset)
            } else {
                val dir = if (activeDirection != 0) activeDirection else if (currentOffset >= 0f) 1 else -1
                (currentOffset * dir).coerceAtLeast(0f)
            }
            val progress = (directedOffsetPx / thresholdPx).coerceIn(0f, 1f)
            val armed = isDragging && directedOffsetPx >= thresholdPx
            val revealedDp = with(density) { directedOffsetPx.toDp() }

            val backdropColor by animateColorAsState(
                targetValue = when {
                    !armed -> cpNestedSurface()
                    isRight -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.tertiaryContainer
                },
                animationSpec = CpMotion.effectsFast(),
                label = "songSwipeBackdrop",
            )
            val pillColor by animateColorAsState(
                targetValue = when {
                    !armed -> cpCardSurface()
                    isRight -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.tertiary
                },
                animationSpec = CpMotion.effectsFast(),
                label = "songSwipePillColor",
            )
            val iconTint by animateColorAsState(
                targetValue = when {
                    !armed -> MaterialTheme.colorScheme.onSurfaceVariant
                    isRight -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onTertiary
                },
                animationSpec = CpMotion.effectsFast(),
                label = "songSwipeIconTint",
            )
            val labelColor by animateColorAsState(
                targetValue = when {
                    !armed -> MaterialTheme.colorScheme.onSurfaceVariant
                    isRight -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onTertiaryContainer
                },
                animationSpec = CpMotion.effectsFast(),
                label = "songSwipeLabelColor",
            )
            val pillScale by animateFloatAsState(
                targetValue = if (armed) 1.08f else 0.78f + 0.22f * progress,
                animationSpec = CpMotion.spatialFast(),
                label = "songSwipePillScale",
            )
            val labelAlpha by animateFloatAsState(
                targetValue = if (progress >= 0.4f) ((progress - 0.4f) / 0.45f).coerceIn(0f, 1f) else 0f,
                animationSpec = CpMotion.effectsFast(),
                label = "songSwipeLabelAlpha",
            )

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(backdropColor),
            ) {
                Box(
                    modifier = Modifier
                        .align(if (isRight) Alignment.CenterStart else Alignment.CenterEnd)
                        .width(revealedDp)
                        .fillMaxHeight()
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Surface(
                            shape = CpShapes.full,
                            color = pillColor,
                            modifier = Modifier
                                .size(width = 40.dp, height = 28.dp)
                                .graphicsLayer {
                                    scaleX = pillScale
                                    scaleY = pillScale
                                    alpha = (0.35f + 0.65f * progress).coerceIn(0f, 1f)
                                },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isRight) Icons.Filled.SkipNext else Icons.AutoMirrored.Filled.QueueMusic,
                                    contentDescription = null,
                                    tint = iconTint,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        CpText(
                            text = if (isRight) playNextLabel else addToQueueLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = labelColor,
                            fontWeight = if (armed) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.graphicsLayer { alpha = labelAlpha },
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier.graphicsLayer { translationX = currentOffset },
        ) {
            content(isVisualActive)
        }
    }
}

private fun applySwipeResistance(rawDelta: Float, thresholdPx: Float, extraPx: Float): Float {
    val absRaw = abs(rawDelta)
    if (absRaw <= thresholdPx) return rawDelta
    val overshoot = absRaw - thresholdPx
    val damped = thresholdPx + extraPx * (1f - exp(-overshoot / extraPx))
    return if (rawDelta >= 0f) damped else -damped
}
