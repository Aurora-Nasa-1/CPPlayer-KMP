package cp.player.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import kotlin.math.pow

// Material 3 Expressive 配色。
//
// ⚠️ **这里不再手写色值。**
//
// 历史：本文件曾经手写 32 个角色（primary / surface / 五级容器…），只有真正被读到的
// 那部分是对的。漏掉的 16 个（`surfaceBright` / `surfaceDim` / `inversePrimary` /
// `surfaceTint` / 12 个 fixed 角色）会静默退到 `lightColorScheme()` 的**基线紫**
// （#FEF7FF / #EADDFF / #6750A4），而且手挑的 secondary / tertiary（青绿 #006A68、
// 珊瑚 #9A4529）与种子色 #4F55A5 的调色结果（近中性 #5D5D72、mauve #73547A）
// 根本不是同一套设计 —— 于是「静态回退」和「动态取色」是两种观感。
//
// 现在两条路走同一个函数：`dynamicColorScheme(seed, SPEC_2025, TonalSpot)`。
// 差异只剩「种子色是哪个」，不再有「色板是不是漏填」这一类问题。
//
// ⚠️ **`style` 不要改成 `Expressive`。** 那一档会把色相偏移与容器对比整体拉高，
// 表现就是「取色很怪、和系统对不上」—— 已经踩过一次。M3E 的表达性来自形状与动效
// （见 `Motion.kt` / `Shape.kt`），不是配色饱和度。

/**
 * 内置固定种子色，同时是 [ColorSource.FIXED] 的种子与所有回退路径的兜底种子。
 *
 * 蓝紫。调色风格固定 `SPEC_2025` + `TonalSpot`，与 Android 系统 Monet /
 * Material Theme Builder 同一档 —— 这是「取色和其他 MD3 应用一致」的基准。
 */
val PrimaryLight = Color(0xFF4F55A5)

/**
 * 从种子展开整套 M3 方案。
 *
 * **为什么可以有静态回退却仍然调用生成函数**：只有「拿不到平台方案 + 拿不到种子」
 * 时才会走到这里（用户选「系统」但读不到 Windows 强调色 / 选「跟随封面」但没封面
 * 也读不到壁纸）。这条路径很冷，一次几十次 HCT 转换可以接受，所以用 `lazy` 挂住。
 *
 * ⚠️ 不要改成在 composition 里现场算 —— 那是每次重组都重算，见 `CpTheme` 第 2 段。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun seedScheme(dark: Boolean): ColorScheme = dynamicColorScheme(
    seedColor = PrimaryLight,
    isDark = dark,
    isAmoled = false,
    style = PaletteStyle.TonalSpot,
    specVersion = ColorSpec.SpecVersion.SPEC_2025,
)

/** 浅色静态回退方案。与动态取色逐角色一致（同函数、同参数）。 */
val LightColors: ColorScheme by lazy { seedScheme(dark = false) }

/** 深色静态回退方案。与动态取色逐角色一致（同函数、同参数）。 */
val DarkColors: ColorScheme by lazy { seedScheme(dark = true) }

/**
 * 修正 materialkolor 5.0.1 在 `SPEC_2025` 下的一个真实缺陷：
 * 深色方案里 `tertiaryContainer` / `onTertiaryContainer` **取反了**。
 *
 * 实测（种子 #4F55A5，深色）：`tertiaryContainer` = #F7D0FD（L\*=90，浅粉），
 * `onTertiaryContainer` = #614468（L\*=34，深紫）—— 等于在深色主题里放了一块浅色容器。
 * primary / secondary 两组的容器都是正常的深色（#4D4F74 / #393A4D），只有 tertiary 反了。
 *
 * 后果不只是「不好看」：`SettingsKit` 的图标块、`HomeScreen` 的强调卡等 15 处都用
 * `tertiaryContainer`，深色下会出现「深色页面上的浅粉块」，层级读起来是错的。
 *
 * 做法是**只在不变量被破坏时交换**，不猜色值：
 * - 浅色：容器必须比它的 on 色**亮**
 * - 深色：容器必须比它的 on 色**暗**
 *
 * 上游修好后这里会自然变成无操作，不用删。
 */
