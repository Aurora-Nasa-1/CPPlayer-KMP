package cp.player.app.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.mikepenz.hypnoticcanvas.NonAndroidRuntimeEffect
import com.mikepenz.hypnoticcanvas.RuntimeEffect
import com.mikepenz.hypnoticcanvas.shaders.Shader
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

actual fun buildRuntimeEffect(shader: Shader): RuntimeEffect =
    NonAndroidRuntimeEffect(shader)

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
