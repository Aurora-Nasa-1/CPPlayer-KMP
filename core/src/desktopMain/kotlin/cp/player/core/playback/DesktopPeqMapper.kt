package cp.player.core.playback

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 把平台无关的 [AudioEffectConfig] 翻译成 FlickEngine PEQ / FX 的**真实下发参数**。
 *
 * ### 为什么要单独一层（而不是在 Player 里就地算）
 * 1. **可测**：Q 值推导、0 dB 段剔除、频点合并全是纯函数，能在 `desktopTest` 里逐条钉住；
 *    放在 Player 里就只能靠「听感」验收。
 * 2. **去抖与 diff 需要稳定的指纹**：[peqPlan] / [fxPlan] 的 `signature()` 是判「参数到底变没变」
 *    的唯一依据。就地算的话，浮点抖动会让每次拖动都被判成「变了」，
 *    于是拖一次推子下发几十次、每次都重建整条 biquad 链（听感上是咔哒 + 相位跳变）。
 *
 * ### 三处与「配置模型」不一致、但**必须**在这里纠正的地方
 *
 * **(a) 0 dB 段不下发**
 * 峰值滤波器在 0 dB 时增益恒为 1（`b0..a2` 退化成分母相同的恒等式），
 * 下发它只是白烧一次乘加、并多引入一次 f32 舍入。5 段里常态只有 1~2 段非零，
 * 剔除后每样本的运算量按段数线性下降。
 *
 * **(b) Q 值由频点间距推导，而不是恒为 1.0**
 * 旧实现给所有段固定 `q = 1.0`（≈1.41 倍频程带宽）。而 5 段默认频点
 * 60 / 230 / 910 / 3600 / 14000 的相邻间距是 1.94 / 1.98 / 1.98 / 1.96 倍频程 ——
 * 也就是说每段滤波器都窄于它负责的频段，**相邻段之间留下凹陷**，
 * 拉高某一段时表现为「一个窄包而不是一段斜坡」。
 * 现在按 `Q = √(2^BW) / (2^BW − 1)`（BW = 该段负责的倍频程数）推导，
 * 带宽正好铺满到相邻段的几何中点，符合图形/参数均衡器的通行做法。
 *
 * ⚠️ Q 一律由**完整的段布局**算出，而不是由「剔除 0 dB 之后剩下的段」——
 * 否则把相邻段拉回 0 dB 会让本段 Q 突然变宽，音色跟着跳。
 *
 * **(c) FX 的 wet 比例必须显式写 0**
 * 见 [CLEAN_FX_*]：引擎 `SpatialFx` 的 `enabled` 只决定「跑不跑」，
 * 真正决定混响量的是 `mix`（默认 0.25）+ `feedback`（默认 0.35）+ 固定 240 ms 延迟。
 * 旧实现为了下发声道平衡/立体声宽度把 `enabled` 置真，却把 `mix`/`feedback` 留在默认值 ⇒
 * **用户只是想往左偏一点，听到的是一条 240 ms 的混响尾巴**。
 */
internal object DesktopPeqMapper {

    /** 低于这个增益（dB）的段视作「平直」，不下发。配置量化步长是 0.1 dB，留一半余量。 */
    const val MIN_AUDIBLE_GAIN_DB = 0.05f

    /** 单段负责带宽的上下限（倍频程）。上限 4 对应 Q≈0.27（极宽），下限 0.3 对应 Q≈4.8。 */
    const val MIN_BANDWIDTH_OCT = 0.3f
    const val MAX_BANDWIDTH_OCT = 4.0f

    /** 合并同频点后允许的最大增益（dB）。两段 +12 dB 叠在同一频点不该直接爆掉。 */
    private const val MAX_MERGED_GAIN_DB = 24f

    /** 频点视为「同一点」的阈值（Hz）。用户手调两个段落到同一频点时合并，而不是串联两次。 */
    private const val SAME_FREQ_EPSILON_HZ = 1.0f

