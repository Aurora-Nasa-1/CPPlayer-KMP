package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.AppLanguage
import cp.player.app.i18n.ProvideCpStrings
import cp.player.app.ui.component.CpAdaptiveListDetailScaffold
import cp.player.app.ui.component.CpText
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.TrackSummary
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class PlaylistDetailAdaptiveVisualTest {

    private fun render(width: Int, height: Int, content: @Composable () -> Unit): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.DARK) {
                ProvideCpStrings(AppLanguage.ZH_HANS) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        content()
                    }
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
    fun `playlist detail renders compact and expanded adaptive layouts`() {
        val summary = PlaylistSummary(
            id = -202L,
            name = "夜航与远方",
            coverUrl = null,
            trackCount = 8,
            creatorName = "CPPlayer",
        )
        val tracks = (1..8).map { index ->
            TrackSummary(
                id = "preview-$index",
                name = "第 $index 首夜行曲目",
                artist = "独立乐队 $index",
                album = "夜航之后",
                coverUrl = null,
                durationMs = 210_000L + index * 1_000L,
            )
        }

        val compact = render(width = 390, height = 844) {
            PlaylistDetailPreview(summary, tracks)
        }
        val expanded = render(width = 1_180, height = 820) {
            PlaylistDetailPreview(summary, tracks)
        }

        File("build/playlist-detail-compact.png").apply {
            parentFile?.mkdirs()
            writeBytes(compact)
        }
        File("build/playlist-detail-expanded.png").apply {
            parentFile?.mkdirs()
            writeBytes(expanded)
        }

        assertTrue(compact.size > 8_000, "紧凑歌单详情预览不应为空，实际 ${compact.size} bytes")
        assertTrue(expanded.size > 8_000, "展开歌单详情预览不应为空，实际 ${expanded.size} bytes")
    }

    @OptIn(ExperimentalMaterial3AdaptiveApi::class)
    @Composable
    private fun PlaylistDetailPreview(summary: PlaylistSummary, tracks: List<TrackSummary>) {
        val navigator = rememberListDetailPaneScaffoldNavigator<Unit>()
        CpAdaptiveListDetailScaffold(
            navigator = navigator,
            listPane = { detailVisible ->
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                ) {
                    Surface(
                        Modifier.size(if (detailVisible) 208.dp else 176.dp),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {}
                    Spacer(Modifier.height(16.dp))
                    CpText(
                        summary.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                    )
                    Spacer(Modifier.height(8.dp))
                    CpText(
                        "${summary.trackCount} 首歌曲",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    tracks.forEachIndexed { index, track ->
                        Row(
                            Modifier.fillMaxWidth().height(48.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            CpText("${index + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.size(12.dp))
                            Column {
                                CpText(track.name, style = MaterialTheme.typography.bodyMedium)
                                CpText(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            detailPane = {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        CpText(
                            "曲目",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    tracks.forEachIndexed { index, track ->
                        Row(
                            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            CpText("${index + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.size(14.dp))
                            Column(Modifier.fillMaxHeight()) {
                                CpText(track.name, style = MaterialTheme.typography.bodyLarge)
                                CpText(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
        )
    }
}
