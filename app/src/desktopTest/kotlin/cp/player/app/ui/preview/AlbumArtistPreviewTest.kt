package cp.player.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.ui.component.AlbumCoverCard
import cp.player.app.ui.component.AlbumItem
import cp.player.app.ui.component.ArtistItem
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.music.AlbumSummary
import cp.player.core.music.ArtistSummary
import java.io.File
import kotlin.test.Test

/**
 * 专辑 / 歌手列表项的**离屏渲染**核对（渲完可删）。
 *
 * 编译与单测都量不到宽度、也看不见对齐 —— 列表项的缩略图尺寸、行高、
 * 文字基线这些只能看渲染结果。两种主题各渲一次，顺便覆盖「压在图上的白字」的
 * `overImage` 兜底分支（浅色主题最容易糊掉）。
 *
 * 用**无封面**的条目渲染：那是占位块分支，也正是最容易画歪的一个。
 */
class AlbumArtistPreviewTest {

    private val albumWithCover = AlbumSummary(
        id = 1001L,
        name = "范特西",
        coverUrl = null,
        artistName = "周杰伦",
        trackCount = 10,
        publishTimeMs = 998688000000L,
    )

    private val albumNoArtist = AlbumSummary(
        id = 1002L,
        name = "2026 年度精选合集（豪华版）",
        coverUrl = null,
        artistName = null,
        trackCount = 0,
    )

    private val artist = ArtistSummary(id = 6452L, name = "周杰伦", avatarUrl = null)

    @Composable
    private fun Preview() {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.TopStart,
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .padding(horizontal = CpSpacing.pageHorizontal, vertical = CpSpacing.pageTop),
                verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
            ) {
                AlbumItem(album = albumWithCover, onClick = {})
                AlbumItem(album = albumNoArtist, onClick = {}, onOptionsClick = {})
                ArtistItem(artist = artist, onClick = {}, subtitle = "歌手")

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AlbumCoverCard(album = albumWithCover, onClick = {})
                    AlbumCoverCard(album = albumNoArtist, onClick = {})
                }
            }
        }
    }

    private fun render(name: String, dark: Boolean) {
        val scene = ImageComposeScene(width = 560, height = 460, density = Density(1f)) {
            CpTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                Preview()
            }
        }
        try {
            val data = scene.render().encodeToData() ?: error("encode failed")
            // 独立的输出目录：`build-verify/` 是多个并行会话共用的，
            // 写进去容易被别人的清理动作带走（AGENTS.md §3 的共享目录问题）。
            val out = File("build-preview-album/preview").apply { mkdirs() }
            File(out, "$name.png").writeBytes(data.bytes)
        } finally {
            scene.close()
        }
    }

    @Test
    fun renderPreviews() {
        render("album-artist-light", dark = false)
        render("album-artist-dark", dark = true)
    }

    /** 副标题的拼接规则（缺项要自动省略，年份不能缺）。 */
    @Test
    fun albumSubtitleKeepsYearAndDropsMissingParts() {
        val withCover = cp.player.app.ui.component.albumSubtitle(albumWithCover)
        val noArtist = cp.player.app.ui.component.albumSubtitle(albumNoArtist)
        println("withCover = $withCover")
        println("noArtist   = $noArtist")
        // 发行年份必须出现 —— 曾经因为 kotlinx-datetime 的运行时版本不匹配被静默吞掉。
        kotlin.test.assertTrue(withCover.contains("2001"), "副标题应含发行年份，实际: $withCover")
        kotlin.test.assertTrue(noArtist.isEmpty(), "全缺项时副标题应为空串，实际: $noArtist")
    }
}
