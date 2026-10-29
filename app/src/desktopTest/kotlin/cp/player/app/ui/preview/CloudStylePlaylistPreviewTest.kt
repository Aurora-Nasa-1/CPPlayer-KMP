package cp.player.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.AppLanguage
import cp.player.app.i18n.ProvideCpStrings
import cp.player.app.ui.component.CpTwoPane
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.SongMenuActions
import cp.player.app.ui.component.TrackEmptySpec
import cp.player.app.ui.component.TrackListShell
import cp.player.app.ui.component.TrackListStatus
import cp.player.app.ui.component.TrackPrimaryAction
import cp.player.app.ui.component.TrackRows
import cp.player.app.ui.component.TrackSecondaryAction
import cp.player.app.ui.component.TrackSecondarySpec
import cp.player.app.ui.component.TrackSelectionUi
import cp.player.app.ui.component.songContextMenuItems
import cp.player.app.ui.screen.PlaylistHeaderInfo
import cp.player.app.ui.screen.PlaylistInfoRail
import cp.player.app.ui.screen.PlaylistTopBarTitle
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.TrackSummary
import java.io.File
import kotlin.test.Test

/**
 * 歌单页「云盘样式」头部的**离屏出图**。
 *
 * 只看版面，不做断言：交付前用它核对「信息卡 + 主播放 + 三个 chip + 曲目行」
 * 这一列在窄屏 / 宽屏 / 深色下是否都成立。
 *
 * 产物：`app/build/preview-cloud/`（`build/` 已被 `.gitignore` 覆盖）。
 */
class CloudStylePlaylistPreviewTest {

    private val playlist = PlaylistSummary(
        id = 42L,
        name = "夜里跑步时听的歌",
        coverUrl = null,
        trackCount = 24,
        creatorName = "阿猫",
    )

    /** 每日推荐 / 心动模式那类虚拟歌单：有封面（首曲封面）、作者固定、没有真实歌单实体。 */
    private val generated = PlaylistSummary(
        id = -101L,
        name = "每日推荐",
        coverUrl = null,
        trackCount = 30,
        creatorName = "CPPlayer",
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
    private fun Page(summary: PlaylistSummary, favorite: Boolean = true) {
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
        val secondary = TrackSecondarySpec(
            actions = listOf(
                TrackSecondaryAction.Add,
                TrackSecondaryAction.DownloadAll,
                TrackSecondaryAction.Sort,
            ),
            onAction = {},
        )
        TrackListShell(
            rows = rows,
            status = TrackListStatus(),
            empty = TrackEmptySpec(title = "歌单暂无歌曲"),
            secondary = secondary,
            title = { PlaylistTopBarTitle(summary = summary) },
            onBack = {},
            header = {
                PlaylistHeaderInfo(
                    summary = summary,
                    trackCountLabel = "${items.size} 首歌曲 · 1:24:00",
                    isFavorite = favorite,
                )
            },
            primaryActions = listOf(TrackPrimaryAction.PlayAll, TrackPrimaryAction.Shuffle),
            onPrimaryAction = {},
            selectable = true,
            selection = TrackSelectionUi(
                active = false,
                selectedCount = 0,
                allSelected = false,
                onToggleAll = {},
                onExit = {},
            ),
            onOverflow = {},
        )
    }

    /** 宽屏双栏：左栏信息卡 + 右栏纯列表。 */
    @Composable
    private fun WidePage() {
        CpTwoPane(
            railWidth = 340.dp,
            rail = { railModifier ->
                PlaylistInfoRail(
                    summary = playlist,
                    isFavorite = true,
                    trackCountLabel = "24 首歌曲 · 1:24:00",
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
            detail = {
                TrackListShell(
                    rows = TrackRows(
                        items = tracks(),
                        key = { it.id },
                        row = { index, track, _ ->
                            SongItem(
                                track = track,
                                index = index,
                                total = tracks().size,
                                isCurrentlyPlaying = index == 2,
                                contextMenu = songContextMenuItems(
                                    SongMenuActions(onPlay = {}, isFavorite = false, onToggleFavorite = {})
                                ),
                                onClick = {},
                                onOptionsClick = {},
                            )
                        },
                    ),
                    status = TrackListStatus(),
                    empty = TrackEmptySpec(title = "歌单暂无歌曲"),
                    title = { PlaylistTopBarTitle(playlist) },
                    onBack = {},
                    search = null,
                    onOverflow = {},
                    modifier = Modifier.fillMaxSize(),
                )
            },
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
            val out = File("build/preview-cloud").apply { mkdirs() }
            File(out, "$name.png").writeBytes(data.bytes)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `render cloud style playlist header`() {
        render("after-narrow-light", dark = false, width = 400, height = 900) { Page(playlist) }
        render("after-narrow-dark", dark = true, width = 400, height = 900) { Page(playlist) }
        render("after-narrow-generated", dark = false, width = 400, height = 900) {
            Page(generated, favorite = false)
        }
        render("after-wide-light", dark = false, width = 1200, height = 800) { WidePage() }
        render("after-wide-dark", dark = true, width = 1200, height = 800) { WidePage() }
        check(File("build/preview-cloud/after-narrow-light.png").exists())
    }
}