    /**
     * 一次 PEQ 下发的完整参数（与 JNI `nativeSetEqualizer(enabled, freqs, gains, qs)` 一一对应）。
     *
     * [active] 为 false 表示**应当关闭** EQ（所有段都在 0 dB 或没有段）——
     * 此时[ freqsHz] 等为空数组，调用方不应把它当成「下发 0 段但开启」。
     */
    data class PeqPlan(
        val freqsHz: FloatArray,
        val gainsDb: FloatArray,
        val qs: FloatArray,
    ) {
        val active: Boolean get() = freqsHz.isNotEmpty()

        /** 参数指纹。值没变就不该重复下发（见类 KDoc 的去抖说明）。 */
        fun signature(): String {
            if (!active) return "off"
            return buildString {
                for (i in freqsHz.indices) {
                    append(freqsHz[i].roundToInt())
                    append(':')
                    append((gainsDb[i] * 100f).roundToInt())
                    append(':')
                    append((qs[i] * 100f).roundToInt())
                    append(';')
                }
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PeqPlan) return false
            return freqsHz.contentEquals(other.freqsHz) &&
                gainsDb.contentEquals(other.gainsDb) &&
                qs.contentEquals(other.qs)
        }

        override fun hashCode(): Int =
            (freqsHz.contentHashCode() * 31 + gainsDb.contentHashCode()) * 31 + qs.contentHashCode()

        companion object {
            val EMPTY = PeqPlan(FloatArray(0), FloatArray(0), FloatArray(0))
        }
    }

    /** 一次 FX 下发的参数（只用到引擎 `set_fx` 的 balance / width 两个字段）。 */
    data class FxPlan(val enabled: Boolean, val balance: Float, val width: Float) {
        fun signature(): String =
            "${if (enabled) 1 else 0}:${(balance * 1000f).roundToInt()}:${(width * 1000f).roundToInt()}"

        companion object {
            val OFF = FxPlan(enabled = false, balance = 0f, width = 1f)
        }
    }

    // ============================ PEQ ============================

    fun peqPlan(config: EqualizerConfig): PeqPlan {
        if (!config.enabled || config.bands.isEmpty()) return PeqPlan.EMPTY
        return peqPlan(config.bands)
    }

    /**
     * 纯函数入口（[EqualizerConfig] 的开关判定在外面）。
     *
     * 步骤固定为：夹频点 → 稳定排序 → 同频点合并 → 按**原布局**算 Q → 剔除 0 dB 段。
     * 顺序不能换：先剔 0 dB 再算 Q 会让 Q 随增益改动跳变（见类 KDoc (b)）。
     */
    fun peqPlan(bands: List<EqBand>): PeqPlan {
        if (bands.isEmpty()) return PeqPlan.EMPTY

        // 1. 夹到模型声明的合法区间。引擎自己也会夹（对 f >= fs/2 的段），
        //    但它不知道用户的意图，越界值先在这里变成「用户可见的边界」。
        val clamped = bands.map { band ->
            band.copy(
                frequencyHz = band.frequencyHz.coerceIn(EqBand.EQ_FREQ_MIN_HZ, EqBand.EQ_FREQ_MAX_HZ),
                gainDb = band.gainDb.coerceIn(EqBand.EQ_GAIN_MIN_DB, EqBand.EQ_GAIN_MAX_DB),
            )
        }.sortedBy { it.frequencyHz }

        // 2. 同频点合并：两段落在同一频率上必须相加，串联两个相同 biquad 等于增益翻倍，
        //    但用户看到的是「两段各 +6」，此处语义应当是「这一频点 +12」。
        val merged = ArrayList<EqBand>(clamped.size)
        for (band in clamped) {
            val last = merged.lastOrNull()
            if (last != null && abs(last.frequencyHz - band.frequencyHz) < SAME_FREQ_EPSILON_HZ) {
                merged[merged.lastIndex] = last.copy(
                    gainDb = (last.gainDb + band.gainDb).coerceIn(-MAX_MERGED_GAIN_DB, MAX_MERGED_GAIN_DB),
                )
            } else {
                merged += band
            }
        }

        // 3. Q 用合并后的完整布局算（布局是稳定的，与增益无关）。
        val layout = merged.map { it.frequencyHz }
        val qs = FloatArray(merged.size) { i -> qForBand(layout, i) }

        // 4. 剔除平直段。
        val freqs = ArrayList<Float>(merged.size)
        val gains = ArrayList<Float>(merged.size)
        val keptQs = ArrayList<Float>(merged.size)
        for (i in merged.indices) {
            val gain = merged[i].gainDb
            if (abs(gain) < MIN_AUDIBLE_GAIN_DB) continue
            freqs += merged[i].frequencyHz
            gains += gain
            keptQs += qs[i]
        }
        if (freqs.isEmpty()) return PeqPlan.EMPTY

        return PeqPlan(freqs.toFloatArray(), gains.toFloatArray(), keptQs.toFloatArray())
    }

