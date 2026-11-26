package cp.player.app.presentation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 架构防回归：状态机与导航库解耦，组件使用应用导航入口。
 *
 * 这不是等价于编译器的检查 —— 编译通过只说明依赖当前可用，不阻止下一轮改动
 * 又把 `ScreenModel` / `LocalNavigator` 塞回业务层。这里直接扫源码，越界即失败。
 */
class DecouplingGuardTest {

    private val appSourceRoot: File by lazy {
        val relative = "src/commonMain/kotlin/cp/player/app"
        var dir: File? = File("").absoluteFile
        val found = buildList {
            while (dir != null) {
                File(dir, relative).takeIf { it.isDirectory }?.let { add(it) }
                dir = dir.parentFile
            }
        }.firstOrNull()
        check(found != null) { "找不到 app 源码根目录（cwd=${File("").absolutePath}）" }
        found
    }

    @Test
    fun `presentation package stays framework agnostic`() {
        val offenders = appSourceRoot.resolve("presentation")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines()
                    .withIndex()
                    .filter { (_, line) ->
                        listOf(
                            "cafe.adriel.voyager",
                            "androidx.navigation3",
                            "androidx.lifecycle",
                            "androidx.compose",
                            "cp.player.app.ui.",
                        ).any { it in line }
                    }
                    .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
            }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "presentation 层不得依赖 Compose、导航框架、Lifecycle 或 ui 包：\n" + offenders.joinToString("\n"),
        )
    }

    @Test
    fun `shared components do not import navigation frameworks`() {
        val roots = listOf("ui/component", "ui/wall").map(appSourceRoot::resolve)
        val offenders = roots
            .flatMap { it.walkTopDown().filter { file -> file.isFile && file.extension == "kt" } }
            .flatMap { file ->
                file.readLines()
                    .withIndex()
                    .filter { (_, line) ->
                        line.startsWith("import androidx.navigation3") || line.startsWith("import cafe.adriel.voyager")
                    }
                    .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
            }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "共享组件只能通过 AppRoute / LocalAppNavigator 跳转：\n" +
                offenders.joinToString("\n"),
        )
    }
}
