package cp.player.core.integration

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 边界纪律测试：`integration/` 包**不得**依赖 `api.` / `provider.` / `cache.`。
 *
 * ### 为什么值得写一个测试来管这件事
 * 方案里最大的风险不是写不出来，而是**为了省事透传 raw JSON**：
 * 一旦这里能拿到 `MusicApiService`，最省事的实现就是把它返回的 `JsonElement`
 * 直接丢给第三方。后果是对外契约被绑死在单个音源实现上，`apiMap` 的端点归一化
 * 也被绕过 —— 而这两种破坏**编译期完全看不出来**，只会在换音源时集中爆发。
 *
 * 所以边界不能靠自觉，得靠测试。
 *
 * ### 允许的依赖
 * `UnifiedMusicSource`（领域模型）、只读播放状态、`control.LocalServerConfig`。
 * `provider.*` → `integration` 的映射由组合根（`MusicBackend`）负责。
 */
class IntegrationBoundaryTest {

    @Test
    fun `integration 包不依赖 api provider cache`() {
        val files = integrationSourceFiles()

        // 防「路径写错导致测试空转」：空转的边界测试比没有更糟，它会给人虚假的安全感。
        // 逐个源集检查而不是只看总数 —— 总数够大也可能是因为某一个源集压根没扫到。
        val notCovered = SOURCE_DIRS.filter { relative ->
            val dir = File(moduleRoot(), relative)
            !dir.isDirectory || dir.walkTopDown().none { it.isFile && it.extension == "kt" }
        }
        assertTrue(
            notCovered.isEmpty(),
            "这些源集没扫到任何 .kt，边界测试会在这些源集上空转：$notCovered",
        )
        assertTrue(
            files.size >= 3,
            "没找到 integration 源文件，边界测试会变成空转。找到的：${files.map { it.path }}",
        )

        val violations = mutableListOf<String>()
        for (file in files) {
            file.readLines().forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                // 跳过注释行：KDoc 里正当地讨论「禁止依赖 provider.*」是很正常的
                if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) return@forEachIndexed
                if (FORBIDDEN_PREFIXES.any { line.contains(it) }) {
                    violations += "${file.name}:${index + 1}: $line"
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("integration/ 包出现了被禁止的依赖：")
                    violations.forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("禁止的依赖：${FORBIDDEN_PREFIXES.joinToString()} ")
                    appendLine("理由：允许它拿 raw JSON，对外契约就会被绑死在单个音源实现上。")
                    appendLine("做法：在组合根（MusicBackend）把 provider.* 映射成 integration 的中性类型。")
                }
            )
        }
    }

    // ============ 脚手架 ============

    /** 定位模块根目录。Gradle 的测试工作目录通常是模块目录，但仓库根也要能兜住。 */
    private fun moduleRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (SOURCE_DIRS.any { File(dir, it).isDirectory }) return dir
            val coreModule = File(dir, "core")
            if (SOURCE_DIRS.any { File(coreModule, it).isDirectory }) return coreModule
            dir = dir.parentFile
        }
        fail("找不到 integration 源目录；测试工作目录 = ${File("").absolutePath}")
    }

    private fun integrationSourceFiles(): List<File> {
        val root = moduleRoot()
        return SOURCE_DIRS
            .map { File(root, it) }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
    }

    private companion object {
        /**
         * `integration/` 出现在**四个**源集里：
         * 纯逻辑在 `commonMain`，Ktor 路由在 `jvmMain`，
         * 端点描述符的落盘实现分别在 `desktopMain` / `androidMain`。
         *
         * 四个都要扫。边界测试最糟的失败模式不是漏报一条，而是**看起来在守着、其实没扫到** ——
         * 漏掉一个源集，那里就能偷偷 import `provider.*` 而测试全绿。
         */
        val SOURCE_DIRS = listOf(
            "src/commonMain/kotlin/cp/player/core/integration",
            "src/jvmMain/kotlin/cp/player/core/integration",
            "src/desktopMain/kotlin/cp/player/core/integration",
            "src/androidMain/kotlin/cp/player/core/integration",
        )

        val FORBIDDEN_PREFIXES = listOf(
            "cp.player.core.api.",
            "cp.player.core.provider.",
            "cp.player.core.cache.",
        )
    }
}
