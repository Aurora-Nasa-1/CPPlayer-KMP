package cp.player.app.ui.component

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.ui.preparation.MeasuredLyricsLine
import com.mocharealm.accompanist.lyrics.ui.profile.CjkProfile
import com.mocharealm.accompanist.lyrics.ui.profile.DefaultLyricsProfile
import com.mocharealm.accompanist.lyrics.ui.profile.ProfileGroupEffects
import com.mocharealm.accompanist.lyrics.ui.profile.ProfileTextUnit
import cp.player.app.ui.theme.AppFontFamilies
import cp.player.app.ui.theme.TextScript
import cp.player.app.ui.theme.appScript
import cp.player.app.ui.theme.buildAppStyledText

/**
 * 歌词的**逐 run 字体路由**，交给歌词库当 `renderProfiles` 用。
 *
 * ## 为什么需要它
 *
 * 歌词库对外只收一个 `TextStyle`（`KaraokeLyricsView(normalLineTextStyle = …)`），
 * 所以改造前 `LyricContent` 只能**整行换家族**（`TextStyle.copy(fontFamily = Noto Sans JP)`）。
 * 后果是：一句日语歌词里的 `Tokyo` / `03:45` / `!` 也全部落到 Noto Sans JP 的字形 ——
 * 而 `buildAppStyledText` 里 `TextScript.LATIN` 的语义是「**不套 span**，沿用样式的默认字体」，
 * 出发点是 Google Sans Flex。整行换家族等于把那个默认值改成了 Noto。
 *
 * 但库的 `LyricsProfile.prepare(line, group, measurer, style)` 允许我们自己造
 * `TextLayoutResult`，而 `DefaultLyricsProfile.draw` 是直接 `drawText(unit.layout)` 的 ——
 * 只要 layout 里装的是带 `SpanStyle` 的 `AnnotatedString`，逐字符的家族就画得出来。
 * 于是这里把「整行换家族」换成「同一份 layout 里带 span」，其余全交给库。
 *
 * ## 与内置 `CjkProfile` 的关系
 *
 * 分组（汉字逐 grapheme、假名词级分词）、折行、发光/缩放这些策略**一律委派给 [CjkProfile]**，
 * 所以行为与内置 CJK 路径逐字节一致 —— 唯一差别是那份 layout 用带 span 的文本量出来。
 *
 * ⚠️ 别自己重写 `groups` / `wrap`：内置那套 ICU 词界 + 均衡折行是库最复杂的部分，
 * 抄一份就等于以后每次升版本都要重新对一遍。
 *
 * ## 生效范围
 *
 * 只接**含汉字或假名**的音节（[matches]）。纯拉丁行继续走内置 `LatinProfile`，
 * 此时样式家族就是 Google Sans Flex，不做任何 span —— 与改造前完全相同。
 */
class ScriptFontLyricsProfile(
    private val families: AppFontFamilies,
) : DefaultLyricsProfile() {

    /**
     * 只有含 CJK 字符的音节才由本 profile 接管。
     *
     * 判据用 [appScript]（与 `buildAppStyledText` 同一套分类），不自己写码位区间：
     * 汉字 / 假名 / 全角标点都算，拉丁、数字、ASCII 标点都不算。
     */
    override fun matches(syllable: KaraokeSyllable): Boolean =
        syllable.content.any {
            it.appScript() == TextScript.JAPANESE || it.appScript() == TextScript.CJK
        }

    /** 逐 grapheme 分组（假名按 ICU 词界），见内置 [CjkProfile.groups]。 */
    override fun groups(syllables: List<KaraokeSyllable>): List<List<KaraokeSyllable>> =
        CjkProfile.groups(syllables)

    /** 长行发光策略与内置一致。 */
    override fun effects(
        units: List<ProfileTextUnit>,
        accompaniment: Boolean,
    ): ProfileGroupEffects = CjkProfile.effects(units, accompaniment)

    /** 均衡折行与内置一致（内部会用到 `style.localeList` 的 `ja` 标签断行）。 */
    override fun wrap(
        units: List<ProfileTextUnit>,
        measurer: TextMeasurer,
        maxWidth: Float,
    ): List<List<ProfileTextUnit>> = CjkProfile.wrap(units, measurer, maxWidth)

    /**
     * 用**带字体 span**的 layout 替换库自己量的那份，再交给 [CjkProfile] 做后续步骤。
     *
     * 库在这个重载里只用到 `line.layout`（用来判断「这份 layout 是不是正好是这一组的文本」），
     * 所以换掉 layout 就等于换掉整组的字形来源：
     * 拉丁 / 数字 / ASCII 标点不套 span（沿用样式的默认家族，即 Google Sans Flex），
     * 假名 → Noto Sans JP，汉字 / 全角标点 → 本段语言的 CJK 字体（见 [buildAppStyledText]）。
     *
     * ⚠️ span **不会**改变 `layoutInput.text.text`，所以库里那句文本一致性检查照样成立。
     *
     * ⚠️ 量不出任何 span（例如 Android 上日文字库还没下载）时**原样委派**：
     * 此时不该凭空多量一次，更不该让汉字掉进 MiSans 的写法。
     */
    override fun prepare(
        line: MeasuredLyricsLine,
        group: List<KaraokeSyllable>,
        measurer: TextMeasurer,
        style: TextStyle,
    ): List<ProfileTextUnit> {
        val text = group.joinToString("") { it.content }
        if (text.isEmpty()) return CjkProfile.prepare(line, group, measurer, style)
        val styled = buildAppStyledText(text, families)
        if (styled.spanStyles.isEmpty()) return CjkProfile.prepare(line, group, measurer, style)

        // softWrap = false：与库自己量整组的方式一致（整组先当成一行量完，再按宽度折行）。
        val layout = measurer.measure(styled, style, softWrap = false)
        return CjkProfile.prepare(MeasuredLyricsLine(layout, group), group, measurer, style)
    }
}