    /**
     * 第 [index] 段应采用的 Q：把它的通带铺到**相邻两段的几何中点之间**。
     *
     * - 中间段：`BW = ½·log2(f[i+1] / f[i−1])` ——
     *   左边界是 `√(f[i−1]·f[i])`、右边界是 `√(f[i]·f[i+1])`，两者之比正好是 `√(f[i+1]/f[i−1])`。
     *   ⚠️ 不能直接取 `log2(f[i+1]/f[i−1])`（那是**两个**间距，会把 Q 算小一半，
     *   5 段默认布局下会得到 Q≈0.27 的「一大坨」，拉任何一段都像整体提亮）；
     * - 端点段：只有一侧邻居，按该间距整体铺开（低频段顺势覆盖次低频，符合听感预期）；
     * - 只有一段时退回 1 倍频程（Q≈1.41），与最常见的单段 PEQ 直觉一致。
     */
    fun qForBand(freqs: List<Float>, index: Int): Float {
        require(index in freqs.indices) { "band index $index out of ${freqs.size}" }
        if (freqs.size <= 1) return qForBandwidth(1.0f)

        val bandwidth = when (index) {
            0 -> log2(freqs[1] / freqs[0])
            freqs.lastIndex -> log2(freqs[index] / freqs[index - 1])
            else -> 0.5f * log2(freqs[index + 1] / freqs[index - 1])
        }
        return qForBandwidth(bandwidth)
    }

    /**
     * 带宽（倍频程）→ Q。
     *
     * 峰值滤波器的 −3 dB 带宽 `BW`（倍频程）与 Q 的关系：
     * `Q = √(2^BW) / (2^BW − 1)`。BW = 1 时 Q = √2 ≈ 1.414；
     * BW → 0（极窄）时 Q 单调增大。这条式子与 RBJ Audio EQ Cookbook 的
     * `alpha = sin(w0)/(2Q)` 一致，只是把「用户可理解的倍频程」换成引擎要的 Q。
     */
    fun qForBandwidth(octaves: Float): Float {
        val bw = octaves.coerceIn(MIN_BANDWIDTH_OCT, MAX_BANDWIDTH_OCT)
        val ratio = 2.0.pow(bw.toDouble()).toFloat()
        val q = sqrt(ratio) / (ratio - 1f)
        // 落到 0.01 分辨率：引擎按 f32 存，多留几位没有意义，反而让指纹抖动。
        return (q * 100f).roundToInt() / 100f
    }

    // ============================ FX ============================

    /**
     * 混音能力 → FX 参数。
     *
     * `width` 是**侧声道增益倍数**（引擎里 `side *= width`），1.0 = 原样；
     * 空间感强度 0..1 映射到 1.0..1.5（最多把侧声道放大一半，仍属"加宽"而非"环绕"）。
     */
    fun fxPlan(config: MixerConfig): FxPlan {
        val balance = config.balance.coerceIn(MixerConfig.BALANCE_MIN, MixerConfig.BALANCE_MAX)
        val strength = config.virtualizerStrength
            .coerceIn(MixerConfig.VIRTUALIZER_MIN, MixerConfig.VIRTUALIZER_MAX)
        val width = 1f + strength * 0.5f
        val enabled = abs(balance) > 1e-4f || abs(width - 1f) > 1e-4f
        return if (enabled) FxPlan(true, balance, width) else FxPlan.OFF
    }

