package cp.player.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.key
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
import cp.player.app.ui.component.LocalPlayerShareContainer
import cp.player.app.ui.component.LocalPlayerDismissHandler
import cp.player.app.ui.component.PlayerDismissHandler
import cp.player.app.ui.component.rememberScreenCornerRadius
import cp.player.app.ui.component.rememberTopScreenCornerRadius
import cp.player.app.ui.feedback.CpHaptic
import cp.player.app.ui.feedback.LocalCpHaptics
import cp.player.app.ui.theme.CpMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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

    internal var forceCloseNonce by mutableLongStateOf(0L)
        private set

    var isVisibleOnScreen by mutableStateOf(initialExpanded)
        internal set

    fun open() {
        isExpanded = true
    }

    fun closeImmediately() {
        if (isExpanded || isVisibleOnScreen) {
            isExpanded = false
            forceCloseNonce++
        }
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

/** Both ends change visibility on the same transition so shared bounds can hand off. */
@Composable
internal fun rememberPlayerOverlayTransition(
    controller: PlayerOverlayController,
    desktopMotion: Boolean,
): Transition<Boolean> = key(if (desktopMotion) 0L else controller.forceCloseNonce) {
    updateTransition(controller.isExpanded, label = "PlayerOverlay")
}

@Composable
internal fun PlayerOverlayHost(
    controller: PlayerOverlayController,
    transition: Transition<Boolean>,
    desktopMotion: Boolean,
    predictiveBackEnabled: Boolean,
    overlayCoverageState: MutableFloatState,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalCpHaptics.current
    val topScreenCorner = rememberTopScreenCornerRadius()
    val bottomScreenCorner = rememberScreenCornerRadius()
    var dragPx by remember(transition) { mutableFloatStateOf(0f) }
    var predictiveProgress by remember(transition) { mutableFloatStateOf(0f) }
    var predictiveSwipeEdge by remember(transition) { mutableIntStateOf(NavigationEvent.EDGE_NONE) }
    var resetJob by remember { mutableStateOf<Job?>(null) }

    val dragThresholdPx = with(density) { 104.dp.toPx() }
    val flingVelocityPx = with(density) { 900.dp.toPx() }
    val cornerRampPx = with(density) { 12.dp.toPx() }
    val predictiveDropPx = with(density) { 48.dp.toPx() }
    val predictiveShiftXPx = with(density) { 24.dp.toPx() }
    val resetMotion = CpMotion.spatial<Float>()
    val gestureTransform = !desktopMotion && (dragPx > 0f || predictiveProgress > 0f)
    val openAmount = if (desktopMotion) null else transition.animateFloat(
        transitionSpec = { resetMotion },
        label = "PlayerSheetPosition",
    ) { expanded -> if (expanded) 1f else 0f }

    val isVisible = transition.currentState || transition.targetState ||
        (openAmount != null && openAmount.value > 0f)

    fun resetGesture() {
        resetJob?.cancel()
        val startDrag = dragPx
        val startProgress = predictiveProgress
        if (startDrag == 0f && startProgress == 0f) return
        resetJob = scope.launch {
            Animatable(1f).animateTo(0f, resetMotion) {
                dragPx = startDrag * value.coerceAtLeast(0f)
                predictiveProgress = startProgress * value.coerceIn(0f, 1f)
            }
            dragPx = 0f
            predictiveProgress = 0f
        }
    }

    fun dismiss() {
        if (controller.isExpanded) {
            // Keep the gesture transform until the exit finishes.
            resetJob?.cancel()
            controller.isExpanded = false
        }
    }

    LaunchedEffect(transition, isVisible, transition.targetState) {
        if (!isVisible) {
            resetJob?.cancel()
            dragPx = 0f
            predictiveProgress = 0f
        } else if (transition.targetState && gestureTransform) {
            // Reopening while the sheet is dismissing reverses both parts of the motion.
            resetGesture()
        }
    }

    val coverage = if (openAmount == null || !isVisible) 0f else {
        val dragFraction = (dragPx / (dragThresholdPx * 3f)).coerceIn(0f, 1f)
        openAmount.value.coerceIn(0f, 1f) * (1f - dragFraction) * (1f - predictiveProgress * 0.65f)
    }
    SideEffect {
        controller.isVisibleOnScreen = isVisible
        overlayCoverageState.floatValue = coverage
    }

    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = isVisible,
        onBackCancelled = { if (controller.isExpanded) resetGesture() },
        onBackCompleted = { dismiss() },
    )
    val transitionState = navEventState.transitionState
    LaunchedEffect(transitionState, predictiveBackEnabled, desktopMotion) {
        if (transitionState is NavigationEventTransitionState.InProgress &&
            predictiveBackEnabled && !desktopMotion && controller.isExpanded &&
            transition.currentState && !transition.isRunning
        ) {
            resetJob?.cancel()
            predictiveSwipeEdge = transitionState.latestEvent.swipeEdge
            predictiveProgress = transitionState.latestEvent.progress.coerceIn(0f, 1f)
        }
    }

    if (!isAndroidPlatform()) {
        BackHandler(enabled = isVisible) { dismiss() }
    }

    val dismissHandler = remember(controller, transition, desktopMotion, dragThresholdPx, flingVelocityPx, haptics, resetMotion) {
        object : PlayerDismissHandler {
            override val isDragging: Boolean
                get() = dragPx > 0f

            override fun onDragDelta(dy: Float) {
                if (desktopMotion || !controller.isExpanded || !transition.currentState || transition.isRunning) return
                resetJob?.cancel()
                dragPx = (dragPx + dy).coerceAtLeast(0f)
            }

            override fun onDragEnd(velocityY: Float) {
                if (!controller.isExpanded || dragPx <= 0f) return
                if (dragPx >= dragThresholdPx || velocityY >= flingVelocityPx) {
                    haptics.perform(CpHaptic.Confirm)
                    dismiss()
                } else {
                    resetGesture()
                }
            }

            override fun onDragCancel() {
                if (controller.isExpanded && dragPx > 0f) resetGesture()
            }

            override fun requestDismiss() = dismiss()
        }
    }

    val fade = if (desktopMotion) {
        tween(CpMotion.PlayerExpandMs, easing = FastOutSlowInEasing)
    } else CpMotion.effects<Float>()

    Box(modifier.fillMaxSize()) {
        transition.AnimatedVisibility(
            visible = { it },
            enter = fadeIn(fade),
            exit = fadeOut(fade),
        ) {
            val animScope = this
            Box(Modifier.fillMaxSize()) {
                // Swallow gestures in the exposed area while the overlay is present.
                Box(Modifier.matchParentSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                })
                val sheet = if (openAmount == null) Modifier else Modifier.graphicsLayer {
                    val closed = 1f - openAmount.value.coerceIn(0f, 1f)
                    val dragged = (dragPx / (size.height * 0.65f).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val edgeSign = if (predictiveSwipeEdge == NavigationEvent.EDGE_RIGHT) -1f else 1f
                    translationX = predictiveProgress * predictiveShiftXPx * edgeSign
                    val gestureY = dragPx + predictiveProgress * predictiveDropPx
                    val restY = size.height * if (gestureTransform) 1f else 0.24f
                    translationY = gestureY + (restY - gestureY).coerceAtLeast(0f) * closed
                    scaleX = (1f - dragged * 0.03f) * (1f - predictiveProgress * 0.10f) * (1f - closed * 0.05f)
                    scaleY = scaleX

                    val rounding = maxOf(dragPx / cornerRampPx, predictiveProgress * 4f, closed * 6f)
                        .coerceIn(0f, 1f)
                    clip = rounding > 0f
                    if (clip) {
                        val top = topScreenCorner * rounding
                        val bottom = bottomScreenCorner * rounding
                        shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
                    }
                }
                Box(Modifier.fillMaxSize().then(sheet)) {
                    CompositionLocalProvider(
                        // Interactive gestures move the whole sheet; its children stay on that layer.
                        LocalPlayerAllowSharedBounds provides !gestureTransform,
                        LocalPlayerShareContainer provides desktopMotion,
                        LocalPlayerDismissHandler provides dismissHandler,
                    ) {
                        animScope.content()
                    }
                }
            }
        }
    }
}
