package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.AppLanguage
import cp.player.app.i18n.ProvideCpStrings
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.playback.PlaybackUiState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class MiniPlayerDesktopBarVisualTest {

    private fun render(width: Int, mode: ThemeMode, output: String) {
        val scene = ImageComposeScene(width = width, height = 112, density = Density(1f)) {
            CpTheme(themeMode = mode) {
                ProvideCpStrings(AppLanguage.ZH_HANS) {
                    Box(
                        Modifier.fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        MiniPlayerDesktopBar(
                            state = PlaybackUiState(
                                positionMs = 91_000L,
                                durationMs = 218_000L,
                                isPlaying = true,
                                shuffleEnabled = true,
                            ),
                            isFavorite = true,
                            qualityLevel = "lossless",
                            cover = {
                                Box(
                                    Modifier.size(48.dp)
                                        .background(MaterialTheme.colorScheme.tertiaryContainer),
                                )
                            },
                            info = {
                                androidx.compose.foundation.layout.Column {
                                    Text("夜に駆ける - YOASOBI", style = MaterialTheme.typography.titleSmall)
                                    Text("沈むように溶けてゆくように", style = MaterialTheme.typography.bodySmall)
                                }
                            },
                            onTogglePlay = {},
                            onSkipPrev = {},
                            onSkipNext = {},
                            onShuffle = {},
                            onRepeat = {},
                            onLike = {},
                            onQueue = {},
                            onMore = {},
                            onVolumeChange = {},
                            onQualityChange = {},
                        )
                    }
                }
            }
        }
        try {
            val bytes = scene.render().encodeToData()?.bytes ?: error("PNG encoding failed")
            val file = File("build/mini-player/$output.png")
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            assertTrue(bytes.size > 2_000, "桌面 MiniPlayer 出图不应为空")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `桌面迷你播放器窄宽与宽屏布局离屏出图`() {
        render(900, ThemeMode.LIGHT, "desktop-light")
        render(1440, ThemeMode.DARK, "desktop-dark")
    }

    @Test
    fun `桌面底栏进度拖到四分之一时定位误差小于百分之一`() {
        val seeks = mutableStateListOf<Long>()
        val scene = ImageComposeScene(width = 900, height = 64, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.DARK) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    CpMinimalSeekBar(
                        positionMs = 0L,
                        durationMs = 240_000L,
                        onSeek = seeks::add,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp)
                            .padding(horizontal = 12.dp),
                    )
                }
            }
        }
        try {
            scene.sendPointerEvent(
                PointerEventType.Press,
                Offset(30f, 10f),
                Offset.Zero,
                0L,
                PointerType.Mouse,
            )
            scene.sendPointerEvent(
                PointerEventType.Move,
                Offset(231f, 10f),
                Offset(201f, 0f),
                16_000_000L,
                PointerType.Mouse,
            )
            scene.sendPointerEvent(
                PointerEventType.Release,
                Offset(231f, 10f),
                Offset.Zero,
                32_000_000L,
                PointerType.Mouse,
            )
            val actual = seeks.lastOrNull() ?: error("拖拽没有发出 seek")
            val fraction = actual / 240_000f
            assertTrue(
                kotlin.math.abs(fraction - 0.25f) < 0.01f,
                "拖到轨道约 25% 应定位到约 60s，实际 ${actual}ms (${fraction * 100}%)",
            )
        } finally {
            scene.close()
        }
    }
}
