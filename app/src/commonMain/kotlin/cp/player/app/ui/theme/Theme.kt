package cp.player.app.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.ktx.animateColorScheme
import com.materialkolor.rememberDynamicColorScheme
import cp.player.app.i18n.CpStrings

/**
 * 主题模式：跟随系统 / 浅色 / 深色。
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 设置页用的短标签。
 *
 * 放这里而不是 UI 层：重构前这段 `when` 在「外观」页和已经删掉的「偏好设置」页
 * 各写了一份，两边措辞已经开始漂移。
 *
 * ⚠️ 收 [CpStrings] 而不是无参：枚举扩展函数读不到组合状态，写死中文的话
 * 英文界面的分段控件里会并排出现 `Follow system / 浅色 / 深色`。
 */
fun ThemeMode.displayName(strings: CpStrings): String = when (this) {
    ThemeMode.SYSTEM -> strings.appearance.themeModeSystem
    ThemeMode.LIGHT -> strings.appearance.themeModeLight
    ThemeMode.DARK -> strings.appearance.themeModeDark
}

/**
 * 换色过渡时长。
 *
 * 600 ms 是权衡结果：再短会显得「跳」，再长则整棵 UI 树要在更久的时间里逐帧重组
 * —— 过渡期间 `MaterialTheme` 的 colorScheme 每帧都变，读色的 composable 会跟着重组。
 * **这个值不能随手调大。**
 */
private const val ColorTransitionMillis = 600

/**
 * 应用主题入口。
 *
 * 管线分三段，成本差别极大，改动时务必分清：
 *
 * 1. **取种子色** —— 按 [colorSource] 从平台 / 封面 / 常量拿到一个 `Color`。
 * 2. **生成方案** —— 种子 → 完整 M3 `ColorScheme`。**这一步很贵**（几十次 HCT 转换），
 *    靠 `remember` 钉在「种子或明暗变化」时才重算。
 * 3. **逐角色插值** —— 旧方案 → 新方案，每帧只有约 40 次 `Color` lerp。这一步便宜，
 *    正是「过渡不掉帧」的关键。
 *
 * ⚠️ **不要把第 2 段搬进动画循环**（例如把 `Animatable<Color>` 的种子直接喂给
 * `dynamicColorScheme`）：那样每帧都要重算整套方案，必然掉帧。
 *
 * @param themeMode 跟随系统 / 浅色 / 深色
 * @param colorSource 种子色来源（系统 / 封面 / 固定）
 * @param paletteStyle 种子色展开成整套方案的风格档位。默认 [PaletteStyle.TonalSpot] ——
 *   这是 Flutter `ColorScheme.fromSeed` / Material Theme Builder 的默认档，也是
 *   Kazumi 观感的基准；换成 `Vibrant` / `Expressive` 会让配色明显更跳。
 *   **不要为了"更 Expressive"就把它调高**：M3E 的表达性主要来自形状与动效，
 *   不是配色饱和度。这一档只在需要时由调用方覆盖。
 * @param pureBlack 深色时把 surface 系列压成纯黑
 * @param coverSeed [ColorSource.COVER] 的当前封面种子色；null 表示当前没有曲目封面
 * @param wallpaperSeed [ColorSource.COVER] 且没有曲目封面时的**系统壁纸**回退种子色；
 *   Android 恒为 null（那边由 Monet 直接给整套方案）
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CpTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    colorSource: ColorSource = ColorSource.FIXED,
    paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    pureBlack: Boolean = false,
    coverSeed: Color? = null,
    wallpaperSeed: Color? = null,
    /**
     * Google Sans Flex 的自定义圆滑度（0–100）；null = 未自定义，跟随平台默认
     * （[cp.player.app.platform.defaultFontRoundness]，Android 16+ 为 100，其余为 0）。
     */
    fontRoundness: Int? = null,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    // 第 1 段：拿到目标方案。两条路 —— 平台给整套方案，或我们自己从种子色生成。
    //
    // 「跟随封面」但当前没有曲目封面（未播放 / 该曲目无封面）时回退到**系统壁纸**：
    // - Android 12+：Monet 本身就是壁纸色，直接取平台方案
    // - 桌面：没有 Monet，改取壁纸图的种子色（[platformWallpaperSeed]）
    // 两条都取不到才退到静态回退色板（Android 12 以下 / 非 Windows / 壁纸是动态壁纸）。
    //
    // ⚠️ 这个回退只在**真的没有封面**时触发。切歌时 `coverSeed` 不会先置 null
    // （见 AppModel.startCoverColorTracking），所以不会出现「每切一首歌闪一下壁纸色」。
    val coverMissing = colorSource == ColorSource.COVER && coverSeed == null
    val platformScheme =
        if (colorSource == ColorSource.PLATFORM || coverMissing) platformDynamicScheme(dark)
        else null

    val seed = when {
        // 平台已给出完整方案，没有「种子色」这一说。
        platformScheme != null -> null
        colorSource == ColorSource.PLATFORM -> platformAccentSeed()
        colorSource == ColorSource.COVER -> coverSeed ?: wallpaperSeed
        else -> DefaultSeedColor
    }

    // 第 2 段：生成方案。只有拿不到平台 Monet 时才需要 materialkolor 展开。
    val generated = if (platformScheme == null && seed != null) {
        rememberDynamicColorScheme(
            seedColor = seed,
            isDark = dark,
            isAmoled = pureBlack,
            style = paletteStyle,
        )
    } else {
        null
    }

    val target = platformScheme ?: generated ?: if (dark) DarkColors else LightColors

    // 第 3 段：逐角色插值。
    val animated = animateColorScheme(
        colorScheme = target,
        animationSpec = { tween(ColorTransitionMillis, easing = FastOutSlowInEasing) },
    )

    // 纯黑覆盖必须排在动画**之后**：若放在动画之前，`#000` 会被插值成深灰，
    // 切换纯黑时整屏会先亮一下再变黑。
    val colorScheme = if (dark && pureBlack) animated.withPureBlackSurfaces() else animated

    // Expressive 动效方案。**必须 remember**：`MotionScheme.expressive()` 每次都返回新实例时，
    // 所有读 `MaterialTheme.motionScheme` 的 `animate*AsState` 会把 targetState 认成变了，
    // 于是动画被无限重启动 —— 表现是「控件一直在轻微抖」+ 持续吃 CPU。
    // （与 TOPICS.md 里 `ColorScheme` 没重写 `equals` 那个坑同源。）
    val motionScheme = remember { MotionScheme.expressive() }

    // 字阶 + 字体。字体要从 Compose 资源读（`Font(Res.font.*)` 是 @Composable），
    // 所以只能在主题里装配，不能放进顶层的 `val AppTypography`（见 Type.kt 的说明）。
    // remember 的 key 是 FontFamily：字体异步加载完成后它会换身份，届时字阶重建一次，
    // 之后就稳定了 —— 不会跟着上面的配色动画每帧重建。
    // 圆滑度变化会让五个字重实例全部换身份 ⇒ 字阶重建一次，这是可变字体换轴的固有代价，
    // 只发生在用户拖设置页滑杆松手的那一次。
    val effectiveRoundness = fontRoundness ?: cp.player.app.platform.defaultFontRoundness()
    val fontFamily = googleSansFlexFamily(effectiveRoundness)
    val typography: Typography = remember(fontFamily) { AppTypography.withFontFamily(fontFamily) }

    CompositionLocalProvider(
        LocalThemeMode provides themeMode,
        LocalIsDarkTheme provides dark,
    ) {
        // Expressive 主题：相对普通 `MaterialTheme` 的差别就是注入 `MotionScheme`，
        // 让所有 material3 组件的默认动画从「匀速 tween」变成带回弹的 spring。
        // 这是「一眼看上去像 M3 Expressive」成本最低的一步。
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = motionScheme,
            typography = typography,
            shapes = AppShapes,
            content = content,
        )
    }
}

