package cp.player.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import cp.player.app.resources.Res
import cp.player.app.resources.google_sans_flex
import cp.player.app.resources.noto_sans_jp
import org.jetbrains.compose.resources.Font

/**
 * Google Sans Flex —— 应用默认字体。
 *
 * 字体文件：`app/src/commonMain/composeResources/font/google_sans_flex.ttf`
 * （与旧版 `reference/cp-player-legacy` 用的是同一个文件，从那儿原样搬过来的）。
 *
 * ⚠️ **它是可变字体，不是静态字重集合。**
 * 六个轴：`opsz` / `wdth` / `wght` / `GRAD` / `ROND` / `slnt`，其中 `wght` 范围 1–1000。
 * 所以**必须**给每个字重显式带上 `FontVariation.Settings(FontVariation.weight(n))`：
 * 只写 `Font(Res.font.google_sans_flex, FontWeight.Bold)` 的话，五个字重拿到的都是
 * 默认实例（wght=400），渲染出来一模一样 —— 加了字重却看不出字重。
 *
 * ⚠️ **它不含 CJK 字形**：整套只有 682 个字形，覆盖拉丁 / 希腊 / 西里尔 + 符号。
 * 中文走系统回退字体（Android 的字体回退、桌面 Skia 的 `FontCollection.setDefaultFontManager`
 * 两条路都有），这是预期行为，旧版也是这样。**不要**为了"统一观感"把中文也钉成这个字体，
 * 那会整屏渲染成豆腐块。
 */
@Composable
internal fun googleSansFlexFamily(roundness: Int?): FontFamily {
    val light = googleSansFlex(300, FontWeight.Light, roundness)
    val normal = googleSansFlex(400, FontWeight.Normal, roundness)
    val medium = googleSansFlex(500, FontWeight.Medium, roundness)
    val semiBold = googleSansFlex(600, FontWeight.SemiBold, roundness)
    val bold = googleSansFlex(700, FontWeight.Bold, roundness)

    // 必须 remember：桌面端 `Font(资源)` 是**异步**加载的（先返回一个占位 Font，
    // 字节读进来后再换真的）。不 remember 的话每次重组都新建一个 FontFamily，
    // 进而让整棵树的 Typography 变身份 ⇒ 所有读 `MaterialTheme.typography` 的
    // composable 跟着重组。ResourceFont 是 data class，所以拿它当 key 是可靠的。
    return remember(light, normal, medium, semiBold, bold) {
        FontFamily(light, normal, medium, semiBold, bold)
    }
}

/**
 * 取 Google Sans Flex 的一个字重实例。
 *
 * [roundness] 是可变字体 `ROND` 轴的取值（0 方正 – 100 最圆润）；传 null 或 0 时不附加
 * 该轴 —— 0 就是 fvar 表里的默认值，写不写渲染结果一样，省一条 variation 指令。
 *
 * 参数全部按位置传：这个 `Font` 是 `org.jetbrains.compose.resources` 的**平台 actual**
 * 函数（Android / 桌面各一份），具名参数在一端改名就会连编译都过不去。
 */
@Composable
private fun googleSansFlex(weight: Int, fontWeight: FontWeight, roundness: Int?) = Font(
    Res.font.google_sans_flex,
    fontWeight,
    FontStyle.Normal,
    if (roundness != null && roundness > 0) {
        FontVariation.Settings(
            FontVariation.weight(weight),
            // compose ui-text 1.11 没有 `FontVariation.axis(tag, value)` 辅助函数
            // （那是 androidx 更高版本才加的），底层构造器就是这个 (tag, value) 对。
            FontVariation.Setting("ROND", roundness.toFloat()),
        )
    } else {
        FontVariation.Settings(FontVariation.weight(weight))
    },
)

/**
 * Noto Sans JP —— 日文字体（只在文本被判为日语时使用，见 [localizedForLanguage]）。
 *
 * 字体文件：`app/src/commonMain/composeResources/font/noto_sans_jp.ttf`
 * （`google/fonts` 的 `ofl/notosansjp/NotoSansJP[wght].ttf`，可变字体，wght 100–900，约 9.6 MB）。
 *
 * ## 为什么必须自带而不是靠系统回退
 *
 * 中日**共用码位**的汉字（`直` / `骨` / `次` / `海` …）在 Unicode 里是同一个字，
 * 选哪个字形完全取决于字体：中文来源的字体给的是大陆写法，日文来源的给的是日本写法。
 * 桌面端的 Skia 回退链按系统语言挑，中文系统上日语歌词就会整屏渲染成「中文字形」。
 * 自带一份日文字体后，「这段文字是日语」这件事由我们自己决定，观感才稳定。
 *
 * ⚠️ 它同时含拉丁与假名，所以**整行**用它可以保持行内一致；但**不要**拿它当全局默认 ——
 * 中文歌词会因此变成日文字形（同 `googleSansFlexFamily` 的注释：别为了统一观感钉死字体）。
 */
@Composable
internal fun notoSansJpFamily(): FontFamily {
    val light = notoSansJp(300, FontWeight.Light)
    val normal = notoSansJp(400, FontWeight.Normal)
    val medium = notoSansJp(500, FontWeight.Medium)
    val semiBold = notoSansJp(600, FontWeight.SemiBold)
    val bold = notoSansJp(700, FontWeight.Bold)
    return remember(light, normal, medium, semiBold, bold) {
        FontFamily(light, normal, medium, semiBold, bold)
    }
}

/**
 * 取 Noto Sans JP 的一个字重实例。
 *
 * 与 [googleSansFlex] 同理：它是**可变字体**，不显式给 `wght` 轴的话五个字重都是 400。
 * 这里没有 `ROND` 之类的第二轴，所以不需要 `if` 分支。
 */
@Composable
private fun notoSansJp(weight: Int, fontWeight: FontWeight) = Font(
    Res.font.noto_sans_jp,
    fontWeight,
    FontStyle.Normal,
    FontVariation.Settings(FontVariation.weight(weight)),
)
