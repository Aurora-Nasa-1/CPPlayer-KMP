package cp.player.app.ui.component

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.toSize
import com.materialkolor.hct.Hct
import com.mikepenz.hypnoticcanvas.RuntimeEffect
import com.mikepenz.hypnoticcanvas.shaders.Shader
import cp.player.app.AppModel
import cp.player.app.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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
 * ### 性能与帧率控制
 *
 * 着色器靠物理时钟节拍分频驱动（见 [FluidFpsConfig]）：
 * - 桌面端：显示屏刷新率的一半（60Hz -> 30fps, 120Hz -> 60fps, 144Hz -> 72fps）；
 * - 手机端：根据屏幕 Hz 在 2..5 范围内取整除因子，目标帧率最大 60fps（120Hz -> 60fps, 90Hz -> 45fps, 60Hz -> 30fps）；
 * 非渲染帧跳过 state 更新，使 Compose 智能抑制重绘，既省电又保持物理流动速度完全一致。
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
    dither: Boolean = AppModel.fluidBackgroundDitherFlow.collectAsState().value,
): Modifier {
    if (!enabled) return this.background(fallback)

    val scheme = MaterialTheme.colorScheme
    val dark = LocalIsDarkTheme.current

    // ⚠️ `remember` 的 key 只能是**颜色本身**，不能是 `scheme`：`ColorScheme` 没重写
    // `equals`（见 TOPICS.md 那条），主题过渡期间每帧都是新实例 ⇒ 每帧重建。
    val primary = scheme.primary
    val base = if (dark) scheme.surfaceContainerLowest else scheme.surface
    val palette = remember(primary, base, dark) { buildFluidPalette(primary, base, dark) }

    // ⚠️ 必须自己 remember 着色器实例，**不能**把 `CpFluidMeshGradient(...)` 直接写在
    // 实参位置上：避免逐帧新建实例导致重新编译 SkSL。
    val settled = rememberSettledPalette(palette)
    val shader = remember(settled, scale) {
        CpFluidMeshGradient(
            colors = settled.toTypedArray(),
            // 库把这个 `speed` 拼进 SkSL（`$speed`），改它要重编译；总速度交给 uniform 控制。
            speed = 1f,
            scale = scale,
            dither = dither,
        )
    }

    val fpsConfig = rememberFluidFpsConfig()

    return this.cpPacedShaderBackground(
        shader = shader,
        fallback = fallback,
        speed = speed,
        fpsConfig = fpsConfig,
        dither = dither,
    )
}

/**
 * 带有精准垂直同步（VSync）节拍分频控制的着色器背景修饰符。
 */
