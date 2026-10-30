package cp.player.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.AppLanguage
import cp.player.app.i18n.ProvideCpStrings
import cp.player.app.ui.screen.AlbumFavoriteChip
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
/**
 * 专辑收藏开关的**点击行为**（不只是静态观感）。
 *
 * [AlbumHeaderPreviewTest] 只把两种状态各画了一张，画得出不等于点得动 ——
 * `CpToggleChip` 的 `onCheckedChange` 收到的是**新值**而不是「被点了一下」，
 * 接线时（`onCheckedChange = { onClick() }`）很容易把参数忽略掉，或者接成
 * `{ onClick(it) }` 格式不匹配。那是运行时才炸的类型问题，编译期看不出来。
 *
 * 这里用离屏场景**真的点一下**，然后按像素判定画面确实变了（从「收藏专辑」变成
 * 「已收藏专辑」：图标从描边变实心、容器从浅底变主色，差异远大于噪声）。
 * 不钉坐标 —— 文案宽度变了，按钮的位置跟着变。
 */
class AlbumFavoriteChipClickTest {

    /** 按一次点击后返回「相对点击前变化了多少像素」。 */
    private fun changedPixelsAfterClick(dark: Boolean): Int {
        var favorite = false
        var clicks = 0
        val scene = ImageComposeScene(width = 320, height = 160, density = Density(1f)) {
            CpTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                ProvideCpStrings(AppLanguage.ZH_HANS) {
                    // 用真实的状态流转而不是固定 true：这样才能验证
                    // 「点了以后 UI 真的换了态」，而不是验证「传进来就能画」。
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        var isFav by remember { mutableStateOf(favorite) }
                        Box(Modifier.padding(16.dp)) {
                            AlbumFavoriteChip(
                                isFavorite = isFav,
                                pending = false,
                                onClick = {
                                    clicks++
                                    isFav = !isFav
                                },
                            )
                        }
                    }
                }
            }
        }
        try {
            var t = 0L
            repeat(5) { scene.render(t); t += 16_000_000L }
            val before = scene.render(t).toRgb()
            t += 16_000_000L

            // 按钮在 (16,16) 起步、高度 42dp 左右 ⇒ 点在 (60,38) 必然落在它里面。
            val at = Offset(60f, 38f)
            scene.sendPointerEvent(
                PointerEventType.Press, at, Offset.Zero, 0L,
                PointerType.Mouse, button = PointerButton.Primary,
            )
            scene.render(t); t += 16_000_000L
            scene.sendPointerEvent(
                PointerEventType.Release, at, Offset.Zero, 20L,
                PointerType.Mouse, button = PointerButton.Primary,
            )
            // 形状 / 颜色动画要几帧才稳：多渲几帧再取终态。
            repeat(20) { scene.render(t); t += 16_000_000L }
            val after = scene.render(t).toRgb()

            assertTrue(clicks == 1, "点击应恰好触发一次回调，实际 $clicks 次")
            return diffPixels(before, after)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `点击收藏开关后画面确实换了态`() {
        val changed = changedPixelsAfterClick(dark = false)
        println("[album-favorite] 点击后变化像素 = $changed")
        assertTrue(changed > 2000, "点了一下收藏开关但画面几乎没变（只变了 $changed 个像素）—— 回调没接上？")
    }

    @Test
    fun `深色主题下同样生效`() {
        val changed = changedPixelsAfterClick(dark = true)
        println("[album-favorite] 深色下点击后变化像素 = $changed")
        assertTrue(changed > 2000, "深色主题下点收藏开关没反应（只变了 $changed 个像素）")
    }

    // ======================== 像素辅助 ========================

    /**
     * `ImageComposeScene.render()` 返回的是 `org.jetbrains.skia.Image`（**不是字节**）。
     * 走 PNG 编解码取 ARGB —— 与 `CpContextMenuRightClickTest` 同一套做法：
     * 不依赖 Skia 内部位图布局，换 skiko 版本也不会坏。
     */
    private fun org.jetbrains.skia.Image.toRgb(): IntArray {
        val png = ImageIO.read(ByteArrayInputStream(encodeToData()?.bytes ?: error("encode failed")))
        return IntArray(png.width * png.height).also { out ->
            png.getRGB(0, 0, png.width, png.height, out, 0, png.width)
        }
    }

    /** 有多少个像素变了（任一通道差 > 8，挡掉抗锯齿 / 动画残留噪声）。 */
    private fun diffPixels(a: IntArray, b: IntArray): Int {
        if (a.size != b.size) return maxOf(a.size, b.size)
        var n = 0
        for (i in a.indices) {
            val p = a[i]
            val q = b[i]
            val dr = kotlin.math.abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF))
            val dg = kotlin.math.abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF))
            val db = kotlin.math.abs((p and 0xFF) - (q and 0xFF))
            if (dr > 8 || dg > 8 || db > 8) n++
        }
        return n
    }
}