    /**
     * FX 的三个「必须显式清零」的参数。
     *
     * 引擎 `SpatialFx` 的 `enabled=true` 会**同时**启用延迟线混响（固定 240 ms）与立体声加宽，
     * 两者共用 `enabled` 一个开关。而我们只用它的加宽/平衡部分，
     * 所以 `mix`（湿声比例）与 `feedback`（反馈量）必须写 0，
     * 否则「调左右平衡」会附带一条可听的混响尾巴。
     *
     * `damp` 只在湿声通路上起作用，mix = 0 时无意义，一并写 0 让意图显式化。
     */
    const val CLEAN_FX_MIX = 0f
    const val CLEAN_FX_FEEDBACK = 0f
    const val CLEAN_FX_DAMP = 0f

    // ============================ 动态处理（响度均衡 / 防爆音） ============================

    /**
     * [LevelingConfig] → 引擎 compressor + limiter 的参数。
     *
     * ### 映射为什么是这样的
     * 用户的两个旋钮是「目标响度」与「防爆音」，而引擎要的是压缩器五参数 +
     * 限幅器三参数。这里做一次**保守的启发式**映射，不追求广播级响度归一：
     * - `targetLevelDb` 直接当压缩阈值：目标越低，越早开始压；
     * - `ratio = 3` / `attack = 10ms` / `release = 200ms`：慢启动快释放之外的
     *   常见折中，快了会抽吸、慢了会漏过峰值；
     * - `makeupGainDb = −targetLevelDb / 2`：只补回一半被压掉的电平，
     *   避免「压完再全补回去」等于没压，同时不至于让轻的段落更轻；
     * - 限幅器固定 `ceiling = −1 dBFS / release = 100ms`，是防爆音（preventClipping）
     *   的硬底，与用户目标响度解耦。
     *
     * 全部是**纯数值**，不碰引擎、不查能力位，便于单测逐条钉住。
     */
    data class DynamicsPlan(
        val compressorEnabled: Boolean,
        val thresholdDb: Float,
        val ratio: Float,
        val attackMs: Float,
        val releaseMs: Float,
        val makeupGainDb: Float,
        val limiterEnabled: Boolean,
        val inputGainDb: Float,
        val ceilingDb: Float,
        val limiterReleaseMs: Float,
    ) {
        fun signature(): String = listOf(
            compressorEnabled, thresholdDb, ratio, attackMs, releaseMs, makeupGainDb,
            limiterEnabled, inputGainDb, ceilingDb, limiterReleaseMs,
        ).joinToString(":") { if (it is Float) (it * 100f).roundToInt().toString() else it.toString() }

        companion object {
            val OFF = DynamicsPlan(
                compressorEnabled = false,
                thresholdDb = LevelingConfig.DEFAULT_TARGET_LEVEL_DB,
                ratio = 3f,
                attackMs = 10f,
                releaseMs = 200f,
                makeupGainDb = 0f,
                limiterEnabled = false,
                inputGainDb = 0f,
                ceilingDb = -1f,
                limiterReleaseMs = 100f,
            )
        }
    }

    fun dynamicsPlan(config: LevelingConfig, overallEnabled: Boolean): DynamicsPlan {
        if (!overallEnabled || !config.enabled) return DynamicsPlan.OFF
        val target = config.targetLevelDb.coerceIn(
            LevelingConfig.TARGET_LEVEL_MIN_DB,
            LevelingConfig.TARGET_LEVEL_MAX_DB,
        )
        return DynamicsPlan(
            compressorEnabled = true,
            thresholdDb = target,
            ratio = 3f,
            attackMs = 10f,
            releaseMs = 200f,
            makeupGainDb = (-target * 0.5f).coerceIn(0f, 6f),
            limiterEnabled = config.preventClipping,
            inputGainDb = 0f,
            ceilingDb = -1f,
            limiterReleaseMs = 100f,
        )
    }
}