internal fun ColorScheme.withConsistentContainerPairs(dark: Boolean): ColorScheme {
    fun pair(container: Color, onContainer: Color): Pair<Color, Color> {
        val containerIsLighter = container.luminance() > onContainer.luminance()
        val expectedLighter = !dark
        return if (containerIsLighter == expectedLighter) {
            container to onContainer
        } else {
            onContainer to container
        }
    }

    val (primaryC, primaryOnC) = pair(primaryContainer, onPrimaryContainer)
    val (secondaryC, secondaryOnC) = pair(secondaryContainer, onSecondaryContainer)
    val (tertiaryC, tertiaryOnC) = pair(tertiaryContainer, onTertiaryContainer)
    val (errorC, errorOnC) = pair(errorContainer, onErrorContainer)
    return copy(
        primaryContainer = primaryC,
        onPrimaryContainer = primaryOnC,
        secondaryContainer = secondaryC,
        onSecondaryContainer = secondaryOnC,
        tertiaryContainer = tertiaryC,
        onTertiaryContainer = tertiaryOnC,
        errorContainer = errorC,
        onErrorContainer = errorOnC,
    )
}

/**
 * 底色 / 文字的**分别**去染色强度。`0` = 保持种子原色，`1` = 完全中性灰。
 *
 * 两者必须分开：M3E 的 surface 本来就是「带一点主题色相的中性色」，背景完全去色
 * 会丢掉分组模型的味道；而文字带色相压在同色相底色上才是「脏 + 发糊」的真正来源。
 *
 * - 背景 [SurfaceNeutralization]：0.35 —— 保留可见的主题色调，但不至于整屏染色；
 * - 文字 [ContentNeutralization]：0.92 —— 近乎中性，保证任何种子色下都可读。
 */
private const val SurfaceNeutralization = 0.35f
private const val ContentNeutralization = 0.92f

/**
 * 把颜色往同明度的中性灰拉 [amount]。
 *
 * ⚠️ 用的是 sRGB 上的 luma 加权（0.2126 / 0.7152 / 0.0722），不是 HCT 的 chroma。
 * 对「低彩度的表面色」来说两者差别可以忽略，而这条不需要引入 HCT 依赖、可读性好。
 */
private fun Color.neutralized(amount: Float): Color {
    val luma = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    return Color(
        red = red + (luma - red) * amount,
        green = green + (luma - green) * amount,
        blue = blue + (luma - blue) * amount,
        alpha = alpha,
    )
}

/** 背景系：轻度去染色，保留主题色调。 */
private fun Color.neutralizedSurface(): Color = neutralized(SurfaceNeutralization)

/** 文字 / 图标系：强去染色，保证对比度与可读性。 */
private fun Color.neutralizedContent(): Color = neutralized(ContentNeutralization)

/**
 * sRGB 通道 ↔ 线性光，用来按**比值**抬亮度而不改色相。
 */
private fun Float.toLinearLight(): Float =
    if (this <= 0.04045f) this / 12.92f else ((this + 0.055f) / 1.055f).pow(2.4f)

private fun Float.fromLinearLight(): Float =
    if (this <= 0.0031308f) this * 12.92f else 1.055f * this.pow(1f / 2.4f) - 0.055f

/** 把颜色搬到指定相对亮度（Y），保持色相与彩度比例不变。 */
private fun Color.atLuminance(target: Float): Color {
    val ratio = target / luminance().coerceAtLeast(0.0001f)
    return Color(
        red = (red.toLinearLight() * ratio).fromLinearLight().coerceIn(0f, 1f),
        green = (green.toLinearLight() * ratio).fromLinearLight().coerceIn(0f, 1f),
        blue = (blue.toLinearLight() * ratio).fromLinearLight().coerceIn(0f, 1f),
        alpha = alpha,
    )
}

/**
 * 把**普通深色**阶梯拉回经典 M3 的亮度与宽度。
 *
 * ### 为什么
 *
 * 两个独立的用户反馈：① 深色卡片不明显；② 深色背景太深。根因都在 M3E 2025 的深色阶梯：
 * `surface` 被压到 L\*4.1（#0E0E12 附近，接近纯黑），卡片 L\*9.0，只差 4.9 个明度点。
 * 而 M3 长期使用的深色基线是 **tone 6（#121212 附近）**，卡片容器到 tone 12～17。
 *
 * 这里回到经典 M3 的宽阶梯（只在大方向上有意偏离 SPEC_2025）：
 *
 * | 角色 | SPEC_2025 L\* | 本应用 L\* | 经典 M3 参考 |
 * |---|---|---|---|
 * | `surface` 页面 | 4.1 | **6** | tone 6 (#121212) |
 * | `surfaceContainerLow` | 6.1 | **10** | tone 10 |
 * | `surfaceContainer` 卡片 | 9.0 | **13** | tone 12 |
 * | `surfaceContainerHigh` 内嵌 | 12.0 | **18** | tone 17 |
 * | `surfaceContainerHighest` 浮层 | 15.0 | **23** | tone 22 |
 *
 * 页面不再近黑，卡片与页面差 7 个明度点，配合 BentoCard 的 `outlineVariant` 官方描边，
 * 深浅两套都能一眼看出卡片边界。
 *
 * ⚠️ **普通深色不需要为 OLED 熄屏让步** —— 那是独立开关 `pureBlack` 的事。
 * `withPureBlackSurfaces` 在动画之后整段覆盖容器阶梯为压缩值（0 / 3 / 6.1 / 9），
 * 本函数在那条路径上**不调用**，两者互不干扰。
 */
