package cp.player.app.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
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
 * - **切歌与播放物理惯性**：切歌 750ms 连续插值过渡；暂停时速度系数平滑降到 0.5x，
 *   相位按每帧速度**累加**，不会因为系数变小而倒带；
 * - **利萨茹混沌游走**：非整数比互质频率双轴驱动，组合周期以十分钟计；
 * - **OLED 屏幕防色带**：1 LSB 加性空间抖动（均匀分布，非 TPDF）打散 8-bit SDR 量化阶梯；
 * - **前景可读性保护**：底栏 28% 非线性渐隐 + 四周暗角抬升高光区的文字对比；
 *   未做逐像素 WCAG 测量，底部遮罩 alpha 0.32 是经验值。
 *
 * @param palette 覆盖色板（调用方已取好色时传入）。
 * @param coverUrl 封面地址，用于取封面派生色板。
 * @param playing 固定播放状态；传 null 时跟随播放器。桌面单测进程不初始化
 *   `MusicBackend`，直接读 `AppModel.playback` 会抛，测试用这个参数注入。
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
    playing: Boolean? = null,
): Modifier {
    if (!enabled) return this.background(fallback)

    val scheme = MaterialTheme.colorScheme
    val dark = LocalIsDarkTheme.current
    val pureBlack by AppModel.pureBlackFlow.collectAsState()

    val primary = scheme.primary
    val base = if (dark) scheme.surfaceContainerLowest else scheme.surface

    val currentCoverPalette by AppModel.currentCoverFluidPalette.collectAsState()
    // currentCoverPalette 必须参与 key：封面是异步解码的，首次组合时缓存必然 miss，
    // 只按 coverUrl 记忆会让 customCoverPalette 永远停在 null，浅色模式就取不到
    // 对应的浅色色盘，只能拿深色盘硬映射。
    val customCoverPalette = remember(coverUrl, dark, currentCoverPalette) {
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

    val isPlaying = playing ?: rememberPlaybackIsPlaying()

    // 速度系数先于时间轴建立：相位在每一帧按当时的速度累加。
    // 原先用「累计秒数 × 当前速度」，暂停时系数从 1.0 降到 0.5，
    // 乘积瞬间减半 ⇒ 色团整体倒退一段，像是背景倒带。
    val velocity = rememberFluidVelocity(isPlaying)
    val time = rememberFluidTime(
        fpsConfig = fpsConfig,
        paused = paused,
        speed = speed,
        velocity = velocity,
    )
    val tier = remember { resolveFluidRenderTier() }

    val sceneModel = rememberFluidSceneModel(
        targetPalette = settled,
        primary = primary,
        time = time,
        isDark = dark,
        pureBlack = pureBlack,
        scale = scale,
    )

    val dither = remember { createFluidDitherBrush() }

    return this
        .renderPlatformFluid(
            tier = tier,
            sceneModel = sceneModel,
            fallback = fallback,
        )
        .drawBehind {
            // 叠加 1 LSB 加性抖动，打碎 8-bit SDR OLED 屏幕上的等高线色带
            drawRect(brush = dither, blendMode = BlendMode.Plus)
        }
        .fluidVignetteAndScrim(isDark = dark)
}

/**
 * 读取播放器当前是否在播放。
 *
 * 单独抽出来是为了让测试能用 [cpFluidBackground] 的 `playing` 参数短路这条依赖：
 * 桌面单测进程不初始化 `MusicBackend`，读 `AppModel.playback` 会直接抛。
 */
@Composable
private fun rememberPlaybackIsPlaying(): Boolean {
    val state by AppModel.playback.state.collectAsState()
    return state.isPlaying
}

/**
 * 导航转场期间冻结流体背景。
 */
internal fun shouldPauseFluidBackgroundDuringNavigation(transitionIsRunning: Boolean): Boolean =
    transitionIsRunning

/**
 * 跨所有渲染梯队统一消费的流体光团物理快照（原地更新，核心渲染循环 0 分配）。
 */
class FluidBlobState(
    var centerX: Float = 0.5f,
    var centerY: Float = 0.5f,
    var color: Color = Color.Black,
    var reach: Float = 1f,
    var intensity: Float = 1f,
) {
    val center: Offset get() = Offset(centerX, centerY)
}

/**
 * 跨所有渲染梯队统一消费的流体帧模型（原地更新，核心渲染循环 0 分配）。
 */
class FluidSceneModel(
    var baseColor: Color,
    val blobs: List<FluidBlobState>,
    var time: Float,
)

@Stable
private class LiveFluidSceneState(
    private val timeState: FloatState,
    private val paletteProgressState: State<Float>,
    initialPalette: List<Color>,
    var isDark: Boolean,
    var pureBlack: Boolean,
    var scale: Float,
) : State<FluidSceneModel> {
    var fromPalette: List<Color> = initialPalette
    var toPalette: List<Color> = initialPalette

    private val workingBlobs = listOf(
        FluidBlobState(),
        FluidBlobState(),
        FluidBlobState(),
    )
    private val scene = FluidSceneModel(
        baseColor = Color.Black,
        blobs = workingBlobs,
        time = 0f,
    )

    override val value: FluidSceneModel
        get() {
            // 在 Draw 阶段读取 Snapshot 状态，确保每帧按 VSync 重绘且不触发重组，同时零对象分配。
            val t = timeState.floatValue
            val fraction = paletteProgressState.value
            val from = fromPalette
            val to = toPalette

            val baseSource = resolveBlendedPaletteColor(from, to, fraction, 3)
            scene.baseColor = if (isDark && pureBlack) Color.Black else baseSource
            scene.time = t

            // 利萨茹双轴游走：0.31/0.23/0.37 与 0.47/0.39/0.29 互质，组合周期以十分钟计。
            val baseReach = (1.5f / scale.coerceAtLeast(0.1f)).coerceIn(0.45f, 1.2f)
            for (i in 0..2) {
                val blob = workingBlobs[i]
                blob.centerX = (0.5f + sin(t * FluidBlobFreqX[i] + FluidBlobPhaseX[i]) * 0.27f)
                    .coerceIn(0.08f, 0.92f)
                blob.centerY = (0.5f + cos(t * FluidBlobFreqY[i] + FluidBlobPhaseY[i]) * 0.23f)
                    .coerceIn(0.08f, 0.92f)
                val breath = 0.94f + sin(t * 0.53f + FluidBlobPhaseX[i]) * 0.08f
                blob.color = resolveBlendedPaletteColor(from, to, fraction, i)
                blob.reach = baseReach * breath
                blob.intensity = breath
            }
            return scene
        }

    fun currentBlendedPaletteSnapshot(): List<Color> {
        val fraction = paletteProgressState.value
        val from = fromPalette
        val to = toPalette
        if (fraction <= 0f) return from
        if (fraction >= 1f) return to
        val size = maxOf(from.size, to.size)
        return List(size) { i -> resolveBlendedPaletteColor(from, to, fraction, i) }
    }
}

private fun resolveBlendedPaletteColor(
    from: List<Color>,
    to: List<Color>,
    fraction: Float,
    index: Int,
): Color {
    if (fraction <= 0f) {
        return from.getOrNull(index) ?: from.lastOrNull() ?: to.getOrNull(index) ?: Color.Transparent
    }
    if (fraction >= 1f) {
        return to.getOrNull(index) ?: to.lastOrNull() ?: from.getOrNull(index) ?: Color.Transparent
    }
    val a = from.getOrNull(index) ?: from.lastOrNull() ?: to.getOrNull(index) ?: Color.Transparent
    val b = to.getOrNull(index) ?: to.lastOrNull() ?: a
    return lerp(a, b, fraction)
}

/**
 * 统一输入模型计算引擎：执行色彩清洗、彩度补偿、切歌过渡、播放物理惯性与利萨茹混沌游走。
 */
@Composable
private fun rememberFluidSceneModel(
    targetPalette: List<Color>,
    primary: Color,
    time: FloatState,
    isDark: Boolean,
    pureBlack: Boolean,
    scale: Float,
): State<FluidSceneModel> {
    // 1. 色彩清洗与饱和度逆向补偿
    val sanitized = remember(targetPalette, primary, isDark) {
        sanitizeAndBoostPalette(targetPalette, primary, isDark)
    }

    val paletteProgress = remember { Animatable(1f) }
    val sceneState = remember(time) {
        LiveFluidSceneState(
            timeState = time,
            paletteProgressState = paletteProgress.asState(),
            initialPalette = sanitized,
            isDark = isDark,
            pureBlack = pureBlack,
            scale = scale,
        )
    }
    sceneState.isDark = isDark
    sceneState.pureBlack = pureBlack
    sceneState.scale = scale

    // 2. 切歌色彩连续过渡：从当前可见色接住，快速连切不跳变。
    LaunchedEffect(sanitized) {
        if (sceneState.toPalette == sanitized) return@LaunchedEffect
        sceneState.fromPalette = sceneState.currentBlendedPaletteSnapshot()
        sceneState.toPalette = sanitized
        paletteProgress.snapTo(0f)
        paletteProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(PaletteTransitionMillis, easing = FastOutSlowInEasing),
        )
    }

    return sceneState
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
private fun Modifier.fluidVignetteAndScrim(isDark: Boolean): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return@drawWithCache onDrawWithContent { drawContent() }

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
        // B. 顶部状态栏保护 (顶部 12% 高度)
        val topScrimBrush = Brush.verticalGradient(
            0.0f to Color.Black.copy(alpha = 0.18f),
            0.5f to Color.Black.copy(alpha = 0.06f),
            1.0f to Color.Transparent,
            startY = 0f,
            endY = h * 0.12f,
        )
        // C. 四周轻微晕影 (Vignette)
        val vignetteBrush = Brush.radialGradient(
            0.0f to Color.Transparent,
            0.68f to Color.Transparent,
            1.0f to Color.Black.copy(alpha = 0.15f),
            center = Offset(w / 2f, h / 2f),
            radius = maxOf(w, h) * 0.72f,
        )
        onDrawWithContent {
            drawContent()
            drawRect(brush = bottomScrimBrush)
            drawRect(brush = topScrimBrush)
            drawRect(brush = vignetteBrush)
        }
    } else {
        // 浅色模式柔和透光遮罩
        val bottomLightScrim = Brush.verticalGradient(
            0.0f to Color.Transparent,
            1.0f to Color.White.copy(alpha = 0.30f),
            startY = h * 0.75f,
            endY = h,
        )
        onDrawWithContent {
            drawContent()
            drawRect(brush = bottomLightScrim)
        }
    }
}

