package cp.player.app.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.music.TrackSummary
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 验证手机端本地歌曲条目能正常渲染「更多」按钮。
 *
 * 在手机端（`LocalWindowChromeActive provides false`）且未传 `onOptionsClick` 时，
 * 只要传入了 `contextMenu`，`SongItem` 必须展示 MoreVert 更多按钮，
 * 点击时回退到具备移动端底部弹层能力的 [CpAnchoredMenu]。
 */
class SongItemMobileMoreButtonTest {

    private val sampleTrack = TrackSummary(
        id = "local://audio/sample.mp3",
        name = "本地测试歌曲",
        artist = "本地艺术家",
        album = "本地专辑",
        coverUrl = null,
        durationMs = 210_000,
    )

    private val sampleMenu = listOf(
        CpContextMenuItem(label = "播放", icon = Icons.Filled.PlayArrow, onClick = {}),
    )

    private fun renderSongItem(
        isDesktop: Boolean,
        hasMenu: Boolean,
        onOptionsClick: (() -> Unit)? = null,
    ): IntArray {
        val scene = ImageComposeScene(
            width = 360,
            height = 72,
            density = Density(1f),
        ) {
            CompositionLocalProvider(LocalWindowChromeActive provides isDesktop) {
                CpTheme(themeMode = ThemeMode.DARK) {
                    Box(Modifier.width(360.dp)) {
                        SongItem(
                            track = sampleTrack,
                            onClick = {},
                            onOptionsClick = onOptionsClick,
                            contextMenu = if (hasMenu) sampleMenu else null,
                        )
                    }
                }
            }
        }
        return try {
            val image = scene.render()
            val bytes = image.encodeToData()?.bytes ?: error("PNG encoding failed")
            val bitmap = ImageIO.read(ByteArrayInputStream(bytes))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getRGB(0, 0, bitmap.width, bitmap.height, pixels, 0, bitmap.width)
            pixels
        } finally {
            scene.close()
        }
    }

    @Test
    fun `mobile song item with contextMenu renders more button`() {
        val pixelsWithoutMenu = renderSongItem(isDesktop = false, hasMenu = false)
        val pixelsWithMenu = renderSongItem(isDesktop = false, hasMenu = true)

        // 检查末尾 48dp 宽度范围内的像素：有菜单时必须出现更多按钮的图标像素
        var diffCountInTrailingArea = 0
        for (y in 0 until 72) {
            for (x in (360 - 48) until 360) {
                val idx = y * 360 + x
                if (pixelsWithoutMenu[idx] != pixelsWithMenu[idx]) {
                    diffCountInTrailingArea++
                }
            }
        }
        assertTrue(
            diffCountInTrailingArea > 20,
            "手机端未传 onOptionsClick 但有 contextMenu 时，尾部必须渲染更多按钮，实际差异像素数: $diffCountInTrailingArea",
        )
    }

    @Test
    fun `desktop song item with contextMenu also renders more button`() {
        val pixelsWithoutMenu = renderSongItem(isDesktop = true, hasMenu = false)
        val pixelsWithMenu = renderSongItem(isDesktop = true, hasMenu = true)

        var diffCountInTrailingArea = 0
        for (y in 0 until 72) {
            for (x in (360 - 48) until 360) {
                val idx = y * 360 + x
                if (pixelsWithoutMenu[idx] != pixelsWithMenu[idx]) {
                    diffCountInTrailingArea++
                }
            }
        }
        assertTrue(
            diffCountInTrailingArea > 20,
            "桌面端有 contextMenu 时，尾部必须渲染更多按钮，实际差异像素数: $diffCountInTrailingArea",
        )
    }

    @Test
    fun `library search screen uses LocalSongRow instead of LegacyListItem`() {
        val searchScreenFile = File("src/commonMain/kotlin/cp/player/app/ui/screen/LibrarySearchScreen.kt").let {
            if (it.exists()) it else File("app/src/commonMain/kotlin/cp/player/app/ui/screen/LibrarySearchScreen.kt")
        }
        val text = searchScreenFile.readText()
        assertTrue(text.contains("LocalSongRow("), "LibrarySearchScreen 应统一使用 LocalSongRow 展示本地曲目")
        assertFalse(text.contains("private fun LocalTrackRow"), "LibrarySearchScreen 不应保留手写的 LocalTrackRow")
        assertFalse(text.contains("LegacyListItem("), "LibrarySearchScreen 不应直接调用 LegacyListItem")
    }
}
