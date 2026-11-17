package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.readPng
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 三段式（仿 Apple Music）播放页进度条的版式回归。
 *
 * 三条规格里的两条只有出图能量：**没有拇指圆点**（静置 / 按住都不能在播放头位置
 * 出现任何点）与 **按住变粗**（轨道从 4dp 长到 12dp）。剩下的「松手回缩」走
 * 同一套绘制路径，不单独量。
 *
 * 判据都用像素列高：轨道是横向实心圆头矩形，某一列的着色像素数就是它的粗细。
 */
class AppleMusicSeekBarLayoutTest {

    private data class Shot(val bytes: ByteArray, val image: cp.player.app.PngImage)

    /** 出图：`press` 时为按住状态出图（先按下再渲染）。 */
    private fun renderSeekBar(press: Boolean): Shot {
        val scene = ImageComposeScene(width = 360, height = 80, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.DARK) {
                Box(Modifier.fillMaxWidth().background(Color.Black).padding(8.dp)) {
                    CpMinimalSeekBar(
                        positionMs = 60_000L,
                        durationMs = 240_000L,
                        onSeek = {},
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    )
                }
            }
        }
        try {
            // 先把动画跑到稳态，再决定是否按下：变粗有 150ms 左右的 fastSpatial。
            var t = 0L
            repeat(4) {
                scene.render(t)
                t += 16_000_000L
            }
            if (press) {
                scene.sendPointerEvent(
                    PointerEventType.Press, Offset(180f, 40f), Offset.Zero, 0L, PointerType.Touch,
                )
                // 动画必须真的走完：出图是同步的，不推进时间就量到中间帧。
                repeat(40) {
                    scene.render(t)
                    t += 16_000_000L
                }
            }
            val bytes = scene.render().encodeToData()?.bytes ?: error("PNG encode failed")
            return Shot(bytes, readPng(bytes))
        } finally {
            scene.close()
        }
    }

    /** 某一列里「明显亮于背景」的像素个数 —— 即轨道在这一列的粗细。 */
    private fun columnThickness(shot: Shot, x: Int): Int {
        var count = 0
        for (y in 0 until shot.image.height) {
            if (luma(shot.image.getRGB(x, y)) > 24) count++
        }
        return count
    }

    private fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    private fun dump(name: String, shot: Shot) {
        val out = File("build/$name.png")
        out.parentFile?.mkdirs()
        out.writeBytes(shot.bytes)
    }

    @Test
    fun `静置时是细轨道且播放头没有圆点`() {
        val shot = renderSeekBar(press = false)
        dump("am-seekbar-idle", shot)
        // 播放头在 25%（60000/240000），x≈ 8 + 0.25*344 ≈ 94。左右各取一列。
        val atPlayhead = columnThickness(shot, 94)
        val behindPlayhead = columnThickness(shot, 60)
        val aheadOfPlayhead = columnThickness(shot, 200)
        println("[seekbar] idle 播放头列=$atPlayhead 已播列=$behindPlayhead 未播列=$aheadOfPlayhead")
        assertTrue(behindPlayhead in 3..6, "静置轨道应为 4dp 细线（粗度 3~6），实测 $behindPlayhead")
        assertTrue(aheadOfPlayhead in 3..6, "静置轨道应为 4dp 细线（粗度 3~6），实测 $aheadOfPlayhead")
        assertEquals(
            behindPlayhead, atPlayhead,
            "播放头处不许比轨道更粗 —— 那就是拇指圆点（被测出 $atPlayhead vs 轨道 $behindPlayhead）",
        )
    }

    @Test
    fun `按住时整条轨道变粗且仍然没有圆点`() {
        val idle = renderSeekBar(press = false)
        val pressed = renderSeekBar(press = true)
        dump("am-seekbar-pressed", pressed)
        val idleBehind = columnThickness(idle, 60)
        val pressedBehind = columnThickness(pressed, 60)
        val pressedAhead = columnThickness(pressed, 200)
        val pressedAtPlayhead = columnThickness(pressed, 94)
        println("[seekbar] idle=$idleBehind pressed=$pressedBehind 未播列=$pressedAhead 播放头列=$pressedAtPlayhead")
        assertTrue(
            pressedBehind >= 10,
            "按住后轨道应长到 12dp（粗度 ≥10），实测 $pressedBehind（静置 $idleBehind）",
        )
        assertEquals(
            pressedBehind, pressedAhead,
            "按住是**整条**变粗，已播与未播必须同粗（$pressedBehind vs $pressedAhead）",
        )
        assertTrue(
            pressedAtPlayhead <= pressedBehind + 1,
            "按住后播放头仍不许出现圆点（播放头列 $pressedAtPlayhead / 轨道 $pressedBehind）",
        )
    }

    @Test
    fun `按住变粗的绘制不读组合期状态`() {
        // 结构断言：粗细必须在 Canvas 的 draw lambda 里由动画值算出，
        // 提到组合期就等于每帧重组整条滑条（含 M3 交互层）。
        val src = readComponentSource("ExpressiveKit.kt")
        val body = src.substringAfter("fun CpMinimalSeekBar(").substringBefore("// ---------------------------------------------------------------- 形状")
        assertTrue("animateFloatAsState(" in body, "粗细应由 animateFloatAsState 驱动")
        assertTrue(
            "expansion.value" in body,
            "动画值必须在绘制阶段读取（expansion.value），不能在组合期解包",
        )
        assertTrue("thumb = {}" in body, "AM 没有拇指圆点：thumb 必须留空")
        // 粗细常量声明在函数之前，要在整份文件里找。
        assertTrue(
            SeekTrackIdleHeightText in src && SeekTrackPressHeightText in src,
            "静置 / 按住的粗细应是命名常量，便于与出图断言对齐",
        )
    }

    private fun readComponentSource(name: String): String {
        var dir: File? = File("").absoluteFile
        val relative = "src/commonMain/kotlin/cp/player/app/ui/component/$name"
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it.readText() }
            File(File(dir, "app"), relative).takeIf { it.isFile }?.let { return it.readText() }
            dir = dir.parentFile
        }
        error("找不到 $relative")
    }

    private companion object {
        const val SeekTrackIdleHeightText = "SeekTrackIdleHeight = 4.dp"
        const val SeekTrackPressHeightText = "SeekTrackPressHeight = 12.dp"
    }
}