/**
 * 播放 / 暂停的速度系数：播放 1.0，暂停 0.5，切换时平滑过渡。
 */
@Composable
private fun rememberFluidVelocity(isPlaying: Boolean): Animatable<Float, AnimationVector1D> {
    val velocity = remember { Animatable(if (isPlaying) 1f else FluidIdleVelocity) }
    LaunchedEffect(isPlaying) {
        val target = if (isPlaying) 1f else FluidIdleVelocity
        // 已经是目标值时不要启动动画：Animatable.animateTo 即使数值不变也会
        // 占着动画帧循环，暂停/冻结场景会因此一直请求新帧、白烧 GPU。
        if (velocity.value == target) return@LaunchedEffect
        velocity.animateTo(
            targetValue = target,
            animationSpec = tween(
                durationMillis = if (isPlaying) 600 else 1000,
                easing = FastOutSlowInEasing,
            ),
        )
    }
    return velocity
}

/**
 * 用物理时钟节拍推进相位；暂停时保留当前相位，恢复后从冻结点继续。
 *
 * 相位按「每帧真实间隔 × 当时速度 × 全局速度」累加，而不是拿累计时间乘速度：
 * 后者在速度系数下降的瞬间会把相位一起缩小，视觉上就是色团倒带。
 */
@Composable
private fun rememberFluidTime(
    fpsConfig: FluidFpsConfig,
    paused: Boolean,
    speed: Float,
    velocity: Animatable<Float, AnimationVector1D>,
): FloatState {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(fpsConfig, paused, speed) {
        if (paused) return@LaunchedEffect

        var frameCount = 0L
        var lastNanos = 0L
        var pendingSeconds = 0f
        var accumulated = phase.floatValue
        val divisor = fpsConfig.divisor.coerceAtLeast(1)
        while (isActive) {
            withInfiniteAnimationFrameNanos { frameTimeNanos ->
                if (lastNanos == 0L) lastNanos = frameTimeNanos
                val delta = (frameTimeNanos - lastNanos) / 1_000_000_000f
                lastNanos = frameTimeNanos
                // 首次采样与长时间挂起后不要吃掉一大段相位，否则恢复时色团会跳很远。
                pendingSeconds += delta.coerceIn(0f, MaxFrameDeltaSeconds)
                frameCount++
                if (frameCount % divisor == 0L) {
                    accumulated += pendingSeconds * speed * velocity.value
                    pendingSeconds = 0f
                    phase.floatValue = accumulated
                }
            }
        }
    }
    return phase
}

