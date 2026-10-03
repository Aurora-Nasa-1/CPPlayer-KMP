package cp.player.app.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 「向上滑动隐藏底部导航栏」的状态。
 *
 * ## 手势来源为什么是**指针观察**而不是嵌套滚动
 *
 * 手势由 [Modifier.observeBottomBarDrag] 直接读内容区上的手指位移，**不经过嵌套滚动**。
 * 嵌套滚动要穿过「页面滚动容器 → 下拉刷新 → 内容区 → Scaffold」整条链，任何一环
 * 吃掉或不再派发增量，底栏就永远收不起来：
 * - 安卓端页面被 `CpRefreshablePage` 包成 `PullToRefreshBox`；
 * - 窄屏 Scaffold 上还挂着顶栏的 `exitUntilCollapsedScrollBehavior` —— 它会吃掉
 *   「大标题收成 64dp」那一段增量。
 *
 * 而「手指能滚动内容」是既定事实 ⇒ 事件必经过内容区 ⇒ 直接看位移，与「谁在滚、
 * 嵌套滚动有没有派发」全都无关。
 *
 * ## 行为
 *
 * - 手指上滑（`dy < 0`）累积隐藏量，下滑回显；完全由原始像素驱动，跟手无迟滞。
 * - 手势结束后向最近端**吸附**：过半收起、否则弹回，吸附走 spring 动画
 *   （逐帧回写 [hiddenPx]），期间再来手势会立刻取消、无跳变。
 *
 * 底栏完整高度由宿主经 [barHeightPx] 回传（`NavigationBar` 的 `onSizeChanged`）；
 * 尚未回传时用构造参数 [fallbackHeightPx] 兜底 —— 早期实现是「测不到高度就一律不动」，
 * 一旦回调没到就退化成「怎么滑都不收」。
 */
class BottomBarHideState(
    private val scope: CoroutineScope,
    /** 兜底高度（px）：实测高度回传前先用它，保证任何情况下都算得出隐藏比例。 */
    private val fallbackHeightPx: Float,
) {
    /** 是否启用（对应设置项）。关闭时应调用 [reset] 归零。 */
    var enabled: Boolean = true

    /** 底栏完整高度（px），由宿主测量回传。 */
    var barHeightPx by mutableFloatStateOf(0f)

    private var hiddenPx by mutableFloatStateOf(0f)

    /** 当前进行中的吸附动画；每次新手势到来都会取消。 */
    private var settleJob: Job? = null

    /** 参考高度：优先实测，未回传时用兜底值。 */
    private val heightPx: Float
        get() = if (barHeightPx > 1f) barHeightPx else fallbackHeightPx

    /** 当前隐藏比例（0 = 完全可见，1 = 完全隐藏）。 */
    val fraction: Float
        get() = (hiddenPx / heightPx).coerceIn(0f, 1f)

    /** 立即回显（关闭设置项 / 状态重建时调用）。 */
    fun reset() {
        settleJob?.cancel()
        hiddenPx = 0f
    }

    /** 累积一次纵向手势增量（px）。`dy < 0` = 手指上滑 = 收起。 */
    fun onDrag(dy: Float) {
        if (!enabled || dy == 0f) return
        hiddenPx = (hiddenPx - dy).coerceIn(0f, heightPx)
        scheduleSettle(SETTLE_DELAY_MS)
    }

    /** 手势结束：立刻开始吸附（不必再等 [SETTLE_DELAY_MS]）。 */
    fun onDragEnd() {
        if (!enabled) return
        scheduleSettle(0L)
    }

    private fun scheduleSettle(delayMs: Long) {
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(delayMs)
            val target = if (hiddenPx > heightPx * 0.5f) heightPx else 0f
            if (abs(hiddenPx - target) < 0.5f) return@launch
            val anim = Animatable(hiddenPx)
            anim.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow)) {
                hiddenPx = value.coerceIn(0f, heightPx)
            }
        }
    }

    private companion object {
        /** 连续拖动期间每帧都会重置这个计时（吸附只在手指停下后才真正开始）。 */
        const val SETTLE_DELAY_MS = 160L
    }
}

/**
 * 把**内容区**上的纵向手势喂给 [state]。挂在内容滚动容器的祖先上。
 *
 * 在 [PointerEventPass.Initial] 里只读位置差、**从不消费** ⇒ 内容照常滚动，
 * 也不会和子级的 clickable / 拖拽抢手势。主轴判定见 [AXIS_LOCK_PX]：
 * 横向为主的手势（翻页 / 侧滑）整段忽略。
 */
fun Modifier.observeBottomBarDrag(state: BottomBarHideState): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var absX = 0f
        var absY = 0f
        var axisLocked = false
        var vertical = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val pressed = change.pressed
            val wasPressed = change.previousPressed
            val delta = change.position - change.previousPosition
            if (wasPressed && !pressed) break
            if (pressed) {
                absX += abs(delta.x)
                absY += abs(delta.y)
                if (!axisLocked && absX + absY > AXIS_LOCK_PX) {
                    axisLocked = true
                    vertical = absY >= absX
                }
                if (vertical) state.onDrag(delta.y)
            }
        }
        state.onDragEnd()
    }
}

/** 判定主轴前先累计的位移阈值（px）：太小会把斜向微动误锁成横向。 */
private const val AXIS_LOCK_PX = 8f
