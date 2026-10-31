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
import cp.player.app.ui.screen.OnboardCloudProviderItem
import cp.player.app.ui.screen.OnboardProviderRow
import cp.player.app.ui.screen.OnboardingContent
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import java.io.File
import kotlin.test.Test

/**
 * 首次使用引导页（含融合云端音源仓库安装的音源配置页）的离屏渲染版面核对。
 *
 * 融合云端推荐音源仓库与本地 .zip 导入后，包含：
 * 1. 欢迎页
 * 2. 音源页：未开启云端、云端展示推荐音源、云端安装中、已有音源
 * 3. 账号页
 */
class OnboardingPreviewTest {

    private val fakeProviders = listOf(
        OnboardProviderRow("网易云", "HTTP · v1.2.0", active = true),
        OnboardProviderRow("备用音源", "HTTP · v0.9.1", active = false),
    )

    private val fakeCloudProviders = listOf(
        OnboardCloudProviderItem(
            key = "provider:netease",
            name = "网易云音乐",
            version = "1.2.0",
            description = "官方维护推荐音源，支持云搜索与歌单同步",
            isInstalled = false,
            isCompatible = true,
        ),
        OnboardCloudProviderItem(
            key = "provider:custom",
            name = "社区备用音源",
            version = "0.9.1",
            description = "备用音频解析模块",
            isInstalled = false,
            isCompatible = true,
        ),
    )

    @Composable
    private fun Preview(
        dark: Boolean,
        hasProvider: Boolean,
        initialPage: Int,
        isNetworkAllowed: Boolean = false,
        isRefreshingCatalog: Boolean = false,
        cloudProviders: List<OnboardCloudProviderItem> = emptyList(),
    ) {
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
                isNetworkAllowed = isNetworkAllowed,
                isRefreshingCatalog = isRefreshingCatalog,
                cloudProviders = cloudProviders,
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
        isNetworkAllowed: Boolean = false,
        isRefreshingCatalog: Boolean = false,
        cloudProviders: List<OnboardCloudProviderItem> = emptyList(),
    ) {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CpTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                Preview(
                    dark = dark,
                    hasProvider = hasProvider,
                    initialPage = initialPage,
                    isNetworkAllowed = isNetworkAllowed,
                    isRefreshingCatalog = isRefreshingCatalog,
                    cloudProviders = cloudProviders,
                )
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

        // 音源页（第二步）：默认未开启云端状态
        render("onboard-provider-empty-light", dark = false, width = 640, height = 620, initialPage = 1, hasProvider = false)
        render("onboard-provider-empty-dark", dark = true, width = 640, height = 620, initialPage = 1, hasProvider = false)

        // 音源页：已开启云端并展示推荐音源
        render(
            "onboard-provider-cloud-list-light",
            dark = false,
            width = 640,
            height = 680,
            initialPage = 1,
            hasProvider = false,
            isNetworkAllowed = true,
            cloudProviders = fakeCloudProviders,
        )
        render(
            "onboard-provider-cloud-list-dark",
            dark = true,
            width = 640,
            height = 680,
            initialPage = 1,
            hasProvider = false,
            isNetworkAllowed = true,
            cloudProviders = fakeCloudProviders,
        )

        // 音源页：已开启云端且某个音源下载安装中
        val downloadingProviders = fakeCloudProviders.mapIndexed { idx, p ->
            if (idx == 0) p.copy(isDownloading = true, downloadProgress = 0.65f) else p
        }
        render(
            "onboard-provider-cloud-downloading-light",
            dark = false,
            width = 640,
            height = 680,
            initialPage = 1,
            hasProvider = false,
            isNetworkAllowed = true,
            cloudProviders = downloadingProviders,
        )

        // 音源页：已有已加载音源
        render("onboard-provider-loaded-light", dark = false, width = 640, height = 620, initialPage = 1, hasProvider = true)

        // 手机宽度测试：未开启云端与开启云端
        render("onboard-provider-empty-mobile-light", dark = false, width = 400, height = 780, initialPage = 1, hasProvider = false)
        render(
            "onboard-provider-cloud-mobile-light",
            dark = false,
            width = 400,
            height = 780,
            initialPage = 1,
            hasProvider = false,
            isNetworkAllowed = true,
            cloudProviders = fakeCloudProviders,
        )

        // 账号页（第三步）
        render("onboard-account-mobile-light", dark = false, width = 400, height = 780, initialPage = 2, hasProvider = true)
    }
}

