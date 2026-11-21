package cp.player.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 配色方案的不变量测试。
 *
 * 被测的是**生成函数本身**（`dynamicColorScheme` + `SPEC_2025` + `TonalSpot`），
 * 因为静态回退色板和动态取色现在走的是同一个函数 —— 这里过了，两条路就都过了。
 *
 * 钉住三件事：
 * 1. `surfaceVariant` 必须与 `surfaceContainerHighest` 等值（M3 里前者只是后者的别名）。
 *    不等值会让仍在读 `surfaceVariant` 的旧代码比同页其它容器暗一档。
 * 2. 表面阶梯单调：序号越高越强调（浅色更暗、深色更亮）。
 * 3. 容器 / on 容器配对方向正确 —— **这条抓的是 materialkolor 5.0.1 在 SPEC_2025 下
 *    把深色 tertiary 容器取反的真实缺陷**（`tertiaryContainer` = #F7D0FD 浅粉）。
 *    断言跑在 `withConsistentContainerPairs` 之后，也就是应用实际使用的那份方案。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class PaletteInvariantsTest {

    private fun generate(dark: Boolean, amoled: Boolean = false): ColorScheme = dynamicColorScheme(
        seedColor = PrimaryLight,
        isDark = dark,
        isAmoled = amoled,
        style = PaletteStyle.TonalSpot,
        specVersion = ColorSpec.SpecVersion.SPEC_2025,
    )

    private fun hex(c: Color): String {
        val a = c.toArgb()
        return "#%02X%02X%02X".format((a shr 16) and 0xFF, (a shr 8) and 0xFF, a and 0xFF)
    }

    @Test
    fun `surfaceVariant 是 surfaceContainerHighest 的别名`() {
        for (dark in listOf(false, true)) {
            val s = generate(dark)
            assertEquals(
                s.surfaceContainerHighest,
                s.surfaceVariant,
                "surfaceVariant 与 surfaceContainerHighest 不等值（dark=$dark）",
            )
        }
    }

    @Test
    fun `表面阶梯单调`() {
        for (dark in listOf(false, true)) {
            val s = generate(dark)
            val ladder = listOf(
                "surface" to s.surface,
                "surfaceContainerLow" to s.surfaceContainerLow,
                "surfaceContainer" to s.surfaceContainer,
                "surfaceContainerHigh" to s.surfaceContainerHigh,
                "surfaceContainerHighest" to s.surfaceContainerHighest,
            )
            ladder.zipWithNext().forEach { (lower, higher) ->
                val ok = if (dark) {
                    higher.second.luminance() > lower.second.luminance()
                } else {
                    higher.second.luminance() < lower.second.luminance()
                }
                assertTrue(
                    ok,
                    "阶梯断了（dark=$dark）：${lower.first}=${hex(lower.second)} → " +
                        "${higher.first}=${hex(higher.second)}",
                )
            }
        }
    }

    @Test
    fun `容器与 on 容器配对方向正确`() {
        for (dark in listOf(false, true)) {
            val s = generate(dark).withConsistentContainerPairs(dark)
            val pairs = listOf(
                "primary" to (s.primaryContainer to s.onPrimaryContainer),
                "secondary" to (s.secondaryContainer to s.onSecondaryContainer),
                "tertiary" to (s.tertiaryContainer to s.onTertiaryContainer),
                "error" to (s.errorContainer to s.onErrorContainer),
            )
            for ((name, pair) in pairs) {
                val (container, onContainer) = pair
                val containerIsLighter = container.luminance() > onContainer.luminance()
                assertEquals(
                    !dark,
                    containerIsLighter,
                    "$name 容器方向反了（dark=$dark）：container=${hex(container)} on=${hex(onContainer)}",
                )
            }
        }
    }

    @Test
    fun `背景保留主题染色而文字与强调色分离`() {
        val raw = generate(dark = true)
        val adapted = raw.withNeutralSurfaces()

        fun chroma(color: Color): Float =
            maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue)

        // 背景保留可见的主题色调（M3E 分组模型的味道），但比原始染色弱。
        val bgBefore = chroma(raw.surfaceContainer)
        val bgAfter = chroma(adapted.surfaceContainer)
        assertTrue(
            bgAfter < bgBefore && bgAfter > bgBefore * 0.4f,
            "背景应保留部分染色：surfaceContainer 彩度 $bgBefore → $bgAfter",
        )
        // 文字近乎中性 —— 这是「脏 + 发糊」的真正根源，必须强去染色。
        val textBefore = chroma(raw.onSurface)
        val textAfter = chroma(adapted.onSurface)
        assertTrue(
            textAfter < textBefore * 0.2f,
            "文字去染色没生效：onSurface 彩度 $textBefore → $textAfter",
        )
        // 强调色是种子色唯一的出口，一个都不能被动到。
        assertEquals(raw.primary, adapted.primary, "primary 不该被去染色")
        assertEquals(raw.primaryContainer, adapted.primaryContainer, "primaryContainer 不该被去染色")
        assertEquals(raw.secondaryContainer, adapted.secondaryContainer, "secondaryContainer 不该被去染色")
        assertEquals(raw.tertiaryContainer, adapted.tertiaryContainer, "tertiaryContainer 不该被去染色")
    }

    /**
     * 把当前生成结果落到 `app/build/palette-dump.txt`，便于排查「某个角色到底是几号色」。
     * 不是断言，失败不影响构建。
     */
    @Test
    fun `dump palette for debugging`() {
        val out = StringBuilder()
        for ((dark, amoled) in listOf(false to false, true to false, true to true)) {
            val s = generate(dark, amoled)
            out.appendLine("### dark=$dark amoled=$amoled")
            listOf(
                "surface" to s.surface,
                "onSurface" to s.onSurface,
                "surfaceDim" to s.surfaceDim,
                "surfaceBright" to s.surfaceBright,
                "surfaceContainerLowest" to s.surfaceContainerLowest,
                "surfaceContainerLow" to s.surfaceContainerLow,
                "surfaceContainer" to s.surfaceContainer,
                "surfaceContainerHigh" to s.surfaceContainerHigh,
                "surfaceContainerHighest" to s.surfaceContainerHighest,
                "surfaceVariant" to s.surfaceVariant,
                "primary" to s.primary,
                "primaryContainer" to s.primaryContainer,
                "secondary" to s.secondary,
                "secondaryContainer" to s.secondaryContainer,
                "tertiary" to s.tertiary,
                "tertiaryContainer" to s.tertiaryContainer,
                "onTertiaryContainer" to s.onTertiaryContainer,
                "outline" to s.outline,
                "outlineVariant" to s.outlineVariant,
            ).forEach { (name, color) -> out.appendLine("$name=${hex(color)}") }
        }
        File("build/palette-dump.txt").apply {
            parentFile?.mkdirs()
            writeText(out.toString())
        }
    }
}
