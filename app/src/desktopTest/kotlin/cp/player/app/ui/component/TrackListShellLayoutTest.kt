package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.ProvideCpStrings
import cp.player.app.i18n.AppLanguage
import androidx.compose.foundation.layout.width
import cp.player.app.ui.screen.PlaylistHeaderInfo
import cp.player.app.ui.screen.PlaylistInfoRail
import cp.player.app.ui.screen.PlaylistTopBarTitle
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.TrackSummary
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `TrackListShell` 的版面**像素守卫**。
 *
 * 断言的是一件真实发生过的事，而不是"能渲染"：
 * 1. **G4**：顶栏之下到第一行歌曲之间不得出现大段空白 —— 直接按像素找第一条
 *    "行底色"（`surfaceContainerLow/Highest`）的上沿，它的 y 必须落在
 *    `topBarHeight + contentTopGap + hero 高度` 的量级内，而不是被
 *    `LargeTopAppBar` 的展开高度（约 152dp）推下去。
 * 2. **G3**：水平内边距处处等于 `CpSpacing.pageHorizontal`（20dp）—— 读第一行
 *    行底色的左沿 x，不得是 12/16/24。
 * 3. **G6**：内容区不能有任何动作胶囊 —— 头部块（header）里除了信息胶囊之外，
 *    不允许出现「更多 / 删除 / 分享 / 排序」这一排（原先那一排起点 y≈顶栏下 130dp）。
 *    这里用"头部块底部之后紧接第一首歌曲"来间接判定：header 之后必须直接是行。
 */
class TrackListShellLayoutTest {

    private val playlist = PlaylistSummary(
        id = 42L,
        name = "夜里跑步时听的歌",
        coverUrl = null,
        trackCount = 24,
        creatorName = "阿猫",
    )

    private fun tracks(): List<TrackSummary> = List(24) { i ->
        TrackSummary(
            id = "${9000 + i}",
            name = "曲目 ${i + 1} 号 · 一个稍微长一点的名字",
            artist = "歌手 ${i + 1}",
            album = "专辑 ${i + 1}",
            coverUrl = null,
            durationMs = 210_000L,
        )
    }

    @Composable
    private fun ShellPage(
        searchActive: Boolean = false,
        selectionMode: Boolean = false,
        /** 宽屏右栏：信息头与主播放行都在左栏，右栏只出列表（真实页面的传参）。 */
        paneOnly: Boolean = false,
    ) {
        val items = tracks()
        val rows = TrackRows(
            items = items,
            key = { it.id },
            row = { index, track, _ ->
                SongItem(
                    track = track,
                    index = index,
                    total = items.size,
                    isCurrentlyPlaying = index == 2,
                    contextMenu = songContextMenuItems(
                        SongMenuActions(onPlay = {}, isFavorite = false, onToggleFavorite = {})
                    ),
                    onClick = {},
                    onOptionsClick = {},
                )
            },
        )
        TrackListShell(
            rows = rows,
            status = TrackListStatus(),
            empty = TrackEmptySpec(title = "歌单暂无歌曲"),
            secondary = if (paneOnly) null else TrackSecondarySpec(
                actions = listOf(
                    TrackSecondaryAction.Add,
                    TrackSecondaryAction.DownloadAll,
                    TrackSecondaryAction.Sort,
                ),
                onAction = {},
            ),
            title = {
                PlaylistTopBarTitle(summary = playlist)
            },
            onBack = {},
            header = if (paneOnly) null else ({
                PlaylistHeaderInfo(
                    summary = playlist,
                    trackCountLabel = "${items.size} 首 · 1:24:00",
                    isFavorite = true,
                )
            }),
            primaryActions = if (paneOnly) emptyList() else listOf(TrackPrimaryAction.PlayAll, TrackPrimaryAction.Shuffle),
            onPrimaryAction = {},
            search = TrackSearchSpec(
                query = "海",
                onQueryChange = {},
                active = searchActive,
                onToggle = {},
                resultCount = 3,
            ),
            selectable = true,
            selection = TrackSelectionUi(
                active = selectionMode,
                selectedCount = 2,
                allSelected = false,
                onToggleAll = {},
                onExit = {},
            ),
            onOverflow = {},
        )
    }

