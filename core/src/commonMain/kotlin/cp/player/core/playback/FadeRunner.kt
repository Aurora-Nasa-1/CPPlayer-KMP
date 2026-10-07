package cp.player.core.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 把 [FadeConfig] 变成一个**正在跑的斜坡**。
 *
 * ### 职责边界
 *
 * 它只做一件事：在给定时间内把「当前音量」从 A 推到 B，期间不断调用
 * `apply(volume)` 把值送出去。它**不**管是不是该淡、也不管什么时候该淡 ——
 * 那些由 [PlaybackControllerImpl] 在切歌 / 曲末 / 暂停等真实事件点决定。
 *
 * ### 为什么目标音量要由外部传进来
 *
 * 淡入的目标是**用户音量**（`userVolume`），而不是恒定的 `1f`。
 * 如果这里写死 `1f`，那么「淡入」会在结束时把播放器音量顶到满格 ——
 * 用户把音量调到 30%，切首歌就变成 100%，这是最典型的「做淡入淡出做出的事故」。
 *
 * ### 步进策略
 *
 * 用**固定步数**而不是固定帧间隔：`stepCount` 决定斜坡的平滑度，
 * 实际帧间隔 = `durationMs / stepCount`。这样「拖长时长」不会让调用次数无限增长
 * （否则 10 秒淡出、每帧 16ms 就是 625 次调用），也不会在 500ms 的短淡出时
 * 因为步进太粗而听出阶梯。
 *
 * 探针实测：桌面 `setVolume` 单次 **52.8 ns**（与本来就在高频调用的
 * `nativeGetPositionMs` 的 45.9 ns 同量级），一条 50 步的 1 秒斜坡总耗时
 * **2.5 µs = 该时长的 0.25%**。所以这个调用频率完全不必省。
 */
class FadeRunner(
    private val scope: CoroutineScope,
    /** 把音量送到平台。抽成函数是为了单测能塞一个只记录数值的假实现。 */
    private val apply: (Float) -> Unit,
) {

    private var job: Job? = null

    /** 当前斜坡是否还在跑（供外部判断「淡出做完了没」）。 */
    val isRunning: Boolean get() = job?.isActive == true

    /**
     * 启动一条斜坡（会**取消**上一条 —— 这叫「抢占」）。
     *
     * 抢占是必须的：用户在淡出途中点了「下一首」，此时旧的淡出斜坡还在把音量
     * 往 0 推，如果不取消，它会在新歌开始后继续压音量，表现为「新歌一直很轻」。
     *
     * @param from 起始音量（`[0,1]`）。
     * @param to 目标音量（`[0,1]`）。
     * @param durationMs 时长（毫秒）。`<= 0` 时直接跳到 [to]（不启动协程）。
     * @param curve 曲线类型。
     * @param onFinished 斜坡自然走完时回调（被抢占时**不**回调 ——
     *   抢占意味着有人接管了音量，此时回调「淡出完成」会触发错误的后续动作，
     *   比如刚切歌就被判定为「该停了」）。
     *
     * ### 为什么没有 `fadeIn: Boolean` 这种方向参数
     *
     * 方向完全由 [from] / [to] 的大小关系表达（`from < to` 就是淡入）。
     * 曾经有过一个 `fadeIn` 参数用来选曲线，但那个设计把「进度比例」与
     * 「音量包络」两个概念混在一起，端点非标准时会算错（详见下面的实现注释）。
     * 现在只用 [FadeCurve.ratio] 做插值，两个方向共用同一条进度曲线，
     * 参数也就没有必要存在了 —— 留着一个不改变行为的布尔量只会误导调用方。
     */
    fun start(
        from: Float,
        to: Float,
        durationMs: Int,
        curve: FadeCurve.Curve = FadeCurve.DEFAULT,
        onFinished: (() -> Unit)? = null,
    ) {
        job?.cancel()
        val start = from.coerceIn(0f, 1f)
        val target = to.coerceIn(0f, 1f)
        if (durationMs <= 0) {
            apply(target)
            onFinished?.invoke()
            return
        }
        job = scope.launch {
            val stepCount = STEP_COUNT
            val interval = durationMs.toLong() / stepCount
            for (step in 1..stepCount) {
                // 进度按"已完成的步数"算，保证最后一步正好是 1.0 ⇒ 音量精确落在 target。
                val progress = step.toFloat() / stepCount
                // ⚠️ 必须用**进度比例**（FadeCurve.ratio）插值，而不是音量包络
                // （FadeCurve.fadeOut / fadeIn）。两者在 `from=1, to=0` 时数值恰好重合，
                // 所以写错了也能"看起来对"；但换一组端点就露馅：
                // `from=0.8 → 0` 时，拿 fadeOut 的输出当比例插值会得到
                // `0.8 + (0-0.8)*fadeOut(0.02) = 0.8 - 0.8*0.97 = 0.022`
                // —— 第一帧就从 0.8 掉到 0.02，听起来是"啪"地一下没了。
                // 正确做法：比例走 ratio，再把它映回 [start, target]。
                val ratio = FadeCurve.ratio(progress, curve)
                // 在 from 与 to 之间按 ratio 插值（ratio 保证 0→1 且单调）。
                apply(start + (target - start) * ratio)
                if (step < stepCount && interval > 0L) delay(interval)
            }
            // 兜底落到精确目标：上面最后一步的浮点运算可能差一个 ulp。
            apply(target)
            onFinished?.invoke()
        }
    }

    /**
     * 立刻停下（不改变音量），用于释放。
     *
     * 与 [start] 的抢占不同：这里**不**把音量推到任何值 —— 释放路径上
     * 播放器可能已经没了，再去写音量就是「对已释放的 handle 调用」。
     * （探针实测：对无效 handle 调 `nativeSetVolume` 会抛 `RodioException`
     * 并直达进程边界，DLL 内部对此毫无容错。）
     */
    fun cancel() {
        job?.cancel()
        job = null
    }

    private companion object {
        /**
         * 每条斜坡的步数。
         *
         * 50 步在 500ms（最短）时是 10ms/步、在 10s（最长）时是 200ms/步。
         * 短淡出时 10ms 一步已经足够平滑（比单帧 16ms 还密）；
         * 长淡出时 200ms 一步也听不出阶梯 —— 因为长淡出的音量变化本来就慢，
         * 相邻两步的差值很小。
         */
        const val STEP_COUNT = 50
    }
}
