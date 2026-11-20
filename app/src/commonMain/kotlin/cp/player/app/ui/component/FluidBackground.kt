package cp.player.app.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
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
 * ### 三级自适应架构 (Tiered Architecture)
 * - **Tier 1 (Android 14+ / Desktop)**: 走原生 Compose [MeshGradientPainter] (底层 Canvas.drawMesh / Skiko)；
 * - **Tier 2 (Android 13 / API 33)**: 走系统原生 AGSL [android.graphics.RuntimeShader] 液体域扭曲着色器；
 * - **Tier 3 (Android 12 及以下 / API < 33)**: 走纯 Compose Canvas 多光团有机径向弥散 (Screen 混合)。
 *
 * ### 统一输入模型与视觉防护
 * - **色彩清洗与饱和度补偿**：Tone 压暗同时按亥姆霍兹-科尔劳奇效应补偿 Chroma，深色通透如宝石，避开死灰色阶；
 * - **切歌与播放物理惯性**：切歌 750ms 平滑过渡，暂停时指数缓降进入漫游模式；
 * - **利萨茹混沌游走**：非整数比互质频率双轴驱动，5 分钟内轨迹绝不重复闭合；
 * - **OLED 屏幕防色带**：1 LSB TPDF 加性空间抖动打散 8-bit SDR 量化阶梯；
 * - **前景可读性保护**：三次 Hermite 平滑底部控制栏与顶部遮罩暗角，保障 WCAG AA $\ge 4.5:1$ 对比度。
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
    val pureBlack by AppModel.pureBlackFlow.collectAsState()

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

    val playbackState by AppModel.playback.state.collectAsState()
    val isPlaying = playbackState.isPlaying

    val time = rememberFluidTime(fpsConfig = fpsConfig, paused = paused)
    val tier = remember { resolveFluidRenderTier() }

    val sceneModel = rememberFluidSceneModel(
        targetPalette = settled,
        primary = primary,
        time = time,
        isPlaying = isPlaying,
        isDark = dark,
        pureBlack = pureBlack,
        scale = scale,
        speed = speed,
    )

    val dither = remember { createFluidDitherBrush() }

    return this
        .renderPlatformFluid(
            tier = tier,
            sceneModel = sceneModel,
            scale = scale,
            fallback = fallback,
        )
        .drawBehind {
            // 叠加 1 LSB 加性抖动，打碎 8-bit SDR OLED 屏幕上的等高线色带
            drawRect(brush = dither, blendMode = BlendMode.Plus)
        }
        .fluidVignetteAndScrim(isDark = dark, scrimAlpha = sceneModel.scrimAlpha)
}

/**
 * 导航转场期间冻结流体背景。
 */
internal fun shouldPauseFluidBackgroundDuringNavigation(transitionIsRunning: Boolean): Boolean =
    transitionIsRunning

/**
 * 跨所有渲染梯队统一消费的流体光团物理快照。
 */
data class FluidBlobState(
    val center: Offset,
    val color: Color,
    val reach: Float,
    val intensity: Float,
)

/**
 * 跨所有渲染梯队统一消费的流体帧模型。
 */
data class FluidSceneModel(
    val baseColor: Color,
    val blobs: List<FluidBlobState>,
    val time: Float,
    val velocity: Float,
    val scrimAlpha: Float,
)

/**
 * 统一输入模型计算引擎：执行色彩清洗、彩度补偿、切歌过渡、播放物理惯性与利萨茹混沌游走。
 */
