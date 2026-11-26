package cp.player.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import cp.player.app.platform.BackHandler
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.ui.component.LocalPlayerAllowSharedBounds
import cp.player.app.ui.component.LocalPlayerDismissHandler
import cp.player.app.ui.component.PlayerDismissHandler
import cp.player.app.ui.component.rememberScreenCornerRadius
import cp.player.app.ui.component.rememberTopScreenCornerRadius
import cp.player.app.ui.feedback.CpHaptic
import cp.player.app.ui.feedback.LocalCpHaptics
import cp.player.app.ui.theme.CpMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 独立于页面导航栈的全屏播放页覆盖层控制器。
 *
 * 将播放页从 [RouteDisplay] 拆出后，下层的上一级页面常驻组合，移动端下拉收起、
 * 预测性侧滑返回与直接点击关闭都能以屏幕同款圆角向下退场并露出后方页面。
 */
@Stable
class PlayerOverlayController internal constructor(
    initialExpanded: Boolean = false,
) {
    var isExpanded by mutableStateOf(initialExpanded)
        internal set

    internal var openRequestNonce by mutableLongStateOf(0L)
        private set

    internal var forceCloseNonce by mutableLongStateOf(0L)
        private set

    internal var dismissRequestNonce by mutableLongStateOf(0L)
        private set

    var isVisibleOnScreen by mutableStateOf(initialExpanded)
        internal set

    fun open() {
        isExpanded = true
        openRequestNonce++
    }

    fun requestDismiss() {
        if (isExpanded) {
            dismissRequestNonce++
        }
    }

    fun closeImmediately() {
        isExpanded = false
        forceCloseNonce++
    }
}

val LocalPlayerOverlayController = staticCompositionLocalOf<PlayerOverlayController?> { null }

@Composable
fun rememberPlayerOverlayController(): PlayerOverlayController {
    val savedExpanded = rememberSaveable { mutableStateOf(false) }
    val controller = remember { PlayerOverlayController(initialExpanded = savedExpanded.value) }
    LaunchedEffect(controller.isExpanded) {
        savedExpanded.value = controller.isExpanded
    }
    return controller
}

private const val PLAYER_MOBILE_EXIT_MS = 250

