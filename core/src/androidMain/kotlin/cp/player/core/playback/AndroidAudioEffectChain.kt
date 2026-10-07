package cp.player.core.playback

import android.media.audiofx.DynamicsProcessing

/**
 * Android 音效链：把平台无关的 [AudioEffectConfig] 落到 `DynamicsProcessing` 上。
 *
 * ### 为什么用 DynamicsProcessing 而不是 Equalizer + LoudnessEnhancer + Virtualizer
 *
 * 三种方案都核过 API（`javap` 逐方法），结论：
 * - `android.media.audiofx.Equalizer` **段数与频点都是系统给的**
 *   （`getNumberOfBands()` / `getCenterFreq()`），改不了 —— 做不到用户调频点，
 *   也就不是 PEQ；
 * - 多个效果叠加（`Equalizer` + `LoudnessEnhancer` + `Virtualizer` + `BassBoost`）
 *   会串成一条长链，每个都带来额外延迟与相位偏移，且**效果间无法统一管理**
 *   （例如防爆音必须在最后一级，但用户可能中途开关前面的效果，顺序就乱了）；
 * - `DynamicsProcessing`（API 28+）**一个实例**同时提供：多段 EQ（段增益 +
 *   段转折频率可调）、每声道输入增益（→ 声道平衡）、限幅器（→ 响度均衡与防爆音）。
 *   前三项正是本功能的三块需求，且**级联顺序由系统固定**（preEq → mbc → postEq
 *   → limiter），不会出现我们自己拼链拼错的情况。
 *
 * ### ⚠️ 已知的 API 限制（诚实记录，别再试图绕）
 * - **Q 值不可运行时调整**：`EqBand` 只有 `setGain` / 继承的 `setCutoffFrequency`，
 *   没有 `setQ`。这是 [EqBand] 里没有 Q 字段的直接原因。
 * - **段数在构造时固定**：`Config.Builder` 的 `preEqBandCount` 一旦定下，
 *   运行时不能增减。故 [EqualizerConfig.EQ_BAND_COUNT] 是个常量。
 * - **`audioSessionId` 会变**：换源时 ExoPlayer 可能重新申请会话，旧会话上的
 *   效果随即静默失效。故每次会话变化都要重建并重放配置。
 *
 * ### 版本降级
 *
 * `DynamicsProcessing` 需要 API 28。本仓 minSdk 是 24，因此 API < 28 返回
 * [NoopAudioEffectChain]（能力位全 false）—— 设置页据此**明示禁用**。
 * ⚠️ **不做**「低版本退化成 Equalizer」的兼容：那会让同一个设置项在不同设备上
 * 语义不同（调频点无效），用户无法理解。宁可如实说不支持。
 */
internal interface AudioEffectChain {

    /** 本设备支持哪些音效能力。 */
    val capabilities: AudioEffectCapabilities

    /**
     * 应用配置。**必须可重复调用**（同一份配置连续调用两次无副作用）。
     *
     * @param config 目标配置；`enabled = false` 表示拆链回直通。
     */
    fun apply(config: AudioEffectConfig)

    /** 音频会话变化（换源 / 重建播放器）后重建效果链。 */
    fun onAudioSessionChanged(sessionId: Int)

    /** 释放底层 effect。 */
    fun release()
}

/** 不支持音效的设备（API < 28）用的空实现。 */
internal object NoopAudioEffectChain : AudioEffectChain {
    override val capabilities: AudioEffectCapabilities = AudioEffectCapabilities.NONE
    override fun apply(config: AudioEffectConfig) = Unit
    override fun onAudioSessionChanged(sessionId: Int) = Unit
    override fun release() = Unit
}

/**
 * `DynamicsProcessing` 实现。
 *
 * ### 线程
 * `audiofx` 的 setter 是**跨进程 Binder 调用**，有开销。调用点在设置页的提交回调
 * （松手才提交，见 `SettingsSliderItem`）与换曲回调上，都不在逐帧路径上，
 * 故不额外切线程 —— 切线程反而会拉大「松开滑杆到听到变化」的延迟。
 */
