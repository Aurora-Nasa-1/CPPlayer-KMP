package cp.player.app.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.SettingsDropdownItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsNoteEmphasis
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSliderItem
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.util.popOrNotify
import cp.player.core.playback.AudioEffectCapabilities
import cp.player.core.playback.AudioEffectConfig
import cp.player.core.playback.AudioEffectPreset
import cp.player.core.playback.EqBand
import cp.player.core.playback.EqualizerConfig
import cp.player.core.playback.LevelingConfig
import cp.player.core.playback.MixerConfig
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 音效设置页（PEQ / 混音 / 音量均衡）。
 *
 * ### 三层结构
 * 1. **均衡器** —— 5 段，每段一个「频率 + 增益」滑杆对，外加预设快捷；
 * 2. **混音** —— 声道平衡；
 * 3. **音量均衡** —— 响度均衡开关 + 目标响度 + 防爆音。
 *
 * ### ⚠️ 桌面端明示不支持，而不是隐藏入口
 *
 * 桌面音频引擎（rodio JNI）没有任何效果接口（见 `PlatformPlayer` 的 KDoc），
 * 所以这一页在桌面端**全部控件禁用** + 顶部一条说明。
 *
 * 为什么不是「桌面直接不显示这个入口」：
 * - 两平台功能不一致会让用户以为桌面版是残缺的，而原因并不明显；
 * - 一条「本平台音频引擎没有效果接口」的说明能解答「为什么」，比消失的入口有用。
 *
 * 这与 `SettingsRegistry` 里 `desktopOnly`（快捷键页）的处理**刻意不同**：
 * 快捷键在桌面有、安卓没有是因为「安卓压根没有物理键盘语义」——
 * 那里隐藏是对的（用户不会期待手机上按 F5）。而音效是**用户的合理期待**，
 * 只是当前平台做不到 —— 这时必须解释，而不是假装没这回事。
 *
 * ### 提交节奏
 * 所有滑杆都遵循 `SettingsSliderItem` 的「拖动只改显示、松手才提交」
 * （桌面 `SettingsStorage` 每次写入是全量文件回写，边拖边写会打爆 IO；
 * 且 Android 侧每次提交都是一串 Binder 调用）。因此本页维护一份**本地草稿**
 * （`remember` 出来的 `AudioEffectConfig`），松手时才 [AppModel.setAudioEffect]。
 */
class AudioEffectSettingsScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val s = cpStrings()

        // 能力位只读一次：它触达平台播放器（Android 会申请音频会话），
        // 不该在每次重组时读。`remember` 足够 —— 能力是设备常量，不会中途变。
        val capabilities = remember { AppModel.audioEffectCapabilities() }
        val supported = capabilities.supportsAny

        // 持久化的配置作为初值；页面内所有改动走本地草稿，松手才提交。
        val persisted by AppModel.audioEffectFlow.collectAsState()
        var draft by remember { mutableStateOf(persisted) }

        /**
         * 提交：**先补齐派生字段**，再落盘 + 下发引擎。全部交互都走这里，
         * 保证整页只有一个提交点（也就只有一处可能忘同步）。
         *
         * ### `enabled` 是**派生值**，没有对应的开关
         * [AudioEffectConfig.enabled] 语义是「音频链是否要挂效果」。它不是给用户按的
         * 总开关，而是由三个子功能的开关算出来的：任一个开着就要挂链，全关就拆链。
         *
         * 为什么不做一个「音效总开关」的 UI：
         * - 用户想「关掉音效」时的真实意图是「把均衡/混音/响度都关掉」，
         *   而这三件事各自有开关，做总开关等于同一状态有两个入口、必然不同步；
         * - 派生就永远不会出现「总开关开着但三个子项全关」（白挂一条效果链）。
         */
        fun commit(next: AudioEffectConfig) {
            val normalized = next.copy(enabled = next.anyFeatureEnabled)
            draft = normalized
            AppModel.setAudioEffect(normalized)
        }

        val body: @Composable (Modifier) -> Unit = { pageModifier ->
            SettingsPage(pageModifier) {
                if (!supported) {
                    // ERROR 而不是 WARNING：这不是「可能有问题」，而是「这一页现在
                    // 什么都不做」——用最显眼的容器，避免用户以为禁用是临时状态。
                    SettingsNote(
                        s.audioEffect.unsupportedNote,
                        emphasis = SettingsNoteEmphasis.ERROR,
                    )
                }

                // ---------------- 均衡器 ----------------
                if (capabilities.supportsEqualizer) {
                    val eq = draft.equalizer
                    SettingsSection(s.audioEffect.sectionEqualizer) {
                        SettingsSwitchItem(
                            title = s.audioEffect.equalizerEnabled,
                            subtitle = s.audioEffect.equalizerEnabledNote,
                            checked = eq.enabled,
                            onCheckedChange = { on ->
                                commit(draft.copy(equalizer = eq.copy(enabled = on)))
                            },
                            index = 0,
                            total = 1 + 1 + EqualizerConfig.EQ_BAND_COUNT * 2,
                        )

                        // 预设：≥5 项，走下拉（`SettingsSegmentedItem` 只适合 2–4 项）。
                        val presetOptions = PRESET_ORDER.map { presetName(s, it) }
                        val presetIndex =
                            PRESET_ORDER.indexOf(currentPreset(eq.bands)).coerceAtLeast(0)
                        SettingsDropdownItem(
                            title = s.audioEffect.equalizerPreset,
                            subtitle = s.audioEffect.equalizerPresetNote,
                            options = presetOptions,
                            selectedIndex = presetIndex,
                            onSelect = { index ->
                                val preset = PRESET_ORDER.getOrNull(index) ?: return@SettingsDropdownItem
                                // 预设只改曲线，不动开关：用户可能先选预设再打开，
                                // 也可能开着的时候换预设。强改开关会打断前一种意图。
                                commit(
                                    draft.copy(
                                        equalizer = eq.copy(bands = preset.bands),
                                    ),
                                )
                            },
                            index = 1,
                            total = 2 + EqualizerConfig.EQ_BAND_COUNT * 2,
                        )

                        // 每段两个滑杆：频率 + 增益。
                        // 频率也做成滑杆而不是固定值 —— 这是本功能称「参数均衡」而非
                        // 「图形均衡」的关键（Android 的 setCutoffFrequency 支持调整）。
                        eq.bands.forEachIndexed { bandIndex, band ->
                            val rowBase = 2 + bandIndex * 2
                            SettingsSliderItem(
                                title = s.audioEffect.bandFrequency,
                                subtitle = s.audioEffect.bandSubtitle(
                                    index = bandIndex,
                                    hz = formatHz(band.frequencyHz),
                                    gainDb = formatGain(band.gainDb),
                                ),
                                value = band.frequencyHz,
                                onValueChange = { hz ->
                                    // 拖动中只更新草稿（不落盘、不下发）。
                                    draft = draft.copy(
                                        equalizer = eq.copy(
                                            bands = eq.bands.replaceAt(
                                                bandIndex,
                                                band.copy(frequencyHz = hz),
                                            ),
                                        ),
                                    )
                                },
                                onValueChangeFinished = { commit(draft) },
                                valueRange = EqBand.EQ_FREQ_MIN_HZ..EqBand.EQ_FREQ_MAX_HZ,
                                steps = 0,
                                valueLabel = formatHz(band.frequencyHz),
                                index = rowBase,
                                total = 2 + EqualizerConfig.EQ_BAND_COUNT * 2,
                                // 曲线**始终可调**，不跟 EQ 开关联动：
                                // 「先调好曲线再打开」是最自然的使用顺序，
                                // 禁用滑杆会逼用户先开 EQ（那一刻听到未经调整的曲线，很怪）。
                                enabled = true,
                            )
                            SettingsSliderItem(
                                title = s.audioEffect.bandGain,
                                value = band.gainDb,
                                onValueChange = { db ->
                                    draft = draft.copy(
                                        equalizer = eq.copy(
                                            bands = eq.bands.replaceAt(
                                                bandIndex,
                                                band.copy(gainDb = db),
                                            ),
                                        ),
                                    )
                                },
                                onValueChangeFinished = { commit(draft) },
                                valueRange = EqBand.EQ_GAIN_MIN_DB..EqBand.EQ_GAIN_MAX_DB,
                                steps = 0,
                                valueLabel = s.audioEffect.gainLabel(formatGain(band.gainDb)),
                                index = rowBase + 1,
                                total = 2 + EqualizerConfig.EQ_BAND_COUNT * 2,
                                enabled = true,
                            )
                        }
                    }
                }

                // ---------------- 混音 ----------------
                if (capabilities.supportsBalance) {
                    val mixer = draft.mixer
                    SettingsSection(s.audioEffect.sectionMixer) {
                        SettingsSliderItem(
                            title = s.audioEffect.balance,
                            subtitle = s.audioEffect.balanceNote,
                            value = mixer.balance,
                            onValueChange = { value ->
                                draft = draft.copy(mixer = mixer.copy(balance = value))
                            },
                            onValueChangeFinished = { commit(draft) },
                            valueRange = MixerConfig.BALANCE_MIN..MixerConfig.BALANCE_MAX,
                            steps = 0,
                            valueLabel = balanceLabel(s, mixer.balance),
                            index = 0,
                            total = 1,
                            // 平衡值与「音效总开关」无关：`enabled` 由三个子项派生，
                            // 平衡非 0 本身就会让它变成 true。这里只需保证
                            // 均衡器没开、响度没开、平衡也是 0 时这一行仍可拖
                            // —— 所以恒 enabled（拖动本身会置 `enabled = true`）。
                            enabled = true,
                        )
                    }
                }

                // ---------------- 音量均衡 ----------------
                if (capabilities.supportsLeveling) {
                    val leveling = draft.leveling
                    SettingsSection(s.audioEffect.sectionLeveling) {
                        SettingsSwitchItem(
                            title = s.audioEffect.levelingEnabled,
                            subtitle = s.audioEffect.levelingEnabledNote,
                            checked = leveling.enabled,
                            onCheckedChange = { on ->
                                commit(draft.copy(leveling = leveling.copy(enabled = on)))
                            },
                            index = 0,
                            total = 3,
                        )
                        SettingsSliderItem(
                            title = s.audioEffect.levelingTarget,
                            subtitle = s.audioEffect.levelingTargetNote,
                            value = leveling.targetLevelDb,
                            onValueChange = { db ->
                                draft = draft.copy(
                                    leveling = leveling.copy(targetLevelDb = db),
                                )
                            },
                            onValueChangeFinished = { commit(draft) },
                            valueRange =
                                LevelingConfig.TARGET_LEVEL_MIN_DB..LevelingConfig.TARGET_LEVEL_MAX_DB,
                            steps = 0,
                            valueLabel = s.audioEffect.gainLabel(
                                formatGain(leveling.targetLevelDb),
                            ),
                            index = 1,
                            total = 3,
                            // 目标响度只在响度均衡开启时有意义；关着的时候
                            // 防爆音仍独立生效，但那个阈值是内部常量，用户调不了。
                            enabled = leveling.enabled,
                        )
                        SettingsSwitchItem(
                            title = s.audioEffect.preventClipping,
                            subtitle = s.audioEffect.preventClippingNote,
                            checked = leveling.preventClipping,
                            onCheckedChange = { on ->
                                commit(draft.copy(leveling = leveling.copy(preventClipping = on)))
                            },
                            index = 2,
                            total = 3,
                        )
                    }
                }

                // 页尾说明：讲清「响度均衡作用于音量之前」以及「均衡/混音会改变信号」。
                if (supported) SettingsNote(s.audioEffect.levelingNote)
            }
        }

        CpRouteScaffold(
            title = s.audioEffect.screenTitle,
            onBack = { navigator.popOrNotify() },
        ) { pageModifier -> body(pageModifier) }
    }
}