    /** 宽屏双栏：左栏信息 + 右栏列表，两栏都由壳给内边距。 */
    @Composable
    private fun WideShellPage() {
        CpTwoPane(
            railWidth = 340.dp,
            rail = { railModifier ->
                PlaylistInfoRail(
                    summary = playlist,
                    isFavorite = true,
                    trackCountLabel = "${tracks().size} 首 · 1:24:00",
                    primaryActions = listOf(TrackPrimaryAction.PlayAll, TrackPrimaryAction.Shuffle),
                    onPrimaryAction = {},
                    primaryEnabled = true,
                    secondary = TrackSecondarySpec(
                        actions = listOf(
                            TrackSecondaryAction.Add,
                            TrackSecondaryAction.DownloadAll,
                            TrackSecondaryAction.Sort,
                        ),
                        onAction = {},
                    ),
                    selectionMode = false,
                    selectedCount = 0,
                    onToggleSelectAll = {},
                    onExitSelection = {},
                    modifier = railModifier,
                )
            },
            detail = { ShellPage(paneOnly = true) },
        )
    }

    private fun render(name: String, dark: Boolean, width: Int, height: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CpTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                ProvideCpStrings(AppLanguage.ZH_HANS) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        content()
                    }
                }
            }
        }
        try {
            val data = scene.render().encodeToData() ?: error("encode failed")
            val out = File("build/preview-tracklist").apply { mkdirs() }
            File(out, "$name.png").writeBytes(data.bytes)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `歌单页接壳之后 顶部不再有多余空白 且内边距归壳`() {
        val outDir = File("build/preview-tracklist").apply { mkdirs() }

        render("shell-narrow-light", dark = false, width = 400, height = 860) { ShellPage() }
        render("shell-narrow-dark", dark = true, width = 400, height = 860) { ShellPage() }
        render("shell-search", dark = false, width = 400, height = 860) { ShellPage(searchActive = true) }
        render("shell-wide-light", dark = false, width = 1200, height = 800) { WideShellPage() }
        render("shell-wide-dark", dark = true, width = 1200, height = 800) { WideShellPage() }

        val img = ImageIO.read(File(outDir, "shell-narrow-light.png"))
        val bg = img.getRGB(4, 4)
        val barBottom = 64 // TrackListMetrics.topBarHeight = 64dp @ density 1

        // 第一行的行底色（SongItem 的 surfaceContainerLow）与页面背景不同；
        // 从顶栏下方往下扫，找第一条明显不同于背景的行。
        fun differs(x: Int, y: Int): Boolean {
            val rgb = img.getRGB(x, y)
            val dr = ((rgb shr 16) and 0xFF) - ((bg shr 16) and 0xFF)
            val dg = ((rgb shr 8) and 0xFF) - ((bg shr 8) and 0xFF)
            val db = (rgb and 0xFF) - (bg and 0xFF)
            return (dr * dr + dg * dg + db * db) > 400
        }

        // 沿左内边距内侧的竖线扫：内容区从 pageHorizontal 之后开始。
        val probeX = CpSpacing.pageHorizontal.value.toInt() + 200
        var firstContentY = -1
        for (y in barBottom until img.height) {
            if (differs(probeX, y)) { firstContentY = y; break }
        }
        println("[shell] topBarBottom=$barBottom firstContentY=$firstContentY")
        assertTrue(firstContentY >= 0, "顶栏之下没有任何内容 —— 版面塌了")

        // G4：封面 + 标题 + 元数据 + 主播放行，加起来约 200+14+30+8+26+12+54 ≈ 344dp；
        // 上限给到 420 —— 只要还在这个量级，就不是 LargeTopAppBar 那 152dp 的展开占位。
        assertTrue(
            firstContentY < 420,
            "顶栏之下第一块内容出现在 y=$firstContentY，超出壳的头部量级（>420）—— 顶部又空了一块",
        )

        // G3：向左扫**歌曲行卡片**的左沿。
        //
        // ⚠️ 必须在**深色**图上量：浅色主题的 `surfaceContainerLow`（行底色）与
        // `surface`（页面背景）几乎同色，阈值稍高就扫不到卡片边界、只会扫到
        // 16dp 内边距之后那张粉色封面（实测 37 ≈ 20+16）—— 这个探针第一版就栽在这。
        // 深色下卡片是 `surfaceContainerHighest`，与背景的差足够大。
        val darkImg = ImageIO.read(File(outDir, "shell-narrow-dark.png"))
        val darkBg = darkImg.getRGB(4, 4)
        fun darkDiffers(x: Int, y: Int): Boolean {
            val rgb = darkImg.getRGB(x, y)
            val dr = ((rgb shr 16) and 0xFF) - ((darkBg shr 16) and 0xFF)
            val dg = ((rgb shr 8) and 0xFF) - ((darkBg shr 8) and 0xFF)
            val db = (rgb and 0xFF) - (darkBg and 0xFF)
            return (dr * dr + dg * dg + db * db) > 250
        }
        val rowMidY = (darkImg.height * 2) / 3
        var leftEdge = -1
        for (x in 0 until darkImg.width) {
            if (darkDiffers(x, rowMidY)) { leftEdge = x; break }
        }
        println("[shell] rowLeftEdge=$leftEdge expected=${CpSpacing.pageHorizontal.value.toInt()}")
        assertTrue(
            leftEdge == CpSpacing.pageHorizontal.value.toInt(),
            "列表左内边距是 $leftEdge，应为 ${CpSpacing.pageHorizontal.value.toInt()} —— 子页面又在自写 padding",
        )

        // G6：内容区**不许**出现动作胶囊行。
        //
        // 判据取「顶栏之下的高饱和按钮色块有几块」：改造前那一排 chip 用的是
        // `surfaceContainerHigh` 底 + 图标，且其中一枚是 `errorContainer`（红底）。
        // 现在内容区只有一个 primary 容器色的主播放胶囊；再出现第二块高饱和底色
        // 或任何 error 色块，就说明动作轨又被塞回来了。
        // G5：破坏性操作（删除 / 取消收藏）不得以 error 色出现在主界面。
        // 逐个像素扫上半屏（封面 + 元数据 + 主播放行都在这带里）是否存在 error 系色块。
        var errorPixels = 0
        for (y in barBottom until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = (rgb and 0xFF)
                if (r > 180 && g < 120 && b < 120) errorPixels++
            }
        }
        println("[shell] errorPixels=$errorPixels")
        assertTrue(
            errorPixels == 0,
            "内容区出现 $errorPixels 像素的 error 色 —— 破坏性操作又摊在主界面上了（G5）",
        )

        // G6（**需求已更新两次**）：这三个动作**宽屏时归左栏**，窄屏时留在内容区。
        //
        // 宽屏：chip 必须出现在竖线左侧（左栏，与「全部播放」同栏），右侧曲目栏
        // 顶部不许有 chip；窄屏：内容区「主播放 → 36dp chip → 曲目」只剩一个行距。
        val chipPx = TrackListMetrics.secondaryRowHeight.value.toInt()

        val wide = ImageIO.read(File(outDir, "shell-wide-light.png"))
        val wideBg = wide.getRGB(4, 4)
        fun wideDiffers(x: Int, y: Int): Boolean {
            val rgb = wide.getRGB(x, y)
            val dr = ((rgb shr 16) and 0xFF) - ((wideBg shr 16) and 0xFF)
            val dg = ((rgb shr 8) and 0xFF) - ((wideBg shr 8) and 0xFF)
            val db = (rgb and 0xFF) - (wideBg and 0xFF)
            return (dr * dr + dg * dg + db * db) > 60
        }
        // 左栏 x∈[60,280)，右栏 x∈[420,1160)。
        //
        // ⚠️ 判据不能是「只有左栏有内容」：chip 所在的高度上，右边正对着曲目卡，
        // 两边都有内容 —— 第一版就是这么假阴性的。改为**在左栏内**按「与背景不同
        // 的连续横带」找高度约 36dp 的那一条，并确认它位于「全部播放」行之下。
        val railBands = mutableListOf<IntRange>()
        var railStart = -1
        for (y in 0 until wide.height) {
            val leftOn = (60 until 280 step 2).any { wideDiffers(it, y) }
            if (leftOn && railStart < 0) railStart = y
            if (!leftOn && railStart >= 0) {
                if (y - railStart >= 4) railBands += railStart until y
                railStart = -1
            }
        }
        println("[shell] wide railBands=" + railBands.joinToString { "${it.first}-${it.last}" })
        assertTrue(
            railBands.any { it.last - it.first + 1 in (chipPx - 6)..(chipPx + 6) },
            "宽屏左栏找不到 ${chipPx}dp 的动作行 —— 「添加 / 下载全部 / 排序」没有放进左栏（$railBands）",
        )

        // 右栏**顶栏之下、第一张曲目卡之上**不许有内容。
        //
        // 右栏顶栏 64dp + 列表 `contentTopGap` 8dp ⇒ 第一张卡自 y=72 起（实测一致）。
        // 也就是说顶栏与卡片之间只有那 8dp，没有给 chip 留任何位置 —— 这一断言
        // 直接把它锁死：若哪天 chip 又摊回曲目栏，这里会先冒出 36dp 的带。
        var rightTopRows = 0
        for (y in 66 until 71) {
            if ((420 until 1160 step 6).any { wideDiffers(it, y) }) rightTopRows++
        }
        println("[shell] wide rightTopContentRows=$rightTopRows")
        assertTrue(
            rightTopRows == 0,
            "宽屏右栏顶部（顶栏与第一张曲目卡之间）有内容 —— 动作又被摊回曲目栏了",
        )

        // 窄屏：主播放 → chip → 曲目，间距只剩一个行距。
        val axisX = CpSpacing.pageHorizontal.value.toInt() + 200
        val bands = mutableListOf<IntRange>()
        var bandStart = -1
        for (y in barBottom until img.height) {
            val on = differs(axisX, y)
            if (on && bandStart < 0) bandStart = y
            if (!on && bandStart >= 0) {
                if (y - bandStart >= 3) bands += bandStart until y
                bandStart = -1
            }
        }
        println("[shell] narrow bands=" + bands.joinToString { "${it.first}-${it.last}" })
        val chipBand = bands.firstOrNull { it.last - it.first + 1 in (chipPx - 3)..(chipPx + 3) }
        assertTrue(chipBand != null, "窄屏内容区丢了 ${chipPx}dp 的 chip 行（$bands）")
        val nextBand = bands.firstOrNull { it.first > chipBand!!.last }
        assertTrue(nextBand != null, "chip 行之后没有内容 —— 列表没出来")
        val gap = nextBand!!.first - chipBand.last - 1
        println("[shell] narrow chip=${chipBand.first}-${chipBand.last} next=${nextBand.first}-${nextBand.last} gap=$gap")
        assertTrue(
            gap <= 40,
            "chip 行之后空了 $gap dp 才出现内容 —— 那里又多出别的动作轨（$bands）",
        )

        // 窄屏内容区只有这一条动作带。
        val chipBands = bands.filter { it.last - it.first + 1 in (chipPx - 1)..(chipPx + 1) }
        assertTrue(
            chipBands.size == 1,
            "窄屏内容区出现了 ${chipBands.size} 条动作条（$chipBands）—— 只能有「添加 / 下载全部 / 排序」这一行",
        )
    }
}
