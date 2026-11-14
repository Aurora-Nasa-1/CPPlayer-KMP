package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import cp.player.app.readPng
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `CpRouteScaffold` 的 FAB 槽位回归 —— **重点是 embedded（双栏右栏）分支**。
 *
 * 这一处坏掉的方式没有任何编译期/运行时信号：页面照常渲染、能滚能点，只是右下角那个
 * 「+」**根本没画**。用户报的「音源管理没有导入入口」就是这么来的 ——
 * 桌面宽屏（默认 1320×860）下设置页用 `CpTwoPane` 把该页渲染进右栏并注入
 * `LocalEmbeddedInPane=true`，而 embedded 分支当时只 `content(...)` 就 return，
 * `floatingActionButton` 被整段丢掉；列表为空时列表项也没有，整页零入口。
 *
 * 所以这里做**离屏出图 + 像素断言**：FAB 用纯红色方块，检查红色像素的位置。
 * 编译和单测都量不到「有没有画」，只有像素能。
 */
class RouteScaffoldFabTest {

    private val canvas = 200
    private val density = 1f

    @Test
    fun `embedded 分支必须画出 floatingActionButton`() {
        val red = render(tail = 0.dp).redBounds() ?: error(
            "双栏右栏（embedded）里没有画出 FAB —— 音源管理会变成「没有导入入口」",
        )

        // density=1f ⇒ 1dp = 1px；槽位是 16dp 边距 + 24dp 方块。
        // ⚠️ 容差 ±1px：24dp 方块与 16dp padding 合成后的布局取整会落在 183 而不是 184
        // （实测），钉死绝对值会让这个用例在无关的取整变化上假红。
        assertNear(canvas - 16, red.right, "FAB 应贴右缘 16dp")
        assertNear(canvas - 16, red.bottom, "没有播放条时 FAB 应贴下缘 16dp")
        assertNear(24, red.right - red.left + 1, "FAB 宽度应为 24dp（没被裁掉半边）")
    }

    @Test
    fun `有迷你播放器尾留白时 FAB 要上移 不被压住`() {
        val plain = render(tail = 0.dp).redBounds() ?: error("无尾留白时也必须画出 FAB")
        val shifted = render(tail = 64.dp).redBounds() ?: error("有尾留白时也必须画出 FAB")

        // 小播放器浮在页面内容之上，只留 16dp 的 FAB 会被它压掉半截、点不到。
        assertTrue(
            plain.bottom - shifted.bottom >= 60,
            "尾留白 64dp 时 FAB 应上移约 64px，实际只移动了 ${plain.bottom - shifted.bottom}px",
        )
    }

    // ---------------- 离屏渲染与像素工具 ----------------

    private class Pixels(val width: Int, val height: Int, val argb: IntArray) {
        fun redBounds(): Bounds? {
            var left = Int.MAX_VALUE
            var top = Int.MAX_VALUE
            var right = -1
            var bottom = -1
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val c = argb[y * width + x]
                    val r = (c ushr 16) and 0xFF
                    val g = (c ushr 8) and 0xFF
                    val b = c and 0xFF
                    if (r > 200 && g < 60 && b < 60) {
                        if (x < left) left = x
                        if (x > right) right = x
                        if (y < top) top = y
                        if (y > bottom) bottom = y
                    }
                }
            }
            return if (right < 0) null else Bounds(left, top, right, bottom)
        }
    }

    private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun render(tail: Dp): Pixels {
        val scene = ImageComposeScene(
            width = canvas,
            height = canvas,
            density = Density(density),
            content = {
                CompositionLocalProvider(LocalMiniPlayerTailSpace provides tail) {
                    // 白底：红色 FAB 之外的区域必须可区分（ImageComposeScene 的背景是透明的）。
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        CpRouteScaffold(
                            title = "音源与歌词管理",
                            onBack = null,
                            embedded = true,
                            floatingActionButton = {
                                Box(Modifier.size(24.dp).background(Color.Red))
                            },
                        ) { modifier ->
                            Box(modifier.background(Color.White))
                        }
                    }
                }
            },
        )
        return try {
            val bytes = scene.render().encodeToData()?.bytes ?: error("encode failed")
            savePreview(bytes, "embedded-tail-${tail.value.toInt()}")
            val png = readPng(bytes)
            val argb = IntArray(png.width * png.height)
            png.getRGB(0, 0, png.width, png.height, argb, 0, png.width)
            Pixels(png.width, png.height, argb)
        } finally {
            scene.close()
        }
    }

    /** 出图留档，便于人工核对（`app/build-preview-route-fab/`）。 */
    private fun savePreview(bytes: ByteArray, name: String) {
        val dir = File("build-preview-route-fab")
        dir.mkdirs()
        File(dir, "$name.png").writeBytes(bytes)
    }

    /** 像素位置断言，容差 ±1px（dp→px 取整）。 */
    private fun assertNear(expected: Int, actual: Int, message: String) {
        assertTrue(
            kotlin.math.abs(expected - actual) <= 1,
            "$message（期望 $expected±1，实际 $actual）",
        )
    }
}
