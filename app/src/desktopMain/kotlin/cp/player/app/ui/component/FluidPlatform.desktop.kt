package cp.player.app.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

actual fun resolveFluidRenderTier(): FluidRenderTier = FluidRenderTier.RUNTIME_SHADER

@Composable
internal actual fun Modifier.renderPlatformFluid(
    tier: FluidRenderTier,
    sceneModel: State<FluidSceneModel>,
    fallback: Brush,
): Modifier {
    val runtimeEffect = remember { DesktopFluidShaderHolder.effect }
    if (runtimeEffect == null) {
        return this.cpMeshGradientBackground(sceneModel)
    }
    return this.cpSkslFluidBackground(runtimeEffect, sceneModel, fallback)
}

/**
 * 桌面端直接用 Skia SkSL [RuntimeEffect] 在 GPU 片元着色器中完成流体混合，
 * 绕过 `MeshGradientPainter.drawTriangles` 每帧海量顶点装箱分配（原 252MB/s）与离屏位图开销。
 */
@Composable
private fun Modifier.cpSkslFluidBackground(
    effect: RuntimeEffect,
    sceneModel: State<FluidSceneModel>,
    fallback: Brush,
): Modifier {
    val builder = remember(effect) { RuntimeShaderBuilder(effect) }
    val paint = remember { Paint().apply { isDither = true } }
    return this.drawBehind {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) {
            drawRect(fallback)
            return@drawBehind
        }

        val model = sceneModel.value
        builder.uniform("uResolution", w, h)
        builder.uniform("uTime", model.time)
        val base = model.baseColor
        builder.uniform("uBaseColor", base.red, base.green, base.blue, base.alpha)

        val blobs = model.blobs
        val b1 = blobs.getOrNull(0)
        val b2 = blobs.getOrNull(1)
        val b3 = blobs.getOrNull(2)

        if (b1 != null) {
            builder.uniform("uBlob1Center", b1.center.x, b1.center.y)
            builder.uniform("uBlob1Color", b1.color.red, b1.color.green, b1.color.blue, b1.intensity)
            builder.uniform("uBlob1Reach", b1.reach)
        }
        if (b2 != null) {
            builder.uniform("uBlob2Center", b2.center.x, b2.center.y)
            builder.uniform("uBlob2Color", b2.color.red, b2.color.green, b2.color.blue, b2.intensity)
            builder.uniform("uBlob2Reach", b2.reach)
        }
        if (b3 != null) {
            builder.uniform("uBlob3Center", b3.center.x, b3.center.y)
            builder.uniform("uBlob3Color", b3.color.red, b3.color.green, b3.color.blue, b3.intensity)
            builder.uniform("uBlob3Reach", b3.reach)
        }

        drawIntoCanvas { canvas ->
            builder.makeShader().use { shader ->
                paint.shader = shader
                canvas.nativeCanvas.drawRect(Rect.makeXYWH(0f, 0f, w, h), paint)
                paint.shader = null
            }
        }
    }
}

private object DesktopFluidShaderHolder {
    val effect: RuntimeEffect? = runCatching { RuntimeEffect.makeForShader(SKSL_FLUID_SRC) }.getOrNull()
}

