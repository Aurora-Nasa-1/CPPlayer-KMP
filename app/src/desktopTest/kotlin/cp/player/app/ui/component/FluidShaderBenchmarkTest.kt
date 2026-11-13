package cp.player.app.ui.component

import androidx.compose.ui.graphics.Color as ComposeColor
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Surface
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Same-machine comparison of the 20ec60e shader block and the current two-field block,
 * rendered with Skia JVM raster surfaces.
 *
 * This is deliberately opt-in and CPU-only. It does not report hardware-GPU or end-to-end
 * Compose frame time. Run with:
 * CP_FLUID_BENCHMARK=1 ./gradlew :app:desktopTest --tests 'cp.player.app.ui.component.FluidShaderBenchmarkTest' --no-daemon --info
 */
class FluidShaderBenchmarkTest {
    @Test
    fun `CpFluidMeshGradient CPU raster before after benchmark`() {
        assumeTrue(
            "Set CP_FLUID_BENCHMARK=1 to run this intentionally slow benchmark",
            System.getenv("CP_FLUID_BENCHMARK") == "1",
        )

        val colors = arrayOf(
            ComposeColor(0xFFB84870),
            ComposeColor(0xFF487FA8),
            ComposeColor(0xFF7C4FA3),
            ComposeColor(0xFF10131A),
        )
        val mesh = CpFluidMeshGradient(colors, speed = 1f, scale = 2f, dither = true)
        val beforeSksl = shaderFromCommit20ec60e(mesh.sksl)
        val beforeWithoutDeadSksl = removeUnusedExtraNoise(beforeSksl)
        val afterSksl = mesh.sksl

        val baselineLoopCallSites = countSnoiseCallsInLoop(beforeSksl)
        val currentLoopCallSites = countSnoiseCallsInLoop(afterSksl)
        val baselineLoopEvaluationsPerPixel = baselineLoopCallSites * (colors.size - 1)
        val baselineDeadCallsPerPixel = 1 // `noise` is computed and maxed, but never read.
        val currentEvaluationsPerPixel = 2 // `noiseA` and `noiseB`, each evaluated once per pixel.
        assertEquals(1, baselineLoopCallSites, "Baseline has one snoise call site inside the palette loop")
        assertEquals(3, baselineLoopEvaluationsPerPixel, "Four colors execute the loop body three times per pixel")
        assertEquals(0, currentLoopCallSites, "Current palette loop reuses fields; it has no snoise call site")
        assertEquals(1, beforeSksl.countOccurrences("float noise = snoise("), "Baseline's extra call is a dead value outside the loop")
        assertEquals(2, afterSksl.countOccurrences("snoise(vec3("), "Current shader computes two shared fields")
        assertTrue(beforeSksl.contains("float noise = snoise("))
        assertTrue(afterSksl.contains("float noiseA = snoise(") && afterSksl.contains("float noiseB = snoise("))

        println(
            "[FluidShaderBenchmark] backend=Skia JVM raster (CPU-only; not hardware GPU or Compose frame time), " +
                "os=${System.getProperty("os.name")}, arch=${System.getProperty("os.arch")}, " +
                "java=${System.getProperty("java.version")}, processors=${Runtime.getRuntime().availableProcessors()}, " +
                "cpu=${cpuModel()}"
        )
        println(
            "[FluidShaderBenchmark] warmup=$WARMUP_FRAMES frames, samples=$MEASURED_FRAMES per variant; " +
                "each sample includes uniform update, shader creation, and synchronous raster draw"
        )
        println(
            "[FluidShaderBenchmark] dynamic source counts/pixel: 20ec60e loop=$baselineLoopEvaluationsPerPixel " +
                "+ dead extra=$baselineDeadCallsPerPixel (compiler may eliminate it); current=$currentEvaluationsPerPixel. " +
                "Dither has no snoise calls; Android/Desktop use the same common SkSL source."
        )

        val beforeEffect = RuntimeEffect.makeForShader(beforeSksl)
        val beforeWithoutDeadEffect = RuntimeEffect.makeForShader(beforeWithoutDeadSksl)
        val afterEffect = RuntimeEffect.makeForShader(afterSksl)
        try {
            for ((width, height) in RESOLUTIONS) {
                val before = measure(beforeEffect, colors, width, height)
                val after = measure(afterEffect, colors, width, height)
                val speedup = before.meanMs / after.meanMs
                println(
                    "[FluidShaderBenchmark] ${width}x$height " +
                        "before=20ec60e mean=${format(before.meanMs)}ms p50=${format(before.p50Ms)}ms " +
                        "p90=${format(before.p90Ms)}ms equiv=${format(1000.0 / before.meanMs)}fps " +
                        "${format(before.megaPixelsPerSecond)}MPix/s; " +
                        "after=current mean=${format(after.meanMs)}ms p50=${format(after.p50Ms)}ms " +
                        "p90=${format(after.p90Ms)}ms equiv=${format(1000.0 / after.meanMs)}fps " +
                        "${format(after.megaPixelsPerSecond)}MPix/s; CPU-raster speedup=${format(speedup)}x"
                )
                assertTrue(before.meanMs.isFinite() && before.meanMs > 0.0, "Before timing must be finite and positive")
                assertTrue(after.meanMs.isFinite() && after.meanMs > 0.0, "After timing must be finite and positive")

                if (width == DEAD_CALL_CHECK_WIDTH) {
                    val withoutDead = measure(beforeWithoutDeadEffect, colors, width, height)
                    val deadCallRatio = before.meanMs / withoutDead.meanMs
                    println(
                        "[FluidShaderBenchmark] dead-value check ${width}x$height: " +
                            "with-unused-call=${format(before.meanMs)}ms, without-unused-call=${format(withoutDead.meanMs)}ms, " +
                            "ratio=${format(deadCallRatio)}x (timing is evidence, not compiled-IR proof)"
                    )
                }
            }
        } finally {
            beforeEffect.close()
            beforeWithoutDeadEffect.close()
            afterEffect.close()
        }
    }

