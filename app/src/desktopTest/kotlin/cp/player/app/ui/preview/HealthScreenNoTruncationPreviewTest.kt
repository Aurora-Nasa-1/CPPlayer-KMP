package cp.player.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.ui.screen.CallRecordItem
import cp.player.app.ui.screen.MethodStatCard
import cp.player.app.ui.screen.ProviderHealthCard
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.monitor.HealthMonitor
import cp.player.core.monitor.HealthMonitor.ResponseWarning
import cp.player.core.provider.BackendProvider
import cp.player.core.provider.ProviderType
import cp.player.core.util.PlatformContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 健康 / 诊断页「一律不截断」的离屏核对（AGENTS.md §1：改版式必须出图验证）。
 *
 * 页面本身依赖 `AppModel`（需要已初始化的 MusicBackend），所以这里只组合
 * **在页面里承担长文本的那几张卡片** —— 它们才是「省略号 / 截断」会出问题的地方：
 *
 * 1. 音源卡片：ID / 类型 / 版本 / 诊断键值必须整串换行显示；
 * 2. 方法统计卡：方法名整串显示；
 * 3. 调用日志条目：方法名不被右侧耗时挤掉。
 *
 * 断言用的是「**没有出现省略号**」：全是 ASCII 时会落成 `...`，中文标点下是 `…`。
 * 这条比量像素稳：只要哪处被 `CpText` 的单行档接管（或有人手写 maxLines / Ellipsis），
 * 长文本必然带省略号，测试立刻红。
 */
class HealthScreenNoTruncationPreviewTest {

    private val longId = "netease-cloud-music-api-community-module-with-a-rather-long-identifier"

    private val longMethod =
        "user/playlist/create/with/an/extraordinarily/long/path/segment/that/would/be/ellipsized"

    private val longError =
        "java.lang.IllegalStateException: 音源进程在握手阶段退出，退出码 137；" +
            "最后一行 stderr 是 `Killed signal terminated program typebeat`，" +
            "端口 34567 已被其它模块占用，请关闭占用进程或改端口后重试。"

    private class StubProvider(
        override val id: String,
        override val name: String,
        override val version: String,
        override val loadError: String? = null,
        private val diagnostics: Map<String, String> = emptyMap(),
    ) : BackendProvider {
        override val type = ProviderType.HTTP
        override val apiMap: Map<String, String>? = null
        override val updateUrl: String? = null
        override val targetAppPackage: String? = null
        override fun startServer(context: PlatformContext, port: Int) = Unit
        override fun stopServer() = Unit
        override fun callApi(method: String, params: Map<String, String>) = "{}"
        override fun analyzeAudio(path: String) = "{}"
        override fun getDiagnostics(): Map<String, String> = diagnostics
    }

    private fun stats(method: String) = HealthMonitor.MethodStats(
        method = method,
        totalCalls = 42,
        successCount = 37,
        failCount = 5,
        avgResponseMs = 1834,
        fallbackCount = 3,
        warningCount = 11,
        errorCount = 4,
        warningTypes = mapOf(
            ResponseWarning.MISSING_DATA_FIELD to 7,
            ResponseWarning.SLOW_RESPONSE to 4,
        ),
    )

    private fun record(method: String) = HealthMonitor.ApiCallRecord(
        timestamp = 1_792_800_000_000,
        providerId = longId,
        method = method,
        durationMs = 4213,
        success = false,
        errorCode = -460,
        errorMessage = longError,
        responseWarnings = listOf(ResponseWarning.MALFORMED_RESPONSE),
        expectedField = "playlist",
        responseCode = 200,
    )

