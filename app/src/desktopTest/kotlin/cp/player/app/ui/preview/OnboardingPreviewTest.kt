package cp.player.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import cp.player.app.ui.screen.OnboardProviderRow
import cp.player.app.ui.screen.OnboardingContent
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import java.io.File
import kotlin.test.Test

/**
 * 首次使用引导页（含内嵌的强制音源导入页）的离屏渲染版面核对。
 *
 * 2026-10-02 起引导是唯一的「欢迎」页，音源导入内嵌在第二步且强制
 * （无音源时「跳过 / 下一步」禁用）—— 版式与禁用态提示必须出图核对。
 */
class OnboardingPreviewTest {

    private val fakeProviders = listOf(
        OnboardProviderRow("网易云", "HTTP · v1.2.0", active = true),
        OnboardProviderRow("备用音源", "HTTP · v0.9.1", active = false),
    )

    @Composable
    private fun Preview(dark: Boolean, hasProvider: Boolean, initialPage: Int) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            OnboardingContent(
                replay = false,
                hasProvider = hasProvider,
                isImporting = false,
                importMessage = null,
                loadedProviders = if (hasProvider) fakeProviders else emptyList(),
                initialPage = initialPage,
            )
        }
    }

    private fun render(
        name: String,
        dark: Boolean,
        width: Int,
        height: Int,
        initialPage: Int,
        hasProvider: Boolean,
    ) {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CpTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                Preview(dark = dark, hasProvider = hasProvider, initialPage = initialPage)
            }
        }
        try {
            val data = scene.render().encodeToData() ?: error("encode failed")
            val out = File("build-verify-onboard/preview").apply { mkdirs() }
            File(out, "$name.png").writeBytes(data.bytes)
        } finally {
            scene.close()
        }
    }

    @Test
    fun renderPreviews() {
        // 欢迎页（第一步）
        render("onboard-welcome-light", dark = false, width = 640, height = 620, initialPage = 0, hasProvider = true)
        // 音源页（强制步骤）：无音源 → 提示 + 按钮不可用；桌面宽度
        render("onboard-provider-empty-light", dark = false, width = 640, height = 620, initialPage = 1, hasProvider = false)
        render("onboard-provider-empty-dark", dark = true, width = 640, height = 620, initialPage = 1, hasProvider = false)
        // 音源页：已有音源 → 已加载列表 + 可继续
        render("onboard-provider-loaded-light", dark = false, width = 640, height = 620, initialPage = 1, hasProvider = true)
        // 手机宽度：确认导入按钮 / 无音源提示在小屏上不溢出
        render("onboard-provider-empty-mobile-light", dark = false, width = 400, height = 780, initialPage = 1, hasProvider = false)
        // 账号页（第三步，去登录入口）
        render("onboard-account-mobile-light", dark = false, width = 400, height = 780, initialPage = 2, hasProvider = true)
    }
}
