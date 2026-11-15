package cp.player.app.ui.component

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.MeshGradientPainter
import androidx.compose.ui.graphics.MeshGradientScope
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.materialkolor.hct.Hct
import cp.player.app.AppModel
import cp.player.app.ui.theme.LocalIsDarkTheme
import cp.player.app.ui.util.DesktopShell
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 流体背景：仿 Apple Music 的缓慢流动的网格渐变。
 *
 * 底层是 Compose 官方的 [MeshGradientPainter]：网格顶点与颜色在 draw 阶段读取动画
 * 时间，色团沿贝塞尔曲面缓慢漂移；不需要自写 SkSL，也不依赖平台专用着色器。
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
 * ### 性能与帧率控制
 *
 * 动画时间靠物理时钟节拍分频驱动（见 [FluidFpsConfig]），最大帧率由用户设置
 * （15/30/60fps）。非渲染帧跳过 state 更新，使 Compose 智能抑制重绘，既省电又保持
 * 物理流动速度完全一致。
 *
 * 网格绘制后再叠一层静态加性噪声（每通道 0/1 LSB，Plus 混合）：8-bit SDR 帧缓冲
 * 上大面积暗色渐变的色带断层会被打散。这里不用 Hardlight —— 乘性抖动在暗部会被
 * 底色压到远小于 1 LSB，正巧是色带最明显的地方；加性噪声在亮部暗部都保持同样的
 * 量化步进，同时不会像动态噪声那样逐帧产生额外开销。
 *
 * @param enabled 设置项「流体背景」。false 时只画 [fallback]。
 * @param fallback 绘制网格前的静态底色，同时是 `enabled = false` 时的唯一背景。
 * @param speed 漂移速度。调大调小只影响时间推进，不重建 painter。
 * @param scale 色团尺度。**越大 → 色团越小**。
 * @param pauseShaderAnimation 为 true 时**冻结**流体画面：停止推进时间轴、停止请求新帧，
 *   但继续绘制当前这一帧（不会退回 [fallback]）。只用于页面转场的那几百毫秒 ——
 *   此时整页都在缩放 / 淡入淡出，背景再叠一层自己的动画既看不出来，又白烧一份绘制开销。
 *
 *   ⚠️ 转场结束必须立刻恢复 false。曾经把它反过来（转场结束才冻结）实现「保留最后一帧」，
 *   结果进播放页的 400ms 一结束，背景就永久停住 —— 用户看到的就是「流体背景完全不动」。
 */
@Composable
fun Modifier.cpFluidBackground(
    enabled: Boolean = true,
    fallback: Brush,
    speed: Float = CpFluidBackgroundDefaults.SPEED,
    scale: Float = CpFluidBackgroundDefaults.SCALE,
    pauseShaderAnimation: Boolean = false,
    palette: List<Color>? = null,
    coverUrl: String? = null,
): Modifier {
    if (!enabled) return this.background(fallback)

    val scheme = MaterialTheme.colorScheme
    val dark = LocalIsDarkTheme.current

    // ⚠️ `remember` 的 key 只能是**颜色本身**，不能是 `scheme`：`ColorScheme` 没重写
    // `equals`（见 TOPICS.md 那条），主题过渡期间每帧都是新实例 ⇒ 每帧重建。
    val primary = scheme.primary
    val base = if (dark) scheme.surfaceContainerLowest else scheme.surface

    val currentCoverPalette by AppModel.currentCoverFluidPalette.collectAsState()
    val customCoverPalette = remember(coverUrl, dark) {
        if (!coverUrl.isNullOrBlank()) {
            cp.player.app.ui.theme.CoverFluidPaletteCache.get(coverUrl, dark)
        } else null
    }

    val effectivePalette: List<Color> = remember(palette, customCoverPalette, currentCoverPalette, primary, base, dark) {
        val current = currentCoverPalette
        when {
            !palette.isNullOrEmpty() -> palette
            customCoverPalette != null -> customCoverPalette
            current != null -> current
            else -> buildFluidPalette(primary, base, dark)
        }
    }

    val targetFps by AppModel.fluidBackgroundFpsFlow.collectAsState()
    val fpsConfig = rememberFluidFpsConfig(targetFps)
    val settled = rememberSettledPalette(effectivePalette)
    val paused = pauseShaderAnimation || DesktopShell.isWindowHidden
    val time = rememberFluidTime(fpsConfig = fpsConfig, paused = paused)

    return this.cpMeshGradientBackground(
        palette = settled,
        time = time,
        speed = speed,
        scale = scale,
        fallback = fallback,
    )
}

