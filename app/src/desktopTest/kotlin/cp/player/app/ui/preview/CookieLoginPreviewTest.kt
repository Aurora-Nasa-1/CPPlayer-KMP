package cp.player.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.ui.screen.CookieLoginForm
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import java.io.File
import kotlin.test.Test

/** 临时：把新增的 Cookie 登录表单离屏渲成 PNG 核对版面（渲完必删）。 */
class CookieLoginPreviewTest {

    private val sample =
        "MUSIC_U=00ABCDEF1234567890ABCDEF1234567890ABCDEF1234567890ABCDEF12; " +
            "__csrf=9f8e7d6c5b4a39281706f5e4d3c2b1a0"

    @Composable
    private fun Preview(raw: String) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.TopStart,
        ) {
            Box(Modifier.width(420.dp).padding(16.dp)) {
                CookieLoginForm(
                    raw = raw,
                    onRawChange = {},
                    normalized = cp.player.app.auth.CookieLogin.normalize(raw),
                )
            }
        }
    }

    private fun render(name: String, dark: Boolean, raw: String) {
        val scene = ImageComposeScene(width = 420, height = 300, density = Density(1f)) {
            CpTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                Preview(raw)
            }
        }
        try {
            val data = scene.render().encodeToData() ?: error("encode failed")
            val out = File("build-v6/preview").apply { mkdirs() }
            File(out, "$name.png").writeBytes(data.bytes)
        } finally {
            scene.close()
        }
    }

    @Test
    fun renderPreviews() {
        render("cookie-filled-light", dark = false, raw = sample)
        render("cookie-filled-dark", dark = true, raw = sample)
        // 粘贴错误的东西时的高亮态
        render("cookie-invalid-light", dark = false, raw = "https://music.163.com/#/login")
        // 空态（placeholder + 提示文案）
        render("cookie-empty-light", dark = false, raw = "")
    }
}
