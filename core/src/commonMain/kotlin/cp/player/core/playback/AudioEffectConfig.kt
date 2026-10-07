package cp.player.core.playback

/**
 * 音效参数模型（平台无关）。
 *
 * ### 为什么是一个整体对象而不是三个独立 setter
 *
 * 三块效果（PEQ / 混音 / 响度均衡）最终都要落到**同一条音频效果链**上
 * （Android 是 `DynamicsProcessing` 的一个实例：各段 EQ + 各声道增益 + 动态处理
 * 在同一个 effect 里）。三个独立 setter 会让每次调节都"重建链"或"局部改一段"，
 * 前者导致听感断续，后者容易漏掉必须一起改的耦合参数
 * （例如：响度均衡的压限阈值与 PEQ 的增益会互相影响）。
 *
 * 传一个不可变快照进去、由平台层一次性 diff 应用，是唯一不会漏的做法。
 *
 * ### 各字段的单位与范围（UI 与平台层共用，不要在别处再写一遍）
 * - 增益：[EqBand.EQ_GAIN_MIN_DB] ~ [EqBand.EQ_GAIN_MAX_DB]，单位 dB。
 * - 频率：[EqBand.EQ_FREQ_MIN_HZ] ~ [EqBand.EQ_FREQ_MAX_HZ]，单位 Hz。
 * - 声道平衡：[MixerConfig.BALANCE_MIN] ~ [MixerConfig.BALANCE_MAX]，
 *   `0` = 居中，负 = 偏左，正 = 偏右。
 */
data class AudioEffectConfig(
    /** 整体开关。关掉时平台层应销毁效果链、回到直通（bit-perfect 观感）。 */
    val enabled: Boolean = false,
    /** PEQ 各段（固定 [EqualizerConfig.EQ_BAND_COUNT] 段）。 */
    val equalizer: EqualizerConfig = EqualizerConfig(),
    /** 混音：声道平衡 / 声像 + 空间感强度。 */
    val mixer: MixerConfig = MixerConfig(),
    /** 音量均衡：实时响度均衡 / 防爆音。 */
    val leveling: LevelingConfig = LevelingConfig(),
) {
    /**
     * 任一子功能是否处于开启状态。
     *
     * [enabled] **应当**等于本值 —— 设置页在提交时会用 `copy(enabled = …)` 补齐
     * （见 `AudioEffectSettingsScreen.commit`）。把它做成计算属性而不是让调用方
     * 各自去 `||` 一遍：漏写一处就会挂一条什么都不做的效果链。
     *
     * ### ⚠️ 出厂状态必须为 `false`（全默认时**不**挂链）
     *
     * 音效改变信号，即便"全默认"理论上等价于直通，本仓也不默认挂一条效果链 ——
     * 多一条 Android 系统 effect 就多一次音频重采样/回环机会，白白牺牲 bit-perfect
     * 观感，还会让「第一次打开播放页偶发卡顿」这类问题多一个嫌疑。
     * 因此本值**各子项全部按默认参数衡量**：子开关一律看它自己的默认值
     * （`LevelingConfig.preventClipping` 默认为 `true`，但它只在
     * [LevelingConfig.enabled] 为 `true` 时才参与判断）。
     * 这条约束由 `AudioEffectSettingsTest.anyFeatureEnabled tracks the sub switches` 钉死。
     */
    val anyFeatureEnabled: Boolean
        get() = equalizer.enabled ||
            leveling.enabled ||
            mixer.balance != 0f ||
            mixer.virtualizerStrength != 0f

    companion object {
        /** `enabled` 关闭时的直通配置。 */
        val OFF = AudioEffectConfig()
    }
}

/**
 * 参数均衡器（PEQ）。
 *
 * ### 为什么固定 5 段
 *
 * 段数固定是**刻意的**，理由有三：
 * 1. 用户看得懂的段数有限 —— 10 段以上只能靠图形均衡器拖，那已经不是「参数」均衡了；
 * 2. 固定段数让持久化编码可以是定长数组，键拼错立刻暴露（而非静默少一段）；
 * 3. `DynamicsProcessing` 的 EQ 段数在**构造时**就固定，运行时无法增删段 ——
 *    可变段数在 Android 上需要重建整条效果链（会有一声咔哒），与「边听边调」冲突。
 *
 * 5 段覆盖的语义（对齐常见的 5 段均衡器）：低频 / 中低频 / 中频 / 中高频 / 高频。
 *
 * ⚠️ **没有 Q 值**，理由见 [EqBand] 的 KDoc（Android 效果 API 不支持运行时改 Q）。
 */
