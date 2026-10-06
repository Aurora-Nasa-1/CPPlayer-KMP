package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import com.mikepenz.hypnoticcanvas.shaderBackground
import com.mikepenz.hypnoticcanvas.shaders.MeshGradient
import cp.player.app.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.delay

/**
 * 流体背景：仿 Apple Music 的缓慢流动的网格渐变。
 *
 * 底层是 HypnoticCanvas（`com.mikepenz.hypnoticcanvas`）的 `MeshGradient` 着色器
 * —— 用 simplex 噪声把若干色团在画面上推来推去，观感就是 Apple Music 播放页背后
 * 那层「活的」底色。着色器以 **SkSL** 写就：桌面走 skiko 的 `RuntimeEffect`，
 * Android 走 `android.graphics.RuntimeShader`。
 *
 * ### 颜色来自主题，不来自封面
 *
 * 这里刻意**不**直接吃封面位图，只吃 [MaterialTheme] 的角色色。理由：
 * 「跟随封面取色」（`ColorSource.COVER`）已经把关色 → 种子 → 整套 M3 方案这条链路
 * 铺好了，封面一换 `primaryContainer` / `tertiaryContainer` 自己就会变。再在播放页
 * 单独解一次封面、单独取一套色，等于把「同一个封面在播放页与其它页配色不一致」
 * 这个坑重新挖出来 —— 而取色算法必须与 Material You 同源（见 `CoverColor.kt`）。
 *
 * 用 **container 系列**而不是 `primary` / `tertiary` 本身：container 是「同色相、
 * 低对比」的那一档（深色下 tone 30、浅色下 tone 90），正好是「大面积铺底」需要的
 * 明度。直接用 `primary` 会在深色主题下甩出一块 tone 80 的亮斑，压在上面的
 * `onSurface` 文字直接不可读。
 *
 * ### 平台差异（库的行为，不是本文件的 bug）
 *
 * **Android 12 及以下没有 `RuntimeShader`（API 33 才有）**，库会跳过着色器、改用
 * 调用方传入的 [fallback] Brush。所以 [fallback] 不是「保险」，而是低版本安卓的
 * **实际外观**，必须是好看的：调用方一律传该页原本用的那支渐变。
 *
 * ### 性能
 *
 * 着色器靠 `withInfiniteAnimationFrameMillis` 驱动，**只要在屏就一直在动**
 * （与 Apple Music 一致：暂停时背景也在缓慢漂移）。代价是播放页在屏期间持续出帧 ——
 * 这正是它必须能被设置项关掉的原因（「外观 → 流体背景」）。关掉时本函数退化成
 * 一次 `background(fallback)`，与改造前完全等价。
 *
 * @param enabled 设置项「流体背景」。false 时只画 [fallback]。
 * @param fallback 低版本安卓 / 着色器未就绪时的静态底色，同时是 `enabled = false` 时的唯一背景。
 * @param speed 漂移速度。库内部还会再乘一次它自己的 `speedModifier`（默认 0.5），
 *   所以这里的 1f 并不是「每秒一格」。它是个 uniform，调大调小**不会**触发着色器重编译。
 * @param scale 色团尺度。**越大 → 色团越小**（库的 `MeshGradient` 原话是
 *   「board 越大 → 色团越小」）。它会被拼进 SkSL 源码（编译期常量），
 *   改它会重建着色器 —— 别接在会逐帧变化的值上。
 */
@Composable
fun Modifier.cpFluidBackground(
    enabled: Boolean = true,
    fallback: Brush,
    speed: Float = CpFluidBackgroundDefaults.SPEED,
    scale: Float = CpFluidBackgroundDefaults.SCALE,
): Modifier {
    if (!enabled) return this.background(fallback)

    val scheme = MaterialTheme.colorScheme
    val dark = LocalIsDarkTheme.current

    // ⚠️ `remember` 的 key 只能是**颜色本身**，不能是 `scheme`：`ColorScheme` 没重写
    // `equals`（见 TOPICS.md 那条），主题过渡期间每帧都是新实例 ⇒ 每帧重建。
    val primary = scheme.primary
    val base = if (dark) scheme.surfaceContainerLowest else scheme.surface
    val palette = remember(primary, base, dark) { buildFluidPalette(primary, base, dark) }

    // ⚠️ 必须自己 remember 着色器实例，**不能**把 `MeshGradient(...)` 直接写在
    // `shaderBackground(...)` 的实参位置上：库内部是 `remember(shader) { buildEffect(shader) }`，
    // 而 `MeshGradient` 没重写 `equals` ⇒ 逐帧新建实例 = 逐帧重新编译 SkSL。
    val settled = rememberSettledPalette(palette)
    val shader = remember(settled, scale) {
        MeshGradient(
            colors = settled.toTypedArray(),
            // 库把这个 `speed` 拼进 SkSL（`$speed`），改它要重编译；总速度交给
            // `shaderBackground(speed = …)` 那个 uniform 控制，这里保持默认。
            speed = 1f,
            scale = scale,
        )
    }

    return this.shaderBackground(
        shader = shader,
        speed = speed,
        fallback = { fallback },
    )
}

/**
 * 流体背景的默认参数。
 *
 * 抽成 object 是为了让「桌面播放页」与「窄屏播放页」用同一组值 —— 两套布局是互斥
 * 分支，各写一份常量迟早会漂移成「同一个功能在两种窗口宽度下动得不一样快」。
 */
