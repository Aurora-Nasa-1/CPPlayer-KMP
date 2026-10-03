package cp.player.app.ui.anim

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.util.resized
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** 等待目标注册的上限：超过则判定「目标不会出现」，原地淡出取消。 */
private const val TARGET_WAIT_MS = 1600L

/** 淡出交还 / 取消的时长。 */
private const val DISMISS_MS = 180L

/**
 * 封面飞行（自制共享元素）。
 *
 * 为什么不用 `SharedTransitionLayout`：飞行跨越的两端分别住在不同的组合子树里
 * （列表项在 Tab 内、MiniPlayer 在 MainScreen 或 App 根、歌单头部在新页面），
 * 且目标出现的时机受网络解析（`currentTrack` 延迟 emit）与页面转场影响，
 * 原生 sharedBounds 无法可靠地把两端配成一对。因此改为：
 *
 * 1. 各处封面通过 [coverFlightSource] / [coverFlightTarget] 把自己的**根坐标矩形**
 *    注册进本对象的注册表（按 token 区分同键的多个实例，取 seq 最新的）；
 * 2. 交互点调用 [play] / [openPlaylist] 记下起点并激活一次 [Flight]；
 * 3. [CoverFlightHost]（挂在 App 最顶层）渲染飞行封面：先在源位「抬起」等待目标注册
 *    （播放要先解析曲目、页面要先入场），再用带回弹的 spring 飞向目标，落位后
 *    先让目标显形（飞行器正好叠在它上方），最后淡出交还。
 *
 * 平台差异由 `isAndroidPlatform()` 决定：Android 走 slow spatial + 上拱弧线
 * （触摸端要看得见起落、有"扔出去"的弧感），桌面走常规 spatial 直线
 * （窗口指针交互不需要夸张的弧线，节奏也不用那么拖）。
 *
 * 整条动画可在「设置 > 外观与主题 > 封面飞行动画」关掉（见 [CoverFlight.start]）。
 */
object CoverFlight {

    /** MiniPlayer 封面槽位键（MainScreen 内与 App 根两处宿主共用同一键，靠 seq 取最新）。 */
    const val TARGET_MINI = "target/mini-cover"

    /** 歌单详情页头部封面键（窄屏 56dp / 宽屏 176dp 互斥组合，不会同时出现）。 */
    const val TARGET_PLAYLIST_HEADER = "target/playlist-header"

    fun trackKey(trackId: String): String = "source/track:$trackId"

    fun playlistKey(playlistId: Long): String = "source/playlist:$playlistId"

    internal class Spot(
        val token: Any,
        val rect: Rect,
        val corner: Dp,
        val seq: Long,
    )

    internal class Flight(
        val id: Long,
        val targetKey: String,
        val coverUrl: String,
        val from: Rect,
        val fromCorner: Dp,
    ) {
        /** 落位后置 true：目标解除隐藏，由飞行器（正好叠在目标上方）淡出交还。 */
        val landed = mutableStateOf(false)
    }

    private val sources = mutableStateMapOf<String, SnapshotStateList<Spot>>()
    private val targets = mutableStateMapOf<String, SnapshotStateList<Spot>>()
    private var seqCounter = 0L
    private var idCounter = 0L

    private val _active = mutableStateOf<Flight?>(null)
    internal val active: State<Flight?> get() = _active

    /**
     * 点击播放：以 [trackId] 当前可见的封面为起点，飞向 [TARGET_MINI]。
     * 无封面或来源已不在屏上时静默忽略（直接播放，不播动画）。
     */
    fun play(trackId: String, coverUrl: String?) = start(TARGET_MINI, coverUrl, trackKey(trackId))

    /**
     * 打开歌单：以歌单卡封面为起点，飞向详情页头部 [TARGET_PLAYLIST_HEADER]。
     * 歌单打开本身走 fade 交叉淡入（目标位置静态、确定性高），飞行器与之叠加。
     */
    fun openPlaylist(playlistId: Long, coverUrl: String?) =
        start(TARGET_PLAYLIST_HEADER, coverUrl, playlistKey(playlistId))

    private fun start(targetKey: String, coverUrl: String?, sourceKey: String) {
        if (coverUrl.isNullOrBlank()) return
        // 「封面飞行动画」开关（设置 > 外观与主题，默认开）。在**起飞点**判一次：
        // 关掉时连 Flight 都不建，目标端（MiniPlayer / 详情页头部）也就不会被
        // 隐藏（见 isFlyingTo），页面按自身转场正常显示，不会留下空洞。
        if (!cp.player.app.AppModel.coverFlightAnimation()) return
        val spot = freshest(sources, sourceKey) ?: return
        _active.value = Flight(++idCounter, targetKey, coverUrl, spot.rect, spot.corner)
    }

