package cp.player.app.ui.preview

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import cp.player.app.i18n.AppLanguage
import cp.player.app.i18n.ProvideCpStrings
import cp.player.app.ui.component.LyricContent
import cp.player.app.ui.component.MobileNowPlayingLayout
import cp.player.app.ui.model.MobilePlayerCustomConfig
import cp.player.app.ui.theme.ColorSource
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.music.TrackSummary
import cp.player.core.music.ArtistSummary
import cp.player.core.playback.AudioFormatInfo
import cp.player.core.playback.LyricsState
import cp.player.core.playback.PlaybackUiState
import cp.player.core.playback.RepeatMode
import cp.player.core.playback.SyncedLyricLine
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoilApi::class)
class MobileNowPlayingLayoutTest {
    private val output = File("../.workbuddy-ai/preview/mobile-player-1105").apply { mkdirs() }
    // Deterministic album-art fixture. Production uses the current track's artwork.
    private val artwork = File(output, "album-fixture.png").also { file ->
        val image = BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.paint = GradientPaint(0f, 0f, Color(0xD2E4DD), 600f, 600f, Color(0x598F8A))
        g.fillRect(0, 0, 600, 600)
        g.color = Color(0xF4D5A4)
        g.fillOval(365, 85, 100, 100)
        g.color = Color(0x3B7370)
        g.fillPolygon(intArrayOf(0, 140, 295, 410, 600, 600, 0), intArrayOf(350, 215, 365, 280, 380, 600, 600), 7)
        g.color = Color(0x204E4C)
        g.fillPolygon(intArrayOf(0, 120, 280, 470, 600, 600, 0), intArrayOf(450, 380, 470, 350, 430, 600, 600), 7)
        g.color = Color(0xDBE7DB)
        g.font = java.awt.Font("Serif", java.awt.Font.PLAIN, 30)
        g.drawString("S L O W   H O R I Z O N S", 90, 525)
        g.dispose()
        ImageIO.write(image, "png", file)
    }
    private val previewHandler = AsyncImagePreviewHandler {
        org.jetbrains.skia.Bitmap.makeFromImage(
            org.jetbrains.skia.Image.makeFromEncoded(artwork.readBytes()),
        ).asImage()
    }
    private val lines = listOf(
        SyncedLyricLine(0L, "让夜色慢慢落下来", 20_000L, "Let the night fall softly"),
        SyncedLyricLine(20_000L, "风经过安静的海", 40_000L, "A quiet wind across the sea"),
        SyncedLyricLine(40_000L, "所有未说完的对白", 60_000L, "All the words we left unsaid"),
        SyncedLyricLine(60_000L, "都留给月光去猜", 80_000L, "Leave them for the moon to find"),
        SyncedLyricLine(80_000L, "在每一个醒来的清晨", 100_000L),
        SyncedLyricLine(100_000L, "听见你温柔的回声", 120_000L),
    )
    private val state = PlaybackUiState(
        currentTrack = TrackSummary(
            "preview-mobile", "慢慢，靠近你", "陈粒", "缓慢的风景",
            artwork.toURI().toString(), 245_000L,
        ),
        isPlaying = true,
        positionMs = 28_000L, durationMs = 245_000L,
        lyrics = LyricsState.Success(lines),
        formatInfo = AudioFormatInfo(codecName = "FLAC", bitDepth = 24, sampleRate = 96_000),
        repeatMode = RepeatMode.ALL,
    )
    private var clock = 0L

