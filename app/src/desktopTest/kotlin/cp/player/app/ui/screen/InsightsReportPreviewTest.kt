package cp.player.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LocalWindowChromeActive
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.music.TrackSummary
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 报告页与最近播放行的离屏版式探针。
 *
 * 报告页本身依赖 `AppModel`，因此用 [InsightsReportPreview] 组合同一批卡片与间距常量；
 * 最近播放行则直接复用生产用的 `recentPlaysRows`，核对 2dp 行距与「其他音源」徽标。
 */
class InsightsReportPreviewTest {

    private fun render(width: Int, height: Int, content: @Composable () -> Unit): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.DARK) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    content()
                }
            }
        }
        return try {
            scene.render().encodeToData()?.bytes ?: error("PNG encoding failed")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `report cards and spacing render offscreen`() {
        val bytes = render(width = 960, height = 980) {
            InsightsReportPreview()
        }
        File("build/insights-report-preview.png").apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
        assertTrue(bytes.size > 10_000, "报告预览图不应为空，实际 ${bytes.size} bytes")
    }

    @Test
    fun `report cards keep readable metrics on compact width`() {
        val bytes = render(width = 390, height = 1_180) {
            InsightsReportPreview()
        }
        File("build/insights-report-preview-compact.png").apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
        assertTrue(bytes.size > 10_000, "窄屏报告预览图不应为空，实际 ${bytes.size} bytes")
    }

    @Test
    fun `recent rows keep segmented spacing and mark foreign source`() {
        val tracks = listOf(
            TrackSummary(
                id = "netease://song/1",
                name = "夜に駆ける",
                artist = "YOASOBI",
                album = "THE BOOK",
                coverUrl = null,
                durationMs = 261_000,
            ),
            TrackSummary(
                id = "qq://song/2",
                name = "其他音源的歌曲",
                artist = "另一个歌手",
                album = null,
                coverUrl = null,
                durationMs = 220_000,
            ),
            TrackSummary(
                id = "local://audio/C:/Music/三日月.flac",
                name = "三日月",
                artist = "絢香",
                album = null,
                coverUrl = null,
                durationMs = 274_000,
            ),
        )
        val bytes = render(width = 760, height = 300) {
            CompositionLocalProvider(LocalWindowChromeActive provides true) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
                ) {
                    recentPlaysRows(
                        tracks = tracks,
                        columns = 1,
                        onPlay = {},
                        onOptions = {},
                        sourceLabelOf = { track ->
                            when {
                                track.id.startsWith("local://") -> "本地"
                                track.id.startsWith("qq://") -> "QQ 音乐"
                                else -> "网易云音乐"
                            }
                        },
                        playableOf = { !it.id.startsWith("qq://") },
                        contextMenu = { emptyList() },
                    )
                }
            }
        }
        File("build/recent-plays-preview.png").apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
        assertTrue(bytes.size > 5_000, "最近播放预览图不应为空，实际 ${bytes.size} bytes")
    }
}