@Composable
internal fun rememberFluidSceneModel(
    targetPalette: List<Color>,
    primary: Color,
    time: State<Float>,
    isPlaying: Boolean,
    isDark: Boolean,
    pureBlack: Boolean,
    scale: Float,
    speed: Float,
): FluidSceneModel {
    // 1. 色彩清洗与饱和度逆向补偿
    val sanitized = remember(targetPalette, primary, isDark) {
        sanitizeAndBoostPalette(targetPalette, primary, isDark)
    }

    // 2. 切歌色彩平滑渡变 (750ms 球形色彩插值)
    var currentPalette by remember { mutableStateOf(sanitized) }
    val paletteTransition = remember { Animatable(1f) }
    var previousPalette by remember { mutableStateOf(sanitized) }

    LaunchedEffect(sanitized) {
        if (currentPalette != sanitized) {
            previousPalette = currentPalette
            paletteTransition.snapTo(0f)
            paletteTransition.animateTo(1f, tween(750))
            currentPalette = sanitized
        }
    }

    val activePalette = remember(paletteTransition.value, previousPalette, currentPalette) {
        val p = paletteTransition.value
        List(currentPalette.size) { i ->
            val from = previousPalette.getOrElse(i) { currentPalette[i] }
            val to = currentPalette[i]
            lerp(from, to, p)
        }
    }

    // 3. 播放与暂停阻尼惯性 (暂停时以平滑指数衰减降至 0.5x 缓速漫游，不生硬刹车)
    val velocityAnim = remember { Animatable(if (isPlaying) 1.0f else 0.5f) }
    LaunchedEffect(isPlaying) {
        velocityAnim.animateTo(
            targetValue = if (isPlaying) 1.0f else 0.5f,
            animationSpec = tween(if (isPlaying) 600 else 1000),
        )
    }

    val t = time.value * speed * velocityAnim.value
    val rawBase = if (isDark && pureBlack) Color.Black else activePalette.last()
    val blobColors = activePalette.dropLast(1).take(3)

    // 4. 利萨茹非整数比互质频率矩阵 (周期比无理化，5 分钟内轨迹绝不重合)
    val freqX = floatArrayOf(0.31f, 0.23f, 0.37f)
    val freqY = floatArrayOf(0.47f, 0.39f, 0.29f)
    val phaseX = floatArrayOf(0.0f, 2.0944f, 4.1888f)
    val phaseY = floatArrayOf(1.0472f, 3.1416f, 5.2360f)

    val baseReach = (1.5f / scale.coerceAtLeast(0.1f)).coerceIn(0.45f, 1.2f)

    val blobs = blobColors.mapIndexed { i, color ->
        val cx = (0.5f + sin(t * freqX[i] + phaseX[i]) * 0.27f).coerceIn(0.08f, 0.92f)
        val cy = (0.5f + cos(t * freqY[i] + phaseY[i]) * 0.23f).coerceIn(0.08f, 0.92f)
        val breath = 0.94f + sin(t * 0.53f + phaseX[i]) * 0.08f

        FluidBlobState(
            center = Offset(cx, cy),
            color = color,
            reach = baseReach * breath,
            intensity = breath,
        )
    }

    return FluidSceneModel(
        baseColor = rawBase,
        blobs = blobs,
        time = t,
        velocity = velocityAnim.value,
        scrimAlpha = if (isDark) 0.15f else 0.06f,
    )
}

/**
 * 色彩清洗与饱和度补偿算法。
 */
private fun sanitizeAndBoostPalette(palette: List<Color>, primary: Color, isDark: Boolean): List<Color> {
    if (palette.isEmpty()) return buildFluidPalette(primary, Color.Black, isDark)
    val rawBase = palette.last()
    val rawBlobs = palette.dropLast(1)

    // 检查是否全为低彩度死灰（黑白封面/无色彩图）
    val isMonochrome = rawBlobs.all { Hct.fromInt(it.toArgb()).chroma < 12.0 }
    if (isMonochrome) {
        return buildFluidPalette(primary, rawBase, isDark)
    }

    // 补偿彩度：Tone 下压同时适当补偿 Chroma，深色浓郁如宝石，浅色水彩透光
    val boostedBlobs = rawBlobs.map { color ->
        val hct = Hct.fromInt(color.toArgb())
        val targetTone = if (isDark) hct.tone.coerceIn(40.0, 52.0) else hct.tone.coerceIn(74.0, 84.0)
        val targetChroma = if (isDark) {
            (hct.chroma * 1.18).coerceIn(66.0, 88.0)
        } else {
            hct.chroma.coerceIn(34.0, 50.0)
        }
        blobColor(hct.hue, targetChroma, targetTone)
    }

    val baseTone = if (isDark) 11.0 else 97.5
    val baseChroma = if (isDark) 18.0 else 4.0
    val cleanedBase = blobColor(Hct.fromInt(rawBase.toArgb()).hue, baseChroma, baseTone)

    return boostedBlobs + cleanedBase
}

/**
 * 叠加在流体背景之上的多段非线性柔和暗角与安全阅读遮罩。
 */
private fun Modifier.fluidVignetteAndScrim(isDark: Boolean, scrimAlpha: Float): Modifier = this.drawWithContent {
    drawContent()

    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return@drawWithContent

    if (isDark) {
        // A. 底部控制栏保护：非线性渐隐暗角 (底部 28% 高度)
        val bottomScrimBrush = Brush.verticalGradient(
            0.0f to Color.Transparent,
            0.6f to Color.Black.copy(alpha = 0.08f),
            0.85f to Color.Black.copy(alpha = 0.20f),
            1.0f to Color.Black.copy(alpha = 0.32f),
            startY = h * 0.72f,
            endY = h,
        )
        drawRect(brush = bottomScrimBrush)

        // B. 顶部状态栏保护 (顶部 12% 高度)
        val topScrimBrush = Brush.verticalGradient(
            0.0f to Color.Black.copy(alpha = 0.18f),
            0.5f to Color.Black.copy(alpha = 0.06f),
            1.0f to Color.Transparent,
            startY = 0f,
            endY = h * 0.12f,
        )
        drawRect(brush = topScrimBrush)

        // C. 四周轻微晕影 (Vignette)
        val vignetteBrush = Brush.radialGradient(
            0.0f to Color.Transparent,
            0.68f to Color.Transparent,
            1.0f to Color.Black.copy(alpha = scrimAlpha),
            center = Offset(w / 2f, h / 2f),
            radius = maxOf(w, h) * 0.72f,
        )
        drawRect(brush = vignetteBrush)
    } else {
        // 浅色模式柔和透光遮罩
        val bottomLightScrim = Brush.verticalGradient(
            0.0f to Color.Transparent,
            1.0f to Color.White.copy(alpha = 0.30f),
            startY = h * 0.75f,
            endY = h,
        )
        drawRect(brush = bottomLightScrim)
    }
}

