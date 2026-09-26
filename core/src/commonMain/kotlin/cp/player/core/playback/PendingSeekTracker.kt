package cp.player.core.playback

/**
 * 待定 seek 的**唯一**状态机。
 *
 * ### 为什么抽出来
 * 桌面（rodio）与安卓（ExoPlayer）原先各自内联了一份「乐观写回 + 轮询确认 + 补发」，
 * 两份代码逐行同构。同构的代价是：任何一处修正都要改两遍，漏一处就只在单平台复现，
 * 而症状（拖动没反应）在两端长得一模一样，极难归因。现在只有这一份实现。
 *
 * ### 它解决的三件事
 * 1. **乐观值**：引擎位置追上目标之前，对外始终汇报目标值，避免松手后先回弹再跳过去；
 * 2. **补发**：引擎在装载期会**静默丢弃** seek，必须补发；
 * 3. **落定**：引擎真追上了就交还控制权，追不上就在宽限期后放弃，绝不让乐观值永久生效。
 *
 * ### ⚠️ 补发为什么必须用「引擎是否就绪」，而不是「位置有没有动」
 * 这是本文件存在的主要理由。旧实现用 `引擎位置 == seek 前的位置` 推断
 * 「seek 没生效，需要补发」。这个推断在**装载窗口**里是错的：
 *
 * - rodio 的 player 还没建好时，`seekTo` 是**静默空操作**（连异常都没有）；
 * - 与此同时，一旦引擎开始播放，位置就**离开**了 seek 前的位置（从 0 往前走）；
 * - 于是「引擎动了」被误读成「seek 生效了」→ **恰好从引擎可用的那一刻起停止补发**；
 * - 结果这次 seek 永远不会生效，乐观值挂到宽限期结束再弹回 = 「拖了没反应」。
 *
 * 所以补发要看 [Tick] 里的 `engineReady`（引擎真的建好了没有）这个**真信号**，
 * 而不是从位置反推。位置只在「就绪后仍然一步没动」时作为**兜底**信号。
 *
 * @param enginePositionMs 读取**引擎真实**位置（不是对外显示的乐观值）。
 * @param dispatchSeek 把 seek 交给引擎；返回 false 表示引擎此刻不接受（未装载 / 已释放）。
 * @param clockMs 单调时钟，注入以便单测精确控制节流与宽限期。
 */
