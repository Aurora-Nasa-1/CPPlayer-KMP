package cp.player.core.playback

import cp.player.core.util.SettingsStorage

/**
 * 音效设置的持久化契约。
 *
 * ### 为什么把键放这里而不是散在两边
 *
 * 写这些键的是前端（设置页 → [cp.player.app.AppModel]），读它们并作用到音频链的是
 * 后端（[PlaybackControllerImpl] / 平台播放器）。键名、默认值、编码格式只写一份、
 * 两边共用同一组常量 —— 否则「设置页写的键」和「播放内核读的键」一旦拼错就会
 * **静默失效**：开关看着能点、能持久化，音频链却永远读到 null 而按默认值走。
 *
 * 与 [PlaybackSessionSettings] / [LyricsSourceMode.SETTINGS_KEY] 是同一套做法。
 *
 * ### 编码格式
 *
 * 每个键存一个**人类可读的字符串**（不是 JSON 二进制）：
 * - 开关：`"true"` / `"false"`；
 * - 数值：`Float.toString()`（如 `"-6.0"`）；
 * - PEQ 各段：`"增益:频率"` 两段用英文冒号分隔、段与段之间用分号分隔，
 *   如 `"0.0:60.0;3.0:230.0;..."`。
 *
 * 选字符串而不是 JSON：设置文件（`cp_player_prefs.properties`）本身是
 * properties 格式，用户排障时能直接读；且**定长 5 段**让「少了一段」这类
 * 编码错误在解析时就暴露（[decodeBands] 解析出非 5 段即整体回落到默认）。
 */
object AudioEffectSettings {

    /** 音效总开关。值：`"true"` / `"false"`。 */
    const val KEY_ENABLED = "audio_effect_enabled"

    /** PEQ 开关。值：`"true"` / `"false"`。 */
    const val KEY_EQ_ENABLED = "audio_effect_eq_enabled"

    /** PEQ 各段参数。值：`"g:f;g:f;..."`（定长 [EqualizerConfig.EQ_BAND_COUNT] 段）。 */
    const val KEY_EQ_BANDS = "audio_effect_eq_bands"

    // ⚠️ 刻意**没有**「预设 id」这个键。
    //
    // 预设（[AudioEffectPreset]）只是往 [KEY_EQ_BANDS] 里写一组曲线的**快捷方式**，
    // 选完预设之后用户还会继续手改各段。存一个「当前选中哪个预设」的键，就会出现
    // 两个事实源（预设 id vs 实际曲线），且必然漂移：用户改了第 3 段，预设 id
    // 还停在 "pop"，UI 该高亮哪个？答案只能是把 id 改成 custom —— 那这个键
    // 其实完全可由曲线反推。所以 UI 侧按「曲线是否等于某预设」现算高亮，
    // 存储里只留曲线本身。

    /** 声道平衡。值：`Float`，`0` = 居中。 */
    const val KEY_BALANCE = "audio_effect_balance"

    /** 空间感强度。值：`Float`，`0` = 关闭。 */
    const val KEY_VIRTUALIZER = "audio_effect_virtualizer"

    /** 响度均衡开关。值：`"true"` / `"false"`。 */
    const val KEY_LEVELING_ENABLED = "audio_effect_leveling_enabled"

    /** 响度均衡目标电平（dB）。值：`Float`。 */
    const val KEY_LEVELING_TARGET_DB = "audio_effect_leveling_target_db"

    /** 防爆音开关。值：`"true"` / `"false"`。 */
    const val KEY_PREVENT_CLIPPING = "audio_effect_prevent_clipping"

    // -----------------------------------------------------------------------
    // 默认值
    // -----------------------------------------------------------------------

    /**
     * 音效总开关默认值：**关**。
     *
     * 与 [PlaybackSessionSettings.DEFAULT_KEEP_LAST_PLAYBACK]（默认开）不同 ——
     * 音效会改变音频信号本身，默认开启等于**替用户做了音质决策**。
     * 「不改动原始音频」应当是默认状态，用户主动打开才付出音质的代价。
     */
    const val DEFAULT_ENABLED = false

    const val DEFAULT_EQ_ENABLED = false
    const val DEFAULT_BALANCE = 0f
    const val DEFAULT_VIRTUALIZER = 0f
    const val DEFAULT_LEVELING_ENABLED = false
    const val DEFAULT_PREVENT_CLIPPING = true

    // -----------------------------------------------------------------------
    // 读
    // -----------------------------------------------------------------------

