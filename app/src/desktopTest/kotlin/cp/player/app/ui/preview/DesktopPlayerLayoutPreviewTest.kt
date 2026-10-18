package cp.player.app.ui.preview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import cp.player.app.ui.screen.DesktopPlayerLayout
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.music.TrackSummary
import cp.player.core.playback.PlaybackUiState
import cp.player.core.playback.QueueItem
import cp.player.core.playback.RepeatMode
import java.io.File
import kotlin.test.Test

/**
 * 临时：核对「各种窗口尺寸下的版式」（渲完必删）。
 *
 * 覆盖：4K / 1440p / 1080p 全屏、常见笔记本、窄窗、矮窗、竖长窗。
 * 密度固定 1f（dp == px），边距与偏移可直接按像素读。
 */
class DesktopPlayerLayoutPreviewTest {

    private val demoQueue: List<QueueItem> = List(12) { index ->
        QueueItem(
            mediaId = "media-$index",
            title = "第 ${index + 1} 首 · 一个足够长的歌曲标题用来观察行宽",
            artist = "歌手 ${index + 1}",
            album = "专辑",
            coverUrl = null,
            durationMs = 214_000L + index * 7_000L,
        )
    }

    private val demoState: PlaybackUiState = PlaybackUiState(
        currentTrack = TrackSummary(
            id = "media-0",
            name = "夜航西飞",
            artist = "陈粒",
            album = "洄游",
            coverUrl = null,
            durationMs = 245_000L,
        ),
        currentIndex = 0,
        queue = demoQueue,
        isPlaying = true,
        positionMs = 92_000L,
        durationMs = 245_000L,
        repeatMode = RepeatMode.ALL,
        qualityLevel = "lossless",
    )

    @Composable
    private fun Layout() {
        DesktopPlayerLayout(
            state = demoState,
            volume = 0.62f,
            onVolumeChange = {},
            onBack = {},
            onTogglePlay = {},
            onSeek = {},
            onSkipNext = {},
            onSkipPrev = {},
            onRepeat = {},
            onShuffle = {},
            onLike = {},
            onPlayAt = {},
            onMove = { _, _ -> },
            modifier = Modifier.fillMaxSize(),
        )
    }

    /** 与 `playerMetrics` 同一套算式，用来定位「队列」页签的点击点。 */
    private fun metricsOf(width: Int, height: Int): Triple<Float, Float, Float> {
        val contentWidth = minOf(width - 56f, (height * 1.34f).coerceIn(1100f, 1800f))
        val leftColumn = minOf(height * 0.48f, contentWidth * 0.44f).coerceIn(280f, 820f)
        val gap = if (contentWidth < 980f) 24f else 32f
        return Triple(contentWidth, leftColumn, gap)
    }

    private fun queueTabPoint(width: Int, height: Int): Offset {
        val (contentWidth, leftColumn, gap) = metricsOf(width, height)
        val contentLeft = 28f + (width - 56f - contentWidth) / 2f
        val panelLeft = contentLeft + leftColumn + gap
        val panelCenter = panelLeft + (contentWidth - leftColumn - gap) / 2f
        val tabRowHeight = if (height < 700) 38f else 44f
        val tabCenterY = 18f + 20f + 2f + tabRowHeight / 2f
        // 页签「队列」在整组居中位置的左侧一个 chip（chip 宽 ≈ 68dp，间距 2dp）。
        return Offset(panelCenter - 70f, tabCenterY)
    }

    private fun render(
        name: String,
        width: Int,
        height: Int,
        dark: Boolean = false,
        clickQueueTab: Boolean = true,
    ) {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CpTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                Layout()
            }
        }
        try {
            scene.render()
            if (clickQueueTab) {
                val p = queueTabPoint(width, height)
                scene.sendPointerEvent(PointerEventType.Move, p)
                scene.sendPointerEvent(PointerEventType.Press, p)
                scene.sendPointerEvent(PointerEventType.Release, p)
            }
            val data = scene.render().encodeToData() ?: error("encode failed")
            val out = File("../.workbuddy-ai/preview/matrix").apply { mkdirs() }
            File(out, "$name.png").writeBytes(data.bytes)
            val (contentWidth, leftColumn, gap) = metricsOf(width, height)
            val margin = 28f + (width - 56f - contentWidth) / 2f
            println(
                "SIZE $name: ${width}x$height content=${contentWidth.toInt()} " +
                    "leftCol=${leftColumn.toInt()} gap=${gap.toInt()} " +
                    "margin=${margin.toInt()} panel=${(contentWidth - leftColumn - gap).toInt()}",
            )
        } finally {
            scene.close()
        }
    }

    @Test
    fun renderPreviews() {
        render("4k-3840x2160", 3840, 2160)
        render("1440p-2560x1440", 2560, 1440)
        render("1080p-1920x1080", 1920, 1080)
        render("1600x900", 1600, 900)
        render("1440x900", 1440, 900)
        render("1366x768", 1366, 768)
        render("1280x800", 1280, 800)
        render("1280x720", 1280, 720)
        render("ultrawide-3440x1440", 3440, 1440)
        render("1024x640", 1024, 640)
        render("900x600", 900, 600)
        render("840x500", 840, 500)
        render("portrait-900x1100", 900, 1100)
        render("1440x900-dark", 1440, 900, dark = true)
        render("1440x900-lyrics", 1440, 900, clickQueueTab = false)
    }
}
