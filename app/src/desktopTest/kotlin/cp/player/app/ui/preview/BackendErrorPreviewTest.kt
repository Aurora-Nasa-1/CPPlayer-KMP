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
import cp.player.app.ui.screen.BackendErrorContent
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.provider.BackendProvider
import cp.player.core.provider.ProviderType
import cp.player.core.util.PlatformContext
import java.io.File
import kotlin.test.Test

/** 后端初始化失败页（含删除音源 / 导入新音源）的离屏渲染版面核对。 */
class BackendErrorPreviewTest {

    private val fakeProviders = listOf(
        StubProvider("netease-broken", "损坏的音源", "2.1.0"),
        StubProvider("backup-http", "备用音源", "1.4.2"),
    )

    private class StubProvider(
        override val id: String,
        override val name: String,
        override val version: String,
    ) : BackendProvider {
        override val type = ProviderType.HTTP
        override val apiMap: Map<String, String>? = null
        override val updateUrl: String? = null
        override val targetAppPackage: String? = null
        override fun startServer(context: PlatformContext, port: Int) = Unit
        override fun stopServer() = Unit
        override fun callApi(method: String, params: Map<String, String>) = "{}"
        override fun analyzeAudio(path: String) = "{}"
    }

    @Composable
    private fun Preview(providers: List<BackendProvider>, actionMessage: String?) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(560.dp)) {
                BackendErrorContent(
                    message = "Provider 服务启动失败：端口 34567 已被占用",
                    providers = providers,
                    busy = null,
                    actionMessage = actionMessage,
                    onRetry = {},
                    onImport = {},
                    onDelete = {},
                )
            }
        }
    }

    private fun render(name: String, dark: Boolean, providers: List<BackendProvider>, actionMessage: String? = null) {
        val scene = ImageComposeScene(width = 640, height = 620, density = Density(1f)) {
            CpTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                Preview(providers, actionMessage)
            }
        }
        try {
            val data = scene.render().encodeToData() ?: error("encode failed")
            val out = File("build-verify-beerr/preview").apply { mkdirs() }
            File(out, "$name.png").writeBytes(data.bytes)
        } finally {
            scene.close()
        }
    }

    @Test
    fun renderPreviews() {
        // 主场景：有已安装音源 + 删除按钮
        render("backend-error-providers-light", dark = false, providers = fakeProviders)
        render("backend-error-providers-dark", dark = true, providers = fakeProviders)
        // 无音源：只显示重试 / 导入按钮
        render("backend-error-empty-light", dark = false, providers = emptyList())
        // 操作反馈文案（成功色）
        render(
            "backend-error-message-light", dark = false,
            providers = fakeProviders, actionMessage = "已导入并自动激活 备用音源",
        )
    }
}