internal class PendingSeekTracker(
    private val enginePositionMs: () -> Long,
    private val dispatchSeek: (Long) -> Boolean,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {

    /** 当前待定目标；null 表示没有待定 seek，对外应直接采信引擎位置。 */
    @Volatile
    var targetMs: Long? = null
        private set

    /** 发起 seek 那一刻的引擎位置：判断「引擎有没有动」的基线。 */
    private var fromMs = 0L

    private var startedAtMs = 0L
    private var lastDispatchAtMs = 0L

    /**
     * 引擎**就绪之后**是否已经补发过。
     *
     * 就绪之前的补发全是空操作、不作数——真正可能生效的那一次必须是就绪后发的。
     */
    private var dispatchedAfterReady = false

    /**
     * 发起一次 seek 并立即交给引擎。
     *
     * ⚠️ 基线必须取 [enginePositionMs] 拿到的**引擎真实位置**，不能取对外显示的乐观值。
     * 取乐观值时：连续拖动的第二次 seek 会把基线记成**上一次的目标**，
     * 于是「引擎一步没动」的兜底判定永远不成立。
     *
     * @param engineReady 发起这一刻引擎是否已经就绪。已就绪则本次下发即算数；
     *   未就绪则本次多半是空操作，由 [tick] 在就绪后补发。
     */
    fun request(target: Long, engineReady: Boolean) {
        val targetCoerced = target.coerceAtLeast(0L)
        fromMs = enginePositionMs()
        targetMs = targetCoerced
        val now = clockMs()
        startedAtMs = now
        lastDispatchAtMs = now
        dispatchedAfterReady = engineReady
        dispatchSeek(targetCoerced)
    }

    /** 放弃待定 seek（换曲 / stop / release）：残留的目标值会把进度条冻在旧位置。 */
    fun cancel() {
        targetMs = null
    }

    /**
     * 每个轮询周期调用一次。
     *
     * @param enginePositionMs 本轮读到的引擎真实位置（由调用方传入，避免重复一次 JNI 调用）。
     * @param engineReady 引擎是否**真的建好了**（桌面：rodio player 已创建；
     *   安卓：media item 已设入）。这是补发的首要依据，见类 KDoc。
     * @param engineLoading 引擎是否仍在装载/缓冲（流媒体首包未到），为真时宽限期放宽。
     */
    fun tick(
        enginePositionMs: Long,
        engineReady: Boolean,
        engineLoading: Boolean,
    ): Tick {
        val target = targetMs ?: return Tick.Idle
        val nowMs = clockMs()

        // ① 就绪跃迁：必须排在落定判定**之前**，否则就是一条实测复现出来的死路。
        //
        // 顺序反了的失效形态：用户在装载期拖动 → 引擎迟迟不就绪（状态 BUFFERING，
        // 靠 4s 装载宽限撑着）→ 1.6s 后首包到达，**同一轮里**状态转为 PLAYING、时长也同时可知
        // ⇒ `engineLoading` 由 true 翻成 false、`engineReady` 由 false 翻成 true。
        // 此时若先做落定判定，用的是「距最初那次拖动」的 elapsed(1.6s) 去比**常规**超时(800ms)
        // ⇒ 立刻判超时放弃，而这一轮本该发出的补发**连发都发不出去**。
        // 表现为位置停在低位慢慢爬（实测 140ms → 7s 后 5.3s），而不是落在目标上。
        //
        // 所以「引擎刚就绪、而 seek 是它未就绪时发的」这一刻要**无条件补发**，
        // 并把宽限期与基线都从**这一刻**重新起算——就绪前的那些下发全是静默空操作，不作数。
        if (engineReady && !dispatchedAfterReady) {
            lastDispatchAtMs = nowMs
            dispatchedAfterReady = true
            startedAtMs = nowMs
            fromMs = enginePositionMs
            dispatchSeek(target)
            return Tick.Hold(target)
        }

        val elapsedMs = nowMs - startedAtMs
        if (SeekSettle.isSettled(enginePositionMs, target, fromMs, elapsedMs, engineLoading)) {
            targetMs = null
            return if (kotlin.math.abs(enginePositionMs - target) <= SeekSettle.TOLERANCE_MS) {
                Tick.Reached
            } else {
                Tick.GaveUp(targetMs = target, actualMs = enginePositionMs)
            }
        }

        val throttled = nowMs - lastDispatchAtMs >= RETRY_INTERVAL_MS
        val shouldRetry = when {
            // ① 引擎还没就绪 → 补发也可能落空，但要在宽限期内持续尝试（节流）。
            !engineReady -> throttled
            // ② 就绪了却一步没动 → 这次定位被吞了，兜底重试（节流）。
            enginePositionMs == fromMs -> throttled
            // ③ 引擎已经在动（正常播放 / 正在定位）→ 不再补发，
            //    否则会把已经落点的进度条重新拽回目标、造成回跳。
            else -> false
        }

        if (shouldRetry) {
            lastDispatchAtMs = nowMs
            dispatchSeek(target)
        }

        return Tick.Hold(target)
    }

    /** 待定期间对外应显示的位置；无待定 seek 时返回 null（交还给引擎真实位置）。 */
    fun displayMs(): Long? = targetMs

    /** [tick] 的结果。 */
    sealed interface Tick {
        /** 没有待定 seek。 */
        data object Idle : Tick

        /** 引擎已追上目标，乐观值已释放。 */
        data object Reached : Tick

        /** 仍在等待：继续对外显示这个目标值。 */
        data class Hold(val valueMs: Long) : Tick

        /**
         * 宽限期到了仍没追上：已放弃乐观值，回落到引擎真实位置。
         *
         * 引擎不支持 seek、或目标落在流媒体无法定位的区间时会出现。
         * 这是**失败信号**，不是静默回弹——调用方应据此决定是否提示用户。
         */
        data class GaveUp(val targetMs: Long, val actualMs: Long) : Tick
    }

    private companion object {
        /**
         * 补发的最小间隔。
         *
         * 不能每个轮询周期（200ms）都发：引擎若正在定位，连发会打断它。
         * 也不能发太少：装载期可能持续数秒，发少了就赶不上引擎就绪的那一刻。
         */
        const val RETRY_INTERVAL_MS = 600L
    }
}