/**
 * 把 surface 系列压到 OLED 友好的极暗色阶。
 *
 * **只压「大面积背景」与「最低两级容器」**，保留 `surfaceContainerLow..Highest` 的极暗灰阶梯。
 *
 * ⚠️ 这里曾经把 `surfaceVariant` + 六个 surface 角色**全部压成 `#000`**，后果是
 * 卡片与背景同色、整页糊成一块，只能靠 `bentoOutline()` 描边去补层级
 * （`LOG.md` 里那条「中性卡片必须带 bentoOutline 描边」正是这个原因）。
 * 但描边是比色阶**更重**的视觉元素 —— 用描边补层级，整页会显得生硬。
 *
 * 保留 4–5 点亮度差的极暗灰（`#0A0A0C` → `#19191D`）既能让 OLED 大面积熄屏，
 * 又能维持**无描边的层级**：肉眼在纯黑屏上仍能分辨卡片边界。
 *
 * 只覆盖 surface / background 系角色，**不动** primary / secondary 等语义色：
 * 纯黑模式的目的是让 OLED 大面积像素熄灭，把强调色也压黑会让界面彻底失去层级。
 */
private fun ColorScheme.withPureBlackSurfaces(): ColorScheme = copy(
    surface = Color.Black,
    background = Color.Black,
    surfaceContainerLowest = Color.Black,
    // ↓ 下面这几档刻意**不压黑**：它们承担卡片与浮层的层级表达。
    surfaceVariant = Color(0xFF16161A),
    surfaceContainerLow = Color(0xFF0A0A0C),
    surfaceContainer = Color(0xFF0E0E11),
    surfaceContainerHigh = Color(0xFF131316),
    surfaceContainerHighest = Color(0xFF19191D),
)

val LocalThemeMode = staticCompositionLocalOf { ThemeMode.SYSTEM }

/**
 * **已解析的**明暗状态（把 `ThemeMode.SYSTEM` 换算成实际的 true/false 之后）。
 *
 * ⚠️ 需要「当前是不是深色」时**必须读它，不要读 `isSystemInDarkTheme()`**。
 *
 * 两者在「主题模式 = 跟随系统」时恰好相等，但本应用允许用户**显式指定**浅色 / 深色
 * （`ThemeMode.LIGHT` / `DARK`）。用户选了深色而系统是浅色时：
 * - `isSystemInDarkTheme()` → false
 * - [LocalIsDarkTheme] → true（这才是对的）
 *
 * 读错的那个会去取**另一套**色板的角色色，典型症状是「卡片与页面背景同色、整块糊住」。
 * 本仓库踩过：`SettingsKit.settingsRowContainer()` / `SongItem` / `PlayerScreen`
 * 都曾用 `isSystemInDarkTheme()` 当代理，导致「设置页的分组卡片在浅色下不可见」。
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }
