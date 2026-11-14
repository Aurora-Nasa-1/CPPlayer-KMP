package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import cp.player.app.PngImage
import cp.player.app.readPng
import cp.player.app.ui.model.MobilePlayerCustomConfig
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.music.TrackSummary
import cp.player.core.playback.PlaybackUiState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 标准播放器的 Apple Music 版式回归。
 *
 * 封面尺寸、标题区与底部入口只能靠离屏出图核对：编译和纯测量都看不到
 * 「标题被挤到两行」「底部四个按钮贴边」这类问题。这里同时锁住两条结构：
 * 主界面不再放音量行，也不再在标题下塞歌词预览。
 */
class MobileNowPlayingVisualTest {

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
    fun `standard player renders apple music style chrome`() {
        val image = renderPlayer(390, 844, "build/mobile-now-playing-preview.png")

        // 顶部是 Apple Music 式的短横条：宽而扁，并且水平居中。
        val handle = brightPixels(image, 150, 8, 240, 48)
        assertTrue(handle.isNotEmpty(), "顶部应出现收起横条")
        val handleLeft = handle.minOf { it.first }
        val handleRight = handle.maxOf { it.first }
        val handleTop = handle.minOf { it.second }
        val handleBottom = handle.maxOf { it.second }
        val handleWidth = handleRight - handleLeft + 1
        val handleHeight = handleBottom - handleTop + 1
        assertTrue(
            handleWidth >= 28 && handleHeight <= 8,
            "顶部应为宽而扁的横条，实测 ${handleWidth}x$handleHeight",
        )
        assertTrue(
            (handleLeft + handleRight) / 2 in 190..200,
            "顶部横条应水平居中，实测中心 ${(handleLeft + handleRight) / 2}",
        )
        // 封面区域应当占据主体，并且与页面底色有区分。
        assertTrue(
            luma(image.getRGB(195, 240)) > luma(image.getRGB(4, 240)) + 600,
            "封面应位于页面中央且可见",
        )
        // 底部四个入口都要有图标像素，避免被裁掉或挤成三个。
        val bottomClusters = brightPixels(image, 20, 720, 370, 800)
            .groupBy { it.first / 40 }
            .filterValues { it.isNotEmpty() }
        assertTrue(bottomClusters.size >= 4, "底部应出现歌词 / 评论 / 投送 / 队列四个入口")
    }

    @Test
    fun `tall screens keep artwork near the top instead of growing a huge blank`() {
        val normal = renderPlayer(390, 844, "build/mobile-now-playing-preview.png")
        val tall = renderPlayer(412, 915, "build/mobile-now-playing-preview-tall.png")
        val normalTop = artworkTop(normal)
        val tallTop = artworkTop(tall)
        assertTrue(normalTop <= 100, "普通长屏封面顶部不应超过 100px，实测 $normalTop")
        assertTrue(tallTop <= 120, "加长屏封面顶部不应超过 120px，实测 $tallTop")
        assertTrue(
            tallTop - normalTop <= 24,
            "屏幕加高后封面顶部不应大幅下移：$normalTop -> $tallTop",
        )
    }

    @Test
    fun `short screens keep the controls inside the viewport`() {
        val image = renderPlayer(360, 640, "build/mobile-now-playing-preview-short.png")
        val top = artworkTop(image)
        assertTrue(top <= 70, "短屏封面也应贴近顶部")
        // 360dp 窄屏要把左右留白收窄，否则封面会明显小于可用宽度。
        val scanY = top + 40
        val background = luma(image.getRGB(2, scanY))
        val artworkX = (0 until image.width)
            .filter { luma(image.getRGB(it, scanY)) > background + 600 }
        assertTrue(artworkX.isNotEmpty(), "短屏应能看到封面")
        assertTrue(artworkX.min() <= 22, "窄屏封面左边距应收窄，实测 ${artworkX.min()}")
        assertTrue(
            image.width - 1 - artworkX.max() <= 22,
            "窄屏封面右边距应收窄，实测 ${image.width - 1 - artworkX.max()}",
        )
        val bottomClusters = brightPixels(image, 16, 570, 344, 636)
            .groupBy { it.first / 40 }
            .filterValues { it.isNotEmpty() }
        assertTrue(bottomClusters.size >= 4, "短屏底部四个入口不能被裁掉")
    }

    @Test
    fun `lyrics focus keeps the transport at the bottom`() {
        val image = renderPlayer(
            390, 844, "build/mobile-now-playing-preview-lyrics.png",
            lyricsExpanded = true,
        )
        assertEquals(
            0xFF1B3A2F.toInt(), image.getRGB(195, 300),
            "歌词区应占据中段",
        )
        val bottomClusters = brightPixels(image, 20, 760, 370, 840)
            .groupBy { it.first / 40 }
            .filterValues { it.isNotEmpty() }
        assertTrue(bottomClusters.size >= 4, "歌词模式下底部四个入口仍应可见")
    }

    private fun renderPlayer(
        width: Int,
        height: Int,
        fileName: String,
        lyricsExpanded: Boolean = false,
    ): PngImage {
        val track = TrackSummary(
            id = "local://audio/echolalia",
            name = "Echolalia",
            artist = "Yves Tumor",
            album = "Praise a Lord Who Chews but Which Does Not Consume",
            coverUrl = null,
            durationMs = 180_000L,
        )
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.DARK) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF252A31), Color(0xFF14171B)),
                        ),
                    ),
                ) {
                    MobileNowPlayingLayout(
                        state = PlaybackUiState(
                            currentTrack = track,
                            isPlaying = true,
                            positionMs = 60_000L,
                            durationMs = 180_000L,
                            isFavorite = true,
                        ),
                        config = MobilePlayerCustomConfig(),
                        lyricsExpanded = lyricsExpanded,
                        supportsComments = true,
                        onBack = {},
                        onToggleLyrics = {},
                        onToggleTranslation = {},
                        onTogglePlay = {},
                        onSeek = {},
                        onSkipNext = {},
                        onSkipPrevious = {},
                        onLike = {},
                        onArtistClick = null,
                        onQueue = {},
                        onComments = {},
                        onMore = {},
                        onCast = {},
                        modifier = Modifier.fillMaxSize(),
                        lyricsContent = { modifier ->
                            Box(modifier.background(Color(0xFF1B3A2F)))
                        },
                    )
                }
            }
        }
        try {
            scene.render()
            val bytes = scene.render().encodeToData()?.bytes ?: error("ImageComposeScene PNG encode failed")
            val image = readPng(bytes)
            val out = File(fileName)
            out.parentFile?.mkdirs()
            out.writeBytes(bytes)
            return image
        } finally {
            scene.close()
        }
    }

    private fun artworkTop(image: PngImage): Int {
        val x = image.width / 2
        val background = luma(image.getRGB(4, image.height / 2))
        for (y in 60 until image.height / 2) {
            if (luma(image.getRGB(x, y)) > background + 600) return y
        }
        error("找不到封面顶部")
    }

    @Test
    fun `standard player keeps apple music structure without volume or lyric preview`() {
        val layout = readSource("src/commonMain/kotlin/cp/player/app/ui/component/MobileNowPlayingLayout.kt")
        val host = readSource("src/commonMain/kotlin/cp/player/app/ui/component/ModernPlayerView.kt")
        assertTrue("AlbumEyebrow(" in layout, "封面下应有专辑/来源小标题")
        assertTrue("Icons.Rounded.MoreHoriz" in layout, "更多入口应回到标题区")
        assertTrue("Icons.Rounded.Cast" in layout, "底部应有投送入口")
        assertTrue("width(36.dp).height(4.dp)" in layout, "顶部收起入口应是短横条")
        assertFalse("KeyboardArrowDown" in layout, "顶部不应再画收起箭头")
        assertFalse("VolumeRow(" in layout, "标准播放器不放音量行")
        assertFalse("InlineLyricsPreview(" in layout, "标准播放器主界面不再塞歌词预览")
        assertTrue("onCast = { more.showDevicePicker = true }" in host, "投送入口应打开设备选择")
    }

    private fun brightPixels(
        image: PngImage,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): List<Pair<Int, Int>> = buildList {
        for (y in top until bottom) {
            for (x in left until right) {
                if (luma(image.getRGB(x, y)) > 5000) add(x to y)
            }
        }
    }

    private fun luma(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return 30 * r + 59 * g + 11 * b
    }
}
