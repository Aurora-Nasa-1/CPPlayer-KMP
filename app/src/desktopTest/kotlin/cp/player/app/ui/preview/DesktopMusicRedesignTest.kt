package cp.player.app.ui.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.AppLanguage
import cp.player.app.i18n.ProvideCpStrings
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.CpActionButtonGroup
import cp.player.app.ui.component.CpActionGroupItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import cp.player.app.ui.model.HomeUiState
import cp.player.app.ui.model.LibraryUiState
import cp.player.app.ui.screen.DesktopHomeLayout
import cp.player.app.ui.screen.DesktopHomeSkeleton
import cp.player.app.ui.screen.DesktopLibraryActions
import cp.player.app.ui.screen.DesktopLibraryLayout
import cp.player.app.ui.screen.DesktopLibraryPlaylist
import cp.player.app.ui.screen.DesktopLibraryViewToggle
import cp.player.app.ui.screen.HomeActions
import cp.player.app.ui.screen.NowPlayingSnapshot
import cp.player.app.ui.screen.MobileHomeLayout
import cp.player.app.ui.screen.DailySelectionFocus
import cp.player.app.ui.screen.LandingNowPlaying
import cp.player.app.ui.screen.LandingTrackRow
import cp.player.app.ui.screen.MobileHomeSkeleton
import cp.player.app.ui.screen.DesktopRecentSelection
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.TrackSummary
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import coil3.asImage
import java.io.ByteArrayInputStream
import java.io.File
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoilApi::class)
class DesktopMusicRedesignTest {
    private val output = File("../.workbuddy-ai/preview/m3e-music-v2-1030")
    // Deterministic artwork fixtures, never production catalogue data or network requests.
    private val artwork = List(4) { index ->
        output.mkdirs()
        val file = File(output, "fixture-art-$index.png")
        val image = BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val palettes = listOf(
            listOf(0xB7D7D2, 0x2F6060, 0xF0B578),
            listOf(0xEBCBC8, 0xAC534E, 0xF1E5D4),
            listOf(0xCBD4A8, 0x53604A, 0xF0CF68),
            listOf(0xA5C6DF, 0x344F78, 0xE1E9EF),
        )
        val colors = palettes[index].map(::Color)
        g.color = colors[0]
        g.fillRect(0, 0, 400, 400)
        g.color = colors[2]
        g.fillOval(235, 55, 95, 95)
        g.color = colors[1]
        g.fillPolygon(intArrayOf(0, 100, 210, 300, 400, 400, 0), intArrayOf(260, 155, 275, 205, 260, 400, 400), 7)
        g.color = colors[2]
        for (y in 310..380 step 18) g.fillRect(25 + (y % 29), y, 250 - (y % 55), 2)
        g.dispose()
        ImageIO.write(image, "png", file)
        file.toURI().toString()
    }
    private val songs = List(12) {
        TrackSummary(
            id = "song-$it",
            name = listOf("夜航星", "向云端", "在你的身边", "漫长的季节")[it % 4],
            artist = listOf("旅行团", "房东的猫", "落日飞车")[it % 3],
            album = null, coverUrl = artwork[it % artwork.size], durationMs = 240_000,
        )
    }
    private val previewHandler = AsyncImagePreviewHandler { request ->
        val file = File(java.net.URI(request.data.toString()))
        org.jetbrains.skia.Bitmap.makeFromImage(
            org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()),
        ).asImage()
    }
    private val playlists = List(19) {
        PlaylistSummary(
            id = it + 1L,
            name = listOf("通勤路上的好心情", "夜晚，留一点时间给自己", "电影散场以后", "独立音乐收藏")[it % 4],
            coverUrl = artwork[it % artwork.size], trackCount = 25 + it, creatorName = "CPPlayer",
        )
    }
    private fun homeCallbacks(
        onDaily: (TrackSummary) -> Unit = {},
        onRecent: (TrackSummary, Int) -> Unit = { _, _ -> },
        onToggle: () -> Unit = {},
        onPlayer: () -> Unit = {},
        onBrowse: () -> Unit = {},
    ) = HomeActions(
        onRefresh = {}, onPlaylistSourceChange = {}, onNewSongRegionChange = {},
        onBannerClick = {}, onOpenPlaylist = {}, onOpenRanking = {}, onOpenAlbum = {},
        onOpenArtist = {}, onOpenDaily = onBrowse, onPlayDailyTrack = onDaily, onPlayPersonalFm = {},
        onOpenIntelligence = {}, onRecentTrackClick = onRecent, onRecentPlayAll = {},
        onOpenRecentPlays = {}, onNewSongPlay = { _, _ -> }, onTrackOptions = {},
        onOpenPlayer = onPlayer, onTogglePlayPause = onToggle,
    )
    private val homeActions = homeCallbacks()
    private val libraryActions = DesktopLibraryActions(
        onSelectTab = {}, onCreatePlaylist = {}, onSearch = {}, onImport = {},
        onRecentPlays = {}, onInsights = {}, onDownloads = {}, onLocal = {},
        onCloud = {}, onAlbumWall = {}, onRetry = {}, onOpenPlaylist = {},
        onPlaylistOptions = {}, isOwner = { false },
    )

    private fun scene(
        width: Int,
        height: Int = 860,
        dark: Boolean = false,
        pureBlack: Boolean = false,
        english: Boolean = false,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) = ImageComposeScene(width, height, density = Density(1f, fontScale)) {
        CpTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, pureBlack = pureBlack) {
            ProvideCpStrings(if (english) AppLanguage.ENGLISH else AppLanguage.ZH_HANS) {
                CompositionLocalProvider(
                    LocalInspectionMode provides true,
                    LocalAsyncImagePreviewHandler provides previewHandler,
                ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { content() }
                }
            }
        }
    }

    private fun save(scene: ImageComposeScene, name: String, minimumColors: Int = 20) {
        repeat(24) {
            scene.render(it * 16_000_000L)
            Thread.sleep(15)
        }
        val bytes = scene.render(500_000_000L).encodeToData()!!.bytes
        output.mkdirs()
        File(output, "$name.png").writeBytes(bytes)
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        val colors = buildSet {
            for (y in 0 until image.height step 9) {
                for (x in 0 until image.width step 9) add(image.getRGB(x, y))
            }
        }
        assertTrue(colors.size > minimumColors, "$name should contain rendered content, not a blank surface")
    }

    @Test
    fun homeAcrossDesktopContentWidths() {
        // Content widths account for the desktop sidebar, not just the window width.
        listOf(640, 1060, 1600).forEach { width ->
            listOf(false, true).forEach { dark ->
                val scene = scene(width, dark = dark) {
                    DesktopHomeLayout(
                        state = HomeUiState(dailySongs = songs, recommendedPlaylists = playlists, loading = false),
                        recentTracks = songs,
                        nowPlaying = NowPlayingSnapshot(songs[2], false, 96_000, 240_000),
                        actions = homeActions,
                    )
                }
                try { save(scene, "home-$width-${if (dark) "dark" else "light"}") }
                finally { scene.close() }
            }
        }
    }

    @Test
    fun libraryAcrossDesktopContentWidths() {
        listOf(640, 1060, 1600).forEach { width ->
            listOf(false, true).forEach { dark ->
                val scene = scene(width, dark = dark) {
                    DesktopLibraryLayout(
                        state = LibraryUiState(playlists = playlists, loading = false),
                        subtitle = "19 张歌单 · 128 首收藏",
                        supportsCloud = true,
                        isLocalMode = false,
                        actions = libraryActions,
                        gridView = true,
                        onGridViewChange = {},
                    ) { CpText("Local library") }
                }
                try {
                    save(scene, "library-$width-${if (dark) "dark" else "light"}")
                    val image = ImageIO.read(File(output, "library-$width-${if (dark) "dark" else "light"}.png"))
                    // The first cover must already be visible within a 640dp-tall window.
                    assertTrue(
                        image.getRGB(((width - 1400) / 2).coerceAtLeast(0) + 70, 500) != image.getRGB(0, 500),
                        "Playlists were pushed below the first screen at width=$width",
                    )
                } finally { scene.close() }
            }
        }
    }

    @Test
    fun mobileIsRecomposedAtTouchWidths() {
        listOf(320, 390, 600).forEach { width ->
            listOf(false, true).forEach { dark ->
                val home = scene(width, height = 900, dark = dark) {
                    MobileHomeLayout(
                        HomeUiState(dailySongs = songs, recommendedPlaylists = playlists, loading = false),
                        songs, NowPlayingSnapshot(songs[2], false), homeActions,
                    )
                }
                try { save(home, "mobile-home-$width-${if (dark) "dark" else "light"}") }
                finally { home.close() }
                val library = scene(width, height = 900, dark = dark) {
                    DesktopLibraryLayout(
                        LibraryUiState(playlists = playlists, loading = false),
                        "19 张歌单 · 128 首收藏", true, false, libraryActions,
                        true, {}, compact = true,
                    ) { CpText("Local library") }
                }
                try { save(library, "mobile-library-$width-${if (dark) "dark" else "light"}") }
                finally { library.close() }
            }
        }
        val missing = scene(320) {
            MobileHomeLayout(
                HomeUiState(dailySongs = songs.map { it.copy(coverUrl = null) }, loading = false),
                emptyList(), NowPlayingSnapshot(null, false), homeActions,
            )
        }
        try { save(missing, "mobile-home-no-artwork") } finally { missing.close() }
        val skeleton = scene(390) { MobileHomeSkeleton() }
        try { save(skeleton, "mobile-home-loading", minimumColors = 3) } finally { skeleton.close() }
        val largeText = scene(320, fontScale = 1.4f) {
            MobileHomeLayout(
                HomeUiState(dailySongs = songs, loading = false), songs,
                NowPlayingSnapshot(null, false), homeActions,
            )
        }
        try { save(largeText, "mobile-home-large-text") } finally { largeText.close() }
        val englishLibrary = scene(320, english = true) {
            DesktopLibraryLayout(
                LibraryUiState(playlists = playlists.map { it.copy(name = "A long playlist title that wraps comfortably") }, loading = false),
                "19 playlists / 128 favorites", true, false, libraryActions,
                true, {}, compact = true,
            ) { CpText("Local library") }
        }
        try { save(englishLibrary, "mobile-library-english-long-titles") } finally { englishLibrary.close() }
    }

    @Test
    fun emptyLoadingOfflineListAndPureBlackStates() {
        val cases = listOf(
            "empty" to LibraryUiState(loading = false),
            "loading" to LibraryUiState(),
            "error" to LibraryUiState(loading = false, error = "Connection unavailable"),
            "offline" to LibraryUiState(loading = false, selectedTab = 1),
            "list" to LibraryUiState(playlists = playlists, loading = false),
        )
        cases.forEach { (name, state) ->
            val scene = scene(640, height = 640, dark = true, pureBlack = true, english = true) {
                DesktopLibraryLayout(
                    state = state,
                    subtitle = "19 playlists",
                    supportsCloud = false,
                    isLocalMode = name == "offline",
                    actions = libraryActions,
                    gridView = name != "list",
                    onGridViewChange = {},
                ) { CpText("Local library / downloads") }
            }
            try { save(scene, "library-$name-pureblack-en") }
            finally { scene.close() }
        }
        val skeleton = scene(640, height = 640) { DesktopHomeSkeleton() }
        try { save(skeleton, "home-loading-640", minimumColors = 3) }
        finally { skeleton.close() }
        val empty = scene(640, height = 640) {
            DesktopHomeLayout(
                HomeUiState(loading = false), emptyList(), NowPlayingSnapshot(null, false), homeActions,
            )
        }
        try { save(empty, "home-empty-640") }
        finally { empty.close() }
    }

    private fun click(scene: ImageComposeScene, at: Offset, time: Long) {
        scene.sendPointerEvent(
            PointerEventType.Press, at, Offset.Zero, time,
            PointerType.Mouse, button = PointerButton.Primary,
        )
        scene.render((time + 10) * 1_000_000)
        scene.sendPointerEvent(
            PointerEventType.Release, at, Offset.Zero, time + 20,
            PointerType.Mouse, button = PointerButton.Primary,
        )
        repeat(6) { scene.render((time + 40 + it * 16) * 1_000_000) }
    }

    @Test
    fun viewToggleActuallyChangesSelection() {
        val changes = mutableListOf<Boolean>()
        val scene = scene(180, height = 100) {
            var grid by remember { mutableStateOf(true) }
            Box(Modifier.padding(16.dp)) {
                DesktopLibraryViewToggle(grid) {
                    grid = it
                    changes += it
                }
            }
        }
        try {
            scene.render(0)
            click(scene, Offset(88f, 40f), 20)
            click(scene, Offset(40f, 40f), 300)
            assertEquals(listOf(false, true), changes)
        } finally { scene.close() }
    }

    @Test
    fun coverAndMoreAreIndependentActions() {
        var opens = 0
        var options = 0
        val scene = scene(240, height = 320) {
            Box(Modifier.padding(16.dp), contentAlignment = Alignment.TopStart) {
                DesktopLibraryPlaylist(
                    playlists.first(),
                    onOpen = { opens++ },
                    onOptions = { options++ },
                    modifier = Modifier.width(200.dp),
                )
            }
        }
        try {
            scene.render(0)
            click(scene, Offset(100f, 100f), 20)
            assertEquals(1, opens)
            click(scene, Offset(192f, 246f), 300)
            assertEquals(1, options)
            assertEquals(1, opens, "More must not also open the playlist")
        } finally { scene.close() }
    }

    @Test
    fun homeControlsKeepTheirPlaybackMeaning() {
        var toggles = 0
        var playerOpens = 0
        var daily: TrackSummary? = null
        var browse = 0
        val actions = homeCallbacks(
                    onDaily = { daily = it },
                    onToggle = { toggles++ },
                    onPlayer = { playerOpens++ },
                    onBrowse = { browse++ },
        )
        val scene = scene(400, height = 600) {
            androidx.compose.foundation.layout.Column {
                DailySelectionFocus(songs, actions, Modifier.width(400.dp).height(280.dp))
                LandingNowPlaying(
                    NowPlayingSnapshot(songs[2], false, 96_000, 240_000),
                    actions, Modifier.width(400.dp).height(280.dp),
                )
            }
        }
        try {
            repeat(6) { scene.render(it * 16_000_000L) }
            click(scene, Offset(352f, 512f), 150)
            assertEquals(1, toggles)
            assertEquals(0, playerOpens, "The play control must not also open the player")
            click(scene, Offset(100f, 236f), 400)
            assertEquals(songs[0], daily)
            click(scene, Offset(246f, 236f), 650)
            assertEquals(1, browse, "The split button's browse action must not play a track")
            click(scene, Offset(160f, 410f), 900)
            assertEquals(1, playerOpens)
        } finally { scene.close() }
    }

    @Test
    fun touchTrackMoreDoesNotAlsoPlay() {
        var plays = 0
        var options = 0
        val scene = scene(320, height = 100) {
            LandingTrackRow(
                songs[0], { plays++ }, { options++ }, NowPlayingSnapshot(null, false),
                Modifier.width(320.dp),
            )
        }
        try {
            scene.render(0)
            click(scene, Offset(292f, 32f), 100)
            assertEquals(1, options)
            assertEquals(0, plays)
            click(scene, Offset(100f, 32f), 400)
            assertEquals(1, plays)
        } finally { scene.close() }
    }

    @Test
    fun recentGridRetainsTheOriginalQueueIndex() {
        var played: Pair<TrackSummary, Int>? = null
        val scene = scene(640, height = 280) {
            DesktopRecentSelection(
                songs, NowPlayingSnapshot(null, false),
                homeCallbacks(onRecent = { track, index -> played = track to index }),
                640.dp,
            )
        }
        try {
            scene.render(0)
            click(scene, Offset(440f, 170f), 100)
            assertEquals(songs[3] to 3, played)
        } finally { scene.close() }
    }

    @Test
    fun officialActionGroupCanOverflowInsideAFilledContainer() {
        val scene = scene(220, height = 100) {
            CpActionButtonGroup(
                List(4) { CpActionGroupItem("Long action $it", Icons.Filled.PlayArrow, {}) },
                Modifier.fillMaxWidth(),
            )
        }
        try { save(scene, "expressive-action-group-overflow", minimumColors = 3) } finally { scene.close() }
    }
}
