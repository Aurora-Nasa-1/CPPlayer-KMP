package cp.player.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.app.ui.wall.AlbumWall
import cp.player.app.ui.wall.PosterOrigin
import cp.player.app.ui.wall.WallCrown
import cp.player.app.ui.wall.WallHud
import cp.player.app.ui.wall.WallChrome
import cp.player.app.ui.wall.WallImmersive
import cp.player.app.ui.wall.WallItem
import cp.player.app.ui.wall.WallKind
import cp.player.app.ui.wall.WallLevel
import cp.player.app.ui.wall.WallSort
import cp.player.app.ui.wall.WallSpan
import cp.player.app.ui.wall.WallTile
import cp.player.app.ui.wall.WallOverlaySlot
import cp.player.app.ui.wall.WallState
import cp.player.app.ui.wall.WallZoomLadder
import cp.player.core.music.TrackSummary
import cp.player.core.playback.PlaybackUiState
import java.io.File
import kotlin.test.Test

/**
 * 专辑墙的离屏出图。
 *
 * 墙的版面**只能靠出图核对**：装箱是否重叠、比例配比是否真的出现、海报 / 沉浸层的
 * 几何对不对 —— 这些编译与单测都量不到。单测（`WallEngineTest`）钉的是
 * "无重叠 / 配比单调 / 鱼眼两端关闭"这类**性质**，出图钉的是"看起来对不对"。
 *
 * ⚠️ **必须把浮层（缩放谱 / 表冠 / 读数条）一起渲进去**。只渲 `AlbumWall` 会漏掉
 * 整整一类缺陷 —— 第一版就是这样漏掉了"缩放谱写死 46dp 宽、把『马赛克』三个字裁掉一半"。
 * 所以这里照抄 `AlbumWallScreen` 的浮层堆叠顺序与对齐方式。
 *
 * 与 `SidebarPreviewTest` 等同属 `ui/preview` 一类的常驻预览夹具。
 * 产物落在 `app/build-wall-preview/`（被 `.gitignore` 的 `build-*` 目录规则覆盖）。
 *
 * ⚠️ 出图**不会走网络**，`coverUrl = null` ⇒ 所有封面都是占位块。因此这些图只能核对
 * **几何与配色**，核对不了封面本身。
 * ⚠️ 海报 / 沉浸两档**必须带 `posterId` + `posterFrom`**，否则渲出来只是"更大的网格"，
 * 会给出假的通过信号。
 */
class WallPreviewTest {

    /** 刻意混四种比例：专辑 `1:1`、歌曲 `2:1`、本地 `1:2`，高权重升 `2:2`。 */
    private val items: List<WallItem> = List(84) { i ->
        WallItem(
            id = "album:$i",
            title = "Album Title ${i + 1}",
            subtitle = "Artist ${i % 17} · 202${i % 10} · 专辑",
            coverUrl = null,
            kind = when (i % 6) {
                4 -> WallKind.SONG
                5 -> WallKind.LOCAL
                else -> WallKind.ALBUM
            },
            weight = 84 - i,
            sourceId = i.toString(),
        )
    }

