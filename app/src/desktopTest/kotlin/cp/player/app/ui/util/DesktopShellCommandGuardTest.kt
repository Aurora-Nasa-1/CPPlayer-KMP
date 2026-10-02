package cp.player.app.ui.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 钉住「全局入口 → 主壳层」这条纪律（源码扫描，与 `IntegrationBoundaryTest` 同一套路）。
 *
 * ## 为什么要用源码扫描而不是行为测试
 *
 * 这个坑**编译期看不出来、运行期也不报错**，只会表现为「点了没反应」：
 *
 * `MainScreen` 是根 Navigator 的**起点**，Voyager 只渲染栈顶 —— push 到歌单详情之后
 * 它就离开组合了。而「设置 / 消息 / 搜索」三个入口发的是 `DesktopShell` 的单向指令，
 * 靠 `MainScreen` 里的 `LaunchedEffect` 消费。**MainScreen 不在 ⇒ 没人消费 ⇒ 点击静默失败**，
 * 而且指令残留成 `true`，等用户退回主壳层时面板又自己弹出来。
 *
 * 用户报的「打开歌单后点设置没弹出」就是这一条。
 *
 * 行为测试在这里成本很高（`Navigator` 构造需要 `SaveableStateHolder` 与 Compose 运行时），
 * 而真正要防的是「将来有人新增一个走指令通道的入口、忘了先回主壳层」——
 * 那种漏法用源码扫描反而最直接。
 */
class DesktopShellCommandGuardTest {

    private val mainFile: File = run {
        // 从本模块的源码树里定位 desktopMain 的 Main.kt。
        // 只扫这一个文件：它是标题栏的唯一注入点（见 App 的 titleBar 槽位）。
        val root = File("src/desktopMain/kotlin/cp/player/app/Main.kt")
        assertTrue(root.exists(), "找不到 ${root.path}（测试的工作目录应为 :app 模块根）")
        root
    }

    private val source: String get() = mainFile.readText()

    /** 这三个字段是通过「单向指令」驱动主壳层面板的，发指令前必须先弹回主壳层。 */
    private val commandFields = listOf(
        "settingsRequested",
        "messagesRequested",
        "pendingSearchQuery",
    )

    @Test
    fun `each shell command is preceded by a pop back to the main shell`() {
        val text = source
        commandFields.forEach { field ->
            // 找到所有 `DesktopShell.<field> = ` 的写入点
            val writes = Regex("DesktopShell\\.$field\\s*=").findAll(text).toList()
            assertTrue(
                writes.isNotEmpty(),
                "Main.kt 里找不到 `DesktopShell.$field = ` 的写入点 —— " +
                    "如果这个入口已经不存在了，请把它从 commandFields 里移除",
            )
            writes.forEach { match ->
                // 往前看一小段窗口（够放下 popToMainShell() 调用即可），
                // 必须出现 popToMainShell —— 它就是「先回主壳层」的动作。
                val windowStart = (match.range.first - 200).coerceAtLeast(0)
                val preceding = text.substring(windowStart, match.range.first)
                assertTrue(
                    preceding.contains("popToMainShell"),
                    "`DesktopShell.$field = ` 之前必须先调用 navigator.popToMainShell()。\n" +
                        "原因：MainScreen 是根 Navigator 的起点，push 出去后它就离开组合了，\n" +
                        "那时这条指令没人消费 ⇒ 点击完全没有反应（静默失败），\n" +
                        "而且残留 true 会让面板在退回主壳层时自己弹出来。\n" +
                        "出错位置附近：\n${preceding.takeLast(160)}",
                )
            }
        }
    }

    @Test
    fun `popToMainShell is imported`() {
        assertTrue(
            source.contains("import cp.player.app.ui.util.popToMainShell"),
            "Main.kt 用了 popToMainShell 却没有 import —— 依赖 IDE 自动导入会在别人的机器上编译失败",
        )
    }
}