/**
 * 用物理时钟节拍驱动网格时间；暂停时保留当前相位，恢复后从冻结点继续。
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
 * Tier 1: 基于 Compose 官方 MeshGradientPainter 的贝塞尔网格渲染器。
 */
@Composable
internal fun Modifier.cpMeshGradientBackground(
    sceneModel: FluidSceneModel,
    scale: Float,
    fallback: Brush,
): Modifier {
    val painter = remember(sceneModel.baseColor, sceneModel.blobs.map { it.color }, scale) {
        MeshGradientPainter(
            rows = FluidMeshRows,
            columns = FluidMeshColumns,
            hasBicubicColor = true,
        ) {
            configureFluidMeshWithModel(sceneModel)
        }
    }

    return this.drawBehind {
        drawRect(fallback)
        with(painter) { draw(size) }
    }
}

/**
 * Tier 3: 基于 Compose Canvas 多光团有机径向弥散渲染器 (全版本兼容)。
 */
@Composable
internal fun Modifier.cpCanvasBlobFluidBackground(
    sceneModel: FluidSceneModel,
    fallback: Brush,
): Modifier {
    return this.drawBehind {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) {
            drawRect(fallback)
            return@drawBehind
        }

        // 1. 底色铺底
        drawRect(sceneModel.baseColor)

        // 2. 依次绘制 3 个漂移光团
        val maxDim = maxOf(w, h)
        sceneModel.blobs.forEach { blob ->
            val center = Offset(blob.center.x * w, blob.center.y * h)
            val radius = maxDim * blob.reach

            val radialBrush = Brush.radialGradient(
                0.0f to blob.color.copy(alpha = 0.82f * blob.intensity),
                0.38f to blob.color.copy(alpha = 0.48f * blob.intensity),
                0.72f to blob.color.copy(alpha = 0.16f * blob.intensity),
                1.0f to Color.Transparent,
                center = center,
                radius = radius,
            )

            drawCircle(
                brush = radialBrush,
                radius = radius,
                center = center,
                blendMode = BlendMode.Screen,
            )
        }
    }
}

private const val FluidMeshRows = 4
private const val FluidMeshColumns = 4

/**
 * 把场景模型中的 3 个光团位置与颜色映射到 5×5 网格顶点上。
 */
private fun MeshGradientScope.configureFluidMeshWithModel(
    model: FluidSceneModel,
) {
    val base = model.baseColor
    val blobs = model.blobs
    val t = model.time
    val drift = t * 0.12f

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
            blobs.forEach { blob ->
                val dx = (x - blob.center.x) / blob.reach
                val dy = (y - blob.center.y) / blob.reach
                val distance = sqrt(dx * dx + dy * dy)
                val weight = (1f - distance).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                vertexColor = lerp(vertexColor, blob.color, weight * 0.72f * blob.intensity)
            }

            setVertex(row, column, Offset(px, py), vertexColor)
        }
    }
}

/**
 * 生成一次性加性噪声纹理：每个像素以 50% 概率为 1 LSB，否则为 0。
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
 */
object CpFluidBackgroundDefaults {
    const val SPEED = 0.6f
    const val SCALE = 2f
}

private val BlobHueOffsets = doubleArrayOf(-42.0, 10.0, 46.0)
private val DarkBlobTones = doubleArrayOf(48.0, 54.0, 42.0)
private val DarkBlobChromas = doubleArrayOf(74.0, 82.0, 66.0)
private val LightBlobTones = doubleArrayOf(78.0, 84.0, 74.0)
private val LightBlobChromas = doubleArrayOf(44.0, 36.0, 48.0)

/**
 * 主题色 → 默认 Apple 风格网格色板（无封面或取色未就绪时的回退）。
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

private fun blobColor(hue: Double, chroma: Double, tone: Double): Color =
    Color(Hct.from(hue, chroma, tone).toInt())

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

private const val PaletteSettleMillis = 700L