data class EqualizerConfig(
    val enabled: Boolean = false,
    val bands: List<EqBand> = DEFAULT_BANDS,
) {
    companion object {
        /** 段数固定为 5。持久化编码、UI 枚举、平台层都以此为准。 */
        const val EQ_BAND_COUNT = 5

        /**
         * 默认 5 段的转折频率（Hz）。
         *
         * 选点对齐常见的 5 段均衡器：60 / 230 / 910 / 3600 / 14000。
         * 用户可在设置页调整这些频点（Android 的 `setCutoffFrequency` 支持）。
         */
        val DEFAULT_FREQUENCIES_HZ = listOf(60f, 230f, 910f, 3600f, 14000f)

        /** 默认各段：增益 0 dB（平直）。 */
        val DEFAULT_BANDS: List<EqBand> =
            DEFAULT_FREQUENCIES_HZ.map { freq -> EqBand(frequencyHz = freq) }
    }
}

/**
 * PEQ 单段。
 *
 * ### ⚠️ 为什么**没有 Q 值**
 *
 * 真实的 PEQ 应该可调 Q（峰宽）。但本仓唯一能实现 PEQ 的平台是 Android，
 * 而 Android 的效果 API 里 **Q 值不可运行时调整**：
 * - `android.media.audiofx.DynamicsProcessing.EqBand` 只有
 *   `setGain(float)` 与继承自 `BandBase` 的 `setCutoffFrequency(float)`，
 *   **没有** `setQ` —— 逐方法 `javap` 核对过；
 * - 老的 `android.media.audiofx.Equalizer` 更差：连频点都是系统给的
 *   （`getCenterFreq(int)`），用户改不了。
 *
 * 也就是说，做一个 Q 滑杆出来，它在任何平台上都**只能是个摆设**。
 * 按本仓「宁可没有，也不要有看着能用实则无效的控件」的一贯取舍
 * （见 `PlaybackSettingsScreen` KDoc 里删掉「立即播放」开关的理由），
 * Q 值留在[类 KDoc][EqualizerConfig] 里说明为什么没有，而不是做成滑杆。
 *
 * 各段的**频点可调**（Android 的 `setCutoffFrequency` 支持），这是与
 * 「10 段图形均衡器」的关键区别，因此仍称 PEQ 而非 GEQ。
 *
 * @param gainDb 增益（dB）。`0` = 不改变该频段。[EQ_GAIN_MIN_DB] ~ [EQ_GAIN_MAX_DB]。
 * @param frequencyHz 该段的转折 / 中心频率（Hz）。[EQ_FREQ_MIN_HZ] ~ [EQ_FREQ_MAX_HZ]。
 */
data class EqBand(
    val gainDb: Float = 0f,
    val frequencyHz: Float = 1000f,
) {
    companion object {
        /** 增益范围（dB）。±12 是「够用且不容易听出失真」的常见上限。 */
        const val EQ_GAIN_MIN_DB = -12f
        const val EQ_GAIN_MAX_DB = 12f

        /** 频率范围（Hz）。下限低于 20Hz 无意义（人耳听不到且会吃掉功放余量）。 */
        const val EQ_FREQ_MIN_HZ = 20f
        const val EQ_FREQ_MAX_HZ = 20000f
    }
}

/**
 * 混音：声道平衡 / 声像 + 空间感。
 *
 * 用户侧是**两件事**，但共用同一条效果链，故合成一个配置对象。
 *
 * @param balance 声道平衡 / 声像。
 *   `0` = 居中；负 = 偏左；正 = 偏右。[BALANCE_MIN] ~ [BALANCE_MAX]。
 *   Android 侧落到 `DynamicsProcessing` 每声道的输入增益（`Channel.setInputGain`）。
 * @param virtualizerStrength 空间感（环绕）强度，`0` = 关闭。
 *   ⚠️ **当前没有任何平台实现它**（详见 [AudioEffectCapabilities] 的说明），
 *   字段先留着是为了让配置与持久化的形状稳定，避免将来成型时又要改一遍编码。
 *   能力位 `supportsVirtualizer` 恒为 false，UI 不出现对应控件。
 */
data class MixerConfig(
    val balance: Float = 0f,
    val virtualizerStrength: Float = 0f,
) {
    companion object {
        /** 平衡范围：-1 全左 / 0 居中 / +1 全右。 */
        const val BALANCE_MIN = -1f
        const val BALANCE_MAX = 1f

        /** 空间感强度范围（0 = 关，1 = 最强）。 */
        const val VIRTUALIZER_MIN = 0f
        const val VIRTUALIZER_MAX = 1f
    }
}