internal class DynamicsProcessingChain(initialSessionId: Int) : AudioEffectChain {

    override val capabilities: AudioEffectCapabilities = AudioEffectCapabilities.ANDROID_DYNAMICS

    /** 底层 effect；null 表示已释放或创建失败（此时全部调用成为空操作）。 */
    private var effect: DynamicsProcessing? = null

    /** 最近一次应用的配置，供会话重建后重放。 */
    private var lastConfig: AudioEffectConfig = AudioEffectConfig.OFF

    /** 当前实际挂载的会话 id（会话变化时用它对比）。 */
    private var currentSessionId: Int = initialSessionId

    init {
        rebuild(initialSessionId)
    }

    /**
     * 用给定会话 id 重建效果链。
     *
     * 失败（会话无效 / 被别的应用占用 / ROM 未实现）一律降级为 effect = null：
     * **不抛异常** —— 音效不可用不该让播放失败。
     */
    @Synchronized
    private fun rebuild(sessionId: Int) {
        releaseEffect()
        currentSessionId = sessionId
        if (sessionId == 0) return
        effect = runCatching { buildEffect(sessionId) }
            .onFailure {
                println("[$TAG] DynamicsProcessing 创建失败（session=$sessionId）: ${it.message}")
            }
            .getOrNull()
    }

