package cp.player.app.platform

import java.awt.Frame
import java.awt.Window
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * JBR（JetBrains Runtime）自定义标题栏 API 的反射封装。
 *
 * ## 为什么存在这个文件
 *
 * Compose 的 `WindowDecoration.Undecorated` 生成的是纯 `WS_POPUP` 窗口（见
 * [WindowsWindowCorners] 的说明）：没有 DWM 阴影、没有原生缩放边框、没有 Aero Snap、
 * 最大化不认账 —— 本仓库为这些洞手写了四个补丁。JBR 提供了另一条路：
 * **窗口在系统层面保持有边框**（`WS_THICKFRAME` 保留），只把客户区向上扩展盖过标题栏，
 * 于是阴影 / 缩放 / 贴边分屏 / 原生最大化 / Win11 自动圆角全部白拿，
 * 最小化 / 最大化 / 关闭三个窗口钮由 JBR 画在客户区右上角。
 *
 * 这套 API（`WindowDecorations`，随 JBR 分发；仓库内钉死的 JBR 21.0.8-b1163.62
 * 已用探针实测含 `java.awt.Window$WindowDecorations`，普通 JDK（如 Zulu）里没有这些类；
 * 本项目又要保留「探测不到 JBR 就回退自绘无边框」的双轨能力，
 * 所以**全程反射**：本文件在普通 JDK 上编译运行都不报错，`isSupported` 为 false 而已。
 *
 * ## 平台支持
 *
 * 官方文档明确：仅 **Windows 与 macOS**。Linux 上 `isSupported` 恒为 false。
 *
 * ## 反射为什么能突破模块限制
 *
 * `java.awt.Window$WindowDecorations` 与其方法不是 public 的，`java.desktop` 又没有
 * 对类路径（unnamed module）open `java.awt`，常规 `setAccessible` 会被 Jigsaw 拒绝。
 * 绕法：`AccessibleObject.override` 是该类的**第一个实例字段**，用同布局的替身类算出偏移，
 * 再用 `sun.misc.Unsafe.putBooleanVolatile` 直接把它翻成 true —— 访问检查被整体跳过。
 * `jdk.unsupported` 模块 open 了 `sun.misc`，取 `theUnsafe` 本身不需要任何 hack。
 * ⚠️ 但**这个模块必须真的在运行时镜像里**：jpackage 出的 release 包是按模块清单裁过的，
 * 缺了它连取句柄的机会都没有 —— 缺类会在 `JbrWindowChrome` 的 `<clinit>` 期就炸成
 * `NoClassDefFoundError`（静态类型引用在类初始化期必须解析），整个应用直接起不来。
 * 所以本文件全程**不出现 `sun.misc.Unsafe` 这个类型**，只用 `Class.forName` 拿句柄：
 * 模块缺失时这里只是拿到 null，回退自绘无边框的链路仍然成立。
 * 打包侧对应 `app/build.gradle.kts` 的 `nativeDistributions.modules(...)`（含 `jdk.unsupported`）。
 * （此手法来自 ButterCam/compose-jetbrains-theme 与 JetBrains 自家的用法，已被广泛验证。）
 *
 * ## 版式约束（谁可点、谁可拖）
 *
 * JBR 的原生拖拽靠 hit-test：光标落在标题栏高度内时，**上一次** `forceHitTest` 的值
 * 决定这次按下归谁。Java 侧的约定（官方文档）：除 Exit 与 Wheel 外，每个鼠标事件
 * 都要回一次 `forceHitTest`。ComposePanel 在整个窗口都挂了监听器 ⇒ 默认整窗被判成
 * 客户区、原生拖拽失效，所以调用方（`DesktopTitleBar`）必须桥接：
 * 可交互控件上回 `forceClient(true)`，空白拖拽区回 `forceClient(false)`。
 */
object JbrWindowChrome {

    /** 当前运行环境是否具备安装条件（Windows/macOS + JBR 新版 WindowDecorations）。 */
    val isSupported: Boolean
        get() = isSupportedOs && api != null

    private val isSupportedOs: Boolean =
        System.getProperty("os.name").orEmpty().let { it.startsWith("Windows") || it.startsWith("Mac OS") }

    /** `java.awt.Window$WindowDecorations` 的探测与缓存。探测失败不抛出，只置空。 */
    private val api: WindowDecorationsApi? by lazy {
        runCatching { WindowDecorationsApi.discover() }
            .onFailure { println("[JbrWindowChrome] JBR WindowDecorations 探测失败（将回退自绘无边框方案）：$it") }
            .getOrNull()
    }

