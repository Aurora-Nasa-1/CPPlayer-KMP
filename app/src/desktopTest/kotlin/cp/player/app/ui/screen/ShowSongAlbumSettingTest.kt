package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.AppModel
import cp.player.app.i18n.CpStringsEn
import cp.player.app.i18n.CpStringsZh
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.music.TrackSummary
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShowSongAlbumSettingTest {

    @Test
    fun `showSongAlbum preference toggles and updates state flow`() {
        val original = AppModel.showSongAlbum()
        try {
            AppModel.setShowSongAlbum(false)
            assertFalse(AppModel.showSongAlbum())
            assertFalse(AppModel.showSongAlbumFlow.value)

            AppModel.setShowSongAlbum(true)
            assertTrue(AppModel.showSongAlbum())
            assertTrue(AppModel.showSongAlbumFlow.value)

            AppModel.setShowSongAlbum(false)
            assertFalse(AppModel.showSongAlbum())
            assertFalse(AppModel.showSongAlbumFlow.value)
        } finally {
            AppModel.setShowSongAlbum(original)
        }
    }

    @Test
    fun `i18n strings for showSongAlbum are non-blank`() {
        assertTrue(CpStringsZh.appearance.showSongAlbum.isNotBlank())
        assertTrue(CpStringsZh.appearance.showSongAlbumNote.isNotBlank())
        assertTrue(CpStringsEn.appearance.showSongAlbum.isNotBlank())
        assertTrue(CpStringsEn.appearance.showSongAlbumNote.isNotBlank())
    }

    @Test
    fun `SongItem renders cleanly with and without album display`() {
        assertFalse(AppModel.initialized.value, "Render regression must exercise the uninitialized path")
        val sampleTrack = TrackSummary(
            id = "test-1",
            name = "七里香",
            artist = "周杰伦",
            album = "七里香",
            coverUrl = null,
            durationMs = 300_000,
        )

        val output = File("build/preview-songitem").apply { mkdirs() }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            for (highlighted in listOf(false, true)) {
                for (showAlbumFlag in listOf(true, false, null)) {
                    val scene = ImageComposeScene(
                        width = 400,
                        height = 80,
                        density = Density(1f),
                    ) {
                        CpTheme(themeMode = theme) {
                            Box(Modifier.width(400.dp)) {
                                SongItem(
                                    track = sampleTrack,
                                    onClick = {},
                                    showAlbum = showAlbumFlag,
                                    isCurrentlyPlaying = highlighted,
                                )
                            }
                        }
                    }
                    try {
                        val image = scene.render()
                        assertEquals(400, image.width)
                        assertEquals(80, image.height)
                        val bytes = image.encodeToData()?.bytes ?: error("PNG encoding failed")
                        val bitmap = ImageIO.read(ByteArrayInputStream(bytes))
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getRGB(0, 0, bitmap.width, bitmap.height, pixels, 0, bitmap.width)
                        assertTrue(pixels.toSet().size > 20, "Song row must contain visible content")
                        File(output, "no-backend-$theme-highlighted-$highlighted-album-$showAlbumFlag.png")
                            .writeBytes(bytes)
                    } finally {
                        scene.close()
                    }
                }
            }
        }
        assertFalse(AppModel.initialized.value, "Rendering must not initialize the backend")
    }
}