    /**
     * 目标位置是否正等待本次飞行落位。为 true 时目标应把自己的封面隐藏，
     * 由飞行器顶替显示（飞行器落位淡出时才显形，避免「两层封面」闪一下）。
     */
    fun isFlyingTo(targetKey: String): Boolean {
        val flight = _active.value ?: return false
        return flight.targetKey == targetKey && !flight.landed.value
    }

    internal fun updateSource(key: String, token: Any, rect: Rect, corner: Dp) =
        upsert(sources, key, token, rect, corner)

    internal fun updateTarget(key: String, token: Any, rect: Rect, corner: Dp) =
        upsert(targets, key, token, rect, corner)

    internal fun removeSource(key: String, token: Any) = remove(sources, key, token)

    internal fun removeTarget(key: String, token: Any) = remove(targets, key, token)

    internal fun peekTarget(key: String): Spot? = freshest(targets, key)

    /** 仅当仍是当前飞行时才收尾（防止旧飞行的迟到回调清掉新飞行）。 */
    internal fun finish(id: Long) {
        if (_active.value?.id == id) _active.value = null
    }

    internal fun cancel() {
        _active.value = null
    }

    private fun upsert(
        map: MutableMap<String, SnapshotStateList<Spot>>,
        key: String,
        token: Any,
        rect: Rect,
        corner: Dp,
    ) {
        val list = map.getOrPut(key) { mutableStateListOf() }
        val spot = Spot(token, rect, corner, ++seqCounter)
        val index = list.indexOfFirst { it.token === token }
        if (index >= 0) list[index] = spot else list.add(spot)
    }

    private fun remove(map: MutableMap<String, SnapshotStateList<Spot>>, key: String, token: Any) {
        val list = map[key] ?: return
        list.removeAll { it.token === token }
        if (list.isEmpty()) map.remove(key)
    }

    /** 同键可能有多个实例短暂共存（双宿主 / 交叉淡入），取最近一次上报的。 */
    private fun freshest(map: MutableMap<String, SnapshotStateList<Spot>>, key: String): Spot? =
        map[key]?.maxByOrNull { it.seq }
}

/**
 * 把当前封面注册为飞行**起点**（[CoverFlight.play] / [CoverFlight.openPlaylist] 的取景对象）。
 * 坐标取根坐标；[corner] 为封面视觉圆角（各组件按实际形状显式传入）。
 */
@Composable
fun Modifier.coverFlightSource(key: String, corner: Dp): Modifier {
    val token = remember { Any() }
    // 缓存最近一次上报的矩形：列表项被复用换歌时 key 变了但位置没变，
    // onGloballyPositioned 不会重发，需要在 effect 里用缓存补注册。
    var lastRect by remember { mutableStateOf<Rect?>(null) }
    val currentCorner by rememberUpdatedState(corner)
    DisposableEffect(key, token) {
        lastRect?.let { CoverFlight.updateSource(key, token, it, currentCorner) }
        onDispose { CoverFlight.removeSource(key, token) }
    }
    return onGloballyPositioned { coordinates ->
        val rect = coordinates.boundsInRoot()
        lastRect = rect
        CoverFlight.updateSource(key, token, rect, currentCorner)
    }
}

/** 把当前封面注册为飞行**落点**（详见 [coverFlightSource]）。 */
@Composable
fun Modifier.coverFlightTarget(key: String, corner: Dp): Modifier {
    val token = remember { Any() }
    DisposableEffect(key, token) {
        onDispose { CoverFlight.removeTarget(key, token) }
    }
    return onGloballyPositioned { coordinates ->
        CoverFlight.updateTarget(key, token, coordinates.boundsInRoot(), corner)
    }
}

/**
 * 飞行器宿主：必须挂在**最顶层**（覆盖在各页面与 MiniPlayer 之上）。
 * 无飞行时不参与布局；App 离开组合时取消未完成的飞行。
 */
@Composable
fun CoverFlightHost() {
    DisposableEffect(Unit) { onDispose { CoverFlight.cancel() } }
    val flight = CoverFlight.active.value
    if (flight != null) {
        key(flight.id) { CoverFlightRenderer(flight) }
    }
}

