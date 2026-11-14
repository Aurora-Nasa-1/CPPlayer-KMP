package cp.player.app.ui.component

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import cp.player.app.ui.model.MobilePlayerCustomConfig
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.playback.PlaybackUiState
import cp.player.app.readPng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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
                        onLike = {},
                        onArtistClick = null,
                        onQueue = {},
                        onComments = {},
                        onMore = {},
                        onCast = {},
                        modifier = Modifier.fillMaxSize(),
                        lyricsContent = {},
                    )
                }
            }
        }
        try {
            scene.render()
            val encoded = scene.render().encodeToData() ?: error("ImageComposeScene PNG encode failed")
            val image = readPng(encoded.bytes)
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

    @Test
    fun `paused fluid animation keeps the rendered shader instead of showing fallback`() {
        val fallback = Color.Magenta
        val scene = ImageComposeScene(width = 240, height = 240, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.DARK) {
                Box(
                    Modifier.fillMaxSize().cpFluidBackground(
                        enabled = true,
                        fallback = SolidColor(fallback),
                        pauseShaderAnimation = true,
                    ),
                )
            }
        }
        try {
            val encoded = scene.render().encodeToData() ?: error("ImageComposeScene PNG encode failed")
            val image = readPng(encoded.bytes)
            assertNotEquals(
                fallback.toArgb(),
                image.getRGB(120, 120),
                "pausing shader time must keep the last fluid frame visible, not replace it with fallback",
            )
        } finally {
            scene.close()
        }
    }
}
