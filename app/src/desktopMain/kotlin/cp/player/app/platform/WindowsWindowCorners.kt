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
 * ## ⚠️ 原生镜像：每个 downcall 签名都要登记
 *
 * 发行版是 GraalVM Native Image，而镜像里 `Linker.downcallHandle(...)` **要求签名在构建期
 * 登记**，否则抛 `MissingForeignRegistrationError`（JVM 上完全不需要，单测覆盖不到）。
 * 本文件用到的 5 个签名已写进 `app/graalvm/reachability-metadata.json` 的
 * `foreign.downcalls`（`long(long)` / `long(long,int)` / `int(long)` / `int(long,void*)` /
 * `int(long,int,void*,int)`）。
 *
 * 2026-11-14 的真实事故：这 5 条漏登记 ⇒ 每次 `downcallHandle` 都抛 ⇒ 被下面的
 * `runCatching` 吞掉 ⇒ 重试 50 次全是同一个异常 ⇒ 原生包里窗口一直是直角、也没有
 * Win11 那圈 1px 细边框（两者都由 DWM 在 `DWMWCP_ROUND` 下才画）。
 * **加/改这里的任何调用点，必须同步改那份 JSON**，并用 `-Ob` 迷你镜像验一次
 * （做法见 `app/graalvm/README.md`）。
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

    /** `DWM_WINDOW_CORNER_PREFERENCE.DWMWCP_ROUND`。 */
    private const val DWMWCP_ROUND = 2

    /** `GetWindow` 的 `GW_HWNDNEXT`：按 Z 序取下一个顶层窗口。 */
    private const val GW_HWNDNEXT = 2

    /** 第三个参数指向的是一个 4 字节枚举。 */
    private const val CORNER_PREFERENCE_BYTES = 4

    /**
     * 算得上「应用窗口」的最小边长（物理像素）。
     *
     * Tao 除了主窗口，还会造一个 16×16 的 `Tao Thread Event Target` 顶层窗口
     * （可见，`WS_EX_LAYERED | WS_EX_TOOLWINDOW | WS_EX_TRANSPARENT`），用来给自己
     * 投递事件。**它必须被跳过**：它比主窗口先可见，如果把它算成「改成功了一个窗口」，
     * 调用方的重试循环就会提前退出 —— 主窗口随后仍停在 Tao 写死的 DONOTROUND 上。
     * （2026-11-14「圆角回不来」的真正原因；只按 PID 认领窗口时它和主窗口无法区分。）
     */
    private const val MIN_APP_WINDOW_SIZE = 96

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
        val getWindowRect = downcall(
            linker, user32, "GetWindowRect",
            ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
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
        val getCornerPreference = downcall(
            linker, dwmapi, "DwmGetWindowAttribute",
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
        )

        val myPid = ProcessHandle.current().pid().toInt()
        val pidOut = arena.allocate(ValueLayout.JAVA_INT)
        val preferenceValue = arena.allocate(ValueLayout.JAVA_INT)
        preferenceValue.set(ValueLayout.JAVA_INT, 0, preference)
        // RECT = 4 × LONG，按 int 布局（Win32 RECT 的四个字段都是 LONG）。
        val rect = arena.allocate(ValueLayout.JAVA_INT.byteSize() * 4)
        val readBack = arena.allocate(ValueLayout.JAVA_INT)

        var changed = 0
        // ⚠️ HWND 在这些描述符里是 JAVA_LONG（不是 ADDRESS），所以 NULL 必须写成 0L。
        // 传 `MemorySegment.NULL` 会在 invokeWithArguments 的类型转换里抛
        // `ClassCastException: NativeMemorySegmentImpl cannot be cast to Number` —— 而且
        // 外层 runCatching 会把它吞掉，表现成「静默不生效」，很难查。
        var hwnd = getTopWindow.invokeWithArguments(0L) as Long
        while (hwnd != 0L) {
            // 只认领自己的窗口，并且必须是「像窗口的」窗口：不可见的辅助窗口、以及
            // Tao 的 16×16 事件窗口都不需要（也不该）被改。
            val visible = (isWindowVisible.invokeWithArguments(hwnd) as Int) != 0
            val hasRect = (getWindowRect.invokeWithArguments(hwnd, rect) as Int) != 0
            if (visible && hasRect) {
                val width = rect.get(ValueLayout.JAVA_INT, 8) - rect.get(ValueLayout.JAVA_INT, 0)
                val height = rect.get(ValueLayout.JAVA_INT, 12) - rect.get(ValueLayout.JAVA_INT, 4)
                getWindowThreadProcessId.invokeWithArguments(hwnd, pidOut)
                if (pidOut.get(ValueLayout.JAVA_INT, 0) == myPid &&
                    width >= MIN_APP_WINDOW_SIZE && height >= MIN_APP_WINDOW_SIZE
                ) {
                    val hr = setCornerPreference.invokeWithArguments(
                        hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, preferenceValue, CORNER_PREFERENCE_BYTES,
                    ) as Int
                    // S_OK == 0；非 0 说明这个窗口不吃这一套（例如系统还没合成它），跳过即可。
                    //
                    // ⚠️ 光看返回值不够：Tao 会在首帧 / 真正 map 之后把偏好写回 DONOTROUND，
                    // 这时 S_OK 但设置已经被覆盖。必须读回确认，否则重试循环会以为
                    // 「已经生效」而提前退出（圆角照样没有）。
                    if (hr == 0) {
                        readBack.set(ValueLayout.JAVA_INT, 0, -1)
                        getCornerPreference.invokeWithArguments(
                            hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, readBack, CORNER_PREFERENCE_BYTES,
                        )
                        if (readBack.get(ValueLayout.JAVA_INT, 0) == preference) changed++
                    }
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