/**
 * 导航转场期间冻结流体背景。
 *
 * 反转它（`!transitionIsRunning`）会让播放页一亮相就永久停在第一帧，
 * 这正是「流体背景完全不动」那个 bug 的形状 —— 别再改回去。
 */
internal fun shouldPauseFluidBackgroundDuringNavigation(transitionIsRunning: Boolean): Boolean =
    transitionIsRunning

/**
 * 用物理时钟节拍驱动网格时间；暂停时保留当前相位，恢复后从冻结点继续。
 *
 * 时间只被 [MeshGradientPainter] 的 draw block 读取，因此更新它只会让绘制失效，
 * 不会触发整棵 Compose 子树的重新组合。
 */
@Composable
private fun rememberFluidTime(fpsConfig: FluidFpsConfig, paused: Boolean): MutableState<Float> {
    val time = remember { mutableStateOf(0f) }
    LaunchedEffect(fpsConfig, paused) {
        if (paused) return@LaunchedEffect

        var frameCount = 0L
        var startNanos = 0L
        val resumeTime = time.value
        val divisor = fpsConfig.divisor.coerceAtLeast(1)
        while (isActive) {
            withInfiniteAnimationFrameNanos { frameTimeNanos ->
                if (startNanos == 0L) startNanos = frameTimeNanos
                frameCount++
                if (frameCount % divisor == 0L) {
                    time.value = resumeTime + (frameTimeNanos - startNanos) / 1_000_000_000f
                }
            }
        }
    }
    return time
}

/**
 * 网格渐变 + 静态加性抖动。
 *
 * 抖动是 128×128 的单通道噪声纹理，用 [BlendMode.Plus] 以约 50% 的概率给每个
 * 通道加 1 个 8-bit LSB。加法在暗部不会被底色衰减，因此恰好能覆盖相邻色带之间
 * 的那一步量化差；纹理静态生成，不需要逐帧计算。
 */
@Composable
private fun Modifier.cpMeshGradientBackground(
    palette: List<Color>,
    time: State<Float>,
    speed: Float,
    scale: Float,
    fallback: Brush,
): Modifier {
    val painter = remember(palette, scale, speed) {
        MeshGradientPainter(
            rows = FluidMeshRows,
            columns = FluidMeshColumns,
            hasBicubicColor = true,
        ) {
            configureFluidMesh(
                palette = palette,
                time = time.value * speed,
                scale = scale,
            )
        }
    }
    val dither = remember { createFluidDitherBrush() }

    return this.drawBehind {
        drawRect(fallback)
        with(painter) { draw(size) }
        drawRect(brush = dither, blendMode = BlendMode.Plus)
    }
}

private const val FluidMeshRows = 4
private const val FluidMeshColumns = 4

/**
 * 把 3 个色团铺到 5×5 顶点上。
 *
 * 顶点颜色由「底色 + 三个移动色团的距离权重」算出，位置只做轻微漂移；边缘顶点固定，
 * 保证网格始终铺满视口。`hasBicubicColor = true` 会把相邻顶点的颜色做成平滑贝塞尔
 * 插值，避免出现一块块多边形的色阶。
 */
private fun MeshGradientScope.configureFluidMesh(
    palette: List<Color>,
    time: Float,
    scale: Float,
) {
    if (palette.isEmpty()) return
    val base = palette.last()
    val blobs = palette.dropLast(1)
    val reach = (1.5f / scale.coerceAtLeast(0.1f)).coerceIn(0.45f, 1.2f)
    val drift = time * 0.12f

    for (row in 0..rows) {
        for (column in 0..columns) {
            val x = column / columns.toFloat()
            val y = row / rows.toFloat()
            val edgeX = column == 0 || column == columns
            val edgeY = row == 0 || row == rows
            val px = (x + if (edgeX) 0f else sin(drift + row * 1.7f + column * 2.3f) * 0.035f)
                .coerceIn(0f, 1f)
            val py = (y + if (edgeY) 0f else cos(drift * 0.83f + row * 2.1f + column * 1.3f) * 0.035f)
                .coerceIn(0f, 1f)

            var vertexColor = base
            blobs.forEachIndexed { index, blob ->
                val phase = index * 2.0943952f + time * 0.05f
                val centerX = 0.5f + cos(phase) * 0.27f
                val centerY = 0.5f + sin(phase * 1.31f) * 0.23f
                val dx = (x - centerX) / reach
                val dy = (y - centerY) / reach
                val distance = sqrt(dx * dx + dy * dy)
                val weight = (1f - distance).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                vertexColor = lerp(vertexColor, blob, weight * 0.72f)
            }

            setVertex(row, column, Offset(px, py), vertexColor)
        }
    }
}