object CpFluidBackgroundDefaults {
    /** 比库的默认（1f）慢一档：Apple Music 的背景是「几乎察觉不到在动」。 */
    const val SPEED = 0.6f

    /** 库默认值。色团大小在桌面 1320×860 与手机竖屏上观感都合适。 */
    const val SCALE = 2f
}

// ---------------------------------------------------------------------------
// 色板
//
// ⚠️ **不要改成直接拿 `primaryContainer` / `tertiaryContainer` 当色团。** 试过了，
// 那是「看着合理、渲出来是黑的」：M3 的 container 是**低彩度**角色（TonalSpot 下
// 彩度只有 16–24），三个 container 的明度又都是同一个 tone（深色 30 / 浅色 90），
// 于是噪声混完只剩一团没有色相的灰。实测深色下整屏像素落在 RGB 14–64 之间 ——
// 肉眼看就是纯黑，「流体」二字完全体现不出来。
//
// 要的是 Apple Music 那种「深底上浮着几团**看得见的彩色**」，所以色相 / 彩度 / 明度
// 三个量必须**分开定**：色相取自主题（跟着种子色走），彩度与明度按「当背景」的目标
// 写死。这正是 `material-color-utilities` 的 HCT 干的事 —— 它本来就是这个项目的
// 取色依赖（`CoverColor.kt`），不是为这个功能新引的东西。
// ---------------------------------------------------------------------------

/** 三个色团相对主题主色相的偏移（度）。取 ±45 而不是 ±60：相邻色相过渡更柔和。 */
private const val HueSpread = 45.0

/**
 * 深色主题：色团压到 tone 38、彩度提到 48。
 *
 * tone 38 是「看得见颜色」与「白字还压得住」的交点：`onSurface` 是 tone 90，
 * 与 tone 38 的对比度约 6:1，正文与标题都安全。
 */
private const val DarkBlobTone = 38.0
private const val DarkBlobChroma = 48.0

/**
 * 浅色主题：色团抬到 tone 76、彩度 40。
 *
 * 底色是 tone 98 左右的 `surface`，所以 76 与 98 之间那 22 级明度差就是「水彩晕开」
 * 的全部可见度；再淡就看不见，再浓就压住了 `onSurface`（tone 10）的正文。
 */
private const val LightBlobTone = 76.0
private const val LightBlobChroma = 40.0

/**
 * 主题色 → 着色器色板。
 *
 * 色相锚在 `scheme.primary` 上 —— 它就是「跟随封面取色」时由封面种子色推出来的那一支，
 * 所以封面一换，整片流体背景跟着换，与界面上其它强调色同源。
 *
 * ⚠️ 顺序有意义：库把**最后一个**色当作「底」，前面几个按噪声混进去。底色必须排在末位，
 * 否则整屏会变成一块高饱和色、而不是「深底上浮着几团色」。
 *
 * @param primary 主题主色，只取它的**色相**。
 * @param base 底色（深色取最暗的那级 surface，浅色取 `surface`）。
 */
private fun buildFluidPalette(primary: Color, base: Color, dark: Boolean): List<Color> {
    val hue = Hct.fromInt(primary.toArgb()).hue
    val tone = if (dark) DarkBlobTone else LightBlobTone
    val chroma = if (dark) DarkBlobChroma else LightBlobChroma
    return listOf(
        blobColor(hue - HueSpread, chroma, tone),
        blobColor(hue, chroma, tone),
        blobColor(hue + HueSpread, chroma, tone),
        base,
    )
}

/**
 * 定一个色团。
 *
 * `Hct.from` 的彩度在部分色相 / 明度组合上会超出 sRGB 色域，此时求解器会**自动收到
 * 域内**（给出该色相下能表现出的最高彩度）—— 这是我们要的行为，不要自己去 clamp，
 * 手写的 clamp 会把色相也一起改掉。
 */
private fun blobColor(hue: Double, chroma: Double, tone: Double): Color =
    Color(Hct.from(hue, chroma, tone).toInt())


/**
 * 等色板稳定后再交给着色器 —— 挡掉**主题过渡期间**的颜色抖动。
 *
 * `CpTheme` 换配色时会把整套 `ColorScheme` 逐帧 lerp 600ms（见 `Theme.kt` 的
 * `ColorTransitionMillis`）。若直接拿 `MaterialTheme.colorScheme` 当 `remember` 的 key，
 * 这 600ms 里每一帧都会命中一个新 key ⇒ **重新编译约 36 次 SkSL**。噪声函数那一段
 * 编译不便宜，表现就是「换封面时播放页卡一下」。
 *
 * 这里的 `LaunchedEffect` 只在颜色**真的变了**才重启，`delay` 比主题过渡略长，于是
 * 整个过渡期间沿用旧色板、结束后重建**一次**。过渡中途那点色差肉眼不可辨 ——
 * 背景本来就在缓慢流动。
 */
@Composable
private fun rememberSettledPalette(palette: List<Color>): List<Color> {
    var settled by remember { mutableStateOf(palette) }
    LaunchedEffect(palette) {
        if (settled == palette) return@LaunchedEffect
        delay(PaletteSettleMillis)
        settled = palette
    }
    return settled
}

/**
 * 比 `Theme.kt` 的 `ColorTransitionMillis`（600）略长：要保证过渡**走完**才换色板，
 * 否则会在过渡的尾巴上再多重建一次。两处一起改。
 */
private const val PaletteSettleMillis = 700L