    /** 与 `AlbumWallScreen` 同构：画布 + 四层浮层。 */
    @Composable
    private fun Preview(zoom: Float, posterId: String?) {
        val state = WallState(initialZoom = zoom)
        state.posterId = posterId
        if (posterId != null) {
            // 真实路径里这份快照是"点击瓦片、缩放之前"写进去的；夹具给一个
            // 与 Z2 瓦片尺寸相当的起点（约 150px），否则测的是兜底分支。
            state.posterFrom = PosterOrigin(x = 420f, y = 300f, w = 150f, h = 150f)
        }
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.TopStart,
        ) {
            AlbumWall(
                items = items,
                state = state,
                onOpenPoster = {},
                onClosePoster = {},
                onPlay = {},
                modifier = Modifier.fillMaxSize(),
            )
            // 与真页面走**同一份**浮层组合 —— 夹具里不再复刻一遍堆叠顺序。
            WallChrome(state = state, sort = WallSort.RECENT, onSortChange = {})
        }
    }

    /**
     * **瓦片解剖**：把四种比例各放大渲一块。
     *
     * 为什么需要它：马赛克层上瓦片只有 60–150px，出图缩放到屏幕尺寸后**看不出**
     * "2:1 的封面是否真的只占左半""1:2 的标题是不是压在封面上"。这一档把每一块
     * 放大到 180–360px，内部版式一眼可辨。
     */
    @Test
    fun `render tile anatomy`() {
        val samples = listOf(
            WallItem("a", "Album Title", "Artist · 2024 · 专辑", null, WallKind.ALBUM, 1, "1"),
            WallItem("b", "Song Title", "Artist · 歌曲", null, WallKind.SONG, 1, "2"),
            WallItem("c", "a-rather-long-local-file-name.flac", "Artist · 本地", null, WallKind.LOCAL, 1, "3"),
            WallItem("d", "Big Tile", "Artist · 2024 · 专辑", null, WallKind.ALBUM, 1, "4"),
        )
        val scene = ImageComposeScene(width = 1320, height = 460, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.LIGHT) {
                Row(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    WallTile(samples[0], WallSpan.Square, 0.55f, false, 256, Modifier.size(180.dp))
                    WallTile(samples[1], WallSpan.Wide, 0.55f, false, 512, Modifier.size(360.dp, 180.dp))
                    WallTile(samples[2], WallSpan.Tall, 0.55f, false, 512, Modifier.size(180.dp, 360.dp))
                    WallTile(samples[3], WallSpan.Big, 0.55f, false, 512, Modifier.size(360.dp))
                }
            }
        }
        try {
            val image = scene.render()
            File("build-wall-preview").mkdirs()
            File("build-wall-preview/tile-anatomy.png").writeBytes(image.encodeToData()!!.bytes)
        } finally {
            scene.close()
        }
    }

    private fun render(
        name: String,
        width: Int,
        height: Int,
        dark: Boolean,
        zoom: Float,
        posterId: String? = null,
    ) {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CpTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                Preview(zoom, posterId)
            }
        }
        try {
            // ⚠️ 渲两遍：第一遍让 `AlbumWall` 回填 `state.layout`，第二遍 HUD 才拿得到布局。
            // （真窗口里那是下一帧的事，出图只有一帧。）
            scene.render()
            val image = scene.render()
            File("build-wall-preview").mkdirs()
            File("build-wall-preview/$name.png").writeBytes(image.encodeToData()!!.bytes)
        } finally {
            scene.close()
        }
    }

    /**
     * **沉浸播放器（Z4）**单独渲一张。
     *
     * 它不在 `WallChrome` 里、也不由 `posterId` 触发 —— 只看 `zoom`，所以主用例覆盖不到它。
     * 少了这张图，"播放器控件被浮层压住""封面糊不糊"这类问题只能靠真机发现。
     */
    @Test
    fun `render immersive player`() {
        val track = TrackSummary(
            id = "song:1",
            name = "Song Title With A Rather Long Name",
            artist = "Artist Name",
            album = "Album",
            coverUrl = null,
            durationMs = 222_000L,
        )
        val playback = PlaybackUiState(
            currentTrack = track,
            isPlaying = true,
            positionMs = 74_000L,
            durationMs = 222_000L,
        )
        for (dark in listOf(true, false)) {
            val state = WallState(initialZoom = 1.0f)
            val scene = ImageComposeScene(width = 1320, height = 860, density = Density(1f)) {
                CpTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                        AlbumWall(state = state, items = items, onOpenPoster = {}, onClosePoster = {}, onPlay = {})
                        WallImmersive(
                            playback = playback,
                            zoom = state.zoom,
                            upNext = items.take(8),
                            onCollapse = {},
                            onPlayPause = {},
                            onSkipNext = {},
                            onSkipPrevious = {},
                            onSeek = {},
                            onPlayUpNext = {},
                        )
                    }
                }
            }
            try {
                scene.render()
                val image = scene.render()
                File("build-wall-preview").mkdirs()
                File("build-wall-preview/immersive-${if (dark) "dark" else "light"}.png")
                    .writeBytes(image.encodeToData()!!.bytes)
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun `render the five zoom levels`() {
        // Z0 尘埃 / Z1 马赛克 / Z2 封面（网格层，无海报）
        val gridLevels = listOf("z0-dust" to 0.02f, "z1-mosaic" to 0.30f, "z2-cover" to 0.55f)
        for ((name, zoom) in gridLevels) {
            render("$name-dark", 1320, 860, dark = true, zoom = zoom)
            render("$name-light", 1320, 860, dark = false, zoom = zoom)
        }
        // Z3 海报 / Z4 沉浸：**必须带 posterId**，否则渲出来只是"更大的网格"。
        render("z3-poster-dark", 1320, 860, dark = true, zoom = WallLevel.POSTER.zoom, posterId = "album:3")
        render("z3-poster-light", 1320, 860, dark = false, zoom = WallLevel.POSTER.zoom, posterId = "album:3")
        render("z4-immersive-dark", 1320, 860, dark = true, zoom = 1.0f, posterId = "album:3")
        // 窄屏（手机）：缩放谱与读数条最容易挤坏的两档
        render("phone-mosaic-dark", 400, 860, dark = true, zoom = 0.30f)
        render("phone-poster-light", 400, 860, dark = false, zoom = WallLevel.POSTER.zoom, posterId = "album:3")
    }
}