/**
 * 生成一次性加性噪声纹理：每个像素以 50% 概率为 1 LSB，否则为 0。
 *
 * 用 [BlendMode.Plus] 叠加时，1 LSB 的随机扰动刚好等于 8-bit 相邻色带的量化差；
 * 128×128 平铺后在 8-bit SDR 大面积平滑渐变上足以打碎色带，又不会形成可见的
 * 颗粒纹理。颜色取 RGB(1,1,1)：预乘后每个颜色通道正好是 1/255。
 */
internal fun createFluidDitherBrush(): Brush {
    val size = 128
    val bitmap = ImageBitmap(size, size)
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(bitmap),
        size = Size(size.toFloat(), size.toFloat()),
    ) {
        val random = Random(0x5EED)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val step = random.nextInt(2)
                drawRect(
                    color = Color(step, step, step),
                    topLeft = Offset(x.toFloat(), y.toFloat()),
                    size = Size(1f, 1f),
                )
            }
        }
    }
    return ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
}

/**
 * 流体背景的默认参数。
 *
 * 抽成 object 是为了让「桌面播放页」与「窄屏播放页」用同一组值 —— 两套布局是互斥
 * 分支，各写一份常量迟早会漂移成「同一个功能在两种窗口宽度下动得不一样快」。
 */
object CpFluidBackgroundDefaults {
    /** Apple Music 的背景是「几乎察觉不到在动」，所以在默认速度上再慢一档。 */
    const val SPEED = 0.6f

    /**
     * 色团尺度：越大 → 色团越小。2f 时 1320×860 上约有两三个大色团。
     */
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
//
// 三个色团之间也要**互相**拉开：色相各自偏一档、明暗错开、彩度不同。三个色团聚成
// 同一 tone / chroma 时，噪声混完只剩一块色相渐变，深度和「体积感」全没了。
// ---------------------------------------------------------------------------

/**
 * 三个色团相对主题主色相的偏移（度）。
 * 采用谐调三色分布（-42°, +10°, +46°），形成自然有层次的多相流动。
 */
private val BlobHueOffsets = doubleArrayOf(-42.0, 10.0, 46.0)

/**
 * 深色主题：提升色团通透鲜活度（Tone 42~54，Chroma 66~82），底色采用幽邃纯黑氛围夜底（Tone 11）。
 * 摆脱了原本 Monet 体系下极低明度（28~32）加泥灰色 surfaceContainerLowest 造成的死黑灰泥感。
 */
private val DarkBlobTones = doubleArrayOf(48.0, 54.0, 42.0)
private val DarkBlobChromas = doubleArrayOf(74.0, 82.0, 66.0)

/**
 * 浅色主题：水彩柔润透光，高对比可读。
 */
private val LightBlobTones = doubleArrayOf(78.0, 84.0, 74.0)
private val LightBlobChromas = doubleArrayOf(44.0, 36.0, 48.0)

/**
 * 主题色 → 默认 Apple 风格网格色板（无封面或取色未就绪时的回退）。
 *
 * ⚠️ 顺序有意义：painter 把**最后一个**色当作「底」，前面几个按距离权重混进去。底色必须排在末位。
 *
 * @param primary 主题主色，只取它的**色相**。
 * @param base 底色参数保留兼容。
 */
private fun buildFluidPalette(primary: Color, base: Color, dark: Boolean): List<Color> {
    val hue = Hct.fromInt(primary.toArgb()).hue
    val tones = if (dark) DarkBlobTones else LightBlobTones
    val chromas = if (dark) DarkBlobChromas else LightBlobChromas
    val effectiveBase = if (dark) {
        blobColor(hue, 18.0, 11.0)
    } else {
        blobColor(hue, 4.0, 97.5)
    }
    return List(tones.size) { i -> blobColor(hue + BlobHueOffsets[i], chromas[i], tones[i]) } + effectiveBase
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
 * 等色板稳定后再交给 painter —— 挡掉**主题过渡期间**的颜色抖动。
 *
 * `CpTheme` 换配色时会把整套 `ColorScheme` 逐帧 lerp 600ms（见 `Theme.kt` 的
 * `ColorTransitionMillis`）。若直接拿 `MaterialTheme.colorScheme` 当 `remember` 的 key，
 * 这 600ms 里每一帧都会命中一个新 key ⇒ 重新创建 painter 并重配整张网格。
 * 表现就是「换封面时播放页卡一下」。
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