@Composable
fun PlayerOverlayHost(
    controller: PlayerOverlayController,
    isDesktopExpanded: Boolean,
    predictiveBackEnabled: Boolean,
    overlayCoverageState: MutableFloatState,
    onExitingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalCpHaptics.current
    val topScreenCorner = rememberTopScreenCornerRadius()
    val bottomScreenCorner = rememberScreenCornerRadius()

    var containerHeightPx by remember { mutableIntStateOf(0) }
    var isExiting by remember { mutableStateOf(false) }
    val dragOffsetPx = remember { Animatable(0f) }
    val exitProgress = remember { Animatable(0f) }
    val predictiveProgress = remember { Animatable(0f) }
    var predictiveSwipeEdge by remember { mutableIntStateOf(NavigationEvent.EDGE_NONE) }
    var exitJob by remember { mutableStateOf<Job?>(null) }

    val dragThresholdPx = with(density) { 104.dp.toPx() }
    val flingVelocityPx = with(density) { 900.dp.toPx() }
    val cornerRampPx = with(density) { 12.dp.toPx() }
    val predictiveDropPx = with(density) { 48.dp.toPx() }
    val predictiveShiftXPx = with(density) { 24.dp.toPx() }
    val minExitSlidePx = with(density) { 180.dp.toPx() }
    val resetMotion = CpMotion.spatial<Float>()

    fun triggerExit() {
        if (isDesktopExpanded) {
            controller.isExpanded = false
            return
        }
        if (isExiting || !controller.isExpanded) return
        isExiting = true
        exitJob?.cancel()
        exitJob = scope.launch {
            exitProgress.snapTo(0f)
            exitProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = PLAYER_MOBILE_EXIT_MS,
                    easing = FastOutSlowInEasing,
                ),
            )
            controller.isExpanded = false
            dragOffsetPx.snapTo(0f)
            predictiveProgress.snapTo(0f)
            exitProgress.snapTo(0f)
            isExiting = false
            exitJob = null
        }
    }

    LaunchedEffect(controller.openRequestNonce) {
        if (controller.openRequestNonce > 0L && controller.isExpanded) {
            exitJob?.cancel()
            exitJob = null
            isExiting = false
            dragOffsetPx.snapTo(0f)
            predictiveProgress.snapTo(0f)
            exitProgress.snapTo(0f)
        }
    }

    LaunchedEffect(controller.forceCloseNonce) {
        if (controller.forceCloseNonce > 0L && !controller.isExpanded) {
            exitJob?.cancel()
            exitJob = null
            isExiting = false
            dragOffsetPx.snapTo(0f)
            predictiveProgress.snapTo(0f)
            exitProgress.snapTo(0f)
        }
    }

    LaunchedEffect(controller.dismissRequestNonce) {
        if (controller.dismissRequestNonce > 0L) {
            triggerExit()
        }
    }

    LaunchedEffect(isExiting) {
        onExitingChange(isExiting)
    }

    SideEffect {
        controller.isVisibleOnScreen = controller.isExpanded || isExiting
    }

    val isInteractive = controller.isExpanded && !isExiting
    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = isInteractive,
        onBackCancelled = {
            scope.launch { predictiveProgress.animateTo(0f, resetMotion) }
        },
        onBackCompleted = { triggerExit() },
    )

    val transitionState = navEventState.transitionState
    LaunchedEffect(transitionState, predictiveBackEnabled, isDesktopExpanded, isInteractive) {
        if (!isInteractive || isDesktopExpanded || !predictiveBackEnabled) {
            if (predictiveProgress.value > 0f && !isExiting) {
                predictiveProgress.snapTo(0f)
            }
            return@LaunchedEffect
        }
        if (transitionState is NavigationEventTransitionState.InProgress) {
            predictiveSwipeEdge = transitionState.latestEvent.swipeEdge
            predictiveProgress.snapTo(transitionState.latestEvent.progress.coerceIn(0f, 1f))
        }
    }

    if (!isAndroidPlatform()) {
        BackHandler(enabled = isInteractive) {
            triggerExit()
        }
    }

    val dismissHandler = remember(dragThresholdPx, flingVelocityPx) {
        object : PlayerDismissHandler {
            override val isDragging: Boolean
                get() = dragOffsetPx.value > 0.5f

            override fun onDragDelta(dy: Float) {
                if (isExiting || !controller.isExpanded) return
                val next = (dragOffsetPx.value + dy).coerceAtLeast(0f)
                scope.launch { dragOffsetPx.snapTo(next) }
            }

            override fun onDragEnd(velocityY: Float) {
                if (isExiting || !controller.isExpanded) return
                if (dragOffsetPx.value >= dragThresholdPx ||
                    (dragOffsetPx.value > 0f && velocityY >= flingVelocityPx)
                ) {
                    haptics.perform(CpHaptic.Confirm)
                    triggerExit()
                } else if (dragOffsetPx.value > 0f) {
                    scope.launch { dragOffsetPx.animateTo(0f, resetMotion) }
                }
            }

            override fun onDragCancel() {
                if (!isExiting && dragOffsetPx.value > 0f) {
                    scope.launch { dragOffsetPx.animateTo(0f, resetMotion) }
                }
            }

            override fun requestDismiss() {
                triggerExit()
            }
        }
    }

    val openFade by animateFloatAsState(
        targetValue = if (controller.isExpanded && !isExiting) 1f else 0f,
        animationSpec = tween(CpMotion.PlayerExpandMs, easing = FastOutSlowInEasing),
        label = "playerOverlayOpenCoverage",
    )

    val height = containerHeightPx.toFloat().coerceAtLeast(1f)
    val drag = dragOffsetPx.value.coerceAtLeast(0f)
    val pred = predictiveProgress.value.coerceIn(0f, 1f)
    val exit = exitProgress.value.coerceIn(0f, 1f)
    val dragFraction = (drag / (height * 0.65f)).coerceIn(0f, 1f)

    val coverage = if (isDesktopExpanded) {
        0f
    } else if (!controller.isExpanded && !isExiting) {
        0f
    } else if (isExiting) {
        val base = ((1f - dragFraction) * (1f - pred * 0.65f)).coerceIn(0f, 1f)
        (base * (1f - exit)).coerceIn(0f, 1f)
    } else {
        val current = ((1f - dragFraction) * (1f - pred * 0.65f)).coerceIn(0f, 1f)
        minOf(openFade, current)
    }

    SideEffect {
        overlayCoverageState.floatValue = coverage
    }

    val allowSharedBounds = !isExiting && drag <= 0.5f && pred <= 0.001f

    val showOverlay = controller.isExpanded || isExiting
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerHeightPx = it.height },
    ) {
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(tween(CpMotion.PlayerExpandMs, easing = FastOutSlowInEasing)),
            exit = if (isDesktopExpanded) {
                fadeOut(tween(CpMotion.PlayerExpandMs, easing = FastOutSlowInEasing))
            } else {
                ExitTransition.None
            },
            label = "IndependentPlayerOverlay",
        ) {
            val animScope = this
            val mobileTransformModifier = if (isDesktopExpanded) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val currentDrag = dragOffsetPx.value.coerceAtLeast(0f)
                        val currentPred = predictiveProgress.value.coerceIn(0f, 1f)
                        val currentExit = exitProgress.value.coerceIn(0f, 1f)
                        val h = size.height.coerceAtLeast(1f)
                        val currentDragFraction = (currentDrag / (h * 0.65f)).coerceIn(0f, 1f)

                        val exitAdditionalY = if (currentExit > 0f) {
                            if (currentDrag > 0f) {
                                currentExit * max(h * 0.42f, (h - currentDrag).coerceAtLeast(minExitSlidePx))
                            } else {
                                currentExit * (h * 0.30f)
                            }
                        } else {
                            0f
                        }

                        translationY = currentDrag + currentPred * predictiveDropPx + exitAdditionalY
                        val edgeSign = if (predictiveSwipeEdge == NavigationEvent.EDGE_RIGHT) -1f else 1f
                        translationX = currentPred * predictiveShiftXPx * edgeSign

                        val dragScale = 1f - currentDragFraction * 0.03f
                        val predScale = 1f - currentPred * 0.10f
                        val exitScale = if (currentDrag <= 0f && currentPred <= 0f) {
                            1f - currentExit * 0.05f
                        } else {
                            1f
                        }
                        val totalScale = dragScale * predScale * exitScale
                        scaleX = totalScale
                        scaleY = totalScale

                        val motionRamp = maxOf(
                            (currentDrag / cornerRampPx).coerceIn(0f, 1f),
                            (currentPred * 4f).coerceIn(0f, 1f),
                            (currentExit * 6f).coerceIn(0f, 1f),
                        )
                        if (motionRamp > 0.01f) {
                            val topRadius = topScreenCorner * motionRamp
                            val bottomRadius = bottomScreenCorner * motionRamp
                            shape = RoundedCornerShape(
                                topStart = topRadius,
                                topEnd = topRadius,
                                bottomStart = bottomRadius,
                                bottomEnd = bottomRadius,
                            )
                            clip = true
                        } else {
                            clip = false
                        }

                        alpha = if (isExiting) {
                            (1f - currentExit * 1.15f).coerceIn(0f, 1f)
                        } else {
                            1f
                        }
                    }
            }

            Box(Modifier.fillMaxSize()) {
                // 挡住穿透到下层 RouteDisplay 的点击与滑动。
                if (isInteractive) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) { detectTapGestures { } },
                    )
                }
                Box(mobileTransformModifier) {
                    CompositionLocalProvider(
                        LocalPlayerDismissHandler provides dismissHandler,
                        LocalPlayerAllowSharedBounds provides allowSharedBounds,
                    ) {
                        animScope.content()
                    }
                }
            }
        }
    }
}
