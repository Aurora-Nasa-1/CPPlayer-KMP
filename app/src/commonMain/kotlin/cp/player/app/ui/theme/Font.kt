package cp.player.app.ui.theme

import androidx.compose.material3.Typography
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
 * 应用使用的五档字重（可变字体里就是 `wght` 轴的取值）。
 *
 * 三款字体全部**共享这一张表**：Google Sans Flex 与 MiSans VF 都是可变字体，
 * 不给每档显式带 `wght` 的话，五档拿到的都是默认实例（渲染出来一模一样）。
 * 放在这里是为了「加一档」时三处一起动，不会出现某个字体少一档。
 *
 * 300 Light · 400 Normal · 500 Medium · 600 SemiBold · 700 Bold
 * （M3 字阶只用到 400 / 500 / 700，300 与 600 是给 `CpText` 的自定义字重留的）
 */
internal val AppFontWeights: List<Pair<Int, FontWeight>> = listOf(
    300 to FontWeight.Light,
    400 to FontWeight.Normal,
    500 to FontWeight.Medium,
    600 to FontWeight.SemiBold,
    700 to FontWeight.Bold,
)

/**
 * Google Sans Flex —— 应用默认字体（[AppFontFace.GOOGLE_SANS_FLEX]）。
 *
 * 字体文件：`app/src/commonMain/composeResources/font/google_sans_flex.ttf`
 * （与旧版 `reference/cp-player-legacy` 用的是同一个文件，从那儿原样搬过来的）。
 * **两端都内置**：它是拉丁 / 数字的默认字体，没有它整个界面就没有骨架。
 *
 * ⚠️ **它是可变字体，不是静态字重集合。**
 * 六个轴：`opsz` / `wdth` / `wght` / `GRAD` / `ROND` / `slnt`，其中 `wght` 范围 1–1000。
 * 所以**必须**给每个字重显式带上 `FontVariation.Settings(FontVariation.weight(n))`：
 * 只写 `Font(Res.font.google_sans_flex, FontWeight.Bold)` 的话，五个字重拿到的都是
 * 默认实例（wght=400），渲染出来一模一样 —— 加了字重却看不出字重。
 *
 * ⚠️ **它不含 CJK 字形**：整套只有 682 个字形，覆盖拉丁 / 希腊 / 西里尔 + 符号。
 * 中日文由 [AppFontFace.NOTO_SANS_JP] / [AppFontFace.MISANS] 承担 ——
 * 见 `FontStack.kt` 的声明与 `buildAppStyledText` 的路由。
 * **不要**为了「统一观感」把中文也钉成这个字体，那会整屏渲染成豆腐块。
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
 * 当前平台可用的中日文字体（[AppFontFamilies]）。
 *
 * - **桌面端**：随安装包内置（`app/src/desktopMain/resources/fonts/`），直接读 classpath。
 * - **Android**：默认**不内置**（两款加起来 30 MB，装进 APK 对谁都不划算），
 *   由用户在「设置 → 外观 → 字库」里从 GitHub 下载（见 `FontPacks`）。
 *   没下载时返回 null，文本自动回到平台回退字体 —— 不会有豆腐块。
 *
 * ⚠️ 这是 `@Composable` 而不是普通函数：Android 侧要读下载状态并在下载完成后重组。
 */
@Composable
expect fun rememberAppFontFamilies(): AppFontFamilies
