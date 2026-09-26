package cp.player.app.ui.theme

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [parseAccentColor] 回归测试：钉住 DWM `AccentColor` 的 **ABGR** 字节序。
 *
 * 这是「取到蓝色主题却渲染成橙色」那类缺陷的唯一防线 —— 字节序写反不会有任何报错，
 * 只会让桌面端的「跟随系统」看起来像随机配色，而没有人会想到去查注册表格式。
 */
class WindowsAccentColorTest {

    @Test
    fun `the Windows default blue round-trips as ABGR`() {
        // 注册表默认值 0xffd47800 必须解析成 Windows 的默认强调蓝 #0078D4。
        // 若按 ARGB 读，得到的是 #D47800（橙）。
        val color = parseAccentColor("    AccentColor    REG_DWORD    0xffd47800")
        assertNotNull(color, "默认值必须能解析出来")
        assertChannel(0x00, color.red, "red")
        assertChannel(0x78, color.green, "green")
        assertChannel(0xD4, color.blue, "blue")
    }

    @Test
    fun `the full reg query output is parsed`() {
        // 真实的 reg query 输出带表头，正则必须能跨行命中目标行。
        val output = buildString {
            appendLine()
            appendLine("HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\DWM")
            appendLine("    AccentColor    REG_DWORD    0xff1d4f4f")
            appendLine()
        }
        val color = parseAccentColor(output)
        assertNotNull(color, "带表头的真实输出必须能解析")
        assertChannel(0x4F, color.red, "red")
        assertChannel(0x4F, color.green, "green")
        assertChannel(0x1D, color.blue, "blue")
    }

    @Test
    fun `a zero alpha is treated as opaque`() {
        // 用户没开「透明效果」时 alpha 常为 0；按原样使用会得到一个全透明的种子色，
        // materialkolor 会据此生成一套几乎全黑的主题。
        val color = parseAccentColor("    AccentColor    REG_DWORD    0x001d4f4f")
        assertNotNull(color)
        assertEquals(1f, color.alpha, "alpha=0 必须当作不透明")
    }

    @Test
    fun `a missing value yields null instead of a wrong colour`() {
        assertNull(
            parseAccentColor("ERROR: The system was unable to find the specified registry key or value."),
            "读不到时必须返回 null 让主题回退，而不是猜一个颜色",
        )
        assertNull(parseAccentColor(""))
    }

    private fun assertChannel(expected: Int, actual: Float, name: String) {
        val value = (actual * 255f).roundToInt()
        assertTrue(
            abs(value - expected) <= 1,
            "$name 通道期望 0x${expected.toString(16)}，实际 0x${value.toString(16)}",
        )
    }
}
