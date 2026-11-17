package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import cp.player.app.readPng
import cp.player.app.ui.model.MobilePlayerCustomConfig
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.music.TrackSummary
import cp.player.core.playback.PlaybackUiState
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 标准播放器「播放面 ↔ 完整歌词」的切帧回归。
 *
 * 纯淡入淡出也能让首尾两帧不同，所以只比较首尾抓不到「有没有过渡」。
 * 这里在展开后连续采样中间帧：实时切换只会有 1~2 个过渡帧，
 * Shared Axis 转场则会在位移 / 缩放 / 淡入过程中持续产生新画面。
 */
class MobileNowPlayingFocusTransitionTest {

    @Test
    fun `lyrics focus transition renders continuous intermediate frames`() {
        var expanded by mutableStateOf(false)
        val scene = ImageComposeScene(width = 390, height = 844, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.DARK) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF252A31), Color(0xFF14171B)),
                        ),
                    ),
                ) {
                    MobileNowPlayingLayout(
                        state = PlaybackUiState(
                            currentTrack = TrackSummary(
                                id = "local://audio/echolalia",
                                name = "Echolalia",
                                artist = "Yves Tumor",
                                album = "Praise",
                                coverUrl = null,
                                durationMs = 180_000L,
                            ),
                            isPlaying = false,
                            positionMs = 60_000L,
                            durationMs = 180_000L,
                            isFavorite = true,
                        ),
                        config = MobilePlayerCustomConfig(),
                        lyricsExpanded = expanded,
                        supportsComments = true,
                        onBack = {},
                        onToggleLyrics = {},
                        onToggleTranslation = {},
                        onTogglePlay = {},
                        onSeek = {},
                        onSkipNext = {},
                        onSkipPrevious = {},
                        onLike = {},
                        onArtistClick = null,
                        onQueue = {},
                        onComments = {},
                        onMore = {},
                        onCast = {},
                        modifier = Modifier.fillMaxSize(),
                        lyricsContent = { modifier ->
                            Box(modifier.background(Color(0xFF1B3A2F)))
                        },
                    )
                }
            }
        }

        try {
            var timeNanos = 0L
            val frameStepNanos = 16_666_667L
            repeat(4) {
                scene.render(timeNanos)
                timeNanos += frameStepNanos
            }
            val beforeBytes = renderBytes(scene, timeNanos)
            timeNanos += frameStepNanos

            expanded = true
            val frameHashes = linkedSetOf<String>()
            repeat(14) { index ->
                val bytes = renderBytes(scene, timeNanos)
                if (index == 5) {
                    writeFrame("build/mobile-now-playing-focus-mid.png", bytes)
                }
                frameHashes += sha1(bytes)
                timeNanos += frameStepNanos
            }

            assertNotEquals(
                sha1(beforeBytes),
                frameHashes.first(),
                "展开歌词后画面必须离开播放面",
            )
            assertTrue(
                frameHashes.size >= 4,
                "展开过程应持续产生中间帧，实测只有 ${frameHashes.size} 个不同画面；" +
                    "退化成瞬切时这里会接近 1~2。",
            )

            timeNanos += 1_000_000_000L
            val finalBytes = renderBytes(scene, timeNanos)
            writeFrame("build/mobile-now-playing-focus-final.png", finalBytes)
            assertEquals(
                0xFF1B3A2F.toInt(),
                readPng(finalBytes).getRGB(195, 300),
                "转场结束后歌词区应完整落位",
            )

            expanded = false
            val collapseHashes = linkedSetOf<String>()
            repeat(14) { index ->
                val bytes = renderBytes(scene, timeNanos)
                if (index == 5) {
                    writeFrame("build/mobile-now-playing-focus-collapse-mid.png", bytes)
                }
                collapseHashes += sha1(bytes)
                timeNanos += frameStepNanos
            }
            assertTrue(
                collapseHashes.size >= 4,
                "收起歌词同样应持续产生中间帧，实测只有 ${collapseHashes.size} 个不同画面。",
            )

            timeNanos += 1_000_000_000L
            val restoredBytes = renderBytes(scene, timeNanos)
            writeFrame("build/mobile-now-playing-focus-restored.png", restoredBytes)
            assertNotEquals(
                0xFF1B3A2F.toInt(),
                readPng(restoredBytes).getRGB(195, 300),
                "收起后应回到播放面，不能停在歌词背景",
            )
        } finally {
            scene.close()
        }
    }

    private fun renderBytes(scene: ImageComposeScene, timeNanos: Long): ByteArray =
        scene.render(timeNanos).encodeToData()?.bytes ?: error("ImageComposeScene PNG encode failed")

    private fun writeFrame(path: String, bytes: ByteArray) {
        File(path).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
