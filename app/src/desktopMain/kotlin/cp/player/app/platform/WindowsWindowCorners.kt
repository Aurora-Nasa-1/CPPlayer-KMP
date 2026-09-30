package cp.player.app.platform

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * 给桌面窗口设置 Windows 11 的 DWM 圆角。
 *
 * ## 为什么需要它
 *
 * Java 的 `undecorated = true` 窗口是纯 `WS_POPUP`（**没有** `WS_THICKFRAME`），
 * 而 Win11 的「自动圆角」只作用于带边框样式的窗口 ⇒ 这种窗口默认是**直角**。
 *
 * 微软文档把「无边框」列进了「永远无法圆角」那一类，但那一条针对的是
 * **逐像素 alpha 分层 / 窗口 region** 这类非矩形窗口；纯矩形的 `WS_POPUP` 属于
 * 「不在默认策略内、但可以手动 opt-in」的那一类。**已用探针实测确认**：
 *
 * ```
 * A 基线      style=0x960B0000  WS_POPUP=true  WS_THICKFRAME=false
 *             左上角逐行起始 x（y0→y15）= 0 0 0 0 … ⇒ 直角
 * B 调 DWM    DwmSetWindowAttribute 返回 S_OK
 *             左上角逐行起始 x（y0→y15）= — 8 5 3 3 2 2 2 1 1 … ⇒ 圆角，半径约 8px
 * ```
 *
 * 半径 ~8px 正是 Win11 普通窗口的标准值。
 *
 * ## 为什么用 FFM 而不是 JNA
 *
 * JDK 22 起 `java.lang.foreign` 是正式 API，本项目桌面端跑 JDK 25，**不需要引任何依赖**。
 * ⚠️ 但需要 `--enable-native-access=ALL-UNNAMED`（见 `app/build.gradle.kts` 的 `jvmArgs`）：
 * 不放行目前只是打警告，后续 JDK 会直接拒绝。
 *
 * ## 怎么找到窗口句柄
 *
 * 刻意**不按标题找**（`FindWindowW(null, title)`）—— 本应用的窗口标题会随曲目变化，
 * 一换歌就再也匹配不上了。改成遍历桌面的顶层窗口、用**进程 ID** 认领自己的窗口：
 * 这样既不受标题影响，也不会误伤别的进程。
 */
internal object WindowsWindowCorners {

    /** `DWMWINDOWATTRIBUTE.DWMWA_WINDOW_CORNER_PREFERENCE`。 */
    private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33

    /** `DWM_WINDOW_CORNER_PREFERENCE.DWMWCP_DONOTROUND`。 */
    private const val DWMWCP_DONOTROUND = 1

    /** `DWM_WINDOW_CORNER_PREFERENCE.DWMWCP_ROUND`。 */
    private const val DWMWCP_ROUND = 2

    /** `GetWindow` 的 `GW_HWNDNEXT`：按 Z 序取下一个顶层窗口。 */
    private const val GW_HWNDNEXT = 2

    /** 第三个参数指向的是一个 4 字节枚举。 */
    private const val CORNER_PREFERENCE_BYTES = 4

    /** 启动时成功过就不再重复尝试（`Main.kt` 会带重试调用）。 */
    private var applied = false

    /**
     * 启动时应用一次圆角，返回**是否真的改到了至少一个窗口**。
     *
     * 返回 `false` 通常只有一个原因：窗口此刻还没真正 map 出来（`IsWindowVisible` 为假就
     * 找不到句柄）。调用方据此决定要不要重试。
     *
     * 失败一律**吞掉异常**：圆角是纯外观，拿不到就当直角用，绝不能因此让应用起不来。
     */
    fun applyRoundCorners(): Boolean {
        if (applied) return true
        if (!isWindows()) {
            applied = true
            return true
        }
        val changed = runCatching { applyCornerPreference(DWMWCP_ROUND) }
            .onFailure {
                println("[WindowsWindowCorners] 未能应用 DWM 圆角（不影响功能，窗口保持直角）：$it")
            }
            .getOrDefault(0)
        if (changed > 0) applied = true
        return changed > 0
    }

