package cp.player.core.playback

import cp.player.core.util.SettingsStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 音效持久化的回归守卫。
 *
 * ### 为什么需要它
 *
 * 音效的键与编解码是**前后端共用**的契约（设置页写、平台播放器读），
 * 拼错键名或改坏编码都不会有编译错误，只会表现为「设置页能调、重开就没了」
 * 或「换首歌音效就恢复默认」—— 即本仓反复强调的那类**静默失效**。
 *
 * 这里钉住四件事：
 * 1. **往返一致**：任意合法配置写进去再读出来必须相同；
 * 2. **缺键回落**：老配置文件没有这些键时，逐项回落到默认值（不是整体清空）；
 * 3. **坏值不污染**：单段编码损坏时**整体**回落默认曲线，绝不部分解析（那会让
 *    频点与段位错位，产出一条看似合理、实际错位的曲线）；
 * 4. **定长段数**：编码永远是 5 段，少写/多写都能被解码端发现。
 */
class AudioEffectSettingsTest {

    private class FakeSettings : SettingsStorage {
        private val map = mutableMapOf<String, String>()
        override fun getString(key: String, default: String?): String? = map[key] ?: default
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }

        override fun remove(key: String) {
            map.remove(key)
        }

        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun clear() = map.clear()
    }

    // -----------------------------------------------------------------------
    // 往返
    // -----------------------------------------------------------------------

    @Test
    fun `config round-trips through storage`() {
        val storage = FakeSettings()
        val config = AudioEffectConfig(
            enabled = true,
            equalizer = EqualizerConfig(
                enabled = true,
                bands = listOf(
                    EqBand(gainDb = 6f, frequencyHz = 80f),
                    EqBand(gainDb = -3.5f, frequencyHz = 250f),
                    EqBand(gainDb = 0f, frequencyHz = 1000f),
                    EqBand(gainDb = 2.25f, frequencyHz = 4000f),
                    EqBand(gainDb = -8f, frequencyHz = 16000f),
                ),
            ),
            mixer = MixerConfig(balance = -0.4f),
            leveling = LevelingConfig(enabled = true, targetLevelDb = -9f, preventClipping = false),
        )

        AudioEffectSettings.write(storage, config)
        val read = AudioEffectSettings.read(storage)

        assertEquals(config, read, "写入后读出的配置必须与写入的完全相同（否则就是键或编码错了）")
    }

    @Test
    fun `defaults survive a round-trip`() {
        val storage = FakeSettings()
        AudioEffectSettings.write(storage, AudioEffectConfig.OFF)
        assertEquals(AudioEffectConfig.OFF, AudioEffectSettings.read(storage))
    }

    // -----------------------------------------------------------------------
    // 缺键：逐项回落，而不是整体作废
    // -----------------------------------------------------------------------

    @Test
    fun `missing keys fall back to defaults per item`() {
        val storage = FakeSettings()
        // 只写「总开关」，其余键一律不存在 —— 模拟老配置文件。
        storage.putString(AudioEffectSettings.KEY_ENABLED, "true")

        val read = AudioEffectSettings.read(storage)

        assertTrue(read.enabled, "显式写过的那一项要读得出来")
        assertEquals(
            EqualizerConfig.DEFAULT_BANDS,
            read.equalizer.bands,
            "缺 PEQ 曲线键时必须回落到默认曲线，而不是空列表",
        )
        assertEquals(EqualizerConfig.DEFAULT_FREQUENCIES_HZ, read.equalizer.bands.map { it.frequencyHz })
        assertEquals(0f, read.mixer.balance, "缺平衡键时回落居中")
        assertEquals(
            LevelingConfig.DEFAULT_TARGET_LEVEL_DB,
            read.leveling.targetLevelDb,
            "缺目标响度键时回落默认电平",
        )
        assertTrue(read.leveling.preventClipping, "防爆音默认开")
    }

    @Test
    fun `illegal values fall back to defaults instead of throwing`() {
        val storage = FakeSettings()
        storage.putString(AudioEffectSettings.KEY_ENABLED, "maybe")
        storage.putString(AudioEffectSettings.KEY_BALANCE, "left")
        storage.putString(AudioEffectSettings.KEY_LEVELING_TARGET_DB, "NaN")
        storage.putString(AudioEffectSettings.KEY_PREVENT_CLIPPING, "yes")

        val read = AudioEffectSettings.read(storage)

        // 严格布尔解析：`maybe` / `yes` 都不是合法布尔串（与本地服务器配置同一套要求）。
        assertEquals(false, read.enabled, "非法布尔串必须回落默认，不能被当成 true")
        assertEquals(0f, read.mixer.balance, "非法浮点必须回落默认")
        assertEquals(
            LevelingConfig.DEFAULT_TARGET_LEVEL_DB,
            read.leveling.targetLevelDb,
            "NaN 必须回落默认（它不是合法电平）",
        )
        assertTrue(read.leveling.preventClipping, "非法布尔串必须回落默认值（此处默认是 true）")
    }

    @Test
    fun `out of range values are clamped on read`() {
        val storage = FakeSettings()
        // 手改配置文件写出越界值：必须钳到合法范围，而不是原样透传给音频链。
        storage.putString(AudioEffectSettings.KEY_BALANCE, "9.5")
        storage.putString(AudioEffectSettings.KEY_LEVELING_TARGET_DB, "50")

        val read = AudioEffectSettings.read(storage)

        assertEquals(MixerConfig.BALANCE_MAX, read.mixer.balance, "越界平衡值必须钳到上限")
        assertEquals(
            LevelingConfig.TARGET_LEVEL_MAX_DB,
            read.leveling.targetLevelDb,
            "越界目标响度必须钳到上限",
        )
    }

    // -----------------------------------------------------------------------
    // PEQ 编解码
    // -----------------------------------------------------------------------

    @Test
    fun `encoding always produces a fixed band count`() {
        // 短了要补齐
        val short = AudioEffectSettings.encodeBands(listOf(EqBand(gainDb = 1f, frequencyHz = 100f)))
        assertEquals(
            EqualizerConfig.EQ_BAND_COUNT,
            short.split(";").size,
            "编码必须补齐到固定段数（段数可变会让解码端的校验失去目标）",
        )
        assertNotNull(AudioEffectSettings.decodeBands(short), "补齐后的编码必须可解码")

        // 长了要截断
        val long = AudioEffectSettings.encodeBands(
            List(EqualizerConfig.EQ_BAND_COUNT + 3) { EqBand(gainDb = 2f, frequencyHz = 500f) },
        )
        assertEquals(
            EqualizerConfig.EQ_BAND_COUNT,
            long.split(";").size,
            "编码必须截断到固定段数",
        )
    }

    @Test
    fun `a corrupted band field rejects the whole curve`() {
        // 中间一段的增益写成非数字：**整体**必须回落，不能只取前两段
        // —— 部分解析会让频点与段位错位，用户看到的是一条错位的曲线。
        val broken = "0.0:60.0;3.0:oops;0.0:910.0;0.0:3600.0;0.0:14000.0"
        assertNull(
            AudioEffectSettings.decodeBands(broken),
            "任一段损坏都必须整体拒绝（部分解析会产出错位的曲线）",
        )
    }

    @Test
    fun `a band count mismatch rejects the curve`() {
        assertNull(AudioEffectSettings.decodeBands("0.0:60.0;3.0:230.0"), "段数不足时必须拒绝")
        assertNull(
            AudioEffectSettings.decodeBands("0.0:60.0;1.0:100.0;2.0:200.0;3.0:300.0;4.0:400.0;5.0:500.0"),
            "段数过多时必须拒绝",
        )
        assertNull(AudioEffectSettings.decodeBands(""), "空串必须拒绝")
    }

    @Test
    fun `decoded band values are clamped`() {
        // 手改文件写出越界增益 / 频率：解码时要钳住。
        val bands = AudioEffectSettings.decodeBands(
            "99.0:5.0;-99.0:99999.0;0.0:910.0;0.0:3600.0;0.0:14000.0",
        )
        assertNotNull(bands)
        assertEquals(EqBand.EQ_GAIN_MAX_DB, bands[0].gainDb)
        assertEquals(EqBand.EQ_FREQ_MIN_HZ, bands[0].frequencyHz)
        assertEquals(EqBand.EQ_GAIN_MIN_DB, bands[1].gainDb)
        assertEquals(EqBand.EQ_FREQ_MAX_HZ, bands[1].frequencyHz)
    }

    // -----------------------------------------------------------------------
    // 预设与派生值
    // -----------------------------------------------------------------------

    @Test
    fun `every preset keeps the fixed band count and legal range`() {
        AudioEffectPreset.entries.filter { it.isPreset }.forEach { preset ->
            assertEquals(
                EqualizerConfig.EQ_BAND_COUNT,
                preset.bands.size,
                "预设 ${preset.id} 的段数必须是 ${EqualizerConfig.EQ_BAND_COUNT} —— " +
                    "否则 UI 的滑杆会与曲线对不上",
            )
            preset.bands.forEach { band ->
                assertTrue(
                    band.gainDb in EqBand.EQ_GAIN_MIN_DB..EqBand.EQ_GAIN_MAX_DB,
                    "预设 ${preset.id} 有越界增益 ${band.gainDb} —— 会被解码端钳住，" +
                        "于是「选了这个预设」与「实际生效的曲线」不一致",
                )
                assertTrue(
                    band.frequencyHz in EqBand.EQ_FREQ_MIN_HZ..EqBand.EQ_FREQ_MAX_HZ,
                    "预设 ${preset.id} 有越界频率 ${band.frequencyHz}",
                )
            }
        }
    }

    @Test
    fun `unknown preset id falls back to the default`() {
        assertEquals(AudioEffectPreset.DEFAULT, AudioEffectPreset.fromId("不存在的预设"))
        assertEquals(AudioEffectPreset.DEFAULT, AudioEffectPreset.fromId(null))
        assertEquals(AudioEffectPreset.ROCK, AudioEffectPreset.fromId("rock"))
    }

    @Test
    fun `preset ids are unique`() {
        val ids = AudioEffectPreset.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "预设 id 必须唯一：$ids")
    }

    @Test
    fun `anyFeatureEnabled tracks the sub switches`() {
        assertTrue(
            !AudioEffectConfig.OFF.anyFeatureEnabled,
            "全默认（无开关、平衡居中）时不应挂效果链",
        )
        assertTrue(
            !AudioEffectConfig(leveling = LevelingConfig(preventClipping = true)).anyFeatureEnabled,
            "只把 preventClipping 留默认、响应度均衡总开关没开时，仍属直通",
        )
        assertTrue(
            AudioEffectConfig(equalizer = EqualizerConfig(enabled = true)).anyFeatureEnabled,
            "PEQ 开着就要挂链",
        )
        assertTrue(
            AudioEffectConfig(mixer = MixerConfig(balance = 0.5f)).anyFeatureEnabled,
            "平衡非 0 就要挂链",
        )
        assertTrue(
            AudioEffectConfig(leveling = LevelingConfig(enabled = true)).anyFeatureEnabled,
            "响应度均衡开着就要挂链",
        )
        assertTrue(
            AudioEffectConfig(
                leveling = LevelingConfig(enabled = true, preventClipping = true),
            ).anyFeatureEnabled,
            "响应度均衡 + 防爆音一起开也要挂链",
        )
        assertTrue(
            !AudioEffectConfig(enabled = true).anyFeatureEnabled,
            "本值只看子开关，不看总开关 —— 否则总开关开着就永远为 true，"
                + "设置页 commit 里的 copy(enabled = anyFeatureEnabled) 会退化成永真",
        )
    }
}