/**
 * 音量均衡（实时响度均衡 / 防爆音）。
 *
 * ### 为什么是「实时」而不是 ReplayGain 式逐曲扫描
 *
 * 逐曲扫描要对每一首歌做整体响度分析（额外一次全曲解码），首次播放拿不到增益、
 * 后台任务与缓存是一整套新工程。实时动态处理不需要任何预扫描，
 * 且对**流媒体**（本仓的主要场景）是唯一可行的方案 —— 你没法扫描一个还在下载的流。
 *
 * ### 与用户音量的关系
 *
 * 这一层作用在**用户音量之前**（平台层保证），因此它调的是"相对响度"，
 * 不会与音量滑杆打架。UI 上要说明这一点，否则用户会以为两个滑杆重复。
 *
 * @param enabled 总开关。
 * @param targetLevelDb 目标响度（dB）。越大整体越响。
 *   它不是"绝对音量"，而是压限器的目标电平 —— 只会把过响的部分压下来、
 *   把过轻的部分抬上去，最终靠向这个值。
 * @param preventClipping 是否启用防爆音（硬限幅）。
 *   独立于 [targetLevelDb]：即使用户只想防爆音、不要整体响度归一，也该能单独开。
 *   ⚠️ 它**只在 [enabled] 为 `true` 时才起作用**。这样"防爆音默认开"与
 *   "全默认时不挂效果链"（见 [AudioEffectConfig.anyFeatureEnabled]）两个诉求
 *   才能同时成立 —— 否则默认配置一上来就挂链，等于替用户做了"要音效"的决定。
 */
data class LevelingConfig(
    val enabled: Boolean = false,
    val targetLevelDb: Float = DEFAULT_TARGET_LEVEL_DB,
    val preventClipping: Boolean = true,
) {
    companion object {
        /** 目标响度默认值：-6 dB，接近常见的「响度均衡」档位。 */
        const val DEFAULT_TARGET_LEVEL_DB = -6f

        /** 目标响度范围（dB）：-24（很轻） ~ 0（不减）。 */
        const val TARGET_LEVEL_MIN_DB = -24f
        const val TARGET_LEVEL_MAX_DB = 0f
    }
}

/**
 * 平台对音效各能力的支持情况。
 *
 * ### 为什么需要它
 *
 * 桌面端的音频栈（composemediaplayer → nucleus.rodio → Rust JNI）**只有音量**，
 * 没有任何 EQ / 滤波 / 效果入口 —— 逐层 javap 与 DLL 导出符号核对过。
 * 若不做能力声明，桌面端就只能「设置项能点、能存盘、但什么都不发生」，
 * 那是比没有功能更糟的状态（用户以为自己调生效了）。
 *
 * 有了它，设置页可以**明示禁用**并给出原因文案，而不是静默失效。
 *
 * ### 粒度
 *
 * 按三块能力分别声明，而不是一个总开关：将来某个平台补齐了其中一块
 * （例如桌面换了播放后端、只有 PEQ 没有空间感），UI 能精确地只禁用那一块。
 */
data class AudioEffectCapabilities(
    /** 是否支持 PEQ。 */
    val supportsEqualizer: Boolean = false,
    /** 是否支持声道平衡 / 声像。 */
    val supportsBalance: Boolean = false,
    /** 是否支持空间感（环绕）效果。 */
    val supportsVirtualizer: Boolean = false,
    /** 是否支持实时响度均衡 / 防爆音。 */
    val supportsLeveling: Boolean = false,
) {
    /** 三块里任意一块可用即视为"支持音效"。 */
    val supportsAny: Boolean
        get() = supportsEqualizer || supportsBalance || supportsVirtualizer || supportsLeveling

    companion object {
        /** 全不支持（桌面端的现状）。 */
        val NONE = AudioEffectCapabilities()

        /**
         * Android 侧的实际能力：`DynamicsProcessing`（API 28+）能做的三项。
         *
         * ⚠️ **不含 `supportsVirtualizer`**：`android.media.audiofx.Virtualizer`
         * 理论上可用，但我们**没有实现** —— 它引入的相位处理在耳机上表现很不稳定
         * （不同设备差异极大），且与「音质优先」的产品定位冲突。
         * 能力位如实为 false，UI 就不会出现一个我们没做过的开关。
         */
        val ANDROID_DYNAMICS = AudioEffectCapabilities(
            supportsEqualizer = true,
            supportsBalance = true,
            supportsVirtualizer = false,
            supportsLeveling = true,
        )
    }
}