/**
 * mesh 离屏绘制的降采样因子。渐变是极低频信号，1/4 尺寸绘制再双线性放大
 * 肉眼无差，但顶点索引数 ÷16，直接消掉 Compose mesh 渲染器逐顶点装箱
 * （Integer.valueOf）带来的巨量 young GC。见下方 draw 块内注释里的 JFR 数据。
 */
private const val FluidMeshLodFactor = 4
private const val FluidMeshMaxRasterSide = 320f
private val FluidBlobFreqX = floatArrayOf(0.31f, 0.23f, 0.37f)
private val FluidBlobFreqY = floatArrayOf(0.47f, 0.39f, 0.29f)
private val FluidBlobPhaseX = floatArrayOf(0.0f, 2.0944f, 4.1888f)
private val FluidBlobPhaseY = floatArrayOf(1.0472f, 3.1416f, 5.2360f)

/** Rebuild the mesh image only when its inputs change. */
@Composable
internal fun Modifier.cpMeshGradientBackground(
    sceneModel: State<FluidSceneModel>,
): Modifier {
    val painter = remember(sceneModel) {
        MeshGradientPainter(
            rows = FluidMeshRows,
            columns = FluidMeshColumns,
            hasBicubicColor = true,
        ) {
            configureFluidMeshWithModel(sceneModel.value)
        }
    }
    val buffer = remember { FluidMeshLodBuffer() }

    // ⚠️ 必须离屏降采样后放大，不能直接 `with(painter) { draw(size) }`。
    //
    // MeshGradientPainter 的细分密度由 MeshGradientTessellationKt 的
    // TargetPxPerSegment=8f 决定并 clamp 到 4..64 段：2560×1440 下每 patch
    // 64×64 段，4×4 网格 = 9 patch ⇒ 一帧约 20 万个顶点索引，而
    // DefaultMeshGradientRenderer.drawTriangles 逐个装箱成 ArrayList<Integer>，
    // hasBicubicColor 的 Catmull-Rom 插值还逐顶点做色域转换。
    //
    // 2026-11-21 JFR 实测（2560×1440，播放页静止，25s）：
    //   分配 252MB/s，98% Integer.valueOf(thread=main)；young GC 13.1 次/s，
    //   平均 3.09ms，合计 40.6ms/s 停顿。1/4 绘制成本 ÷16。
    // 位图按尺寸复用：每帧重建 ImageBitmap 会把省下的顶点开销又还回去。
    return drawWithCache {
        val model = sceneModel.value
        // Bound triangle generation on large windows and keep the raster's aspect ratio.
        val divisor = maxOf(FluidMeshLodFactor.toFloat(), size.maxDimension / FluidMeshMaxRasterSide)
        val lodWidth = (size.width / divisor).toInt().coerceAtLeast(64)
        val lodHeight = (size.height / divisor).toInt().coerceAtLeast(64)
        val lodSize = Size(lodWidth.toFloat(), lodHeight.toFloat())
        val bitmap = buffer.obtain(lodWidth, lodHeight)
        buffer.drawScope.draw(
            density = this,
            layoutDirection = layoutDirection,
            canvas = buffer.canvas,
            size = lodSize,
        ) {
            drawRect(model.baseColor)
            with(painter) { draw(lodSize) }
        }
        val destination = IntSize(size.width.toInt(), size.height.toInt())
        onDrawBehind { drawImage(bitmap, dstSize = destination) }
    }
}

