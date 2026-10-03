package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 回归：[CpContextMenu] 的**副键弹出时机**。
 *
 * 「侧栏歌单支持右键菜单」靠的就是它 —— 侧栏只是把它包在歌单行外面，弹不出来就全白搭。
 * 这里只用离屏渲图做像素级判定，不去碰侧栏本身（整条 `DesktopSidebar` 依赖 `AppModel`，
 * 在测试 JVM 里起不来）。
 *
 * 两条判据都对应真实踩过的坑：
 * ① 副键**松开**时才弹 —— 桌面端 Popup 会抢焦点，Press 就弹会被随后的 Release
 *    当成外部点击立刻关掉（表现就是「右键没反应」）；
 * ② 只按下不松开，屏上不该出现任何东西。
 *
 * 判据用「两帧图像有多少像素不同」而不是比对某个坐标的颜色：菜单面板的位置、圆角、
 * 阴影都会随主题和排版微调而变，钉死坐标的断言会在无关改版时假失败。
 */
class CpContextMenuRightClickTest {

    private fun menuItems(): List<CpContextMenuItem> = buildList {
        add(CpContextMenuItem("播放", Icons.Filled.PlayArrow, onClick = {}))
        add(CpContextMenuItem("加入队列", Icons.AutoMirrored.Filled.QueueMusic, onClick = {}))
        add(CpContextMenuItem("全部下载", Icons.Filled.Download, onClick = {}))
        add(CpContextMenuSeparator)
        add(CpContextMenuItem("分享歌单", Icons.Filled.Share, onClick = {}))
        add(CpContextMenuItem("删除歌单", Icons.Filled.Delete, onClick = {}, danger = true))
    }

    @Composable
    private fun Content() {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow),
            contentAlignment = Alignment.TopStart,
        ) {
            CpContextMenu(items = menuItems()) {
                Box(Modifier.width(248.dp).padding(12.dp)) {
                    Text("在此处右键", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    /** 副键按下 → （按 [release] 决定是否松开）→ 返回「相对初始画面变化了多少像素」。 */
    private fun changedPixels(release: Boolean, dark: Boolean = false): Int {
        val scene = ImageComposeScene(width = 420, height = 420, density = Density(1f)) {
            CpTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) { Content() }
        }
        try {
            var t = 0L
            repeat(5) { scene.render(t); t += 16_000_000L }
            val before = scene.render(t).toRgb()
            t += 16_000_000L

            val at = Offset(40f, 16f)
            scene.sendPointerEvent(
                PointerEventType.Press, at, Offset.Zero, 0L,
                PointerType.Mouse, button = PointerButton.Secondary,
            )
            scene.render(t); t += 16_000_000L
            if (release) {
                scene.sendPointerEvent(
                    PointerEventType.Release, at, Offset.Zero, 20L,
                    PointerType.Mouse, button = PointerButton.Secondary,
                )
                scene.render(t); t += 16_000_000L
            }
            val after = scene.render(t).toRgb()
            return diffPixels(before, after)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `副键按下再松开 弹出菜单`() {
        val changed = changedPixels(release = true)
        println("[context-menu] 右键后变化像素 = $changed")
        assertTrue(changed > 3000, "右键没弹出菜单（只变了 $changed 个像素）")
    }

    @Test
    fun `只按下不松开 不弹菜单`() {
        val changed = changedPixels(release = false)
        println("[context-menu] 只按下时变化像素 = $changed")
        assertTrue(changed < 100, "菜单在副键松开前就弹了（变了 $changed 个像素）")
    }

    @Test
    fun `深色主题下同样弹出`() {
        val changed = changedPixels(release = true, dark = true)
        println("[context-menu] 深色下右键后变化像素 = $changed")
        assertTrue(changed > 3000, "深色主题下右键没弹出菜单（只变了 $changed 个像素）")
    }
}

/** 把一帧渲染结果解成 ARGB 像素数组（走 PNG 编解码，避免依赖 Skia 内部布局）。 */
private fun org.jetbrains.skia.Image.toRgb(): IntArray {
    val png = ImageIO.read(ByteArrayInputStream(encodeToData()?.bytes ?: error("encode failed")))
    return IntArray(png.width * png.height).also { out ->
        png.getRGB(0, 0, png.width, png.height, out, 0, png.width)
    }
}

private fun diffPixels(a: IntArray, b: IntArray): Int {
    if (a.size != b.size) return maxOf(a.size, b.size)
    var n = 0
    for (i in a.indices) if (a[i] != b[i]) n++
    return n
}