    /**
     * 构造 `DynamicsProcessing`。
     *
     * ### 通道数固定为 2（立体声）
     * 我们内部的 EQ / 平衡都是按立体声建模的。传单声道会让声道平衡失去意义
     * （只有一个声道）。系统最终会重混到设备实际支持的布局。
     *
     * ### 用 `Config.Builder` 而不是 `DynamicsProcessing(variant, channelCount, ...)`
     * Builder 更啰嗦但**可读**：段数、各功能是否启用都必须显式声明，
     * 而位置参数版本在 9 个 int/bool 里极易看错一个。
     */
    private fun buildEffect(sessionId: Int): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            /* variant = */ DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            /* channelCount = */ STEREO_CHANNELS,
            /* preEqInUse = */ true,
            /* preEqBandCount = */ EqualizerConfig.EQ_BAND_COUNT,
            /* mbcInUse = */ false,
            /* mbcBandCount = */ 0,
            /* postEqInUse = */ false,
            /* postEqBandCount = */ 0,
            /* limiterInUse = */ true,
        ).build()
        return DynamicsProcessing(0, sessionId, config)
    }

    override fun apply(config: AudioEffectConfig) {
        lastConfig = config
        applyInternal(config)
    }

    /**
     * 把配置写到 effect 上。
     *
     * 全程 `runCatching`：`audiofx` 的 setter 在 effect 被系统回收后会抛
     * `IllegalStateException`，音效失效不该把播放带崩。
     *
     * ⚠️ 这里**每段都是全量重设**，不做「值没变就跳过」的优化。
     * 试过加缓存对比，但 `DynamicsProcessing` 的 getter 返回的是**副本**
     * （读回来和写进去的不是同一个对象，equals 也不实现），做对比反而要自建一份
     * 影子状态 —— 多一份状态就多一处能漂移的地方。松手才提交的节奏下，
     * 十余次 Binder 调用在毫秒级，不值得为它引入影子状态。
     */
    private fun applyInternal(config: AudioEffectConfig) {
        val dp = effect ?: return
        runCatching {
            val on = config.enabled
            applyEqualizer(dp, if (on) config.equalizer else null)
            applyBalance(dp, if (on) config.mixer else null)
            applyLimiter(dp, if (on) config.leveling else null)
        }.onFailure { println("[$TAG] 应用音效失败: ${it.message}") }
    }

    /**
     * PEQ → `preEq` 各段。
     *
     * `EqBand(enabled, cutoffFrequency, gain)`：转折频率与增益都可运行时改，
     * **Q 值不可**（见类 KDoc）。
     */
    private fun applyEqualizer(dp: DynamicsProcessing, eq: EqualizerConfig?) {
        val enabled = eq != null && eq.enabled

        // 段本身是否启用：整条 EQ 的开关。放在逐段写之前，避免「关掉后曲线仍在」。
        for (channel in 0 until STEREO_CHANNELS) {
            runCatching {
                val eqStage = dp.getPreEqByChannelIndex(channel)
                if (eqStage.isEnabled != enabled) {
                    eqStage.setEnabled(enabled)
                    dp.setPreEqByChannelIndex(channel, eqStage)
                }
            }
        }

        // 增益与频点：关了就不写（保留上次的值，下次打开即恢复）。
        if (eq == null) return
        eq.bands.forEachIndexed { index, band ->
            if (index >= EqualizerConfig.EQ_BAND_COUNT) return@forEachIndexed
            val nativeBand = DynamicsProcessing.EqBand(
                /* enabled = */ true,
                /* cutoffFrequency = */ band.frequencyHz,
                /* gain = */ band.gainDb,
            )
            for (channel in 0 until STEREO_CHANNELS) {
                runCatching { dp.setPreEqBandByChannelIndex(channel, index, nativeBand) }
            }
        }
    }

    /**
     * 声道平衡 → 每声道输入增益。
     *
     * ### 换算
     * `balance` 是 `-1`（全左）~ `+1`（全右）。映射成两个声道各自的 dB 增益：
     * 一侧衰减、另一侧保持在 0dB（**不是**把另一侧也抬起来 —— 那样整体会变响，
     * 用户会以为音量滑杆失灵）。
     *
     * `-1` ⇒ 左 0dB / 右 [MAX_BALANCE_ATTENUATION_DB]；`+1` ⇒ 反向。
     * 用 `-60dB` 而非真正的 `-Infinity`：后者无法写进 float 参数，
     * 而 -60dB 在听感上已经等同静音。
     */
    private fun applyBalance(dp: DynamicsProcessing, mixer: MixerConfig?) {
        val balance = (mixer?.balance ?: 0f)
            .coerceIn(MixerConfig.BALANCE_MIN, MixerConfig.BALANCE_MAX)
        val leftDb = if (balance > 0f) MAX_BALANCE_ATTENUATION_DB * balance else 0f
        val rightDb = if (balance < 0f) MAX_BALANCE_ATTENUATION_DB * -balance else 0f

        listOf(leftDb, rightDb).forEachIndexed { channel, db ->
            runCatching {
                val channelConfig = dp.getChannelByChannelIndex(channel) ?: return@runCatching
                channelConfig.setInputGain(db)
                dp.setChannelTo(channel, channelConfig)
            }
        }
    }

    /**
     * 响度均衡 / 防爆音 → 限幅器。
     *
     * ### 为什么只用限幅器，不用多段压缩（`mbc`）
     * - 压缩需要按频段配交叉点，对「整体响度均衡」而言过细，且会引入明显的
     *   泵动感（pumping）；
     * - 限幅器听感更自然，且**级联在最后一级**，天然满足防爆音需求。
     *
     * ### 参数映射
     * `Limiter(inUse, enabled, linkGroup, attackTime, releaseTime, ratio, threshold, postGain)`：
     * - 目标电平（[LevelingConfig.targetLevelDb]）→ `threshold`：目标越响
     *   （越接近 0dB），阈值越接近 0（削得越少）；
     * - 「防爆音」→ 阈值固定在最接近 0 的一档（只削真正会削顶的峰值）。
     *   两者都开时取**更激进（更负）**的那个。
     * - `ratio` 不设成无限大（那是硬削顶，会听出失真），用一个「接近限制器」的高值；
     * - `linkGroup` 用单组，让两个声道**联动**降增益 —— 不联动会导致大音量时
     *   声像左右漂移。
     */
    private fun applyLimiter(dp: DynamicsProcessing, leveling: LevelingConfig?) {
        // ⚠️ preventClipping 只在响应度均衡的开关打开时才生效 —— 与
        // AudioEffectConfig.anyFeatureEnabled 的出厂状态保持一致：默认配置
        // （leveling.enabled = false、preventClipping = true）必须等价于直通，
        // 否则"防爆音默认开"会让全默认配置一上来就挂一条限幅器。
        val enabled = leveling != null && leveling.enabled
        val preventClipping = enabled && leveling!!.preventClipping
        val active = enabled || preventClipping

        val threshold = when {
            leveling == null -> CLIP_THRESHOLD_DB
            leveling.enabled && leveling.preventClipping ->
                minOf(leveling.targetLevelDb, CLIP_THRESHOLD_DB)
            leveling.enabled -> leveling.targetLevelDb
            else -> CLIP_THRESHOLD_DB
        }

        for (channel in 0 until STEREO_CHANNELS) {
            runCatching {
                val limiter = dp.getLimiterByChannelIndex(channel) ?: return@runCatching
                limiter.setEnabled(active)
                limiter.setLinkGroup(LINK_GROUP)
                limiter.setAttackTime(LIMITER_ATTACK_MS)
                limiter.setReleaseTime(LIMITER_RELEASE_MS)
                limiter.setRatio(LIMITER_RATIO)
                limiter.setThreshold(threshold)
                // postGain 保持 0：限幅器只负责"不超标"，不做整体增益补偿。
                // 补偿响度是用户音量滑杆的事，两处都调会出现"两个音量"的混乱。
                limiter.setPostGain(0f)
                dp.setLimiterByChannelIndex(channel, limiter)
            }
        }
    }

    @Synchronized
    override fun onAudioSessionChanged(sessionId: Int) {
        if (sessionId == currentSessionId) return
        rebuild(sessionId)
    }

    @Synchronized
    override fun release() {
        releaseEffect()
    }

    private fun releaseEffect() {
        runCatching { effect?.enabled = false }
        runCatching { effect?.release() }
        effect = null
    }

    private companion object {
        const val TAG = "AudioEffectChain"

        /** 立体声：`AudioFormat.CHANNEL_OUT_STEREO` 的声道数。 */
        const val STEREO_CHANNELS = 2

        /**
         * 防爆音阈值（dB）。
         *
         * `-3dB` 而不是 `0dB`：数字满刻度附近常有解码器内插造成的过冲，
         * 留 3dB 余量能挡住这些瞬时过冲，又不会让正常峰值被压到听得出来。
         */
        const val CLIP_THRESHOLD_DB = -3f

        /**
         * 平衡最大衰减（dB）。
         *
         * `-60dB` 在听感上等同静音（人耳分辨不出更低的值），而真正的 `-Infinity`
         * 无法写进 `audiofx` 的 float 参数。
         */
        const val MAX_BALANCE_ATTENUATION_DB = -60f

        /** 限幅器 attack（ms）：必须很短才能抓住瞬态峰值。 */
        const val LIMITER_ATTACK_MS = 1f

        /** 限幅器 release（ms）：太长会「憋住」（响度变化跟不上），太短会失真。 */
        const val LIMITER_RELEASE_MS = 200f

        /**
         * 限幅器压缩比。
         *
         * 不用 `Float.MAX_VALUE`（那等于硬削顶，会听出明显失真）；
         * 20:1 是「接近限制器但不割裂」的常用值。
         */
        const val LIMITER_RATIO = 20f

        /** 联动组号：两个声道用同一组 ⇒ 大音量时一起降增益，声像不会漂。 */
        const val LINK_GROUP = 0
    }
}