    private fun scene(
        width: Int = 390,
        height: Int = 844,
        dark: Boolean = false,
        expanded: Boolean = false,
        fontScale: Float = 1f,
        initialState: PlaybackUiState = state,
        initialConfig: MobilePlayerCustomConfig = MobilePlayerCustomConfig(),
        supportsComments: Boolean = true,
        onArtistClick: ((ArtistSummary) -> Unit)? = null,
        onAction: (String) -> Unit = {},
        onSeek: (Long) -> Unit = {},
    ): ImageComposeScene = ImageComposeScene(width, height, density = Density(1f, fontScale)) {
        CpTheme(
            themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT,
            colorSource = ColorSource.COVER,
            coverSeed = androidx.compose.ui.graphics.Color(0xFF3B7370),
        ) {
            ProvideCpStrings(AppLanguage.ZH_HANS) {
                CompositionLocalProvider(
                    LocalInspectionMode provides true,
                    LocalAsyncImagePreviewHandler provides previewHandler,
                ) {
                    var lyrics by remember { mutableStateOf(expanded) }
                    var config by remember { mutableStateOf(initialConfig) }
                    MobileNowPlayingLayout(
                        state = initialState, config = config,
                        lyricsExpanded = lyrics, supportsComments = supportsComments,
                        onBack = { onAction("back") },
                        onToggleLyrics = { lyrics = !lyrics; onAction("lyrics") },
                        onToggleTranslation = {
                            config = config.copy(showTranslation = !config.showTranslation)
                            onAction("translation")
                        },
                        onTogglePlay = { onAction("play") }, onSeek = onSeek,
                        onSkipNext = { onAction("next") },
                        onSkipPrevious = { onAction("previous") },
                        onRepeat = { onAction("repeat") }, onShuffle = { onAction("shuffle") },
                        onLike = { onAction("like") }, onArtistClick = onArtistClick,
                        onQueue = { onAction("queue") }, onComments = { onAction("comments") },
                        onMore = { onAction("more") },
                        contentModifier = Modifier.padding(top = 24.dp, bottom = 24.dp),
                    ) { modifier ->
                        LyricContent(
                            state = initialState, showTranslation = config.showTranslation,
                            onSeek = onSeek, modifier = modifier,
                        )
                    }
                }
            }
        }
    }

    private fun settle(scene: ImageComposeScene, frames: Int = 32) {
        repeat(frames) {
            clock += 20_000_000L
            scene.render(clock)
            Thread.sleep(5)
        }
    }

    private fun save(scene: ImageComposeScene, name: String): BufferedImage {
        settle(scene)
        val bytes = scene.render(clock).encodeToData()!!.bytes
        File(output, "$name.png").writeBytes(bytes)
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        val unique = buildSet {
            for (y in 0 until image.height step 7) {
                for (x in 0 until image.width step 7) add(image.getRGB(x, y))
            }
        }
        assertTrue(unique.size > 40, "$name must render real artwork and text")
        return image
    }

    private fun click(scene: ImageComposeScene, x: Float, y: Float) {
        val at = Offset(x, y)
        scene.sendPointerEvent(
            PointerEventType.Press, at, Offset.Zero, clock / 1_000_000,
            PointerType.Mouse, button = PointerButton.Primary,
        )
        clock += 20_000_000L
        scene.render(clock)
        scene.sendPointerEvent(
            PointerEventType.Release, at, Offset.Zero, clock / 1_000_000,
            PointerType.Mouse, button = PointerButton.Primary,
        )
        settle(scene)
    }

    private fun swipeArtwork(scene: ImageComposeScene, delta: Float) {
        val start = Offset(195f, 250f)
        scene.sendPointerEvent(
            PointerEventType.Press, start, Offset.Zero, clock / 1_000_000,
            PointerType.Touch,
        )
        repeat(6) { index ->
            clock += 20_000_000L
            scene.sendPointerEvent(
                PointerEventType.Move, start + Offset(delta * (index + 1) / 6f, 0f),
                Offset.Zero, clock / 1_000_000, PointerType.Touch,
            )
            scene.render(clock)
        }
        scene.sendPointerEvent(
            PointerEventType.Release, start + Offset(delta, 0f), Offset.Zero,
            clock / 1_000_000, PointerType.Touch,
        )
        settle(scene)
    }

