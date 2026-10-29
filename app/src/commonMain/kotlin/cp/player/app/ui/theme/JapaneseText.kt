package cp.player.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.intl.LocaleList

/**
 * 日语的排版语言标签。
 *
 * 这就是 HTML 里 `lang="ja"` 的等价物：`TextStyle.localeList` 会被带到排版引擎
 * （Android 的 `StaticLayout` / 桌面 Skia 的段落样式）里，影响**断行与标点规则**
 * （禁则处理：`、。」` 不出现在行首，`（「` 不出现在行尾）。
 *
 * ⚠️ 它**不负责选字体**。字体由 [buildAppStyledText] 按脚本路由
 * （假名 → Noto Sans JP，汉字 → 本段语言的 CJK 字体）；两件事必须一起做：
 * 只给 locale 的话，汉字仍可能用中文字体渲染（同码位不同写法）。
 */
private val JapaneseLocaleList = LocaleList("ja-JP, ja")

/** 日语 OpenType 特性：`palt`（假名比例排版，非 1:1 全角网格填充）。 */
private const val JapaneseFeatures = "palt"

/**
 * 这段文本是不是日语。
 *
 * 判据：**含平假名或片假名**（含小假名、长音符与假名语音扩展，见 [isKana]）。
 * - 中文不用假名（零星借用整段仍按中文排版，所以只认假名区段）；
 * - 韩文 / 拉丁文本自然为 false。
 *
 * ⚠️ 这是**段落级**判定：整段任一处有假名就按日语处理。别改成逐句 / 逐字判断 ——
 * 那样同一行里的汉字会一会儿日式一会儿中式。
 */
fun String.containsKana(): Boolean = any { it.isKana() }

/**
 * 按文本语言补上「语言标签 + 假名比例排版特性」。
 *
 * 只对**含假名**的文本生效；其余原样返回（返回的是同一个实例，`remember` 的 key 稳定）。
 *
 * ⚠️ 这里**不换字体**（改造前会换）。字体是逐 run 的事情，见 [buildAppStyledText]：
 * 一行里「Tokyo 東京」的拉丁与汉字本来就该用不同字体，而 `TextStyle` 只能装一个家族。
 */
fun TextStyle.forTextLanguage(text: String): TextStyle =
    if (text.containsKana()) withJapaneseLocale() else this

/**
 * 只打日语的语言标签与 OpenType 特性，**不动字体**。
 */
private fun TextStyle.withJapaneseLocale(): TextStyle {
    val existingFeatures = fontFeatureSettings
    val features = when {
        existingFeatures.isNullOrBlank() -> JapaneseFeatures
        existingFeatures.contains(JapaneseFeatures) -> existingFeatures
        else -> "$existingFeatures, $JapaneseFeatures"
    }
    return copy(localeList = JapaneseLocaleList, fontFeatureSettings = features)
}

/**
 * 无条件按日语排版：换日文字体 + `lang=ja-JP` + `palt`。
 *
 * 给「整块内容已经判定为日语」的场景用（例如整首歌词）—— 那里是按**行**渲染的，
 * 拿不到逐 run 的 `AnnotatedString`，只能整行换家族。
 *
 * 等宽样式（`FontFamily.Monospace`，用于路径 / 文件名）不参与替换：
 * 那些内容里的日文也需要等宽，换成比例字体反而错位。
 */
fun TextStyle.withJapaneseTypography(japaneseFamily: FontFamily?): TextStyle {
    val family = when {
        fontFamily == FontFamily.Monospace -> FontFamily.Monospace
        japaneseFamily != null -> japaneseFamily
        else -> fontFamily
    }
    return withJapaneseLocale().copy(fontFamily = family)
}

/**
 * 只打日语排版：`lang=ja-JP` + `palt`，**不换字体**。
 *
 * 与 [withJapaneseTypography] 的区别就是家族：字体要逐 run 路由（同一句里的拉丁与假名
 * 本就是两家字体）时用这个，交给 `buildAppStyledText` 的 span 决定家族。
 */
fun TextStyle.withJapaneseTypesetting(): TextStyle = withJapaneseLocale()

/**
 * 整段中文（或其它非日文 CJK 文本）换到 [cjkFamily]（MiSans）。
 *
 * 与 [withJapaneseTypography] 对称，**不带** `ja` 语言标签与 `palt` ——
 * 中文断行规则（避头尾）由平台的默认逻辑处理，`palt` 会把汉字间距改成比例排布，
 * 中文正文反而更松散。
 */
fun TextStyle.withCjkTypography(cjkFamily: FontFamily?): TextStyle {
    if (cjkFamily == null) return this
    if (fontFamily == FontFamily.Monospace) return this
    return copy(fontFamily = cjkFamily)
}

/**
 * [forTextLanguage] 的 Composable 包装。
 *
 * 用 `remember` 钉住结果 —— 文本与样式没变时返回**同一个** `TextStyle` 实例，
 * 下游（`Text` 的测量缓存、`remember(style)`）不会因为每次重组新建对象而失效。
 */
@Composable
fun TextStyle.localizedForLanguage(text: String): TextStyle =
    remember(this, text) { forTextLanguage(text) }