    /**
     * 最大化 / 还原时切换圆角。
     *
     * ⚠️ **为什么必须切**：Windows 只在**它认为窗口已最大化**时才自动去圆角。而本应用的
     * 最大化是自己按工作区设 bounds 的（`extendedState` 始终是 `NORMAL`，见 [WindowMaximizer]）
     * —— 系统不认为它最大化了，于是 DWM 会**继续给圆角**，最大化之后**四个角上露出桌面**，
     * 看起来就是「全屏之后缺了四个角」。
     *
     * 实测（把窗口摆成 2560×1392 的工作区）：
     * ```
     * DWMWCP_ROUND      → 四角 = #07011B / #04000E / #0D0125 / #320907（都是桌面）
     * DWMWCP_DONOTROUND → 四角 = #3060C0（窗口填充色）
     * ```
     */
    fun setRounded(enabled: Boolean): Boolean {
        if (!isWindows()) return false
        return runCatching { applyCornerPreference(if (enabled) DWMWCP_ROUND else DWMWCP_DONOTROUND) }
            .onFailure {
                println("[WindowsWindowCorners] 未能切换 DWM 圆角（不影响功能）：$it")
            }
            .getOrDefault(0) > 0
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows")

    /** 返回成功改到的窗口数。 */
    private fun applyCornerPreference(preference: Int): Int = Arena.ofConfined().use { arena ->
        val linker = Linker.nativeLinker()
        val user32 = SymbolLookup.libraryLookup("user32", arena)
        val dwmapi = SymbolLookup.libraryLookup("dwmapi", arena)

        val getTopWindow = downcall(
            linker, user32, "GetTopWindow", ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
        )
        val getWindow = downcall(
            linker, user32, "GetWindow", ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
        )
        val isWindowVisible = downcall(
            linker, user32, "IsWindowVisible", ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
        )
        val getWindowThreadProcessId = downcall(
            linker, user32, "GetWindowThreadProcessId",
            ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
        )
        val setCornerPreference = downcall(
            linker, dwmapi, "DwmSetWindowAttribute",
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
        )

        val myPid = ProcessHandle.current().pid().toInt()
        val pidOut = arena.allocate(ValueLayout.JAVA_INT)
        val preferenceValue = arena.allocate(ValueLayout.JAVA_INT)
        preferenceValue.set(ValueLayout.JAVA_INT, 0, preference)

        var changed = 0
        // ⚠️ HWND 在这些描述符里是 JAVA_LONG（不是 ADDRESS），所以 NULL 必须写成 0L。
        // 传 `MemorySegment.NULL` 会在 invokeWithArguments 的类型转换里抛
        // `ClassCastException: NativeMemorySegmentImpl cannot be cast to Number` —— 而且
        // 外层 runCatching 会把它吞掉，表现成「静默不生效」，很难查。
        var hwnd = getTopWindow.invokeWithArguments(0L) as Long
        while (hwnd != 0L) {
            // 只认领自己的窗口：AWT 还会造一堆不可见的辅助窗口，它们不需要（也不该）被改。
            if ((isWindowVisible.invokeWithArguments(hwnd) as Int) != 0) {
                getWindowThreadProcessId.invokeWithArguments(hwnd, pidOut)
                if (pidOut.get(ValueLayout.JAVA_INT, 0) == myPid) {
                    val hr = setCornerPreference.invokeWithArguments(
                        hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, preferenceValue, CORNER_PREFERENCE_BYTES,
                    ) as Int
                    // S_OK == 0；非 0 说明这个窗口不吃这一套（例如系统还没合成它），跳过即可。
                    if (hr == 0) changed++
                }
            }
            hwnd = getWindow.invokeWithArguments(hwnd, GW_HWNDNEXT) as Long
        }
        changed
    }

    /**
     * Kotlin 用不了 `MethodHandle.invoke` 的签名多态，只能走 `invokeWithArguments`
     * （参数装箱、返回 `Object` 再转型）。这里只调用个位数次，性能无关紧要。
     */
    private fun downcall(
        linker: Linker,
        library: SymbolLookup,
        name: String,
        returns: MemoryLayout,
        vararg parameters: MemoryLayout,
    ): MethodHandle = linker.downcallHandle(
        library.find(name).orElseThrow(),
        FunctionDescriptor.of(returns, *parameters),
    )
}