    private fun assertArtworkReadable(image: BufferedImage) {
        // Sun diameter is 1/6 of fixture width; its exact color is absent from the UI.
        var left = image.width
        var right = -1
        for (y in 80 until image.height / 2) for (x in 0 until image.width) {
            if ((image.getRGB(x, y) and 0xFFFFFF) == 0xF4D5A4) {
                left = minOf(left, x)
                right = maxOf(right, x)
            }
        }
        assertTrue(right - left >= 20, "Default artwork must stay at least about 126dp wide")
    }

    @Test
    fun renderPhoneSizesAndReadingFocus() {
        listOf(320 to 568, 360 to 640, 390 to 844, 412 to 892).forEach { (width, height) ->
            listOf(false, true).forEach { dark ->
                val scene = scene(width, height, dark)
                try {
                    assertArtworkReadable(save(scene, "cover-${width}x$height-${if (dark) "dark" else "light"}"))
                }
                finally { scene.close() }
            }
        }
        listOf(false, true).forEach { dark ->
            val scene = scene(dark = dark, expanded = true)
            try { save(scene, "lyrics-390x844-${if (dark) "dark" else "light"}") }
            finally { scene.close() }
        }
        val largeText = scene(360, 640, fontScale = 1.5f)
        try { save(largeText, "large-text-360x640") }
        finally { largeText.close() }
        val largestText = scene(360, 640, fontScale = 2f)
        try { save(largestText, "largest-text-360x640") }
        finally { largestText.close() }
    }

    @Test
    fun emptyErrorLoadingAndNonSeekableStatesRender() {
        val cases = mapOf(
            "no-cover" to state.copy(currentTrack = state.currentTrack!!.copy(coverUrl = null), isPlaying = false),
            "no-lyrics" to state.copy(lyrics = LyricsState.NoLyrics),
            "loading" to state.copy(lyrics = LyricsState.Loading, isBuffering = true),
            "unknown-duration" to state.copy(durationMs = 0L),
            "caching" to state.copy(isLocalizing = true, sleepTimerRemainingMs = 480_000L),
            "playback-error" to state.copy(error = "播放失败，请检查网络后重试", isPlaying = false),
            "no-track-error" to PlaybackUiState(error = "暂时无法加载这首歌曲"),
            "long-title" to state.copy(currentTrack = state.currentTrack!!.copy(
                name = "有些话还没说完，就留给下一个漫长的清晨",
                artist = "一位名字很长的歌手 / Another Artist With A Long Name",
            )),
        )
        cases.forEach { (name, state) ->
            val scene = scene(360, 640, initialState = state)
            try { save(scene, name) } finally { scene.close() }
        }
    }

    @Test
    fun focusSwitchKeepsTransportFixedAndButtonsReachable() {
        val actions = mutableListOf<String>()
        val scene = scene(onAction = actions::add)
        try {
            val before = save(scene, "interaction-before")
            click(scene, 195f, 714f)
            assertEquals(listOf("play"), actions)
            click(scene, 338f, 52f)
            assertEquals("more", actions.last())
            click(scene, 52f, 788f)
            assertEquals("lyrics", actions.last())
            val after = save(scene, "interaction-expanded")
            // Ignore the footer's selected lyrics button; the timeline and transport must be identical.
            var changed = 0
            for (y in 612 until 748) for (x in 28 until 362) {
                if (before.getRGB(x, y) != after.getRGB(x, y)) changed++
            }
            assertTrue(changed < 30, "Opening lyrics moved or repainted transport: $changed pixels")
            click(scene, 195f, 714f)
            assertEquals("play", actions.last())
            click(scene, 338f, 788f)
            assertEquals("queue", actions.last())
            click(scene, 195f, 788f)
            assertEquals("comments", actions.last())
            click(scene, 52f, 788f)
            assertEquals("lyrics", actions.last())
        } finally { scene.close() }
    }

    @Test
    fun commentsCapabilityAndDisabledSeekAreHonored() {
        val actions = mutableListOf<String>()
        val seeks = mutableListOf<Long>()
        val scene = scene(
            supportsComments = false, initialState = state.copy(isLocalizing = true),
            onAction = actions::add, onSeek = seeks::add,
        )
        try {
            settle(scene)
            click(scene, 195f, 788f)
            assertTrue(actions.isEmpty(), "Unsupported comments must not expose an active button")
            click(scene, 240f, 608f)
            assertTrue(seeks.isEmpty(), "Caching must disable seeking")
        } finally { scene.close() }
    }