@Composable
private fun Modifier.cpPacedShaderBackground(
    shader: Shader,
    fallback: Brush,
    speed: Float,
    fpsConfig: FluidFpsConfig,
    dither: Boolean = true,
): Modifier {
    val effect = remember(shader) { buildRuntimeEffect(shader) }
    if (!effect.supported) return this.background(fallback)

    val speedModifier = shader.speedModifier
    var reportedSize by remember { mutableStateOf(Size.Zero) }

    val time by produceState(initialValue = 0f, key1 = fpsConfig) {
        var frameCount = 0L
        var startNanos = 0L
        val divisor = fpsConfig.divisor.coerceAtLeast(1)
        while (isActive) {
            withInfiniteAnimationFrameNanos { frameTimeNanos ->
                if (startNanos == 0L) startNanos = frameTimeNanos
                frameCount++
                if (frameCount % divisor == 0L) {
                    value = (frameTimeNanos - startNanos) / 1_000_000_000f
                }
            }
        }
    }

    return this
        .onGloballyPositioned { reportedSize = it.size.toSize() }
        .drawBehind {
            val w = if (reportedSize.width > 0f) reportedSize.width else size.width
            val h = if (reportedSize.height > 0f) reportedSize.height else size.height
            if (shader is CpFluidMeshGradient) {
                shader.dither = dither
            }
            effect.update(shader, time * speed * speedModifier, w, h)
            if (effect.ready) {
                drawRect(effect.build())
            } else {
                drawRect(fallback)
            }
        }
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

/**
 * 流体网格着色器（带 TPDF 高频空间抖动）。
 *
 * 在 Simplex 噪声网格渐变的基础上：
 * 1. 引入了 [dither] 均匀变量 `uDither`，在片元着色器末端注入微量高频三角分布抖动（TPDF - Triangular
 *    Probability Density Function）；
 * 2. 在 8-bit SDR 帧缓冲区（`RGBA_8888`）下，将低频大面积暗色平滑渐变的色带断层（Color Banding）
 *    完全打碎，使肉眼观感达到逼近 10-bit / 12-bit 的丝滑过渡；
 * 3. 抖动通过 uniform 变量传递，动态开关无需重新编译 SkSL，零卡顿。
 */
class CpFluidMeshGradient(
    private val colors: Array<Color>,
    val speed: Float = 1f,
    val scale: Float = 2f,
    @Volatile var dither: Boolean = true,
) : Shader {
    private val colorCount: Int = colors.size
    private val colorUniforms: FloatArray = colors.flatMap {
        listOf(it.red, it.green, it.blue)
    }.toFloatArray()

    override val name: String = "CpFluidMeshGradient"
    override val authorName: String = "Mike Penz / Johnny Leek / CPPlayer"
    override val authorUrl: String = "https://github.com/mikepenz/"
    override val credit: String = "Mesh gradient with TPDF dither"
    override val license: String = "MIT License"
    override val licenseUrl: String = "https://opensource.org/license/mit"
    override val speedModifier: Float get() = 0.5f

    override val sksl: String = buildSksl(colorCount, scale, speed)

    override fun applyUniforms(runtimeEffect: RuntimeEffect, time: Float, width: Float, height: Float) {
        super.applyUniforms(runtimeEffect, time, width, height)
        runtimeEffect.setFloatUniform("uColor", colorUniforms)
        runtimeEffect.setFloatUniform("uDither", if (dither) 1f else 0f)
    }

    companion object {
        private fun buildSksl(colorCount: Int, scale: Float, speed: Float): String = """
uniform float uTime;
uniform vec3 uResolution;
uniform float uDither;

vec3 vColor;
const int MAX_COLORS = $colorCount;
uniform vec3 uColor[MAX_COLORS];

// Simplex 3D Noise by Ian McEwan, Ashima Arts
vec4 permute(vec4 x) {
    return mod(((x * 34.0) + 1.0) * x, 289.0);
}
vec4 taylorInvSqrt(vec4 r) {
    return 1.79284291400159 - 0.85373472095314 * r;
}

float snoise(vec3 v) {
    const vec2 C = vec2(1.0 / 6.0, 1.0 / 3.0);
    const vec4 D = vec4(0.0, 0.5, 1.0, 2.0);

    vec3 i = floor(v + dot(v, C.yyy));
    vec3 x0 = v - i + dot(i, C.xxx);

    vec3 g = step(x0.yzx, x0.xyz);
    vec3 l = 1.0 - g;
    vec3 i1 = min(g.xyz, l.zxy);
    vec3 i2 = max(g.xyz, l.zxy);

    vec3 x1 = x0 - i1 + 1.0 * C.xxx;
    vec3 x2 = x0 - i2 + 2.0 * C.xxx;
    vec3 x3 = x0 - 1.0 + 3.0 * C.xxx;

    i = mod(i, 289.0);
    vec4 p = permute(permute(permute(i.z + vec4(0.0, i1.z, i2.z, 1.0)) + i.y + vec4(0.0, i1.y, i2.y, 1.0)) + i.x + vec4(0.0, i1.x, i2.x, 1.0));

    float n_ = 1.0 / 7.0;
    vec3 ns = n_ * D.wyz - D.xzx;

    vec4 j = p - 49.0 * floor(p * ns.z * ns.z);

    vec4 x_ = floor(j * ns.z);
    vec4 y_ = floor(j - 7.0 * x_);

    vec4 x = x_ * ns.x + ns.yyyy;
    vec4 y = y_ * ns.x + ns.yyyy;
    vec4 h = 1.0 - abs(x) - abs(y);

    vec4 b0 = vec4(x.xy, y.xy);
    vec4 b1 = vec4(x.zw, y.zw);

    vec4 s0 = floor(b0) * 2.0 + 1.0;
    vec4 s1 = floor(b1) * 2.0 + 1.0;
    vec4 sh = -step(h, vec4(0.0));

    vec4 a0 = b0.xzyw + s0.xzyw * sh.xxyy;
    vec4 a1 = b1.xzyw + s1.xzyw * sh.zzww;

    vec3 p0 = vec3(a0.xy, h.x);
    vec3 p1 = vec3(a0.zw, h.y);
    vec3 p2 = vec3(a1.xy, h.z);
    vec3 p3 = vec3(a1.zw, h.w);

    vec4 norm = taylorInvSqrt(vec4(dot(p0, p0), dot(p1, p1), dot(p2, p2), dot(p3, p3)));
    p0 *= norm.x;
    p1 *= norm.y;
    p2 *= norm.z;
    p3 *= norm.w;

    vec4 m = max(0.6 - vec4(dot(x0, x0), dot(x1, x1), dot(x2, x2), dot(x3, x3)), 0.0);
    m = m * m;
    return 42.0 * dot(m * m, vec4(dot(p0, x0), dot(p1, x1), dot(p2, x2), dot(p3, x3)));
}

// Author : Johnny Leek / Yuri Artiukh / CPPlayer
vec4 main( vec2 fragCoord ) {
    float mr = min(uResolution.x, uResolution.y);
    vec2 uv = (fragCoord * $scale - uResolution.xy) / mr;

    vec2 base = uv / 2.0;

    float tilt = -0.5 * uv.y;
    float incline = uv.x * 0.1;

    float offset = incline * mix(-0.25, 0.25, uv.y);

    float noise = snoise(vec3(base.x + uTime * 0.2, base.y, uTime * 0.2));

    noise = max(0.0, noise);

    vColor = uColor[MAX_COLORS - 1];

    for(int i = 0; i < MAX_COLORS - 1; i++) {
        float flow = 5.0 + float(i) * 0.3;
        float speed = 6.0 * $speed + float(i) * 0.3;
        float seed = 1.0 + float(i) * 4.0;

        vec2 frequency = vec2(0.3, 0.7);

        float noiseFloor = 0.00001;
        float noiseCeil = 0.6 + float(i) * 0.07;

        float noiseVal = smoothstep(noiseFloor, noiseCeil, snoise(vec3(base.x * frequency.x + uTime * 0.005 * flow, base.y * frequency.y, uTime * 0.005 * speed + seed)));

        vColor = mix(vColor, uColor[i], noiseVal);
    }

    // High-frequency Triangular Probability Density Function (TPDF) Dithering
    // Eliminates color banding in 8-bit SDR framebuffers (simulating 10-bit smoothness)
    if (uDither > 0.5) {
        float n1 = fract(52.9829189 * fract(dot(fragCoord, vec2(0.06711056, 0.00583715))));
        float n2 = fract(52.9829189 * fract(dot(fragCoord + vec2(0.5, 0.5), vec2(0.06711056, 0.00583715))));
        float dither = (n1 + n2 - 1.0) / 255.0;
        vColor = clamp(vColor + vec3(dither), 0.0, 1.0);
    }

    return vec4(vColor, 1.0);
}
""".trimIndent()
    }
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