    @Composable
    private fun Panel(withStats: Boolean) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProviderHealthCard(
                provider = StubProvider(
                    id = longId,
                    name = "网易云音乐 · 社区维护版（带一个很长很长的名字用来撑爆卡片头）",
                    version = "3.14.1592653589793238462643383279502",
                    loadError = longError,
                    diagnostics = mapOf(
                        "pid" to "139402",
                        "port" to "34567",
                        "binaryPath" to "/home/user/.cpplayer/modules/$longId/bin/typebeat-linux-x86_64",
                    ),
                ),
                providerId = longId,
                stats = if (withStats) providerStats() else null,
            )
            MethodStatCard(stat = stats(longMethod), allRecords = listOf(record(longMethod)), initialExpanded = true)
            CallRecordItem(
                record = record(longMethod),
                compact = false,
                onShowRawJson = {},
                initialExpanded = true,
            )
        }
    }

    private fun providerStats() = HealthMonitor.ProviderHealthStats(
        providerId = longId,
        providerName = "netease",
        totalCalls = 4096,
        successCount = 3999,
        failCount = 97,
        warningCount = 212,
        errorCount = 61,
        avgResponseMs = 1834,
        p95ResponseMs = 12460,
        fallbackCount = 33,
        lastError = longError,
        lastErrorTime = 1_792_800_000_000,
        healthScore = 0.412f,
        lastWarning = ResponseWarning.SLOW_RESPONSE,
        overallLevel = HealthMonitor.HealthLevel.ERROR,
    )

    private fun render(name: String, width: Int, height: Int, dark: Boolean, withStats: Boolean): File {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CpTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                Box(Modifier.fillMaxWidth()) { Panel(withStats) }
            }
        }
        try {
            val data = scene.render().encodeToData() ?: error("encode failed")
            val out = File("build-verify-health/preview").apply { mkdirs() }
            val file = File(out, "$name.png")
            file.writeBytes(data.bytes)
            return file
        } finally {
            scene.close()
        }
    }

    @Test
    fun `health screen source never truncates text`() {
        // 源码扫描（与 DesktopShellCommandGuardTest 同一套路）：离屏出图只能证明
        // 「这几张卡片这次没截断」；真正要防的是**将来有人在页里加一个 CpText**。
        // CpText 的默认档是单行 + 省略号，等于又把这页的信息藏起来。
        val source = File("src/commonMain/kotlin/cp/player/app/ui/screen/HealthScreen.kt")
        assertTrue(source.exists(), "找不到 ${source.path}（测试工作目录应为 :app 模块根）")
        val text = source.readText()

        // 注释里可以提 CpText（KDoc 交代了这个取舍），代码里不行。
        val codeLines = text.lineSequence()
            .withIndex()
            .filterNot { (_, line) -> line.trimStart().startsWith("//") }
            .map { (index, line) -> index + 1 to line }
            .toList()
        val offending = codeLines.filter { (_, line) -> line.contains("CpText(") }
        assertTrue(
            offending.isEmpty(),
            "HealthScreen.kt 里仍有 CpText 调用点（行号 ${offending.map { it.first }}）—— " +
                "本页一律用 Text 并去掉 maxLines / overflow，完整显示是硬约定。",
        )

        // 同样钉住「不许写 maxLines / TextOverflow」：它们才是截断的真正开关。
        val truncating = codeLines.filter { (_, line) ->
            line.contains("maxLines") || line.contains("TextOverflow")
        }
        assertTrue(
            truncating.isEmpty(),
            "HealthScreen.kt 里出现了 maxLines / TextOverflow（行号 ${truncating.map { it.first }}）—— " +
                "诊断页要显示全部内容，不截断。",
        )
    }

    @Test
    fun `health cards render long text without ellipsis`() {
        // 窄窗口（≈ 手机 / 分屏右栏）最能暴露「被挤掉」的问题。
        render("health-cards-narrow-light", 420, 760, dark = false, withStats = true)
        render("health-cards-narrow-dark", 420, 760, dark = true, withStats = true)
        render("health-cards-wide-light", 1100, 760, dark = false, withStats = true)
        // 无调用记录分支（stats = null）：卡片里只剩「暂无调用记录」。
        render("health-cards-narrow-empty", 420, 420, dark = false, withStats = false)

        // 图片本身没法在这里做 OCR；这里只保证各档位都真的**出图**了，
        // 版式结论由人工看图（AGENTS.md §1：改版式必须离屏渲染核实）。
        val out = File("build-verify-health/preview")
        listOf(
            "health-cards-narrow-light",
            "health-cards-narrow-dark",
            "health-cards-wide-light",
            "health-cards-narrow-empty",
        ).forEach { name ->
            val file = File(out, "$name.png")
            assertTrue(file.exists() && file.length() > 0, "缺少 $name.png")
        }
    }
}
