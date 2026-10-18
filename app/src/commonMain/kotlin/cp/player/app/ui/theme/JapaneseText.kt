package cp.player.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList

/**
 * 当前主题装配好的日文字体；null = 不提供日语排版（离屏测试 / 未接入主题的场景）。
 *
 * 走 CompositionLocal 而不是让每个调用点自己 `Font(Res.font.…)`：字体资源的读取是
 * `@Composable` 且桌面端**异步加载**的，逐处新建会把整棵树的 `TextStyle` 身份搅乱
 * （见 `Font.kt` 里 `remember` 那段注释）。主题里只装一次，这里只读。
 */
val LocalJapaneseFontFamily = staticCompositionLocalOf<FontFamily?> { null }

/**
 * 日语的排版语言标签。
 *
 * 这就是 HTML 里 `lang="ja"` 的等价物：`TextStyle.localeList` 会被带到排版引擎
 * （Android 的 `StaticLayout` / 桌面 Skia 的段落样式）里，影响**断行与标点规则**
 * （禁则处理：`、。」` 不出现在行首，`（「` 不出现在行尾）。
 *
 * ⚠️ 它**不负责选字体** —— 字形来自 [LocalJapaneseFontFamily] 指向的 Noto Sans JP。
 * 两件事必须一起做：只给 locale 的话，系统仍可能用中文字体渲染汉字（同码位不同字形）。
 */
private val JapaneseLocaleList = LocaleList("ja-JP, ja")

/**
 * 这段文本是不是日语。
 *
 * 判据：**含平假名或片假名**（含小假名、长音符与假名语音扩展）。
 * - 中文不用假名（零星借用整段仍按中文排版，所以只认假名区段）；
 * - 韩文 / 拉丁文本自然为 false。
 */
fun String.containsKana(): Boolean = any { ch ->
    ch in '\u3040'..'\u309F' || ch in '\u30A0'..'\u30FF' || ch in '\u31F0'..'\u31FF' || ch in '\uFF65'..'\uFF9F'
}

/**
 * 按文本语言给样式补上「字体 + 语言标签 + 假名比例排版特性」。
 *
 * 只对**含假名**的文本生效；其余原样返回（返回的是同一个实例，`remember` 的 key 稳定）。
 * 等宽样式（`FontFamily.Monospace`，用于路径 / 文件名）不参与替换：那些内容里的日文
 * 也需要等宽，换成比例字体反而错位。
 *
 * @param japaneseFamily 主题提供的日文字体；null 时退化为「只打语言标签与 OpenType 特性」。
 */
fun TextStyle.forTextLanguage(text: String, japaneseFamily: FontFamily?): TextStyle {
    if (!text.containsKana()) return this
    return withJapaneseTypography(japaneseFamily)
}

/**
 * 无条件按日语排版：换日文字体 + `lang=ja-JP/jp` + `palt` 假名比例排版（非 1:1 全角网格填充）。
 *
 * 开启 OpenType `palt`（Proportional Alternate Metrics）特性，
 * 让假名按其自然字形宽度排列，消除默认 1:1 全角方形网格带来的生硬间距。
 * 给「整块内容已经判定为日语」的场景用（例如整首歌词），这时不必逐行再看假名。
 */
fun TextStyle.withJapaneseTypography(japaneseFamily: FontFamily?): TextStyle {
    val family = when {
        fontFamily == FontFamily.Monospace -> FontFamily.Monospace
        japaneseFamily != null -> japaneseFamily
        else -> fontFamily
    }
    val existingFeatures = fontFeatureSettings
    val features = when {
        existingFeatures.isNullOrBlank() -> "palt"
        existingFeatures.contains("palt") -> existingFeatures
        else -> "$existingFeatures, palt"
    }
    return copy(fontFamily = family, localeList = JapaneseLocaleList, fontFeatureSettings = features)
}

/**
 * [forTextLanguage] 的 Composable 包装：从主题取日文字体。
 *
 * 用 `remember` 钉住结果 —— 文本与样式没变时返回**同一个** `TextStyle` 实例，
 * 下游（`Text` 的测量缓存、`remember(style)`）不会因为每次重组新建对象而失效。
 */
@Composable
fun TextStyle.localizedForLanguage(text: String): TextStyle {
    val japaneseFamily = LocalJapaneseFontFamily.current
    return remember(this, text, japaneseFamily) {
        forTextLanguage(text, japaneseFamily)
    }
}