/** 低分辨率网格位图：只在尺寸变化时重建，其余帧原地重绘。 */
private class FluidMeshLodBuffer {
    private var bitmap: ImageBitmap? = null
    lateinit var canvas: Canvas
        private set
    val drawScope = CanvasDrawScope()

    fun obtain(width: Int, height: Int): ImageBitmap {
        val current = bitmap
        if (current != null && current.width == width && current.height == height) return current
        return ImageBitmap(width, height).also {
            bitmap = it
            canvas = Canvas(it)
        }
    }
}

/**
 * Tier 3: 基于 Compose Canvas 多光团有机径向弥散渲染器 (全版本兼容，缓存单位径向 Brush 实现 0 分配)。
 */
@Composable
internal fun Modifier.cpCanvasBlobFluidBackground(
    sceneModel: State<FluidSceneModel>,
    fallback: Brush,
): Modifier {
    val brushCache = remember { CanvasBlobBrushCache() }
    return this.drawBehind {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) {
            drawRect(fallback)
            return@drawBehind
        }

        // 1. 底色铺底
        val model = sceneModel.value
        drawRect(model.baseColor)

        // 2. 依次绘制 3 个漂移光团（色值未变时直接复用 100px 单位径向 Brush，仅通过平移缩放绘制）
        val maxDim = maxOf(w, h)
        val blobs = model.blobs
        for (i in 0 until blobs.size) {
            val blob = blobs[i]
            val cx = blob.center.x * w
            val cy = blob.center.y * h
            val radius = maxDim * blob.reach
            val unitScale = radius / CanvasBlobUnitRadius
            val radialBrush = brushCache.obtain(i, blob.color)

            translate(left = cx, top = cy) {
                scale(scaleX = unitScale, scaleY = unitScale, pivot = Offset.Zero) {
                    drawCircle(
                        brush = radialBrush,
                        radius = CanvasBlobUnitRadius,
                        center = Offset.Zero,
                        alpha = blob.intensity.coerceIn(0f, 1f),
                        blendMode = BlendMode.Screen,
                    )
                }
            }
        }
    }
}