@Composable
private fun CoverFlightRenderer(flight: CoverFlight.Flight) {
    val android = isAndroidPlatform()
    val density = LocalDensity.current
    // 飞行时长：一次飞行要跨越大半个屏幕，属于「大面积元素位移」，取的是偏慢的一档，
    // 而不是小控件即时反馈用的 fast。
    //
    // ⚠️ Android 这里曾取 `spatialFast()`（≈350ms）—— 跨越整屏距离下"一闪而过"，
    // 既看不清起落，也和同一动作在桌面用 `spatial()` 的节奏对不上。改用
    // `spatialSlow()`（≈900ms，CpMotion 里正是「整页级转场 / 大面积元素」那一档）。
    // 桌面保持 `spatial()`：指针交互不需要更拖的节奏。
    val flySpec: FiniteAnimationSpec<Float> =
        if (android) CpMotion.spatialSlow() else CpMotion.spatial()
    val arcPx = with(density) { (if (android) 48.dp else 0.dp).toPx() }

    var target by remember(flight.id) { mutableStateOf<CoverFlight.Spot?>(null) }
    var dismissing by remember(flight.id) { mutableStateOf(false) }
    val progress = remember(flight.id) { Animatable(0f) }

    // 等待目标期间微微抬起，起飞时落回；取消/交还时整体淡出。
    val lift by animateFloatAsState(
        targetValue = if (target == null && !dismissing) 1f else 0f,
        animationSpec = CpMotion.spatialFast(),
        label = "coverFlightLift",
    )
    val fade by animateFloatAsState(
        targetValue = if (dismissing) 0f else 1f,
        animationSpec = CpMotion.effectsFast(),
        label = "coverFlightFade",
    )

    LaunchedEffect(flight.id) {
        var ended = false
        suspend fun end() {
            if (ended) return
            ended = true
            flight.landed.value = true
            CoverFlight.finish(flight.id)
        }

        suspend fun dismiss() {
            if (ended || dismissing) return
            dismissing = true
            progress.stop()
            delay(DISMISS_MS)
            end()
        }

        // 目标可能还没出现（播放要先解析曲目 / 页面还在入场）：先在源位抬起等待；
        // 超时仍无目标则原地淡出取消，避免飞行器悬在半空。
        val spot = withTimeoutOrNull(TARGET_WAIT_MS) {
            snapshotFlow { CoverFlight.peekTarget(flight.targetKey) }.first { it != null }
        }
        if (spot == null) {
            dismiss()
            return@LaunchedEffect
        }
        target = spot
        launch {
            snapshotFlow { CoverFlight.peekTarget(flight.targetKey) }.collect { next ->
                if (next == null) {
                    // 飞行途中目标消失（例如用户返回）：原地淡出收尾，不再追逐。
                    dismiss()
                } else {
                    // 目标中途移动（窗口尺寸变化等）：改飞向最新位置。
                    target = next
                }
            }
        }

        progress.snapTo(0f)
        progress.animateTo(1f, flySpec)

        // 落位：目标在飞行器正下方显形，飞行器随后淡出完成无缝交还。
        flight.landed.value = true
        dismissing = true
        delay(DISMISS_MS)
        end()
    }

    val p = progress.value
    val t = p.coerceIn(0f, 1f)
    val from = flight.from
    val to = target?.rect ?: from
    // 位置按 raw p 插值（允许回弹过冲）；弧线 / 圆角 / 尺寸用 clamp 后的 t，避免过冲时形状反算。
    val left = lerp(from.left, to.left, p)
    val top = lerp(from.top, to.top, p) - arcPx * sin(PI * t).toFloat()
    val width = lerp(from.width, to.width, p)
    val height = lerp(from.height, to.height, p)
    val toCorner = target?.corner ?: flight.fromCorner
    val corner = flight.fromCorner + (toCorner - flight.fromCorner) * t
    val scale = 1f + 0.05f * lift

    if (fade > 0.004f && width > 0f && height > 0f) {
        Box(
            Modifier
                .size(with(density) { width.toDp() }, with(density) { height.toDp() })
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .graphicsLayer {
                    alpha = fade
                    scaleX = scale
                    scaleY = scale
                    shape = RoundedCornerShape(corner)
                    clip = true
                    shadowElevation = with(density) { 12.dp.toPx() } * (0.5f + 0.5f * lift)
                }
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            AsyncImage(
                model = flight.coverUrl.resized(600),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private fun lerp(a: Float, b: Float, f: Float): Float = a + (b - a) * f
