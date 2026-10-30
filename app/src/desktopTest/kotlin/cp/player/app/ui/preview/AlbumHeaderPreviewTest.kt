package cp.player.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.AppLanguage
import cp.player.app.i18n.ProvideCpStrings
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.screen.AlbumHeader
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.music.AlbumDetail
import cp.player.core.music.TrackSummary
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 专辑详情头部的**离屏出图**核对（含 2026-10-30 新增的收藏开关）。
 *
 * 编译与单测都量不到宽度、看不见对齐 —— 「收藏专辑 / 已收藏专辑」两种文案在窄屏
 * 窄栏里会不会把动作组挤掉、选中态的形状变化是否真的看得出来，只能看渲染结果。
 *
 * 四种组合都要过：**窄 / 宽 × 未收藏 / 已收藏**。宽屏那两张是重点 —— 收藏开关与
 * 动作组并排在同一列里，宽度不够时 `CpActionButtonGroup` 的 FlowRow 会折行，
 * 而 `CpToggleChip` 不会（它没有折行能力，只能被挤窄）。
 *
 * 专辑封面用 `null`（占位块分支）：那是没有网络时唯一能出图的分支。
 */
class AlbumHeaderPreviewTest {

    private val album = AlbumDetail(
        id = 1001L,
        name = "范特西",
        coverUrl = null,
        artistName = "周杰伦",
        artistId = 6452L,
        publishTimeMs = 998688000000L,
        company = "杰威尔音乐",
        description = "周杰伦第二张录音室专辑，收录《爱在西元前》《简单爱》等十首作品。",
        tracks = List(10) { i ->
            TrackSummary(
                id = "t$i",
                name = "曲目 ${i + 1}",
                artist = "周杰伦",
                album = "范特西",
                coverUrl = null,
                durationMs = 240_000L,
            )
        },
    )

    /** 长专辑名 + 长说明的极端情形：标题最多折 3 行，容易把下面的东西推下去。 */
    private val longNameAlbum = album.copy(
        name = "2026 年度华语流行精选合集（豪华重制版·附赠现场实录）",
        company = "某某某某某某某某某某唱片有限公司",
    )

    @Composable
    private fun Preview(detail: AlbumDetail, wide: Boolean, favorite: Boolean) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.TopStart,
        ) {
            Column(Modifier.fillMaxWidth().padding(CpSpacing.pageHorizontal)) {
                AlbumHeader(
                    detail = detail,
                    trackCount = detail.tracks.size,
                    totalDurationMs = detail.tracks.sumOf { it.durationMs },
                    wide = wide,
                    isFavorite = favorite,
                    favoritePending = false,
                    onToggleFavorite = {},
                    onPlayAll = {},
                    onShuffle = {},
                    onAddToQueue = {},
                    onDownloadAll = {},
                )
            }
        }
    }

    private fun render(name: String, dark: Boolean, width: Int, height: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CpTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                ProvideCpStrings(AppLanguage.ZH_HANS) { content() }
            }
        }
        try {
            val data = scene.render().encodeToData() ?: error("encode failed")
            val out = File("build-verify-albumfav/preview-album-header").apply { mkdirs() }
            File(out, "$name.png").writeBytes(data.bytes)
        } finally {
            scene.close()
        }
    }

    @Test
    fun renderAlbumHeaderStates() {
        // 窄屏（手机 / 双栏右栏）：封面在左上，说明与动作组纵向排开。
        render("narrow-unfav", dark = false, width = 400, height = 900) {
            Preview(album, wide = false, favorite = false)
        }
        render("narrow-fav", dark = false, width = 400, height = 900) {
            Preview(album, wide = false, favorite = true)
        }
        // 宽屏：封面在左、文字在右，「未收藏」与「已收藏」各一张 —— 后者文案更长。
        render("wide-unfav", dark = false, width = 1100, height = 520) {
            Preview(album, wide = true, favorite = false)
        }
        render("wide-fav", dark = false, width = 1100, height = 520) {
            Preview(album, wide = true, favorite = true)
        }
        // 长名 + 深色：标题折行后收藏开关仍要在说明下面、不被挤出可见区。
        render("wide-longname-dark", dark = true, width = 1100, height = 620) {
            Preview(longNameAlbum, wide = true, favorite = true)
        }
    }

    /** 两种状态的文案必须真的不同 —— 否则出图上看不出变化，用户也看不出。 */
    @Test
    fun favoriteChipLabelsDifferBetweenStates() {
        val zh = cp.player.app.i18n.CpStringsZh.album
        val en = cp.player.app.i18n.CpStringsEn.album
        assertTrue(zh.favoriteAlbum != zh.favoriteAlbumActive, "中文：未收藏 / 已收藏 文案必须不同")
        assertTrue(en.favoriteAlbum != en.favoriteAlbumActive, "英文：未收藏 / 已收藏 文案必须不同")
        // 带参文案要真的把专辑名写进去（CpStringsTest 的哨兵机制只覆盖参数被「用到」，
        // 这里额外确认取消收藏的确认正文是一句完整的话）。
        assertTrue(zh.unfavoriteAlbumMessage("范特西").contains("范特西"))
        assertTrue(en.unfavoriteAlbumMessage("Fantasie").contains("Fantasie"))
    }

    /**
     * 确认框的**动词**不能复用状态标签。
     *
     * `favoriteAlbumActive`（「已收藏专辑」）是一个**状态描述**，而确认框的标题与确认键
     * 需要的是「取消收藏」这个**动作**。两者混用会得到「已收藏专辑 / 确定取消收藏… /
     * 已收藏专辑」这样的框，点确认键看起来像是在「再次收藏」——用户根本不知道点下去
     * 到底会发生什么。所以这里钉住它们是两个不同的字符串，防止以后为了「少加一条文案」
     * 把动词退化成状态标签。
     */
    @Test
    fun unfavoriteConfirmUsesAVerbNotTheStateLabel() {
        val zh = cp.player.app.i18n.CpStringsZh.album
        val en = cp.player.app.i18n.CpStringsEn.album
        assertTrue(
            zh.unfavoriteAlbumAction != zh.favoriteAlbumActive,
            "中文：确认键必须是动词「取消收藏」，不能复用状态标签「已收藏专辑」",
        )
        assertTrue(
            en.unfavoriteAlbumAction != en.favoriteAlbumActive,
            "英文：确认键必须是动词，不能复用状态标签",
        )
    }
}
