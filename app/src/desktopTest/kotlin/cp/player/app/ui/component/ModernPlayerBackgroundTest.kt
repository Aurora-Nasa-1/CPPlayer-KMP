package cp.player.app.ui.component

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import cp.player.app.ui.model.MobilePlayerCustomConfig
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.playback.PlaybackUiState
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

class ModernPlayerBackgroundTest {

    @Test
    fun `MobileNowPlayingLayout leaves the caller background visible`() {
        val scene = ImageComposeScene(width = 360, height = 720, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.DARK) {
                Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                    MobileNowPlayingLayout(
                        state = PlaybackUiState(),
                        config = MobilePlayerCustomConfig(),
                        lyricsExpanded = false,
                        supportsComments = false,
                        onBack = {},
                        onToggleLyrics = {},
                        onToggleTranslation = {},
                        onTogglePlay = {},
                        onSeek = {},
                        onSkipNext = {},
                        onSkipPrevious = {},
                        onRepeat = {},
                        onShuffle = {},
                        onLike = {},
                        onArtistClick = null,
                        onQueue = {},
                        onComments = {},
                        onMore = {},
                        modifier = Modifier.fillMaxSize(),
                        lyricsContent = {},
                    )
                }
            }
        }
        try {
            scene.render()
            val encoded = scene.render().encodeToData() ?: error("ImageComposeScene PNG encode failed")
            val image = ImageIO.read(ByteArrayInputStream(encoded.bytes))
            assertEquals(0xFFFF00FF.toInt(), image.getRGB(1, 1), "layout must not paint an opaque full-screen background")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `modern player fallback keeps the original three gradient colors`() {
        val colorScheme = darkColorScheme()
        assertEquals(
            listOf(colorScheme.surfaceContainerHigh, colorScheme.surfaceContainerLow, colorScheme.surface),
            modernPlayerFallbackColors(colorScheme),
        )
    }
}