    /**
     * 从存储读回完整音效配置。
     *
     * 任何一项缺失或非法都**单独回落到该项默认值**，而不是整体作废 ——
     * 用户手改过配置文件、或将来删掉某个键时，其余设置不该被一起清空。
     */
    fun read(storage: SettingsStorage): AudioEffectConfig = AudioEffectConfig(
        enabled = storage.boolean(KEY_ENABLED, DEFAULT_ENABLED),
        equalizer = EqualizerConfig(
            enabled = storage.boolean(KEY_EQ_ENABLED, DEFAULT_EQ_ENABLED),
            bands = storage.getString(KEY_EQ_BANDS)?.let(::decodeBands)
                ?: EqualizerConfig.DEFAULT_BANDS,
        ),
        mixer = MixerConfig(
            balance = storage.float(KEY_BALANCE, DEFAULT_BALANCE)
                .coerceIn(MixerConfig.BALANCE_MIN, MixerConfig.BALANCE_MAX),
            virtualizerStrength = storage.float(KEY_VIRTUALIZER, DEFAULT_VIRTUALIZER)
                .coerceIn(MixerConfig.VIRTUALIZER_MIN, MixerConfig.VIRTUALIZER_MAX),
        ),
        leveling = LevelingConfig(
            enabled = storage.boolean(KEY_LEVELING_ENABLED, DEFAULT_LEVELING_ENABLED),
            targetLevelDb = storage.float(
                KEY_LEVELING_TARGET_DB,
                LevelingConfig.DEFAULT_TARGET_LEVEL_DB,
            ).coerceIn(LevelingConfig.TARGET_LEVEL_MIN_DB, LevelingConfig.TARGET_LEVEL_MAX_DB),
            preventClipping = storage.boolean(KEY_PREVENT_CLIPPING, DEFAULT_PREVENT_CLIPPING),
        ),
    )

    // -----------------------------------------------------------------------
    // 写
    // -----------------------------------------------------------------------

    /**
     * 把完整配置写回存储（全量覆盖）。
     *
     * 全量写而不是增量：设置页每次改动本来就持有完整配置（不可变对象），
     * 增量写只会多一份"哪些字段变了"的判断逻辑，而这层判断错了就会漏写。
     */
    fun write(storage: SettingsStorage, config: AudioEffectConfig) {
        storage.putString(KEY_ENABLED, config.enabled.toString())
        storage.putString(KEY_EQ_ENABLED, config.equalizer.enabled.toString())
        storage.putString(KEY_EQ_BANDS, encodeBands(config.equalizer.bands))
        storage.putString(KEY_BALANCE, config.mixer.balance.toString())
        storage.putString(KEY_VIRTUALIZER, config.mixer.virtualizerStrength.toString())
        storage.putString(KEY_LEVELING_ENABLED, config.leveling.enabled.toString())
        storage.putString(KEY_LEVELING_TARGET_DB, config.leveling.targetLevelDb.toString())
        storage.putString(KEY_PREVENT_CLIPPING, config.leveling.preventClipping.toString())
    }

    // -----------------------------------------------------------------------
    // 编解码（PEQ 各段）
    // -----------------------------------------------------------------------

    /**
     * 把各段编码成 `"g:f;g:f;..."`。
     *
     * 输出**常量段数**（不足时补默认段、超出时截断）：
     * 定长让 [decodeBands] 的校验有明确的目标，也让存储里的旧值永远是自洽的。
     */
    fun encodeBands(bands: List<EqBand>): String {
        val normalized = List(EqualizerConfig.EQ_BAND_COUNT) { index ->
            bands.getOrNull(index)
                ?: EqBand(frequencyHz = EqualizerConfig.DEFAULT_FREQUENCIES_HZ[index])
        }
        return normalized.joinToString(SEGMENT_SEPARATOR) { band ->
            "${band.gainDb}$FIELD_SEPARATOR${band.frequencyHz}"
        }
    }

    /**
     * 解析 `"g:f;g:f;..."`；**任何一处不合法就整体返回 null**，
     * 由调用方回落到 [EqualizerConfig.DEFAULT_BANDS]。
     *
     * 部分解析（前两段对了、第三段坏了就只取前两段）会让频率与段位错位 ——
     * 那比「整体回落」危险得多：用户看到的是一条看似合理、实际错位的均衡曲线。
     */
    fun decodeBands(raw: String): List<EqBand>? {
        val segments = raw.split(SEGMENT_SEPARATOR)
        if (segments.size != EqualizerConfig.EQ_BAND_COUNT) return null
        val bands = segments.map { segment ->
            val fields = segment.split(FIELD_SEPARATOR)
            if (fields.size != 2) return null
            val gain = fields[0].toFloatOrNull() ?: return null
            val freq = fields[1].toFloatOrNull() ?: return null
            if (!gain.isFinite() || !freq.isFinite()) return null
            EqBand(
                gainDb = gain.coerceIn(EqBand.EQ_GAIN_MIN_DB, EqBand.EQ_GAIN_MAX_DB),
                frequencyHz = freq.coerceIn(EqBand.EQ_FREQ_MIN_HZ, EqBand.EQ_FREQ_MAX_HZ),
            )
        }
        return bands
    }