private const val CanvasBlobUnitRadius = 100f

private class CanvasBlobBrushCache {
    private val lastColors = arrayOf(Color.Unspecified, Color.Unspecified, Color.Unspecified)
    private val brushes = arrayOfNulls<Brush>(3)

    fun obtain(slot: Int, color: Color): Brush {
        val idx = slot.coerceIn(0, 2)
        val cached = brushes[idx]
        if (cached != null && lastColors[idx] == color) return cached
        lastColors[idx] = color
        return Brush.radialGradient(
            0.0f to color.copy(alpha = 0.82f),
            0.38f to color.copy(alpha = 0.48f),
            0.72f to color.copy(alpha = 0.16f),
            1.0f to Color.Transparent,
            center = Offset.Zero,
            radius = CanvasBlobUnitRadius,
        ).also { brushes[idx] = it }
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
            for (i in 0 until blobs.size) {
                val blob = blobs[i]
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

private const val PaletteSettleMillis = 220L

/** 切歌色彩过渡时长；与 [PaletteSettleMillis] 相加才是用户感知的总延迟。 */
private const val PaletteTransitionMillis = 750

/** 暂停时的漫游速度系数（相对播放中）。 */
private const val FluidIdleVelocity = 0.5f

/** 单帧可计入相位的最大真实时长，防止挂起恢复后色团一次性跳很远。 */
private const val MaxFrameDeltaSeconds = 0.1f
