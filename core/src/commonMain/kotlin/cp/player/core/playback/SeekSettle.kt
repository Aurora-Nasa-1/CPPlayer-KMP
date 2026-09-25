package cp.player.core.playback

import kotlin.math.abs

/**
 * 乐观 seek 的「何时算落定」判定。
 *
 * ### 为什么需要它
 * 两个平台都在 `seekTo` 时先把目标位置**乐观写回** UI（避免松手后进度条回弹），
 * 再由轮询确认引擎是否已经追上，追上了才放弃目标值、回到引擎的真实位置。
 *
 * ### 只比「距离」为什么不行
 * 朴素写法是 `|引擎位置 - 目标| <= 容差` 就算追上。但目标离 seek **之前**的位置
 * 不足一个容差时（例如从 10.0s 拖到 10.5s），引擎**还没动**就已经满足该条件，
 * 于是被误判为"已追上" → 立刻放弃目标值 → UI 先回弹到旧位置、下一轮再跳过去。
 * 表现为「小幅 seek 会闪一下」。
 *
 * ### 这里的判定
 * 1. 超时 → 落定（引擎可能不支持 seek，或始终对不准，不能让乐观值永远生效）；
 * 2. 引擎位置**仍等于 seek 前的位置** → 未落定（seek 还没生效，不能下结论）；
 * 3. 否则 → 引擎位置已进入目标附近即落定。
 *
 * 容差取 250ms 而非 1s：引擎通常能精确落点，留一点余量给只能按关键帧定位的流；
 * 真正对不准的情况由超时兜底，代价只是多显示 800ms 目标值。
 *
 * ### 装载期为什么要放宽超时（流媒体场景）
 * 固定 800ms 对本地文件够用，但流媒体 seek 到**尚未缓冲**的区间时，引擎要先建连、
 * 拿首包，再定位——这段时间引擎状态是 Buffering，位置停在原地不动。
 * 若仍按 800ms 放弃乐观值，进度条就会先回弹到旧位置、等首包到了再跳过去，
 * 用户看到的就是「seek 没生效」。因此调用方可用 [isSettled] 的 `engineLoading`
 * 声明「引擎正在装载/缓冲」，此时改用 [LOADING_TIMEOUT_MS] 的宽限上限。
 * 宽限期仍然有限——真不可定位的流最终还是会被放弃，不会把乐观值永远挂在那里。
 */
internal object SeekSettle {

    /** 引擎位置与目标相差在此范围内即认为已追上。 */
    const val TOLERANCE_MS = 250L

    /** 超过此时间仍未追上则放弃乐观值，回落到引擎真实位置。 */
    const val TIMEOUT_MS = 800L

    /**
     * 引擎处于装载/缓冲（流媒体首包未到）时的宽限上限。
     *
     * 取 4s：够 CDN 建连 + 首包，又不至于让一次真的失败的 seek 把乐观值挂太久。
     */
    const val LOADING_TIMEOUT_MS = 4_000L

    /**
     * @param enginePositionMs 引擎当前上报的位置。
     * @param targetMs seek 的目标位置。
     * @param fromPositionMs seek 发起时 UI 显示的位置。
     * @param elapsedMs 距 seek 发起已过的毫秒数。
     * @param engineLoading 引擎是否仍在装载/缓冲（流媒体首包未到）。为 true 时用
     *   [LOADING_TIMEOUT_MS] 宽限，避免首包还没到就把乐观值判死。
     */
    fun isSettled(
        enginePositionMs: Long,
        targetMs: Long,
        fromPositionMs: Long,
        elapsedMs: Long,
        engineLoading: Boolean = false,
    ): Boolean {
        if (elapsedMs > if (engineLoading) LOADING_TIMEOUT_MS else TIMEOUT_MS) return true
        // 引擎还没离开 seek 前的位置 —— seek 尚未生效，绝不能判定为落定。
        if (enginePositionMs == fromPositionMs) return false
        return abs(enginePositionMs - targetMs) <= TOLERANCE_MS
    }
}