// ============================================================ 辅助

/**
 * 预设下拉的**固定顺序**。
 *
 * 单独列一份而不是直接用 `AudioEffectPreset.entries`：枚举的声明顺序是
 * 代码内部的事，而这里要保证「平直」永远在第一位（它是默认值，
 * 也是用户找「关掉一切」时第一个会看的地方）。两者分开，改枚举顺序就不会
 * 静默改变 UI 排序。
 *
 * ⚠️ **不含 [AudioEffectPreset.CUSTOM]**：它不是可选预设，
 * 而是「用户手改过」的标记 —— 出现在下拉里会让用户误以为点它能得到什么曲线。
 */
private val PRESET_ORDER = listOf(
    AudioEffectPreset.FLAT,
    AudioEffectPreset.POP,
    AudioEffectPreset.ROCK,
    AudioEffectPreset.VOCAL,
    AudioEffectPreset.BASS_BOOST,
    AudioEffectPreset.TREBLE_BOOST,
)

/** 预设显示名。枚举只存 id，显示名在这里查文案层（理由见 `AudioEffectPreset` 的 KDoc）。 */
private fun presetName(s: cp.player.app.i18n.CpStrings, preset: AudioEffectPreset): String =
    when (preset) {
        AudioEffectPreset.FLAT -> s.audioEffect.presetFlat
        AudioEffectPreset.POP -> s.audioEffect.presetPop
        AudioEffectPreset.ROCK -> s.audioEffect.presetRock
        AudioEffectPreset.VOCAL -> s.audioEffect.presetVocal
        AudioEffectPreset.BASS_BOOST -> s.audioEffect.presetBassBoost
        AudioEffectPreset.TREBLE_BOOST -> s.audioEffect.presetTrebleBoost
        AudioEffectPreset.CUSTOM -> s.audioEffect.presetCustom
    }

