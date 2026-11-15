package cp.player.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.AppLanguage
import cp.player.app.i18n.ProvideCpStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.app.ui.model.DownloadsScreenModel
import cp.player.core.media.LocalMediaItem
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 本地媒体库 Expressive 版式探针。
 *
 * 编译与纯测量看不到「大标题被封面堆挤成两行」「六个分段胶囊在 390dp 上溢出」
 * 「分类磁贴的连体圆角对不齐」这类问题，必须离屏出图核对。
 */
class LocalLibraryVisualTest {

    private fun render(
        width: Int,
        height: Int,
        themeMode: ThemeMode,
        content: @Composable () -> Unit,
    ): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CpTheme(themeMode = themeMode) {
                ProvideCpStrings(AppLanguage.ZH_HANS) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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
    fun `local library quick actions and tabs fit a phone`() {
        val bytes = render(390, 844, ThemeMode.DARK) {
            val s = cpStrings()
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                LocalLibrarySubTabs(
                    selected = 0,
                    songCount = 128,
                    albumCount = 12,
                    artistCount = 8,
                    favoriteCount = 23,
                    playlistCount = 4,
                    folderCount = 9,
                    strings = s,
                    onSelect = {},
                )
                SampleQuickActions(Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(24.dp))
            }
        }
        write("build/local-library-hero-phone.png", bytes)
        assertTrue(bytes.size > 10_000, "手机端本地库动作组不应为空，实际 ${bytes.size} bytes")
    }

    @Test
    fun `library local section fits phone and desktop`() {
        val phone = render(390, 844, ThemeMode.DARK) {
            LocalSectionPreview()
        }
        val desktop = render(1100, 900, ThemeMode.LIGHT) {
            LocalSectionPreview()
        }
        write("build/local-library-section-phone.png", phone)
        write("build/local-library-section-desktop.png", desktop)
        assertTrue(phone.size > 20_000, "手机端本地库概览不应为空，实际 ${phone.size} bytes")
        assertTrue(desktop.size > 20_000, "桌面端本地库概览不应为空，实际 ${desktop.size} bytes")
    }

    @Test
    fun `local library page header and rows fit a phone`() {
        val bytes = render(390, 844, ThemeMode.DARK) {
            val s = cpStrings()
            val items = sampleItems()
            val model = DownloadsScreenModel()
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                LocalLibrarySubTabs(
                    selected = 0,
                    songCount = items.size,
                    albumCount = 1,
                    artistCount = 1,
                    favoriteCount = 1,
                    playlistCount = 0,
                    folderCount = 1,
                    strings = s,
                    onSelect = {},
                )
                SampleQuickActions(Modifier.padding(horizontal = 20.dp))
                LocalSectionHeader(title = s.downloads.tabSongs, count = items.size) {
                    FilledTonalIconButton(
                        onClick = {},
                        modifier = Modifier.size(40.dp),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Icon(Icons.Filled.Shuffle, null, Modifier.size(18.dp))
                    }
                    FilledTonalIconButton(
                        onClick = {},
                        modifier = Modifier.size(40.dp),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                    }
                }
                Column(
                    Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items.forEachIndexed { index, song ->
                        LocalSongRow(
                            song = song,
                            isCurrentlyPlaying = index == 0,
                            likedIds = emptySet(),
                            favoritePaths = emptySet(),
                            model = model,
                            strings = s,
                            onPlay = {},
                            onToggleCloudFav = { _ -> },
                            onToggleLocalFav = { _ -> },
                            onAddToCloudPlaylist = { _ -> },
                            onAddToLocalPlaylist = { _ -> },
                            onRemoveSong = { _ -> },
                            onDeleteSong = { _ -> },
                            onShowInfo = { _ -> },
                            onOptionsClick = { _ -> },
                            index = index,
                            total = items.size,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        write("build/local-library-page-phone.png", bytes)
        assertTrue(bytes.size > 25_000, "手机端本地库页面不应为空，实际 ${bytes.size} bytes")
    }

    @Test
    fun `local detail header fits a phone`() {
        val bytes = render(390, 300, ThemeMode.DARK) {
            val s = cpStrings()
            Column {
                DetailHeader(
                    title = "Late Night Mix",
                    subtitle = "12 首歌曲 · 48:12",
                    coverUri = null,
                    fallbackIcon = Icons.AutoMirrored.Filled.QueueMusic,
                    onBack = {},
                    trailingActions = {
                        IconButton(onClick = {}) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null)
                        }
                    },
                )
                LocalPlayAllRow(
                    playAllLabel = s.downloads.playAll,
                    enabled = true,
                    onPlayAll = {},
                    onShuffle = {},
                )
                Spacer(Modifier.height(24.dp))
            }
        }
        write("build/local-library-detail-phone.png", bytes)
        assertTrue(bytes.size > 8_000, "手机端本地详情头部不应为空，实际 ${bytes.size} bytes")
    }

    @Composable
    private fun LocalSectionPreview() {
        val s = cpStrings()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            LocalCategoryGrid(
                items = listOf(
                    LocalCategoryItem(s.downloads.tabSongs, 128, Icons.Filled.MusicNote, 0),
                    LocalCategoryItem(s.downloads.tabAlbums, 12, Icons.Filled.Album, 1),
                    LocalCategoryItem(s.downloads.tabArtists, 8, Icons.Filled.Person, 2),
                    LocalCategoryItem(s.downloads.tabLocalFavorites, 23, Icons.Filled.Favorite, 3),
                    LocalCategoryItem(
                        s.downloads.tabLocalPlaylists,
                        4,
                        Icons.AutoMirrored.Filled.QueueMusic,
                        4,
                    ),
                    LocalCategoryItem(s.downloads.tabFolders, 9, Icons.Filled.FolderOpen, 5),
                ),
                onOpenLocal = {},
            )
            Spacer(Modifier.height(16.dp))
            SampleQuickActions()
            Spacer(Modifier.height(24.dp))
        }
    }

    @Composable
    private fun SampleQuickActions(modifier: Modifier = Modifier) {
        val d = cpStrings().downloads
        LocalLibraryQuickActions(
            actions = listOf(
                LocalLibraryQuickAction(d.playAll, Icons.Filled.PlayArrow, {}),
                LocalLibraryQuickAction(d.shuffleAll, Icons.Filled.Shuffle, {}),
                LocalLibraryQuickAction(d.scanDevice, Icons.Filled.Refresh, {}),
                LocalLibraryQuickAction(d.importFolder, Icons.Filled.FolderOpen, {}),
            ),
            modifier = modifier,
        )
    }

    private fun sampleItems(): List<LocalMediaItem> = listOf(
        LocalMediaItem(
            path = "/music/a.flac",
            title = "A",
            artist = "Artist",
            album = "Album",
            coverUri = "file:///preview/a.jpg",
        ),
        LocalMediaItem(
            path = "/music/b.flac",
            title = "B",
            artist = "Artist",
            album = "Album",
            coverUri = "file:///preview/b.jpg",
        ),
        LocalMediaItem(
            path = "/music/c.flac",
            title = "C",
            artist = "Artist",
            album = "Album",
            coverUri = "file:///preview/c.jpg",
        ),
    )

    private fun write(path: String, bytes: ByteArray) {
        File(path).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
    }
}
