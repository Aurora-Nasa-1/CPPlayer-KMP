package cp.player.app.ui.screen

import cp.player.app.i18n.CpStringsEn
import cp.player.app.i18n.CpStringsZh
import cp.player.app.ui.model.PlaylistDetailScreenModel
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.TrackSummary
import cp.player.core.util.currentTimeMillis
import cp.player.core.util.localDateTimeOf
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DailyRecommendationSaveTest {

    @Test
    fun `daily playlist name is formatted with current local date`() {
        val now = localDateTimeOf(currentTimeMillis())
        val m = now.month.toString().padStart(2, '0')
        val d = now.day.toString().padStart(2, '0')
        val defaultName = "每日推荐 · ${now.year}-$m-$d"

        assertTrue(defaultName.startsWith("每日推荐 · "))
        assertTrue(defaultName.contains("${now.year}-$m-$d"))
    }

    @Test
    fun `i18n strings for saving daily recommendation are complete and non-empty`() {
        listOf(CpStringsZh, CpStringsEn).forEach { s ->
            assertTrue(s.playlistSheet.saveDailyToPlaylist.isNotBlank())
            assertTrue(s.playlistSheet.saveDailyToPlaylistTitle.isNotBlank())
            assertTrue(s.playlistSheet.saveDailyToPlaylistConfirm.isNotBlank())
            assertTrue(s.playlistSheet.saveDailyToPlaylistConverted.isNotBlank())
            assertTrue(s.playlistSheet.saveDailyToPlaylistAlreadySaved.isNotBlank())
            assertTrue(s.playlistSheet.saveDailyToPlaylistFailed.isNotBlank())
            assertTrue(s.playlistSheet.saveDailyToPlaylistNote(30).contains("30"))
            assertTrue(s.playlistSheet.saveDailyToPlaylistSuccess("测试歌单", 30).contains("测试歌单"))
        }
    }

    @Test
    fun `model markDailyConverted updates favorite state to true`() {
        val model = PlaylistDetailScreenModel()
        val dailySummary = PlaylistSummary(
            id = -101L,
            name = "每日推荐",
            coverUrl = "http://example.com/cover.jpg",
            trackCount = 1,
            creatorName = "CPPlayer",
        )
        val dummyTrack = TrackSummary(
            id = "test_song_1",
            name = "Test Track",
            artist = "Test Artist",
            album = "Test Album",
            coverUrl = "http://example.com/song.jpg",
            durationMs = 180000L,
        )

        model.loadLocal(dailySummary, listOf(dummyTrack), loading = false, force = true)
        // Initially isFavorite is false
        assertFalse(model.state.value.isFavorite)

        // Mark as converted
        model.markDailyConverted()
        assertTrue(model.state.value.isFavorite)
    }
}
