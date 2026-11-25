package cp.player.app.ui.component

import android.content.Context
import android.graphics.RuntimeShader
import android.os.Build
import android.view.Display
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

actual fun resolveFluidRenderTier(): FluidRenderTier = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> FluidRenderTier.MESH_GRADIENT
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> FluidRenderTier.RUNTIME_SHADER
    else -> FluidRenderTier.CANVAS_BLOBS
}

@Composable
internal actual fun Modifier.renderPlatformFluid(
    tier: FluidRenderTier,
    sceneModel: State<FluidSceneModel>,
    fallback: Brush,
): Modifier = when (tier) {
    FluidRenderTier.MESH_GRADIENT -> {
        // Android 14+ (API 34+) 走硬件加速 Canvas.drawMesh
        this.cpMeshGradientBackground(sceneModel)
    }
    FluidRenderTier.RUNTIME_SHADER -> {
        // Android 13 (API 33) 走系统原生 AGSL RuntimeShader
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.cpAgslFluidBackground(sceneModel)
        } else {
            this.cpCanvasBlobFluidBackground(sceneModel, fallback)
        }
    }
    FluidRenderTier.CANVAS_BLOBS -> {
        // Android 12 及以下走 Compose Canvas 多光团有机径向弥散
        this.cpCanvasBlobFluidBackground(sceneModel, fallback)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Modifier.cpAgslFluidBackground(
    sceneModel: State<FluidSceneModel>,
): Modifier {
    val shader = remember { RuntimeShader(AGSL_FLUID_SRC) }
    val brush = remember(shader) { ShaderBrush(shader) }

    return this.drawBehind {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@drawBehind

        val model = sceneModel.value
        shader.setFloatUniform("uResolution", w, h)
        shader.setFloatUniform("uTime", model.time)
        shader.setColorUniform("uBaseColor", model.baseColor.toArgb())

        val b1 = model.blobs.getOrNull(0)
        val b2 = model.blobs.getOrNull(1)
        val b3 = model.blobs.getOrNull(2)

        if (b1 != null) {
            shader.setFloatUniform("uBlob1Center", b1.center.x, b1.center.y)
            shader.setColorUniform("uBlob1Color", b1.color.toArgb())
            shader.setFloatUniform("uBlob1Reach", b1.reach)
        }
        if (b2 != null) {
            shader.setFloatUniform("uBlob2Center", b2.center.x, b2.center.y)
            shader.setColorUniform("uBlob2Color", b2.color.toArgb())
            shader.setFloatUniform("uBlob2Reach", b2.reach)
        }
        if (b3 != null) {
            shader.setFloatUniform("uBlob3Center", b3.center.x, b3.center.y)
            shader.setColorUniform("uBlob3Color", b3.color.toArgb())
            shader.setFloatUniform("uBlob3Reach", b3.reach)
        }

        drawRect(brush = brush)
    }
}

private const val AGSL_FLUID_SRC = """
uniform float2 uResolution;
uniform float uTime;
layout(color) uniform half4 uBaseColor;

uniform float2 uBlob1Center;
layout(color) uniform half4 uBlob1Color;
uniform float uBlob1Reach;

uniform float2 uBlob2Center;
layout(color) uniform half4 uBlob2Color;
uniform float uBlob2Reach;

uniform float2 uBlob3Center;
layout(color) uniform half4 uBlob3Color;
uniform float uBlob3Reach;

vec3 srgbToLinear(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
}
vec3 linearToSrgb(vec3 c) {
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c));
}

float hash(vec2 p) {
    vec2 q = fract(p * vec2(123.34, 456.21));
    q += dot(q, q + 45.32);
    return fract(q.x * q.y);
}
float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution;
    float t = uTime * 0.15;
    float warp = vnoise(uv * 2.2 + vec2(t * 0.4, t * 0.3));
    float2 warpedUv = uv + vec2(cos(warp * 6.28318), sin(warp * 6.28318)) * 0.06;

    vec3 baseLin = srgbToLinear(uBaseColor.rgb);
    vec3 colLin = baseLin;

    // Blob 1
    float d1 = length(warpedUv - uBlob1Center) / max(0.1, uBlob1Reach);
    float w1 = smoothstep(1.0, 0.0, d1) * 0.76;
    vec3 b1Lin = srgbToLinear(uBlob1Color.rgb);
    colLin = mix(colLin, b1Lin, w1);

    // Blob 2
    float d2 = length(warpedUv - uBlob2Center) / max(0.1, uBlob2Reach);
    float w2 = smoothstep(1.0, 0.0, d2) * 0.76;
    vec3 b2Lin = srgbToLinear(uBlob2Color.rgb);
    colLin = mix(colLin, b2Lin, w2);

    // Blob 3
    float d3 = length(warpedUv - uBlob3Center) / max(0.1, uBlob3Reach);
    float w3 = smoothstep(1.0, 0.0, d3) * 0.72;
    vec3 b3Lin = srgbToLinear(uBlob3Color.rgb);
    colLin = mix(colLin, b3Lin, w3);

    return half4(half3(linearToSrgb(colLin)), 1.0);
}
"""

@Composable
actual fun rememberFluidFpsConfig(targetFps: Int): FluidFpsConfig {
    val context = LocalContext.current
    return remember(context, targetFps) {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.display
            } catch (_: Throwable) {
                null
            }
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
        }
        val rate = display?.refreshRate ?: 60f
        val screenHz = if (rate > 0f) rate.roundToInt().coerceIn(30, 360) else 60

        calculateFluidFpsConfig(screenHz, targetFps)
    }
}