internal fun ColorScheme.withReadableDarkSurfaces(): ColorScheme {
    val page = surface.atLuminance(0.0068f) // L*6
    val highest = surfaceContainerHighest.atLuminance(0.0255f) // L*18.5
    return copy(
        background = page,
        surface = page,
        surfaceDim = page,
        surfaceContainerLow = surfaceContainerLow.atLuminance(0.0095f), // L*8.5
        surfaceContainer = surfaceContainer.atLuminance(0.0135f), // L*11.5
        surfaceContainerHigh = surfaceContainerHigh.atLuminance(0.0195f), // L*15.5
        surfaceContainerHighest = highest,
        surfaceVariant = highest, // M3: surfaceVariant 是 Highest 的别名
        surfaceBright = surfaceBright.atLuminance(0.0330f), // L*21.5，始终高于 Highest
    )
}

/**
 * 分离底色与文字的色相：**背景保留主题色调、文字保持中性**。
 *
 * ### 为什么不是「全部去染色」
 *
 * 跟随封面取色时，整套 surface 会被同一个色相染色。褐色种子的深色方案实测：
 *
 * | 角色 | 实测 | 问题 |
 * |---|---|---|
 * | `surface` 页面 | #130C0C | 大面积暖色 |
 * | `surfaceContainer` 卡片 | #211717 | 与页面同色相，只靠明度差 |
 * | `onSurface` 正文 | #FFDAD9（粉调） | **文字染色才是「脏」的根源** |
 * | `onSurfaceVariant` 次要文字 | #BCA6A5（粉调） | 同上 |
 *
 * 「脏」是**底色与文字同色相 + 层级差太小**的叠加，不是染色本身。M3E 的分组模型要求
 * 背景是「带主题色调的中性色」，全部去色会丢掉这个味道。所以分开处理：
 *
 * - 背景系（surface / 各级容器 / outline）：`neutralizedSurface()` —— 轻度去色，
 *   保留可见的封面色调；卡片是白/亮灰，与染色背景明度差足够大，堆不出「脏」。
 * - 文字系（on* / inverseOnSurface）：`neutralizedContent()` —— 近乎中性，
 *   任何种子色下都可读。
 *
 * ⚠️ 只动 surface / onSurface / outline / inverse 系。`primary*` / `secondary*` /
 * `tertiary*` / `error*` 一律不动 —— 那是强调色，是种子色唯一的出口。
 *
 * ⚠️ 顺序：必须在 `withConsistentContainerPairs` **之前**，否则配对判据用的还是
 * 去染色前的亮度关系（虽然去染色不改明度，但两者都是 copy，别赌）。
 */
internal fun ColorScheme.withNeutralSurfaces(): ColorScheme = copy(
    background = background.neutralizedSurface(),
    onBackground = onBackground.neutralizedContent(),
    surface = surface.neutralizedSurface(),
    onSurface = onSurface.neutralizedContent(),
    surfaceVariant = surfaceVariant.neutralizedSurface(),
    onSurfaceVariant = onSurfaceVariant.neutralizedContent(),
    surfaceDim = surfaceDim.neutralizedSurface(),
    surfaceBright = surfaceBright.neutralizedSurface(),
    surfaceContainerLowest = surfaceContainerLowest.neutralizedSurface(),
    surfaceContainerLow = surfaceContainerLow.neutralizedSurface(),
    surfaceContainer = surfaceContainer.neutralizedSurface(),
    surfaceContainerHigh = surfaceContainerHigh.neutralizedSurface(),
    surfaceContainerHighest = surfaceContainerHighest.neutralizedSurface(),
    outline = outline.neutralizedSurface(),
    outlineVariant = outlineVariant.neutralizedSurface(),
    inverseSurface = inverseSurface.neutralizedSurface(),
    inverseOnSurface = inverseOnSurface.neutralizedContent(),
)
