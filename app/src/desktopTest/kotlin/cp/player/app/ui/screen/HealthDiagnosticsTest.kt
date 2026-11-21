package cp.player.app.ui.screen

import cp.player.app.diagnostics.AppDiagnostics
import cp.player.app.diagnostics.DiagnosticCategory
import cp.player.app.diagnostics.DiagnosticItem
import cp.player.app.diagnostics.DiagnosticReport
import cp.player.app.diagnostics.DiagnosticStatus
import cp.player.app.i18n.CpStrings
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthDiagnosticsTest {

    @Test
    fun `diagnostic report markdown formatting contains all sections`() {
        val sampleReport = DiagnosticReport(
            timestamp = 1732147200000L,
            score = 85,
            overallStatus = DiagnosticStatus.WARNING,
            items = listOf(
                DiagnosticItem(
                    id = "net",
                    category = DiagnosticCategory.NETWORK,
                    title = "公网连通性",
                    status = DiagnosticStatus.OK,
                    summary = "连接正常",
                    latencyMs = 45L,
                ),
                DiagnosticItem(
                    id = "search",
                    category = DiagnosticCategory.PROVIDER,
                    title = "搜索能力",
                    status = DiagnosticStatus.WARNING,
                    summary = "响应偏慢",
                    latencyMs = 3200L,
                    details = "返回 10 首歌曲",
                    suggestion = "请检查网络代理",
                ),
            ),
            systemSummary = "平台: Desktop · 版本: v1.0.0",
        )

        val md = AppDiagnostics.formatDiagnosticReportMarkdown(sampleReport)

        assertTrue(md.contains("# CPPlayer 诊断与健康体检报告"), "Markdown 缺少标题")
        assertTrue(md.contains("综合得分: 85 / 100 (WARNING)"), "Markdown 缺少评分或等级")
        assertTrue(md.contains("[通过] 公网连通性"), "缺少公网测试明细")
        assertTrue(md.contains("[警告] 搜索能力"), "缺少搜索测试明细")
        assertTrue(md.contains("排查建议: 请检查网络代理"), "缺少排查建议")
    }

    @Test
    fun `health screen adheres to non truncation font rules`() {
        val relative = "src/commonMain/kotlin/cp/player/app/ui/screen/HealthScreen.kt"
        var dir: File? = File("").absoluteFile
        val file = buildList {
            while (dir != null) {
                File(dir, relative).takeIf { it.isFile }?.let { add(it) }
                dir = dir.parentFile
            }
        }.firstOrNull() ?: error("找不到 HealthScreen.kt")

        val codeLines = file.readLines().filterNot { it.trimStart().startsWith("//") }

        val cpTextOccurrences = codeLines.filter { "CpText(" in it }
        assertTrue(
            cpTextOccurrences.isEmpty(),
            "HealthScreen 绝不允许使用 CpText（必须用裸 Text 以免信息被截断）：\n" + cpTextOccurrences.joinToString("\n"),
        )

        val maxLinesOccurrences = codeLines.filter { "maxLines" in it }
        assertTrue(
            maxLinesOccurrences.isEmpty(),
            "HealthScreen 绝不允许限制 maxLines（诊断原始数据必须自然折行完整展示）：\n" + maxLinesOccurrences.joinToString("\n"),
        )
    }

    @Test
    fun `health strings match and have no blank values`() {
        val zh = CpStrings.zh.health
        val en = CpStrings.en.health

        assertFalse(zh.screenTitle.isBlank())
        assertFalse(en.screenTitle.isBlank())

        assertFalse(zh.tabOverview.isBlank())
        assertFalse(en.tabOverview.isBlank())

        assertFalse(zh.tabProbe.isBlank())
        assertFalse(en.tabProbe.isBlank())

        assertFalse(zh.diagnosticTitle.isBlank())
        assertFalse(en.diagnosticTitle.isBlank())

        assertFalse(zh.runDiagnostics.isBlank())
        assertFalse(en.runDiagnostics.isBlank())

        assertFalse(zh.toolkitTitle.isBlank())
        assertFalse(en.toolkitTitle.isBlank())

        assertFalse(zh.probeTitle.isBlank())
        assertFalse(en.probeTitle.isBlank())

        assertFalse(zh.clearImageCacheAction.isBlank())
        assertFalse(en.clearImageCacheAction.isBlank())
        assertFalse(zh.probeSwitchProvider.isBlank())
        assertFalse(en.probeSwitchProvider.isBlank())
        assertFalse(zh.probeSearchQuickStream.isBlank())
        assertFalse(en.probeSearchQuickStream.isBlank())
        assertFalse(zh.probeSearchQuickLyric.isBlank())
        assertFalse(en.probeSearchQuickLyric.isBlank())
        assertFalse(zh.probeCopyResult.isBlank())
        assertFalse(en.probeCopyResult.isBlank())

        // 验证没有 Markdown 加粗标记混入文案
        assertFalse(zh.diagnosticTitle.contains("**"))
        assertFalse(en.diagnosticTitle.contains("**"))
        assertFalse(zh.diagnosticSubtitle.contains("**"))
        assertFalse(en.diagnosticSubtitle.contains("**"))
    }
}