    @Test
    fun enabledTimelineSeeksAndCoverGesturesRespectPreference() {
        val actions = mutableListOf<String>()
        val seeks = mutableListOf<Long>()
        val scene = scene(onAction = actions::add, onSeek = seeks::add)
        try {
            settle(scene)
            click(scene, 275f, 624f)
            assertEquals(1, seeks.size, "Timeline must commit exactly one seek")
            assertTrue(seeks.single() in 160_000L..210_000L)
            swipeArtwork(scene, -120f)
            assertEquals(listOf("next"), actions)
            swipeArtwork(scene, 120f)
            assertEquals(listOf("next", "previous"), actions)
        } finally { scene.close() }
        actions.clear()
        val disabled = scene(
            initialConfig = MobilePlayerCustomConfig(coverSwipeGesture = false),
            onAction = actions::add,
        )
        try {
            settle(disabled)
            swipeArtwork(disabled, -120f)
            assertTrue(actions.isEmpty(), "Disabled cover gestures must not skip tracks")
        } finally { disabled.close() }
    }

    @Test
    fun everyLongPlayerTextActuallyScrolls() {
        val longState = state.copy(
            currentTrack = state.currentTrack!!.copy(
                name = "有些话还没说完，就留给下一个漫长的清晨",
                album = "在每一个漫长的清晨听见那些温柔而遥远的回声",
                artist = "一位名字很长的歌手 / Another Artist With A Long Name",
            ),
            lyrics = LyricsState.Success(listOf(SyncedLyricLine(
                0L, "所有未说完的对白都留给月光去猜在每一个醒来的清晨",
                245_000L, "All the words we left unsaid, leave them for the moon to find",
            ))),
        )
        val scene = scene(initialState = longState)
        try {
            val before = save(scene, "marquee-before")
            repeat(18) {
                clock += 200_000_000L
                scene.render(clock)
            }
            val after = save(scene, "marquee-after")
            val regions = mapOf(
                "album" to (42 until 65),
                "title" to (460 until 486),
                "artist" to (495 until 518),
                "lyric" to (535 until 558),
                "translation" to (566 until 585),
            )
            regions.forEach { (label, ys) ->
                var changed = 0
                for (y in ys) for (x in 32 until 304) {
                    if (before.getRGB(x, y) != after.getRGB(x, y)) changed++
                }
                assertTrue(changed > 30, "$label must scroll instead of staying ellipsized: $changed pixels")
            }
        } finally { scene.close() }
    }

    @Test
    fun marqueeArtistRowKeepsCorrectDestinations() {
        val artists = listOf(
            ArtistSummary(101L, "第一位歌手", null),
            ArtistSummary(202L, "第二位歌手", null),
        )
        val selected = mutableListOf<ArtistSummary>()
        val single = scene(
            initialState = state.copy(currentTrack = state.currentTrack!!.copy(
                artist = artists.first().name, artists = artists.take(1),
            )),
            onArtistClick = selected::add,
        )
        try {
            settle(single)
            click(single, 100f, 503f)
            assertEquals(listOf(101L), selected.map { it.id })
        } finally { single.close() }
        selected.clear()
        val duet = scene(
            initialState = state.copy(currentTrack = state.currentTrack!!.copy(
                artist = artists.joinToString(" / ") { it.name }, artists = artists,
            )),
            onArtistClick = selected::add,
        )
        try {
            settle(duet)
            click(duet, 100f, 503f)
            save(duet, "artist-chooser")
            assertTrue(selected.isEmpty(), "A duet must ask which artist instead of opening the first")
            click(duet, 100f, 766f)
            assertEquals(listOf(202L), selected.map { it.id })
        } finally { duet.close() }
    }
}