    private const val SEGMENT_SEPARATOR = ";"
    private const val FIELD_SEPARATOR = ":"

    // -----------------------------------------------------------------------
    // 存储读取小工具（缺省值语义统一在这里，别在调用点各写一遍）
    // -----------------------------------------------------------------------

    private fun SettingsStorage.boolean(key: String, default: Boolean): Boolean =
        getString(key)?.toBooleanStrictOrNull() ?: default

    private fun SettingsStorage.float(key: String, default: Float): Float =
        getString(key)?.toFloatOrNull()?.takeIf { it.isFinite() } ?: default
}

/**
 * PEQ 预设。
 *
 * ### 为什么预设的曲线放在这里（core）而不是 UI 层
 *
 * 「流行」「摇滚」这些曲线最终是**频率增益值**，属数据不属界面；
 * 而且将来若要做「导入第三方预设文件」，解析出的也是同一组 [EqBand]。
 * 放 UI 层会让这两种来路各存一份曲线。
 *
 * ### 名称不做本地化
 *
 * `"Pop"` / `"Rock"` 这类是**均衡器的行业通用标签**（中文界面上也普遍直接写
 * 「Pop」「Rock」或对应意译）——但它属于会被枚举携带的文案，按 `docs/dev/I18N.md`
 * §5.3 的铁律**不能**存成 `val label: String`（类加载时求值、读不到语言）。
 * 因此这里只存 [id]，显示名由文案层查 `AudioEffectStrings.presetName(id)`。
 */
enum class AudioEffectPreset(val id: String, val bands: List<EqBand>) {
    /** 平直：五段增益都为 0。等价于关闭 PEQ 的效果，但保留开关状态。 */
    FLAT(
        id = "flat",
        bands = listOf(0f, 0f, 0f, 0f, 0f).mapIndexed { index, gain ->
            EqBand(gainDb = gain, frequencyHz = EqualizerConfig.DEFAULT_FREQUENCIES_HZ[index])
        },
    ),

    /** 流行：抬一点中低频与高频，削一点中频（人声会稍靠后，整体更"亮"）。 */
    POP(
        id = "pop",
        bands = listOf(2f, 1f, -1f, 2f, 2.5f).mapIndexed { index, gain ->
            EqBand(gainDb = gain, frequencyHz = EqualizerConfig.DEFAULT_FREQUENCIES_HZ[index])
        },
    ),

    /** 摇滚：低频与中高频一起抬，中低频略削（鼓与吉他都跳出来）。 */
    ROCK(
        id = "rock",
        bands = listOf(4f, 1.5f, -2f, 2.5f, 3.5f).mapIndexed { index, gain ->
            EqBand(gainDb = gain, frequencyHz = EqualizerConfig.DEFAULT_FREQUENCIES_HZ[index])
        },
    ),

    /** 人声：中频单独抬高，两端略降（把唱词从混音里"拎"出来）。 */
    VOCAL(
        id = "vocal",
        bands = listOf(-2f, -1f, 4f, 3f, 1f).mapIndexed { index, gain ->
            EqBand(gainDb = gain, frequencyHz = EqualizerConfig.DEFAULT_FREQUENCIES_HZ[index])
        },
    ),

    /** 低音增强：只抬最低一段，且不削其它段（保持整体厚度）。 */
    BASS_BOOST(
        id = "bass_boost",
        bands = listOf(6f, 3f, 0f, 0f, 0f).mapIndexed { index, gain ->
            EqBand(gainDb = gain, frequencyHz = EqualizerConfig.DEFAULT_FREQUENCIES_HZ[index])
        },
    ),

    /** 高音增强：抬最高一段（"空气感"）。 */
    TREBLE_BOOST(
        id = "treble_boost",
        bands = listOf(0f, 0f, 0f, 3f, 6f).mapIndexed { index, gain ->
            EqBand(gainDb = gain, frequencyHz = EqualizerConfig.DEFAULT_FREQUENCIES_HZ[index])
        },
    ),

    /** 自定义：用户在预设基础上手改了任意一段后自动切到这里（不落盘为独立曲线）。 */
    CUSTOM(id = "custom", bands = EqualizerConfig.DEFAULT_BANDS),
    ;

    /** 这个预设是否有可对照的用户手改基准（[CUSTOM] 没有，它只是个标记）。 */
    val isPreset: Boolean get() = this != CUSTOM

    companion object {
        /** 默认预设：平直。 */
        val DEFAULT = FLAT

        /** 按存储里的 id 解析；无法识别时回落到 [DEFAULT]。 */
        fun fromId(id: String?): AudioEffectPreset =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