    /**
     * 给窗口安装自定义标题栏，失败返回 null（调用方自行重试 / 回退）。
     *
     * [titleBarHeightPx] 是标题栏高度，**单位是 Swing 像素**（官方文档口径：
     * 从客户区顶部量起、不含顶部边框），调用方用 Compose density 换算。
     * 可重复调用（换显示器 / 改缩放后重装一次即可更新高度）。
     */
    fun install(window: Window, titleBarHeightPx: Float): Controller? {
        val a = api ?: return null
        if (window !is Frame) {
            println("[JbrWindowChrome] 窗口不是 Frame（${window.javaClass.name}），无法安装")
            return null
        }
        return runCatching {
            val bar = a.createCustomTitleBar.invoke(a.windowDecorations)
            if (bar == null) {
                println("[JbrWindowChrome] createCustomTitleBar() 返回 null，放弃安装")
                return null
            }
            val setHeight = findMethod(bar.javaClass, "setHeight", paramCount = 1)
            if (setHeight == null) {
                println("[JbrWindowChrome] 自定义标题栏实现类上找不到 setHeight(float)，放弃安装")
                return null
            }
            // ⚠️ **这两行的顺序不能反**（2026-10-05 实测踩到，症状是「原生标题栏又回来了」）：
            // `Window.setCustomTitleBar` 开头就校验 `bar.getHeight() > 0`，而
            // `createCustomTitleBar()` 刚造出来的 bar 高度是 **0**（其构造器只初始化 insets）。
            // ⇒ 先装后设高必然抛 `IllegalArgumentException: TitleBar height must be positive`，
            // 被下面的 runCatching 吞掉后表现成「安装失败」，而且 20 次重试每次都以同样方式失败，
            // 最终回落到「窗口保留系统标题栏」—— 用户看到的就是系统标题栏叠在自绘顶栏之上。
            //
            // 反过来先设高是安全的：`setHeight` 末尾的 `notifyUpdate()` 在 `bar.window == null`
            // 时直接返回，不会去碰还没挂上的 peer（JBR 的 `Window$CustomTitleBar` 反汇编确认）。
            setHeight.invoke(bar, titleBarHeightPx)
            a.setCustomTitleBar.invoke(a.windowDecorations, window, bar)
            Controller(
                window = window,
                api = a,
                titleBar = bar,
                forceHitTest = findMethod(bar.javaClass, "forceHitTest", paramCount = 1),
                putProperty = findMethod(bar.javaClass, "putProperty", paramCount = 2),
                getRightInset = findMethod(bar.javaClass, "getRightInset", paramCount = 0),
            )
        }.onFailure {
            println("[JbrWindowChrome] 安装自定义标题栏失败：$it")
        }.getOrNull()
    }

    /** 已安装的自定义标题栏句柄。所有方法都吞异常：装饰是外观，绝不能反过来弄崩应用。 */
    class Controller internal constructor(
        private val window: Frame,
        private val api: WindowDecorationsApi,
        private val titleBar: Any,
        private val forceHitTest: Method?,
        private val putProperty: Method?,
        private val getRightInset: Method?,
    ) {

        /** 原生窗口钮区宽度（像素）：JBR 画的最小化 / 最大化 / 关闭占据的右侧空间，布局要给它留位。 */
        val rightInsetPx: Float
            get() = getRightInset?.let { m ->
                runCatching { m.invoke(titleBar) as? Float }.getOrNull()
            } ?: 0f

        /**
         * 回应 hit-test：`true` = 这一片是客户区（交互归应用），`false` = 交给原生
         * （拖拽 / 双击最大化 / 右键系统菜单）。必须在鼠标事件回调里调用（EDT 上），
         * 且 Exit / Wheel 之外**每个事件都要回**——原生侧用的是「上一次」的值。
         */
        fun forceClient(client: Boolean) {
            val m = forceHitTest ?: return
            runCatching { m.invoke(titleBar, client) }
        }

        /** 原生窗口钮的明暗。`dark = true` 是深色主题配色（深底浅色图标），跟随应用主题。 */
        fun setControlsDark(dark: Boolean) {
            val m = putProperty ?: return
            runCatching { m.invoke(titleBar, "controls.dark", dark) }
        }

        /** 撤销自定义标题栏、恢复系统标题栏（目前只有测试场景用得上）。 */
        fun uninstall() {
            runCatching { api.setCustomTitleBar.invoke(api.windowDecorations, window, null) }
        }
    }