    private fun measure(
        effect: RuntimeEffect,
        colors: Array<ComposeColor>,
        width: Int,
        height: Int,
    ): TimingSummary {
        val surface = Surface.makeRaster(ImageInfo.makeN32Premul(width, height))
        val builder = RuntimeShaderBuilder(effect)
        val paint = Paint()
        try {
            val colorUniforms = colors.flatMap { listOf(it.red, it.green, it.blue) }.toFloatArray()
            builder.uniform("uColor", colorUniforms)
            builder.uniform("uResolution", width.toFloat(), height.toFloat(), 1f)
            builder.uniform("uDither", 1f)

            fun renderFrame(frame: Int) {
                builder.uniform("uTime", frame / 30f)
                val shader = builder.makeShader()
                try {
                    paint.shader = shader
                    surface.canvas.drawPaint(paint)
                } finally {
                    shader.close()
                }
            }

            repeat(WARMUP_FRAMES) { renderFrame(it) }
            val samplesMs = DoubleArray(MEASURED_FRAMES) { index ->
                val start = System.nanoTime()
                renderFrame(WARMUP_FRAMES + index)
                (System.nanoTime() - start) / 1_000_000.0
            }
            val sorted = samplesMs.sorted().toDoubleArray()
            val meanMs = samplesMs.average()
            return TimingSummary(
                meanMs = meanMs,
                p50Ms = percentile(sorted, 0.50),
                p90Ms = percentile(sorted, 0.90),
                megaPixelsPerSecond = (width.toDouble() * height / 1_000_000.0) / (meanMs / 1_000.0),
            )
        } finally {
            paint.close()
            builder.close()
            surface.close()
        }
    }

    /** Replace only the changed noise block with the source logic from commit 20ec60e. */
    private fun shaderFromCommit20ec60e(currentSksl: String): String {
        val start = currentSksl.indexOf("    // 这两个独立噪声场")
        val end = currentSksl.indexOf("    // High-frequency Triangular Probability Density Function", startIndex = start)
        require(start >= 0 && end > start) { "Could not locate the current shader's noise block for the baseline variant" }

        val oldBlock = """
    float tilt = -0.5 * uv.y;
    float incline = uv.x * 0.1;
    float offset = incline * mix(-0.25, 0.25, uv.y);
    float noise = snoise(vec3(base.x + uTime * 0.2, base.y, uTime * 0.2));
    noise = max(0.0, noise);

    for(int i = 0; i < MAX_COLORS - 1; i++) {
        float flow = 5.0 + float(i) * 0.3;
        float speed = 6.0 + float(i) * 0.3;
        float seed = 1.0 + float(i) * 4.0;
        vec2 frequency = vec2(0.3, 0.7);
        float noiseFloor = 0.00001;
        float noiseCeil = 0.6 + float(i) * 0.07;
        float noiseVal = smoothstep(noiseFloor, noiseCeil,
            snoise(vec3(base.x * frequency.x + uTime * 0.005 * flow,
                        base.y * frequency.y,
                        uTime * 0.005 * speed + seed)));
        vColor = mix(vColor, uColor[i], noiseVal);
    }

"""
        return currentSksl.replaceRange(start, end, oldBlock)
    }

    private fun removeUnusedExtraNoise(source: String): String {
        val start = source.indexOf("    float noise = snoise(")
        val end = source.indexOf("\n\n", start)
        require(start >= 0 && end > start) { "Could not locate the baseline dead-noise statement" }
        return source.removeRange(start, end)
    }

    private fun countSnoiseCallsInLoop(source: String): Int {
        val start = source.indexOf("for(int i = 0; i < MAX_COLORS - 1; i++)")
        val end = source.indexOf("\n    }", start)
        require(start >= 0 && end > start) { "Could not locate the palette loop" }
        return source.substring(start, end).countOccurrences("snoise(vec3(")
    }

    private fun String.countOccurrences(needle: String): Int =
        windowed(needle.length).count { it == needle }

    private fun percentile(sortedSamples: DoubleArray, fraction: Double): Double {
        val index = ((sortedSamples.size - 1) * fraction).toInt()
        return sortedSamples[index]
    }

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    private fun cpuModel(): String = runCatching {
        File("/proc/cpuinfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("model name") }
                ?.substringAfter(':')
                ?.trim()
                ?: "unknown"
        }
    }.getOrDefault("unknown")

    private data class TimingSummary(
        val meanMs: Double,
        val p50Ms: Double,
        val p90Ms: Double,
        val megaPixelsPerSecond: Double,
    )

    private companion object {
        val RESOLUTIONS = listOf(1280 to 720, 1920 to 1080, 2560 to 1440)
        const val WARMUP_FRAMES = 4
        const val MEASURED_FRAMES = 10
        const val DEAD_CALL_CHECK_WIDTH = 1280
    }
}
