package cp.player.app.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 「向上滑动隐藏底部导航栏」的状态与嵌套滚动连接。
 *
 * 工作方式：
 * - 作为**只观察、不消费**的 [NestedScrollConnection] 挂在窄屏 Scaffold 上，
 *   在 [onPreScroll] 里读每一帧的纵向手势增量 —— 在子滚动容器（各 tab 页的列表）
 *   消费之前就能看到，所以底栏与内容**同帧**移动，而不是等列表滚到边界才动。
 * - 手指上滑（`available.y < 0`）累积隐藏量，下滑回显；完全由原始像素驱动，
 *   跟手无迟滞。
 * - 滚动停止（[SETTLE_DELAY_MS] 内没有新的增量）后向最近端**吸附**：
 *   过半隐藏就收起、否则弹回，吸附走 spring 动画（逐帧回写 [hiddenPx]），
 *   期间再来手势会立刻取消吸附、无跳变。
 *
 * 底栏本体的高度由宿主经 [barHeightPx] 回传（NavigationBar 的 onSizeChanged）；
 * 视觉裁切（高度收缩 + 上移 + 裁边）由 [AppNavigationBar] 按 [fraction] 完成。
 */
class BottomBarHideState(private val scope: kotlinx.coroutines.CoroutineScope) :
    NestedScrollConnection {

    /** 是否启用（对应设置项）。关闭时应调用 [reset] 归零。 */
    var enabled: Boolean = true

    /** 底栏完整高度（px），由宿主测量回传。未知（≤1）时不参与隐藏。 */
    var barHeightPx by mutableFloatStateOf(0f)

    private var hiddenPx by mutableFloatStateOf(0f)

    /** 当前进行中的吸附动画；每次新手势到来都会取消。 */
    private var settleJob: kotlinx.coroutines.Job? = null

    /** 当前隐藏比例（0 = 完全可见，1 = 完全隐藏）。 */
    val fraction: Float
        get() = if (barHeightPx > 1f) (hiddenPx / barHeightPx).coerceIn(0f, 1f) else 0f

    /** 立即回显（关闭设置项 / 状态重建时调用）。 */
    fun reset() {
        settleJob?.cancel()
        hiddenPx = 0f
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!enabled || barHeightPx <= 1f) return Offset.Zero
        val dy = available.y
        if (dy == 0f) return Offset.Zero
        // 手指上滑 dy < 0 ⇒ -dy > 0 ⇒ hiddenPx 增大（隐藏）。
        hiddenPx = (hiddenPx - dy).coerceIn(0f, barHeightPx)
        scheduleSettle()
        // 只观察不消费：内容照常滚动。
        return Offset.Zero
    }

    private fun scheduleSettle() {
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(SETTLE_DELAY_MS)
            val target = if (hiddenPx > barHeightPx * 0.5f) barHeightPx else 0f
            if (abs(hiddenPx - target) < 0.5f) return@launch
            val anim = Animatable(hiddenPx)
            anim.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow)) {
                hiddenPx = value.coerceIn(0f, barHeightPx)
            }
        }
    }

    private companion object {
        /** 滚动停止多久后吸附。连续滚动期间每帧都会重置这个计时。 */
        const val SETTLE_DELAY_MS = 160L
    }
}
