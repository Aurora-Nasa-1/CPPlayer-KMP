package cp.player.core.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 淡入淡出的**音量曲线**计算。
 *
 * ### 为什么单独把这个拎出来
 *
 * 淡入淡出是两个正交问题的叠加：
 * 1. **在什么时候、把音量推到多少**（本文件）—— 纯函数，与播放器无关；
 * 2. **怎么把这个值送进引擎**（[FadeRunner]）—— 依赖协程与平台播放器。
 *
 * 拆开的好处是「曲线对不对」可以脱离音频设备单测：参考实现里的
 * `cp-player-legacy` 用的是幂函数（[Curve.EXPONENTIAL_ISH]），
 * Rust 引擎用的是 sin/cos（[Curve.EQUAL_POWER]）。哪个更对是可以用数字验证的，
 * 不该混在协程调度里靠听感判断。
 *
 * ### ⚠️ 两层概念不要混淆（这是这个功能最容易写错的地方）
 *
 * - **[ratio] 系列**：归一化的**进度比例**，恒为 `0 → 1`，与起止音量无关。
 *   它是"这一步完成了多少"，用于在 `[from, to]` 之间插值。
 * - **[fadeIn] / [fadeOut]**：归一化的**音量包络**，其中 [fadeOut] 是 `1 → 0`
 *   递减的。它描述"如果从满音量淡到静音，此刻应该是多少"。
 *
 * 两者在 `from=1, to=0` 时数值恰好重合，**换一组端点就会分道扬镳** ——
 * 直接混用会写出"第一帧音量比起点还低"这种错（已踩，见 [FadeRunner] 的注释）。
 * 所以插值一律用 [ratio]，[fadeIn] / [fadeOut] 只用于"从满到零"这种标准场景
 * （以及将来 crossfade 时两轨叠加的等功率计算）。
 */
object FadeCurve {

    /**
     * 曲线类型。
     *
     * - [LINEAR]：音量线性爬升。**听感上是错的** —— 人耳对响度的感知接近对数，
     *   线性音量在中段会让人觉得「一下就上来了、后半段没变化」。
     * - [EQUAL_POWER]：`sin` / `cos`。这条曲线保证**功率恒定**，
     *   是交叉淡入淡出的标准做法（两首歌叠加时总功率不塌陷）。
     * - [EXPONENTIAL_ISH]：幂函数，`cp-player-legacy` 在 Kotlin 层用的那组
     *   （淡出 `p^1.5`、淡入 `p^0.8`）。它比线性自然，又不像 sin/cos 那样
     *   在中段压得太快。
     *
     * ### 单播放器该用哪条
     *
     * 用 [EXPONENTIAL_ISH]。理由：sin/cos 的**功率恒定**是为「两条音轨叠加」
     * 服务的；单播放器淡出时并没有第二条轨在补位，用等功率曲线只会让
     * 音乐在中段塌得比听感需要的更快（一条 `cos` 从 1 掉到 0.7 只要走到 45%）。
     * 幂函数那组（淡出 1.5 次幂、淡入 0.8 次幂）是参考实现在**同样的
     * 单播放器场景**下调出来的，比我们凭感觉另选一条更有依据。
     */
    enum class Curve {
        LINEAR,
        EQUAL_POWER,
        EXPONENTIAL_ISH,
    }

    /** 本仓单播放器淡入淡出采用的曲线。 */
    val DEFAULT = Curve.EXPONENTIAL_ISH

    /**
     * **进度比例**：给定归一化时间 `[0,1]`，返回"过渡完成了多少" `[0,1]`。
     *
     * 这是唯一驱动插值的函数 —— 淡入淡出**共用**它（两个方向的形状本就是对偶的：
     * 淡入是"由慢到快地接近目标"，淡出就是反过来）。
     *
     * @param progress 归一化时间进度，越界会被钳制。
     */
    fun ratio(progress: Float, curve: Curve = DEFAULT): Float {
        val p = progress.coerceIn(0f, 1f)
        return when (curve) {
            // 线性：比例就等于时间。
            Curve.LINEAR -> p
            // 等功率：用 sin 的 1/4 周期当进度形状。
            // ⚠️ 注意这里返回的是 `sin(p·π/2)`，**不是**等功率音量包络本身 ——
            // 两者在这个方向上数值一致（因为起点 0、终点 1），
            // 但语义不同：这个是进度，见下面的 fadeOut 才是包络。
            Curve.EQUAL_POWER -> sin(p * (PI / 2)).toFloat()
            // 0.8 次幂：pow(0.5, 0.8) = 0.574 —— 比线性略快抬起来，
            // 小声时更快可闻，符合"淡入要趁早听出来"的直觉。
            Curve.EXPONENTIAL_ISH -> powApprox(p, 0.8f)
        }
    }

    /**
     * 淡入的**音量包络**：进度 `0 → 1` 时音量 `0 → 1`。
     *
     * 与 [ratio] 数值相同（两者都是 0→1 递增），保留独立名字是为了让
     * "从满淡到零"这类标准场景的调用点读起来是音量语义而不是进度语义。
     */
    fun fadeIn(progress: Float, curve: Curve = DEFAULT): Float = ratio(progress, curve)

    /**
     * 淡出的**音量包络**：进度 `0 → 1` 时音量 `1 → 0`。
     *
     * ⚠️ 参数语义是**进度**而不是音量：`0` = 刚开始淡出（音量仍为满），
     * `1` = 淡出结束（音量 0）。把两者搞反是这个功能最容易出的错，
     * 也是唯一值得单测钉死的地方。
     *
     * ⚠️ 返回值是**归一化音量**，不是可以直接拿去插值的比例 ——
     * 在 `[from, to]` 端点不标准时必须用 [ratio]，理由见本类的 KDoc。
     */
    fun fadeOut(progress: Float, curve: Curve = DEFAULT): Float {
        val p = progress.coerceIn(0f, 1f)
        return when (curve) {
            Curve.LINEAR -> 1f - p
            // 等功率的"淡出"是 cos 那一支（与 sin 互补，功率恒定）。
            Curve.EQUAL_POWER -> cos(p * (PI / 2)).toFloat()
            // 1.5 次幂：pow(0.5, 1.5) = 0.354，比线性掉得慢（尾部更缓），
            // 听感上「渐弱」而不是「被掐掉」。
            Curve.EXPONENTIAL_ISH -> powApprox(1f - p, 1.5f)
        }
    }

    /**
     * `x^exponent`（`x ∈ [0,1]`）的近似实现。
     *
     * 为什么不用 `kotlin.math.pow`：`Float.pow(Float)` 在 **commonMain 里不可用**
     * （它是 JVM/JS 等平台各自的扩展，Kotlin 没有把它放进公共库）。
     * 而这个函数的定义域很窄（`[0,1]` 且指数固定为 0.8 / 1.5），
     * 用 `exp(ln(x) * e)` 的等价变形即可，代价可忽略（每条斜坡只算几十次）。
     */
    private fun powApprox(x: Float, exponent: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        // exp(ln(x) * e)：用 Double 算避免中间精度损失，结果再回 Float。
        val v = kotlin.math.exp(kotlin.math.ln(x.toDouble()) * exponent.toDouble())
        return v.toFloat().coerceIn(0f, 1f)
    }
}
