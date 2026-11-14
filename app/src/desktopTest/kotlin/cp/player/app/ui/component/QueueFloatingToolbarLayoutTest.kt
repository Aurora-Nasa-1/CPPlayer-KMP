package cp.player.app.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import cp.player.app.readPng
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.playback.QueueItem
import cp.player.core.playback.RepeatMode
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 播放列表悬浮工具条的版式与接线回归。
 *
 * 工具条不占布局行、覆盖在列表之上；宽度与对齐只能靠离屏出图核对，
 * 编译和单测量都看不出来。随机 / 循环从三段式播放器的传输行收纳进队列，
 * 用接线断言兜住，避免哪天又被搬回主界面。
 */
class QueueFloatingToolbarLayoutTest {

    private fun readSource(relative: String): String {
        var dir: File? = File("").absoluteFile
        val found = buildList {
            while (dir != null) {
                File(dir, relative).takeIf { it.isFile }?.let { add(it) }
                val underApp = File(File(dir, "app"), relative)
                if (underApp.isFile) add(underApp)
                dir = dir.parentFile
            }
        }.firstOrNull()
        check(found != null) { "找不到 $relative" }
        return found.readText()
    }

    @Test
    fun `悬浮工具条离屏出图后覆盖在列表底部中央`() {
        val queue = List(12) { i ->
            QueueItem(
                mediaId = "local://audio/track-$i",
                title = "曲目 $i",
                artist = "歌手 $i",
                album = "专辑",
                coverUrl = null,
                durationMs = 200_000L,
            )
        }
        var capsuleArgb = 0
        var selectedArgb = 0
        val scene = ImageComposeScene(width = 360, height = 640, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.DARK) {
                // 取主题里的容器色 / 选中色作为像素标记，避免把列表卡片色误判成胶囊。
                capsuleArgb = MaterialTheme.colorScheme.surfaceContainerHigh.toArgb()
                selectedArgb = MaterialTheme.colorScheme.primaryContainer.toArgb()
                Box(Modifier.fillMaxSize()) {
                    QueueContent(
                        queue = queue,
                        currentIndex = 0,
                        onPlayAt = {},
                        onRemove = {},
                        onMove = { _, _ -> },
                        onClear = {},
                        shuffleEnabled = true,
                        repeatMode = RepeatMode.ONE,
                        onShuffle = {},
                        onRepeat = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        try {
            scene.render()
            val bytes = scene.render().encodeToData()?.bytes ?: error("ImageComposeScene PNG encode failed")
            val image = readPng(bytes)
            val out = File("build/queue-floating-toolbar-preview.png")
            out.parentFile?.mkdirs()
            out.writeBytes(bytes)
            // 工具条居中悬浮：同一高度上，中央应被胶囊容器覆盖，两侧仍是列表区域。
            assertNotEquals(
                image.getRGB(8, 604), image.getRGB(180, 604),
                "悬浮工具条必须绘制在列表之上，且位于底部中央",
            )

            val y = 604
            val bg = capsuleArgb
            val selected = selectedArgb
            val bgXs = (0 until image.width).filter { image.getRGB(it, y) == bg }
            assertTrue(bgXs.isNotEmpty(), "工具条中心行必须出现胶囊容器底色")
            val capsuleLeft = bgXs.min()
            val capsuleRight = bgXs.max()
            assertTrue(
                abs((capsuleLeft + capsuleRight) - image.width) <= 3,
                "胶囊必须水平居中，实测左右边界 $capsuleLeft / $capsuleRight",
            )

            // 两个选中圆之间必须留出容器底色：相切或连成一块时中点会是圆底/图标色。
            val selectedXs = (0 until image.width).filter { image.getRGB(it, y) == selected }
            assertTrue(selectedXs.size >= 20, "两个选中态圆底应出现在工具条里")
            val gapCenter = (selectedXs.min() + selectedXs.max()) / 2
            assertTrue(
                (gapCenter - 2..gapCenter + 2).all { image.getRGB(it, y) == bg },
                "两个选中圆之间必须留出至少 4px 间隙，不能挤在一起",
            )

            // 分组竖线：第二个圆右侧的一小段窗口里要有明显亮于容器底色的像素。
            assertTrue(
                (selectedXs.max() + 6..selectedXs.max() + 20)
                    .any { luma(image.getRGB(it, y)) > luma(bg) + 2500 },
                "随机 / 循环与队列动作之间的竖直分隔线必须可见",
            )
        } finally {
            scene.close()
        }
    }

    @Test
    fun `随机与循环收纳进播放列表且不占主界面传输行`() {
        val sheet = readSource("src/commonMain/kotlin/cp/player/app/ui/component/QueueBottomSheet.kt")
        val host = readSource("src/commonMain/kotlin/cp/player/app/ui/component/ModernPlayerView.kt")
        val layout = readSource("src/commonMain/kotlin/cp/player/app/ui/component/MobileNowPlayingLayout.kt")
        assertTrue("shuffleEnabled = state.shuffleEnabled" in host, "三段式播放器应把随机状态传给队列弹层")
        assertTrue("onRepeat = onRepeat" in host, "三段式播放器应把循环回调传给队列弹层")
        assertTrue("contentPadding = PaddingValues(bottom = 96.dp)" in sheet, "悬浮工具条必须给列表留出底部留白")
        assertFalse("Icons.Rounded.Shuffle" in layout, "主界面传输行不应再出现随机按钮")
        assertFalse("Icons.Rounded.Repeat" in layout, "主界面传输行不应再出现循环按钮")
        assertTrue("QueueFloatingToolbar {" in sheet, "队列工具条必须使用居中的自定义胶囊容器")
        assertFalse("CpFloatingToolbar {" in sheet, "官方悬浮工具条会把按钮挤向左侧，不能用于队列")
    }

    /** 0xAARRGGBB 的近似亮度，用于判断竖线与容器底色是否有足够对比。 */
    private fun luma(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return 30 * r + 59 * g + 11 * b
    }
}
