package cp.player.app.ui.screen

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import cp.player.app.i18n.AppLanguage
import cp.player.app.i18n.ProvideCpStrings
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.music.ArtistSummary
import cp.player.core.music.TrackSummary
import cp.player.core.playback.LyricsState
import cp.player.core.playback.PlaybackUiState
import cp.player.core.playback.QueueItem
import cp.player.core.playback.RepeatMode
import cp.player.core.playback.SyncedLyricLine
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TabletAndDesktopPlayerVisualTest {

    private fun sampleState(): PlaybackUiState {
        val track = TrackSummary(
            id = "netease:1001",
            name = "夜に駆ける (Into The Night)",
            artist = "YOASOBI",
            album = "THE BOOK",
            coverUrl = null,
            durationMs = 261_000L,
            artists = listOf(ArtistSummary(id = 1L, name = "YOASOBI", avatarUrl = null)),
        )
        val lines = listOf(
            SyncedLyricLine(time = 0L, text = "沈むように溶けてゆくように", translation = "仿佛下沉般 仿佛融化般"),
            SyncedLyricLine(time = 4_000L, text = "二人だけの空が広がる夜に", translation = "在只属于两人的天空蔓延的夜晚"),
            SyncedLyricLine(time = 8_000L, text = "「さよなら」だけだった", translation = "仅仅是一句「再见」"),
            SyncedLyricLine(time = 12_000L, text = "その一言で全てが分かった", translation = "仅此一句话便明白了一切"),
        )
        return PlaybackUiState(
            currentTrack = track,
            isPlaying = true,
            positionMs = 4_800L,
            durationMs = 261_000L,
            isFavorite = true,
            repeatMode = RepeatMode.ALL,
            shuffleEnabled = true,
            volume = 0.72f,
            sourceQualityLabel = "Hi-Res 96kHz/24bit",
            lyrics = LyricsState.Success(lines),
            queue = listOf(
                QueueItem(
                    mediaId = track.id,
                    title = track.name,
                    artist = track.artist,
                    album = track.album,
                    coverUrl = track.coverUrl,
                    durationMs = track.durationMs,
                ),
                QueueItem(
                    mediaId = "netease:1002",
                    title = "アイドル",
                    artist = "YOASOBI",
                    album = "THE BOOK 3",
                    coverUrl = null,
                    durationMs = 213_000L,
                ),
            ),
            currentIndex = 0,
        )
    }

    @Test
    fun `tablet portrait player renders cleanly`() {
        val state = sampleState()
        val scene = ImageComposeScene(width = 720, height = 1040, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.DARK) {
                ProvideCpStrings(AppLanguage.ZH_HANS) {
                    TabletPlayerLayout(
                        state = state,
                        onBack = {},
                        onTogglePlay = {},
                        onSeek = {},
                        onSkipNext = {},
                        onSkipPrev = {},
                        onRepeat = {},
                        onShuffle = {},
                        onLike = {},
                        onPlayAt = {},
                        onMove = { _, _ -> },
                    )
                }
            }
        }
        val png = try {
            scene.render().encodeToData()?.bytes ?: error("PNG encoding failed")
        } finally {
            scene.close()
        }
        File("build/tablet-player-portrait.png").apply {
            parentFile?.mkdirs()
            writeBytes(png)
        }
        assertTrue(png.size > 10_000, "平板竖屏播放页渲染体积过小: ${png.size} bytes")
    }

    @Test
    fun `tablet landscape player renders cleanly`() {
        val state = sampleState()
        val scene = ImageComposeScene(width = 1120, height = 760, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.DARK) {
                ProvideCpStrings(AppLanguage.ZH_HANS) {
                    TabletPlayerLayout(
                        state = state,
                        onBack = {},
                        onTogglePlay = {},
                        onSeek = {},
                        onSkipNext = {},
                        onSkipPrev = {},
                        onRepeat = {},
                        onShuffle = {},
                        onLike = {},
                        onPlayAt = {},
                        onMove = { _, _ -> },
                    )
                }
            }
        }
        val png = try {
            scene.render().encodeToData()?.bytes ?: error("PNG encoding failed")
        } finally {
            scene.close()
        }
        File("build/tablet-player-landscape.png").apply {
            parentFile?.mkdirs()
            writeBytes(png)
        }
        assertTrue(png.size > 10_000, "平板横屏播放页渲染体积过小: ${png.size} bytes")
    }

    @Test
    fun `desktop player renders cleanly in dark and light modes`() {
        val state = sampleState()
        listOf(ThemeMode.DARK to "desktop-player-dark.png", ThemeMode.LIGHT to "desktop-player-light.png").forEach { (mode, fileName) ->
            val scene = ImageComposeScene(width = 1440, height = 900, density = Density(1f)) {
                CpTheme(themeMode = mode) {
                    ProvideCpStrings(AppLanguage.ZH_HANS) {
                        DesktopPlayerLayout(
                            state = state,
                            volume = state.volume,
                            onVolumeChange = {},
                            onBack = {},
                            onTogglePlay = {},
                            onSeek = {},
                            onSkipNext = {},
                            onSkipPrev = {},
                            onRepeat = {},
                            onShuffle = {},
                            onLike = {},
                            onPlayAt = {},
                            onMove = { _, _ -> },
                        )
                    }
                }
            }
            val png = try {
                scene.render().encodeToData()?.bytes ?: error("PNG encoding failed")
            } finally {
                scene.close()
            }
            File("build/$fileName").apply {
                parentFile?.mkdirs()
                writeBytes(png)
            }
            assertTrue(png.size > 10_000, "桌面播放页 ($fileName) 渲染体积过小: ${png.size} bytes")
        }
    }
}
