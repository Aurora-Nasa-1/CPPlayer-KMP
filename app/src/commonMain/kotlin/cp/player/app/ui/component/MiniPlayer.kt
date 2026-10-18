package cp.player.app.ui.component

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.anim.coverFlightTarget
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.theme.CpShapes
import cp.player.app.ui.util.LyricOffsetManager
import cp.player.app.ui.util.next
import cp.player.app.ui.util.resized
import cp.player.core.music.TrackSummary
import cp.player.core.playback.LyricsState
import cp.player.core.playback.PlaybackUiState
import cp.player.core.playback.RepeatMode
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 底部 MiniBar。
 *
 * 视觉：顶圆角 28dp + 底圆角 16dp 的 Card，surfaceContainerHigh 色，2dp 抬升阴影；
 * 内含 48dp 封面 / 标题 / 歌手 / 上一首 + 播放暂停 + 下一首；下方贴底一条**直线**进度条。
 *
 * Expressive 化的两处：
 * - 进度条用 [CpLinearProgress]（M3 Expressive 的非波形形态：圆头 + 末端 stop indicator）。
 *   ⚠️ **这里刻意不用 [CpWavyProgress]**：波形要靠"高度"才能看出起伏，压到 4dp 的窄条上
 *   只剩一团抖动的色块，而迷你播放器是**余光扫一眼**的地方 —— 直线更易读也更安静。
 *   波形留给桌面播放页那条大尺度、可拖动的 [CpSeekBar]
 *   （移动端播放页已改用旧版同款直线进度条 [CpPlainSeekBar]）。
 * - 播放/暂停换成 [CpPlayPauseButton]，按下时圆角收缩 + 图标回弹。
 *
 * ## 两套排布：手机三按钮 / 桌面三区
 *
 * 判据是 [LocalWindowChromeActive]（**窗口 chrome 是否接管**）而不是平台 ——
 * 与 `AppScaffold` / `MainScreen` 的顶栏判据同源：宽屏平板走的是同一套桌面外壳。
 *
 * - 手机（默认）：封面 + 两行文字 + 上一首/播放/下一首。底栏在手机上只有 ~360dp 宽，
 *   再塞随机 / 循环 / 队列只会把歌名挤成两个字。
 * - 桌面（[MiniPlayerDesktopBar]）：左 = 封面 + 曲目（溢出走 [CpText] 跑马灯）+ 喜欢；
 *   中 = 随机 / 上一首 / 播放 / 下一首 / 循环；右 = 队列 / 音量 / 音质 / 更多。
 *   桌面窗口最窄也有 900dp，横向空间足够 —— 这些控件在桌面播放页里本来就有，
 *   底栏没有的话，想切循环 / 调音量就必须先进播放页。
 *
 * 小按钮一律**不带常驻底色**（M3 Expressive 的次级动作语汇）：只有悬停 / 激活时才浮出
 * 一层淡底或主色，静止时与底栏融为一体。唯一有实底的是播放键 —— 它是这一屏的主动作。
 *
 * 点击主体区域 → [onClick]（展开全屏播放页）。
 * 仅当 [PlaybackUiState.currentTrack] 非空时渲染。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.MiniPlayer(
    state: PlaybackUiState,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
    onClick: () -> Unit,
    onTogglePlay: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val track = state.currentTrack ?: return
    // 封面飞行落点：飞行未落位时把自己的封面藏起来，由飞行器顶替显示。
    val hideCover = CoverFlight.isFlyingTo(CoverFlight.TARGET_MINI)
    val progress = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else 0f
    val coverCorner by animateDpAsState(
        targetValue = if (state.isPlaying) 24.dp else 12.dp,
        animationSpec = CpMotion.spatial(),
        label = "miniCoverCornerMorph",
    )
    val coverShape = remember(coverCorner) { RoundedCornerShape(coverCorner) }
    val press = rememberPressedScale()
    val desktop = LocalWindowChromeActive.current
    val s = cpStrings()

    // 队列弹层 / 「更多」弹层的开关（仅桌面分支会用到）。
    var showQueueSheet by remember { mutableStateOf(false) }
    val moreSheets = rememberPlayerMoreSheetState()

    Surface(
        onClick = onClick,
        interactionSource = press.first,
        shape = CpShapes.miniPlayer,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .then(press.second)
            .sharedBounds(
                sharedContentState = rememberSharedContentState(key = "player-container"),
                animatedVisibilityScope = animatedVisibilityScope
            ),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Column {
            // 「一起听」条：只在已经在房间里时绘制（见 ListenTogetherStrip 的 KDoc）。
            // 放在小播放器**内部**而不是宿主上：两个宿主共用本组件，一处改动即覆盖
            // 三个 tab 与所有路由页；而且尾留白量的是本组件的实测高度，加在这里
            // 留白会自动跟着变，不会出现「条把列表最后一行压住」。
            ListenTogetherStrip()

            if (desktop) {
                val isFavorite by AppModel.currentFavoriteFlow.collectAsState()
                val qualityLevel by AppModel.playbackQualityFlow.collectAsState()
                MiniPlayerDesktopBar(
                    state = state,
                    isFavorite = isFavorite,
                    qualityLevel = qualityLevel,
                    cover = {
                        MiniCover(
                            track = track,
                            hideCover = hideCover,
                            coverCorner = coverCorner,
                            coverShape = coverShape,
                            isPlaying = state.isPlaying,
                            animatedVisibilityScope = animatedVisibilityScope,
                            size = 48.dp,
                            contentScale = ContentScale.Crop,
                        )
                    },
                    info = {
                        MiniTrackInfo(
                            track = track,
                            isPlaying = state.isPlaying,
                            lyricText = currentLyricLine(state),
                            titleStyle = MaterialTheme.typography.titleSmall,
                            secondLineStyle = MaterialTheme.typography.bodySmall,
                            animatedVisibilityScope = animatedVisibilityScope,
                            // 桌面底栏第二行用「悬停浮出」而不是跑马灯：一屏同时跑两条
                            // 滚动文字太吵，而桌面上指针本来就在（见 CpTextReveal）。
                            secondLineEmphasized = false,
                        )
                    },
                    onTogglePlay = onTogglePlay,
                    onSkipPrev = onSkipPrev,
                    onSkipNext = onSkipNext,
                    // 以下动作直接走 AppModel：它们与播放页里同名按钮是**同一个动作**，
                    // 再经宿主转发一遍只会多两处可能漂移的接线。
                    onShuffle = { AppModel.playback.toggleShuffle() },
                    onRepeat = { AppModel.playback.setRepeatMode(state.repeatMode.next()) },
                    onLike = { AppModel.toggleFavoriteOf(track.id) },
                    onVolumeChange = { AppModel.playback.setVolume(it) },
                    onQualityChange = AppModel::setPlaybackQuality,
                    onQueue = { showQueueSheet = true },
                    onMore = { moreSheets.showMoreMenu = true },
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MiniCover(
                        track = track,
                        hideCover = hideCover,
                        coverCorner = coverCorner,
                        coverShape = coverShape,
                        isPlaying = state.isPlaying,
                        animatedVisibilityScope = animatedVisibilityScope,
                        size = 48.dp,
                        contentScale = ContentScale.Crop,
                    )
                    Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        MiniTrackInfo(
                            track = track,
                            isPlaying = state.isPlaying,
                            lyricText = currentLyricLine(state),
                            titleStyle = MaterialTheme.typography.titleSmall,
                            secondLineStyle = MaterialTheme.typography.bodySmall,
                            animatedVisibilityScope = animatedVisibilityScope,
                            // 手机上两条都跑（与治理前一致）：没有悬停，且底栏是唯一的
                            // 「现在在放什么」，跑马灯是唯一能读全的途径。
                            secondLineEmphasized = true,
                            // 手机上第二行有歌词时用主色（「正在唱这句」），没有歌词时是歌手名。
                            lyricHighlight = true,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MiniBarIconButton(
                            icon = Icons.Filled.SkipPrevious,
                            contentDescription = s.player.previousTrack,
                            onClick = onSkipPrev,
                            size = 40.dp,
                            iconSize = 24.dp,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                        CpPlayPauseButton(
                            isPlaying = state.isPlaying,
                            onClick = onTogglePlay,
                            size = 40.dp,
                            isLoading = state.isBuffering,
                        )
                        MiniBarIconButton(
                            icon = Icons.Filled.SkipNext,
                            contentDescription = s.player.nextTrack,
                            onClick = onSkipNext,
                            size = 40.dp,
                            iconSize = 24.dp,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // 直线进度条：左右留 12dp 与文字对齐，底部留 8dp 让它"浮"在卡片里而不是贴边。
            CpLinearProgress(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 8.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
            )
            val errorText = state.error
            if (!errorText.isNullOrBlank()) {
                CpText(
                    errorText,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                )
            }
        }
    }

    // 弹层挂在卡片**外面**：ModalBottomSheet 自己带一层 scrim 与独立窗口，
    // 塞进带 onClick 的 Surface 里会让它的点击落到底栏的「展开播放页」上。
    if (desktop) {
        if (showQueueSheet) {
            // 与 PlayerMoreSheets 同一条纪律：控制器只在这一分支里解析 ——
            // 离屏渲染 / preview 环境没有后端，急切求值会让整个组件不可组合。
            val controller = AppModel.playback
            val scope = rememberCoroutineScope()
            QueueBottomSheet(
                queue = state.queue,
                currentIndex = state.currentIndex,
                onPlayAt = { index -> scope.launch { controller.playAt(index) } },
                onRemove = { index -> scope.launch { controller.removeQueueItem(index) } },
                onMove = { from, to -> scope.launch { controller.moveQueueItem(from, to) } },
                onClear = { controller.clearQueue() },
                onClose = { showQueueSheet = false },
            )
        }
        PlayerMoreSheets(state = state, sheets = moreSheets)
    }
}

/**
 * 当前应展示的那一行「副标题」：有逐行歌词就用当前这句，否则退回歌手名。
 *
 * 抽出来是因为桌面 / 手机两套排布都要用；判定规则（含歌词偏移补偿）只此一份。
 */
private fun currentLyricLine(state: PlaybackUiState): String {
    val trackId = state.currentTrack?.id.orEmpty()
    val lyricOffset = if (trackId.isNotBlank()) LyricOffsetManager.getOffset(trackId) else 0L
    val effectivePos = state.positionMs + lyricOffset
    val success = state.lyrics as? LyricsState.Success ?: return ""
    if (success.lines.isEmpty()) return ""
    val line = success.lines.lastOrNull { effectivePos >= it.time } ?: success.lines.first()
    return line.text.trim().ifEmpty { line.translation?.trim().orEmpty() }
}

// =====================================================================
// 桌面底栏：左 / 中 / 右 三区
// =====================================================================

/**
 * 桌面底部迷你播放器的**版式本体**（无 `SharedTransitionScope`、无 AppModel 依赖，
 * 封面与曲目信息以 slot 传入）—— 便于 `ImageComposeScene` 离屏核对各窗口宽度下的排布。
 *
 * ## 三区怎么分
 *
 * 中区（走带）**必须**在视觉中心：它是这一屏的主控件，窗口一拉宽就看见按钮往右漂，
 * 会以为布局错位。所以宽度足够时左区和右区**等权**（[MiniZoneThreshold] 以上），
 * 右区内容靠 `CenterEnd` 贴边 —— 这样中区正好落在整条底栏的中线上。
 *
 * 左区里真正显示歌名的那块**有上限**（[MINI_INFO_MAX_WIDTH]）：1280 / 1920 下如果让它
 * 一路吃掉剩余宽度，歌名与「喜欢」之间会空出一大片，而歌名又永远滚不起来 ——
 * 那正是「左边控制最大显示范围」要解决的问题。超出的部分交给 [CpText] 的跑马灯 /
 * 悬停浮出（见 `CpTextReveal`），而不是把整行拉长。
 *
 * 宽度分档只做一件事：窄到 560dp 以下就收掉音质文字（只留一个图标甚至整块收起）。
 * 音量**不占常驻宽度** —— 点击才浮出滑杆弹层，见 [MiniVolumeControl]。
 */
private val MINI_INFO_MAX_WIDTH = 460.dp

/**
 * 底栏小按钮的边长。
 *
 * 音量弹层的锚点偏移要按「按钮高」算（见 [MiniVolumeControl]），所以抽成常量 ——
 * 两处各写一个 `32.dp` 的话，哪天按钮改大，弹层就会压到按钮上。
 */
private val MiniBarButtonSize = 32.dp

/**
 * 左右等权（中区严格居中）的宽度下限。
 *
 * 取 760dp 而不是「窗口最小宽度 900dp」：这里量的是**底栏内部**的可用宽度
 * （窗口宽 - 两侧留白 - 底栏内边距），900dp 的窗口到这一层只剩 ~860dp。
 * 760 之下左右各只剩 < 294dp，右区那一排（队列 + 音量 + 音质 + 更多，约 168dp；
 * 音量收进弹层后省下了一条 84dp 的滑轨）
 * 就会把左区挤到只剩两个字，此时宁可让中区偏一点，也要保住歌名的可读宽度。
 */
private val MiniZoneThreshold = 760.dp

@Composable
internal fun MiniPlayerDesktopBar(
    state: PlaybackUiState,
    isFavorite: Boolean,
    qualityLevel: String,
    cover: @Composable () -> Unit,
    info: @Composable () -> Unit,
    onTogglePlay: () -> Unit,
    onSkipPrev: () -> Unit,
    onSkipNext: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onLike: () -> Unit,
    onQueue: () -> Unit,
    onMore: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onQualityChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = cpStrings()
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        // 桌面窗口最窄 900dp；这一档只在「侧栏把宽度吃掉」时才会命中。
        // 音量不再占常驻横向空间（点击才浮出弹层），所以只剩音质这一档。
        val showQualityChip = maxWidth >= 560.dp
        val centeredTransport = maxWidth >= MiniZoneThreshold
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ── 左区：封面 + 曲目信息（限宽）+ 喜欢 ──
            //
            // 文字块的宽度**算出来**而不是靠 weight 抢：weight + fillMaxWidth 会把本区
            // 剩余宽度吃干，后面的「喜欢」就被挤出本区（720dp 下实测把心形顶到走带下面）。
            // 这里显式减掉封面 / 间距 / 心形，再夹到 [MINI_INFO_MAX_WIDTH]：
            // 短歌名不会让心形跳位（块宽固定），长歌名也不会把心形推出去（绝不超算）。
            BoxWithConstraints(Modifier.weight(1f)) {
                val textWidth = (maxWidth - 48.dp - 12.dp - 32.dp)
                    .coerceAtLeast(0.dp)
                    .coerceAtMost(MINI_INFO_MAX_WIDTH)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    cover()
                    Box(Modifier.padding(start = 12.dp).width(textWidth)) { info() }
                    MiniBarIconButton(
                        icon = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (isFavorite) s.player.unlike else s.player.like,
                        onClick = onLike,
                        active = isFavorite,
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            // ── 中区：走带（随机 / 上一首 / 播放 / 下一首 / 循环），位置与窗口宽度无关 ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                MiniBarIconButton(
                    icon = Icons.Filled.Shuffle,
                    contentDescription = s.player.shuffle,
                    onClick = onShuffle,
                    active = state.shuffleEnabled,
                )
                MiniBarIconButton(
                    icon = Icons.Filled.SkipPrevious,
                    contentDescription = s.player.previousTrack,
                    onClick = onSkipPrev,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                CpPlayPauseButton(
                    isPlaying = state.isPlaying,
                    onClick = onTogglePlay,
                    size = 38.dp,
                    isLoading = state.isBuffering,
                )
                MiniBarIconButton(
                    icon = Icons.Filled.SkipNext,
                    contentDescription = s.player.nextTrack,
                    onClick = onSkipNext,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                MiniBarIconButton(
                    icon = if (state.repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = s.player.repeat,
                    onClick = onRepeat,
                    active = state.repeatMode != RepeatMode.OFF,
                )
            }

            Spacer(Modifier.width(10.dp))

            // ── 右区：队列 / 音量 / 音质 / 更多 ──
            if (centeredTransport) {
                // 与左区等权、内容贴右：中区因此落在整条底栏的中线上。
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    MiniRightCluster(
                        state = state,
                        qualityLevel = qualityLevel,
                        showQualityChip = showQualityChip,
                        onQueue = onQueue,
                        onMore = onMore,
                        onVolumeChange = onVolumeChange,
                        onQualityChange = onQualityChange,
                    )
                }
            } else {
                MiniRightCluster(
                    state = state,
                    qualityLevel = qualityLevel,
                    showQualityChip = showQualityChip,
                    onQueue = onQueue,
                    onMore = onMore,
                    onVolumeChange = onVolumeChange,
                    onQualityChange = onQualityChange,
                )
            }
        }
    }
}

/** 右区那一排：队列、音量（点击浮出滑杆）、音质、更多。 */
@Composable
private fun MiniRightCluster(
    state: PlaybackUiState,
    qualityLevel: String,
    showQualityChip: Boolean,
    onQueue: () -> Unit,
    onMore: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onQualityChange: (String) -> Unit,
) {
    val s = cpStrings()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        MiniBarIconButton(
            icon = Icons.AutoMirrored.Filled.QueueMusic,
            contentDescription = s.player.queue,
            onClick = onQueue,
        )
        MiniVolumeControl(
            volume = state.volume,
            onVolumeChange = onVolumeChange,
        )
        if (showQualityChip) {
            QualityMenuChip(level = qualityLevel, onSelect = onQualityChange)
        }
        MiniBarIconButton(
            icon = Icons.Filled.MoreVert,
            contentDescription = s.player.more,
            onClick = onMore,
        )
    }
}

/**
 * 底栏的小图标按钮：**静止时没有任何底色**，只有悬停时浮出一层极淡的容器色。
 *
 * 它与 `IconButton` 的区别就在这里 —— `IconButton` 只有按压涟漪，桌面上鼠标扫过去
 * 完全没有反馈；而给每颗都套一层 `FilledIconButton` 又会让底栏变成一排色块
 * （用户明确要求「小按钮不要有背景」）。激活态（随机 / 循环 / 已收藏）用**主色图标**
 * 表达，仍然不加底。
 */
@Composable
private fun MiniBarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = MiniBarButtonSize,
    iconSize: Dp = 18.dp,
    active: Boolean = false,
    tint: Color? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val container by animateColorAsState(
        targetValue = if (hovered) {
            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)
        } else {
            Color.Transparent
        },
        animationSpec = CpMotion.effectsFast(),
        label = "miniBarIconContainer",
    )
    Surface(
        onClick = onClick,
        modifier = modifier.size(size).hoverable(interaction),
        shape = CircleShape,
        color = container,
        interactionSource = interaction,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
                tint = when {
                    active -> MaterialTheme.colorScheme.primary
                    tint != null -> tint
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * 底栏的音量：**点一下才浮出滑杆**。
 *
 * 为什么不再常驻一条滑轨：底栏的横向空间是要留给歌名的 —— 一条 84dp 的滑轨
 * 等于永久借走两个字的位置，而音量是**偶尔才动一次**的参数。收进弹层后，
 * 静默状态下这颗按钮和其它小按钮一样只占 32dp。
 *
 * 图标本身就表达当前音量（静音 / 中低 / 偏高），**不再承担静音动作** ——
 * 那颗键的位置现在是「打开滑杆」，静音挪进弹层里，两个动作就不会抢同一个手势。
 *
 * 读的是 [PlaybackUiState.volume]（用户设定值，不是淡入淡出期间的瞬时音量）——
 * 与桌面播放页那条音量条同一个事实源，两处互相看得见。
 */
@Composable
private fun MiniVolumeControl(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = cpStrings()
    var expanded by remember { mutableStateOf(false) }
    val muted = volume <= 0.001f
    val icon = when {
        muted -> Icons.AutoMirrored.Filled.VolumeOff
        volume < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
        else -> Icons.AutoMirrored.Filled.VolumeUp
    }
    Box(modifier) {
        MiniBarIconButton(
            icon = icon,
            contentDescription = s.player.volume,
            onClick = { expanded = true },
            active = expanded || muted,
        )
        if (expanded) {
            // 锚点只有按钮那么大（[MiniBarButtonSize]）。用 `BottomEnd` 让弹层的
            // **右下角**贴住按钮的右下角，再整体上移「按钮高 + 8dp」⇒ 弹层稳定落在
            // 底栏**上方**、右边缘与音量键对齐。关键在于这个偏移只跟**按钮**高度有关，
            // 与弹层自身宽高无关 —— 后者随字号 / 语言变，拿它算偏移就会错位。
            val lift = with(LocalDensity.current) { (MiniBarButtonSize + 8.dp).roundToPx() }
            Popup(
                alignment = Alignment.BottomEnd,
                offset = IntOffset(0, -lift),
                onDismissRequest = { expanded = false },
                // clippingEnabled = false：桌面端 Popup 是独立窗口，关掉钳制后才能
                // 伸出底栏 / 主窗口边界而不被裁掉（与 [CpContextMenu] 同一处理）。
                properties = PopupProperties(focusable = true, clippingEnabled = false),
            ) {
                MiniVolumePopupCard(volume = volume, onVolumeChange = onVolumeChange)
            }
        }
    }
}

/**
 * 音量弹层的**本体**。
 *
 * 独立成函数是为了能离屏核对：桌面端 `Popup` 是另一个窗口，`ImageComposeScene`
 * 里抓不到它 —— 版式出图直接渲染这张卡片，量的就是用户看到的那块。
 */
@Composable
internal fun MiniVolumePopupCard(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = cpStrings()
    // 记住「静音前是多少」：点静音键要能回到原音量，而不是回到 100%。
    var lastNonZero by remember { mutableStateOf(if (volume > 0.05f) volume else 1f) }
    SideEffect { if (volume > 0.05f) lastNonZero = volume }
    val muted = volume <= 0.001f
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MiniBarIconButton(
                icon = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (muted) s.player.unmute else s.player.mute,
                onClick = { onVolumeChange(if (muted) lastNonZero else 0f) },
                active = muted,
            )
            Slider(
                value = volume,
                onValueChange = onVolumeChange,
                valueRange = 0f..1f,
                modifier = Modifier.width(148.dp),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            )
            Text(
                // 数字用 ASCII 百分号：跟着数值走，不需要翻译。
                text = "${(volume * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 34.dp),
            )
        }
    }
}

// =====================================================================
// 两套排布共用的小块
// =====================================================================

/** 底栏封面：有图就画图，没图走全应用统一的 Expressive 占位（见 [CpCoverPlaceholder]）。 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.MiniCover(
    track: TrackSummary,
    hideCover: Boolean,
    coverCorner: Dp,
    coverShape: androidx.compose.ui.graphics.Shape,
    isPlaying: Boolean,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
    size: Dp,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.size(size).graphicsLayer { alpha = if (hideCover) 0f else 1f },
    ) {
        if (!track.coverUrl.isNullOrBlank()) {
            // 用 150px 缩略图，省内存与带宽。
            AsyncImage(
                model = track.coverUrl.resized(150),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
                    .coverFlightTarget(CoverFlight.TARGET_MINI, coverCorner)
                    .sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "cover-${track.id}"),
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                    .clip(coverShape),
                contentScale = contentScale,
            )
        } else {
            // 空封面走全应用统一的 Expressive 占位（渐变 + 形状），
            // 而不是本地再画一个灰底 MusicNote —— 同一个「没有封面」
            // 不该在迷你播放器和播放页长得不一样。
            CpCoverPlaceholder(
                modifier = Modifier.fillMaxSize(),
                corner = coverCorner,
                // 48dp 下变形看不出来，关掉省一条常驻动画。
                animated = false,
            )
        }
        // 「正在响」标记：与列表项里那颗均衡器**完全同款**。
        // 有它在，用户余光扫到底栏就知道还在放，不必去读播放/暂停按钮的图标。
        //
        // **常驻**（不再 `if (isPlaying)`）：播放中是跳动的三根棒，暂停时
        // 塌成三个点 —— 「当前这首」与「它在放还是停着」两件事都一眼可读。
        // 暂停态是静态绘制，不挂无限动画，所以常驻不等于常驻开销。
        CpPlayingEqualizer(
            modifier = Modifier.align(Alignment.BottomStart).padding(3.dp),
            isPlaying = isPlaying,
        )
    }
}

/**
 * 底栏的曲目信息（标题 + 副标题）。
 *
 * 标题一行是**焦点位**：溢出即跑马灯（[CpText.emphasized]），暂停时停住 ——
 * 画面跟着声音一起停。`basicMarquee` 只把无限宽约束发给子节点、自己上报的仍是父级
 * 给的受限尺寸，所以挂在它外面的 `sharedBounds` 量到的 bounds 不受影响
 * （守卫测试见 `CpTextMarqueeTest`）。
 *
 * @param lyricText 当前歌词行；非空时作为副标题并（在手机排布下）用主色。
 * @param secondLineEmphasized 副标题是否也允许跑马灯。桌面上为 false ⇒ 降级为悬停浮出。
 * @param lyricHighlight 副标题是歌词时是否用主色；桌面底栏与手机底栏的既有观感不同，
 *   这里显式区分而不是让两处各写一遍。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.MiniTrackInfo(
    track: TrackSummary,
    isPlaying: Boolean,
    lyricText: String,
    titleStyle: TextStyle,
    secondLineStyle: TextStyle,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
    secondLineEmphasized: Boolean,
    lyricHighlight: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
        CpText(
            text = track.name,
            style = titleStyle,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurface,
            emphasized = true,
            // 暂停时停住不滚：画面跟着声音一起停。见 CpText.marqueeEnabled。
            marqueeEnabled = isPlaying,
            modifier = Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = "title-${track.id}"),
                animatedVisibilityScope = animatedVisibilityScope,
            )
        )
        CpText(
            text = lyricText.ifBlank { track.artist },
            style = secondLineStyle,
            maxLines = 1,
            color = if (lyricHighlight && lyricText.isNotBlank()) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            emphasized = secondLineEmphasized,
            marqueeEnabled = isPlaying,
            modifier = Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = "artist-${track.id}"),
                animatedVisibilityScope = animatedVisibilityScope,
            )
        )
    }
}
