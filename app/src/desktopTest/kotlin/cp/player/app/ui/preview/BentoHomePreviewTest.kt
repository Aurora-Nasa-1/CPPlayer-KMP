package cp.player.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import cp.player.app.ui.model.HomeUiState
import cp.player.app.ui.model.PlaylistSource
import cp.player.app.ui.screen.DesktopHomeLayout
import cp.player.app.ui.screen.HomeActions
import cp.player.app.ui.screen.NowPlayingSnapshot
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.music.AlbumSummary
import cp.player.core.music.ArtistSummary
import cp.player.core.music.BannerItem
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.RankingSummary
import cp.player.core.music.TrackSummary
import java.io.File
import kotlin.test.Test

/**
 * 临时：核对首页 BentoCards 复用后的版面（渲完必删）。
 *
 * 核对点：
 * 1. `HomeSectionCard` / `HeroQuickPanel` 换成 `BentoCard` 后版式**没有跑偏**
 *    （在播态的进度条是否仍压在卡片底部 —— 那里靠 `Spacer(weight(1f))`，
 *    需要 BentoCard 的 fillHeight 给出有界高度）。
 * 2. **纯黑模式**下，仍在容器内的区块（`surfaceContainerLow`）是否还能看出边界 ——
 *    这是 `bentoOutline()` 唯一还有意义的场景。
 */
class BentoHomePreviewTest {

    private fun tracks(prefix: String, count: Int) = List(count) { i ->
        TrackSummary(
            id = "$prefix-$i",
            name = "$prefix 曲目 ${i + 1} 号",
            artist = "歌手 ${i + 1}",
            album = null,
            coverUrl = null,
            durationMs = 240_000L,
        )
    }

    private fun playlists(prefix: String, count: Int) = List(count) { i ->
        PlaylistSummary(
            id = (i + 1).toLong(),
            name = "$prefix 歌单 ${i + 1}",
            coverUrl = null,
            trackCount = 30 + i,
            creatorName = "创建者",
        )
    }

    private fun baseState(banners: List<BannerItem>) = HomeUiState(
        dailySongs = tracks("每日推荐", 30),
        banners = banners,
        rankings = List(5) { i ->
            RankingSummary(
                id = (i + 1).toLong(),
                name = "排行榜 ${i + 1}",
                coverUrl = null,
                updateFrequency = "每天更新",
                trackCount = 100,
            )
        },
        newAlbums = List(6) { i ->
            AlbumSummary(id = (i + 1).toLong(), name = "新碟 ${i + 1}", coverUrl = null, artistName = "歌手 ${i + 1}", trackCount = 10)
        },
        hotArtists = List(7) { i -> ArtistSummary(id = (i + 1).toLong(), name = "歌手 ${i + 1}", avatarUrl = null) },
        newSongs = tracks("新歌", 8),
        recommendedPlaylists = playlists("推荐", 14),
        playlistSource = PlaylistSource.Recommended,
        loading = false,
    )

    private val actions = HomeActions(
        onRefresh = {},
        onPlaylistSourceChange = {},
        onNewSongRegionChange = {},
        onBannerClick = {},
        onOpenPlaylist = {},
        onOpenRanking = {},
        onOpenAlbum = {},
        onOpenArtist = {},
        onOpenDaily = {},
        onPlayDailyTrack = {},
        onPlayPersonalFm = {},
        onOpenIntelligence = {},
        onRecentTrackClick = { _, _ -> },
        onRecentPlayAll = {},
        onOpenRecentPlays = {},
        onNewSongPlay = { _, _ -> },
        onTrackOptions = {},
        onOpenPlayer = {},
    )

    @Composable
    private fun Preview(banners: List<BannerItem>, playing: TrackSummary?) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            DesktopHomeLayout(
                state = baseState(banners),
                recentTracks = tracks("最近播放", 12),
                nowPlaying = NowPlayingSnapshot(
                    track = playing,
                    isPlaying = playing != null,
                    positionMs = if (playing != null) 96_000L else 0L,
                    durationMs = if (playing != null) 253_000L else 0L,
                ),
                actions = actions,
            )
        }
    }

    private fun render(
        name: String,
        dark: Boolean,
        banners: List<BannerItem>,
        height: Int,
        pureBlack: Boolean = false,
        playing: TrackSummary? = null,
    ) {
        val scene = ImageComposeScene(width = 1440, height = height, density = Density(1f)) {
            CpTheme(
                themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT,
                pureBlack = pureBlack,
            ) { Preview(banners, playing) }
        }
        try {
            val data = scene.render().encodeToData() ?: error("encode failed")
            val out = File("build-preview-bento/preview").apply { mkdirs() }
            File(out, "$name.png").writeBytes(data.bytes)
        } finally {
            scene.close()
        }
    }

    private val withBanners = listOf(
        BannerItem(id = "b1", title = "焦点图一", imageUrl = "https://example.invalid/a.jpg", targetType = 2, targetId = "1"),
        BannerItem(id = "b2", title = "焦点图二", imageUrl = "https://example.invalid/b.jpg", targetType = 2, targetId = "2"),
    )

    private val playingTrack = TrackSummary(
        id = "playing-1",
        name = "正在播放的这首歌歌名比较长用来测两行",
        artist = "某位歌手",
        album = null,
        coverUrl = null,
        durationMs = 253_000L,
    )

    @Test
    fun renderBento() {
        // 空闲态（三行电台）：浅 / 深 / 纯黑
        render("bento-idle-light", dark = false, banners = withBanners, height = 1500)
        render("bento-idle-dark", dark = true, banners = withBanners, height = 1500)
        render("bento-idle-pureblack", dark = true, banners = withBanners, height = 1500, pureBlack = true)
        // 在播态（继续收听）：重点是进度条是否仍压底
        render("bento-playing-light", dark = false, banners = withBanners, height = 1500, playing = playingTrack)
        render("bento-playing-pureblack", dark = true, banners = withBanners, height = 1500, pureBlack = true, playing = playingTrack)
        // 无焦点图分支
        render("bento-nobanner-light", dark = false, banners = emptyList(), height = 1500)
    }
}