private const val SKSL_FLUID_SRC = """
uniform float2 uResolution;
uniform float uTime;
uniform half4 uBaseColor;

uniform float2 uBlob1Center;
uniform half4 uBlob1Color;
uniform float uBlob1Reach;

uniform float2 uBlob2Center;
uniform half4 uBlob2Color;
uniform float uBlob2Reach;

uniform float2 uBlob3Center;
uniform half4 uBlob3Color;
uniform float uBlob3Reach;

float3 srgbToLinear(float3 c) {
    return mix(
        c / 12.92,
        pow(max((c + 0.055) / 1.055, float3(0.0)), float3(2.4)),
        step(float3(0.04045), c)
    );
}

float3 linearToSrgb(float3 c) {
    return mix(
        c * 12.92,
        1.055 * pow(max(c, float3(0.0)), float3(1.0 / 2.4)) - 0.055,
        step(float3(0.0031308), c)
    );
}

float hash(float2 p) {
    float2 q = fract(p * float2(123.34, 456.21));
    q += dot(q, q + 45.32);
    return fract(q.x * q.y);
}

float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / max(uResolution, float2(1.0));
    float t = uTime * 0.15;
    float warp = vnoise(uv * 2.2 + float2(t * 0.4, t * 0.3));
    float2 warpedUv = uv + float2(cos(warp * 6.28318), sin(warp * 6.28318)) * 0.06;

    float3 colLin = srgbToLinear(float3(uBaseColor.rgb));

    float d1 = length(warpedUv - uBlob1Center) / max(0.1, uBlob1Reach);
    float w1 = (1.0 - smoothstep(0.0, 1.0, d1)) * 0.76 * clamp(float(uBlob1Color.a), 0.7, 1.1);
    colLin = mix(colLin, srgbToLinear(float3(uBlob1Color.rgb)), w1);

    float d2 = length(warpedUv - uBlob2Center) / max(0.1, uBlob2Reach);
    float w2 = (1.0 - smoothstep(0.0, 1.0, d2)) * 0.76 * clamp(float(uBlob2Color.a), 0.7, 1.1);
    colLin = mix(colLin, srgbToLinear(float3(uBlob2Color.rgb)), w2);

    float d3 = length(warpedUv - uBlob3Center) / max(0.1, uBlob3Reach);
    float w3 = (1.0 - smoothstep(0.0, 1.0, d3)) * 0.72 * clamp(float(uBlob3Color.a), 0.7, 1.1);
    colLin = mix(colLin, srgbToLinear(float3(uBlob3Color.rgb)), w3);

    return half4(half3(linearToSrgb(clamp(colLin, 0.0, 1.0))), 1.0);
}
"""

/**
 * 用 Win32 `EnumDisplaySettingsW` 读主显示器刷新率，不碰 AWT `GraphicsEnvironment`。
 *
 * `DEVMODEW.dmDisplayFrequency` 位于偏移 184（Windows SDK `wingdi.h` 核对：
 * 64 字节设备名 + 8 × WORD/DWORD 头 + 8 字节 union + 10 × short + 64 字节 formName
 * + WORD + 6 × DWORD）。查询失败一律回 60 Hz —— 与原实现的兜底一致。
 *
 * ⚠️ `jint(void*, jint, void*)` 这条 downcall 签名登记在
 * `app/graalvm/reachability-metadata.json`，改动要同步。
 */
@Composable
actual fun rememberFluidFpsConfig(targetFps: Int): FluidFpsConfig {
    return remember(targetFps) {
        val hz = runCatching { displayRefreshRateHz() }
            .getOrDefault(60)
            .takeIf { it in 1..240 } ?: 60
        calculateFluidFpsConfig(hz, targetFps)
    }
}

private fun displayRefreshRateHz(): Int {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return 60
    return Arena.ofConfined().use { arena ->
        val linker = Linker.nativeLinker()
        val user32 = SymbolLookup.libraryLookup("user32", arena)
        val enumDisplaySettings = linker.downcallHandle(
            user32.find("EnumDisplaySettingsW").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )
        val devMode = arena.allocate(DEVMODEW_BYTES)
        // DEVMODEW.dmSize = sizeof(DEVMODEW)，EnumDisplaySettingsW 要求调用方先填。
        devMode.set(ValueLayout.JAVA_SHORT, DM_SIZE_OFFSET, DEVMODEW_SIZE.toShort())
        val ok = enumDisplaySettings.invokeWithArguments(
            MemorySegment.NULL, ENUM_CURRENT_SETTINGS, devMode,
        ) as Int
        if (ok == 0) return@use 60
        devMode.get(ValueLayout.JAVA_INT, DM_DISPLAY_FREQUENCY_OFFSET)
    }
}

private const val DEVMODEW_BYTES = 256L
private const val DEVMODEW_SIZE = 220
private const val DM_SIZE_OFFSET = 68L
private const val DM_DISPLAY_FREQUENCY_OFFSET = 184L
private const val ENUM_CURRENT_SETTINGS = -1