/**
 * 由**当前曲线反推**选中的预设（而不是存一个「当前预设 id」的键）。
 *
 * 这样「用户手改某一段」会自动落到 [AudioEffectPreset.CUSTOM]，
 * 不会出现「下拉显示 流行、实际曲线是别的」这种漂移。
 * 曲线比较带一个小容差：滑杆返回的 Float 与预设常量之间可能有末位误差，
 * 严格相等会让「选完预设、没动过」也被显示成自定义。
 */
private fun currentPreset(bands: List<EqBand>): AudioEffectPreset =
    PRESET_ORDER.firstOrNull { preset ->
        preset.bands.size == bands.size &&
            preset.bands.zip(bands).all { (expected, actual) ->
                abs(expected.gainDb - actual.gainDb) < GAIN_EPSILON &&
                    abs(expected.frequencyHz - actual.frequencyHz) < FREQ_EPSILON_HZ
            }
    } ?: AudioEffectPreset.CUSTOM

/** 增益比较容差（dB）。远小于滑杆的可见精度（0.1dB 量级），不会误判。 */
private const val GAIN_EPSILON = 0.05f

/** 频率比较容差（Hz）。 */
private const val FREQ_EPSILON_HZ = 1f

/** 不可变替换：`List` 没有 `set`，手写一个避免在 UI 里到处写 `toMutableList()`。 */
private fun List<EqBand>.replaceAt(index: Int, band: EqBand): List<EqBand> =
    mapIndexed { i, existing -> if (i == index) band else existing }

/**
 * 频率的数字部分（不含单位）：1kHz 以上用 `k` 缩写，避免「14000 Hz」在滑杆读数里过长。
 *
 * ⚠️ **只返回数字**，单位由 [formatHz] 补。分成两个函数是因为有两个消费方：
 * - 滑杆读数直接走 [formatHz]（带单位）；
 * - 副标题里的频率**嵌在一句更长的话里**（`60 Hz · +2.0 dB`），也走 [formatHz]。
 * 两者都得用带单位的版本，但数字部分要能单独取出来给别处复用，
 * 所以拆成两层，避免出现「60 Hz Hz」这种双单位。
 */
private fun formatHzNumber(hz: Float): String =
    if (hz >= 1000f) {
        val k = hz / 1000f
        // 保留一位小数，但整数时不留（`3 kHz` 而不是 `3.0 kHz`）。
        // 注意：这里返回的字符串已经带 `k` 前缀的缩写数字，单位仍由调用方补。
        if (abs(k - k.roundToInt()) < 0.05f) "${k.roundToInt()}" else round1(k)
    } else {
        "${hz.roundToInt()}"
    }

/**
 * 频率的完整读数：数字 + 单位。
 *
 * 1kHz 以下用 `Hz`、以上用 `kHz` —— 单位**按数量级选**，不写死。
 * 不做本地化：`Hz`/`kHz` 是国际单位符号，中英文界面写法一致
 * （翻译它反而不专业），故直接用 ASCII。
 */
private fun formatHz(hz: Float): String =
    if (hz >= 1000f) "${formatHzNumber(hz)} kHz" else "${formatHzNumber(hz)} Hz"

/** 增益格式化：带正负号（`+3.0 dB` / `-3.0 dB` / `0.0 dB`），单位由文案层补。 */
private fun formatGain(db: Float): String {
    // 先四舍五入到一位小数再判正负：`0.02f` 的直接比较会判成"正"，
    // 但显示出来是 `0.0`，于是出现「+0.0 dB」这种自相矛盾的读数。
    val tenths = (db * 10f).roundToInt()
    val sign = if (tenths > 0) "+" else ""
    return "$sign${round1(db)}"
}

/** 保留一位小数（不用 `String.format`：commonMain 没有它）。 */
private fun round1(value: Float): String {
    val scaled = (value * 10f).roundToInt()
    val whole = scaled / 10
    val frac = abs(scaled % 10)
    return "$whole.$frac"
}

/** 平衡读数：居中 / 偏左 N% / 偏右 N%。 */
private fun balanceLabel(s: cp.player.app.i18n.CpStrings, balance: Float): String {
    val percent = (abs(balance) * 100f).roundToInt()
    return when {
        percent < 1 -> s.audioEffect.balanceCenter
        balance < 0f -> "${s.audioEffect.balanceLeft} $percent%"
        else -> "${s.audioEffect.balanceRight} $percent%"
    }
}