    /** `WindowDecorations` 单例与其入口方法的反射句柄。 */
    internal class WindowDecorationsApi internal constructor(
        val windowDecorations: Any,
        val createCustomTitleBar: Method,
        val setCustomTitleBar: Method,
    ) {
        companion object {
            fun discover(): WindowDecorationsApi? {
                val wdClass = Class.forName("java.awt.Window\$WindowDecorations")
                // 实现类的构造器不公开，取第一个构造器强开（gist 与 compose-jetbrains-theme 同款做法）。
                val ctor = wdClass.declaredConstructors.first().also { forceAccessible(it) }
                val wd = ctor.newInstance()
                val create = findMethod(wdClass, "createCustomTitleBar", paramCount = 0) ?: return null
                // 有 Frame / Dialog 两个重载，按第一参数类型挑出 Frame 版。
                val setBar = wdClass.declaredMethods.firstOrNull {
                    it.name == "setCustomTitleBar" &&
                        it.parameterTypes.size == 2 &&
                        it.parameterTypes[0].isAssignableFrom(Frame::class.java)
                }?.also { forceAccessible(it) } ?: return null
                return WindowDecorationsApi(wd, create, setBar)
            }
        }
    }

    /** 沿类层次找方法（实现类可能把 API 方法留在父类上），找到即强开可访问。 */
    private fun findMethod(start: Class<*>, name: String, paramCount: Int): Method? {
        var c: Class<*>? = start
        while (c != null) {
            val m = c.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.size == paramCount
            }
            if (m != null) {
                forceAccessible(m)
                return m
            }
            c = c.superclass
        }
        return null
    }

    /** 与 `AccessibleObject` 同布局的替身类：布尔在前、对象引用在后。 */
    private class AccessibleOverrideLayout {
        var first = false

        @Volatile
        var second: Any? = null
    }

    private val unsafeAccess: UnsafeAccess? by lazy { UnsafeAccess.load() }

    /**
     * `sun.misc.Unsafe` 的**纯反射**句柄（只用到 `objectFieldOffset` / `putBooleanVolatile`）。
     *
     * ⚠️ 这里刻意不 `import sun.misc.Unsafe`、也不让这个类型出现在任何签名里。
     * 它只存在于 `jdk.unsupported` 模块，而 release 的 jlink 镜像是按模块清单裁过的：
     * 一旦镜像里没有它，**类常量池里的静态类型引用会在 `JbrWindowChrome` 的 `<clinit>` 期
     * 炸成 `NoClassDefFoundError: sun/misc/Unsafe`** —— 整个类初始化失败 ⇒ `main()` 直接退出，
     * 本文件所有「探测失败就回退无边框」的兜底一行都跑不到。
     * （2026-10-05 真实事故：1.4.3 安装包启动即 `Failed to launch JVM`，本地 `desktopRun` 因跑在
     * 完整 JBR SDK 上无法复现。打包侧已补 `modules("jdk.unsupported")`，这里再兜一层：
     * 缺模块时只是拿到 null，回退链路才真的成立。）
     */
    private class UnsafeAccess private constructor(
        private val instance: Any,
        private val objectFieldOffsetMethod: Method,
        private val putBooleanVolatileMethod: Method,
    ) {
        fun objectFieldOffset(field: Field): Long = objectFieldOffsetMethod.invoke(instance, field) as Long

        fun putBooleanVolatile(target: Any, offset: Long, value: Boolean) {
            putBooleanVolatileMethod.invoke(instance, target, offset, value)
        }

        companion object {
            fun load(): UnsafeAccess? = runCatching {
                val cls = Class.forName("sun.misc.Unsafe")
                val theUnsafe = cls.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)!!
                UnsafeAccess(
                    instance = theUnsafe,
                    objectFieldOffsetMethod = cls.getMethod("objectFieldOffset", Field::class.java),
                    putBooleanVolatileMethod = cls.getMethod(
                        "putBooleanVolatile",
                        Any::class.java,
                        Long::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                    ),
                )
            }.onFailure {
                println(
                    "[JbrWindowChrome] 取不到 sun.misc.Unsafe（运行时是否缺 jdk.unsupported 模块？），" +
                        "JBR 反射兜底不可用（将回退自绘无边框方案）：$it",
                )
            }.getOrNull()
        }
    }

    /**
     * 无条件把反射成员标成可访问。先走正规途径（public 成员本就不需要 open），
     * 被拒再翻 `override` 位 —— 偏移按「第一个实例字段」用替身类算出。
     */
    private fun forceAccessible(obj: AccessibleObject) {
        if (obj.trySetAccessible()) return
        val u = unsafeAccess ?: return
        val offset = u.objectFieldOffset(AccessibleOverrideLayout::class.java.getDeclaredField("first"))
        u.putBooleanVolatile(obj, offset, true)
    }
}
