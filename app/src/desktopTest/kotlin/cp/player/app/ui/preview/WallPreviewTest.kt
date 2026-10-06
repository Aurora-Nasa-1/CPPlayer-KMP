package cp.player.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.app.ui.wall.AlbumWall
import cp.player.app.ui.wall.WallItem
import cp.player.app.ui.wall.WallKind
import cp.player.app.ui.wall.WallState
import java.io.File
import kotlin.test.Test

/**
 * 专辑墙的离屏出图。
 *
 * 墙的版面**只能靠出图核对**：装箱是否重叠、比例配比是否真的出现、海报 / 沉浸层的
 * 几何对不对 —— 这些编译与单测都量不到。单测（`WallEngineTest`）钉的是
 * "无重叠 / 配比单调 / 鱼眼两端关闭"这类**性质**，出图钉的是"看起来对不对"。
 *
 * 与 `SidebarPreviewTest` 等同属 `ui/preview` 一类的常驻预览夹具，不是临时脚手架。
 * 产物落在 `app/build-wall-preview/`（被 `.gitignore` 的 `build-*` 目录规则覆盖）。
 *
 * ⚠️ 出图**不会走网络**，`coverUrl = null` ⇒ 所有封面都是占位块。因此这些图只能核对
 * **几何与配色**，核对不了封面本身；要看真封面得让夹具给一个能解析的 URL。
 * ⚠️ 海报 / 沉浸两档**必须带 `posterId`**，否则渲出来只是"更大的网格"，
 * 会给出假的通过信号。
 */
class WallPreviewTest {

    private val items: List<WallItem> = List(84) { i ->
        WallItem(
            id = "album:$i",
            title = "Album Title ${i + 1}",
            subtitle = "Artist ${i % 17} · 202${i % 10} · 专辑",
            coverUrl = null,
            kind = when (i % 6) {
                4 -> WallKind.PLAYLIST
                5 -> WallKind.SINGLE
                else -> WallKind.ALBUM
            },
            weight = 84 - i,
            sourceId = i.toLong(),
        )
    }

    @Composable
    private fun Preview(zoom: Float, posterId: String?) {
        val state = WallState(initialZoom = zoom)
        state.posterId = posterId
        if (posterId != null) {
            // 真实路径里这份快照是"点击瓦片、缩放之前"写进去的；夹具直接给一个
            // 与 Z2 瓦片尺寸相当的起点（约 150px），否则测的是兜底分支。
            state.posterFrom = cp.player.app.ui.wall.PosterOrigin(
                x = 420f, y = 300f, w = 150f, h = 150f,
            )
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
            val image = scene.render()
            File("build-wall-preview").mkdirs()
            File("build-wall-preview/$name.png").writeBytes(image.encodeToData()!!.bytes)
        } finally {
            scene.close()
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
        // Z3 海报 / Z4 沉浸：**必须带 posterId**，否则渲出来只是"更大的网格"，
        // 那两张图会给出假的通过信号。
        render("z3-poster-dark", 1320, 860, dark = true, zoom = 0.78f, posterId = "album:3")
        render("z3-poster-light", 1320, 860, dark = false, zoom = 0.78f, posterId = "album:3")
        render("z4-immersive-dark", 1320, 860, dark = true, zoom = 1.0f, posterId = "album:3")
        // 窄屏（手机）：海报层是最容易挤坏的一档
        render("phone-mosaic-dark", 400, 860, dark = true, zoom = 0.30f)
        render("phone-poster-light", 400, 860, dark = false, zoom = 0.78f, posterId = "album:3")
    }
}
