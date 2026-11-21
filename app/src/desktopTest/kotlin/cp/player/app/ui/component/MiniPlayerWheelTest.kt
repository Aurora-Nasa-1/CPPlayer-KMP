package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals

class MiniPlayerWheelTest {

    @Test
    fun `seek滚轮按五秒步进并限制在曲目范围`() {
        assertEquals(65_000L, miniPlayerWheelSeekTarget(60_000L, 240_000L, -1f))
        assertEquals(55_000L, miniPlayerWheelSeekTarget(60_000L, 240_000L, 1f))
        assertEquals(0L, miniPlayerWheelSeekTarget(2_000L, 240_000L, 1f))
        assertEquals(240_000L, miniPlayerWheelSeekTarget(238_000L, 240_000L, -1f))
        assertEquals(60_000L, miniPlayerWheelSeekTarget(60_000L, 0L, -1f))
    }

    @Test
    fun `音量滚轮按百分之四步进并限制在零到一`() {
        assertEquals(0.54f, miniPlayerWheelVolume(0.5f, -1f))
        assertEquals(0.46f, miniPlayerWheelVolume(0.5f, 1f))
        assertEquals(1f, miniPlayerWheelVolume(0.99f, -1f))
        assertEquals(0f, miniPlayerWheelVolume(0.01f, 1f))
    }

    @Test
    fun `音量区域消费滚轮后不触发播放器seek`() {
        var volumeScrolls = 0
        var playerScrolls = 0
        val scene = ImageComposeScene(width = 100, height = 100, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.DARK) {
                Box(
                    Modifier.fillMaxSize()
                        .background(Color.Black)
                        .miniPlayerMouseWheel { playerScrolls++ },
                ) {
                    Surface(
                        onClick = {},
                        modifier = Modifier.size(40.dp)
                            .miniPlayerMouseWheel(handleConsumed = true) { volumeScrolls++ },
                    ) {}
                }
            }
        }
        try {
            scene.sendPointerEvent(
                PointerEventType.Scroll,
                Offset(20f, 20f),
                Offset(0f, -1f),
                0L,
                PointerType.Mouse,
            )
            assertEquals(1, volumeScrolls)
            assertEquals(0, playerScrolls)

            scene.sendPointerEvent(
                PointerEventType.Scroll,
                Offset(80f, 20f),
                Offset(0f, -1f),
                16_000_000L,
                PointerType.Mouse,
            )
            assertEquals(1, playerScrolls)
        } finally {
            scene.close()
        }
    }
}
