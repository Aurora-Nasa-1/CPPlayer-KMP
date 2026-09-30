package cp.player.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.ui.screen.SidebarRowsPreview
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import java.io.File
import kotlin.test.Test

/** 临时：把侧栏条目离屏渲成 PNG 核对版面（渲完必删）。 */
class SidebarPreviewTest {

    @Composable
    private fun Preview() {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow),
            contentAlignment = Alignment.TopStart,
        ) {
            Box(Modifier.width(248.dp)) { SidebarRowsPreview() }
        }
    }

    private fun render(name: String, dark: Boolean) {
        val scene = ImageComposeScene(width = 248, height = 420, density = Density(1f)) {
            CpTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                Preview()
            }
        }
        try {
            val data = scene.render().encodeToData() ?: error("encode failed")
            val out = File("build-v5/preview").apply { mkdirs() }
            File(out, "$name.png").writeBytes(data.bytes)
        } finally {
            scene.close()
        }
    }

    @Test
    fun renderPreviews() {
        render("sidebar-light", dark = false)
        render("sidebar-dark", dark = true)
    }
}
