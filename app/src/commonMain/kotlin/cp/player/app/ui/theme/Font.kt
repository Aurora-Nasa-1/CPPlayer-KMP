package cp.player.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import cp.player.app.resources.Res
import cp.player.app.resources.google_sans_flex
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
internal fun googleSansFlexFamily(): FontFamily {
    val light = googleSansFlex(300, FontWeight.Light)
    val normal = googleSansFlex(400, FontWeight.Normal)
    val medium = googleSansFlex(500, FontWeight.Medium)
    val semiBold = googleSansFlex(600, FontWeight.SemiBold)
    val bold = googleSansFlex(700, FontWeight.Bold)

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
 * 参数全部按位置传：这个 `Font` 是 `org.jetbrains.compose.resources` 的**平台 actual**
 * 函数（Android / 桌面各一份），具名参数在一端改名就会连编译都过不去。
 */
@Composable
private fun googleSansFlex(weight: Int, fontWeight: FontWeight) =
    Font(
        Res.font.google_sans_flex,
        fontWeight,
        FontStyle.Normal,
        FontVariation.Settings(FontVariation.weight(weight)),
    )
