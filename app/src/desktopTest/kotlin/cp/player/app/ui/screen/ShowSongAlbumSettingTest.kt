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
import cp.player.core.music.TrackSummary
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
        val sampleTrack = TrackSummary(
            id = "test-1",
            name = "七里香",
            artist = "周杰伦",
            album = "七里香",
            coverUrl = null,
            durationMs = 300_000,
        )

        // 验证显式传 showAlbum = true / false 以及跟随全局设置均可正常完成组合与渲染
        listOf(true, false, null).forEach { showAlbumFlag ->
            val scene = ImageComposeScene(
                width = 400,
                height = 80,
                density = Density(1f),
            ) {
                CpTheme {
                    Box(Modifier.width(400.dp)) {
                        SongItem(
                            track = sampleTrack,
                            onClick = {},
                            showAlbum = showAlbumFlag,
                        )
                    }
                }
            }
            try {
                val image = scene.render()
                assertEquals(400, image.width)
                assertEquals(80, image.height)
            } finally {
                scene.close()
            }
        }
    }
}
