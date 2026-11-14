package cp.player.app.platform

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

/**
 * 主显示器工作区（扣掉任务栏 / 停靠栏），单位 dp。
 *
 * 走 Win32 `SystemParametersInfoW(SPI_GETWORKAREA)` + `GetDpiForSystem`，**不初始化
 * AWT Toolkit**。窗口尚未创建时也要能问「工作区多大」——启动前恢复持久化窗口尺寸
 * 就靠这条路径兜住「上次在更大的显示器上关窗」的情况。
 *
 * 非 Windows 平台返回 null，调用方原样使用持久化尺寸；查询失败不该挡启动。
 */
internal object TaoPrimaryDisplay {

    private const val SPI_GETWORKAREA = 0x0030
    private const val RECT_BYTES = 16L

    fun workAreaDp(): DpSize? {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return null
        return runCatching {
            Arena.ofConfined().use { arena ->
                val linker = Linker.nativeLinker()
                val user32 = SymbolLookup.libraryLookup("user32", arena)

                val rect = arena.allocate(RECT_BYTES)
                val systemParametersInfo = linker.downcallHandle(
                    user32.find("SystemParametersInfoW").orElseThrow(),
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                    ),
                )
                val ok = systemParametersInfo.invokeWithArguments(
                    SPI_GETWORKAREA,
                    0,
                    rect,
                    0,
                ) as Int
                if (ok == 0) return@use null

                val widthPx = rect.get(ValueLayout.JAVA_INT, 8L) - rect.get(ValueLayout.JAVA_INT, 0L)
                val heightPx = rect.get(ValueLayout.JAVA_INT, 12L) - rect.get(ValueLayout.JAVA_INT, 4L)
                if (widthPx <= 0 || heightPx <= 0) return@use null

                val dpi = runCatching {
                    val getDpiForSystem = linker.downcallHandle(
                        user32.find("GetDpiForSystem").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT),
                    )
                    getDpiForSystem.invokeWithArguments() as Int
                }.getOrDefault(96)
                val scale = (if (dpi in 48..480) dpi else 96) / 96f
                DpSize((widthPx / scale).dp, (heightPx / scale).dp)
            }
        }.getOrNull()
    }
}
