package cp.player.core.playback.drm

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 真实浏览器内核冒烟：用本机 Edge / Chrome / WebView2 Runtime 加载播放页，
 * 断言页面连入 WebSocket 并完成 Widevine EME 探测。
 *
 * 这是「宿主 ↔ 页面 ↔ CDM」链路的最后一环；没有可用内核的机器上跳过
 * （`cargo test` 之外的环境差异不应让 CI 红），真机验收见
 * `docs/dev/providers/DRM_VERIFICATION.md`。
 */
class WebViewDrmBrowserSmokeTest {

    private var process: Process? = null
    private var server: WebViewDrmServer? = null

    @AfterTest
    fun tearDown() {
        process?.destroy()
        process = null
        server?.stop()
        server = null
    }

    @Test
    fun `browser kernel reports widevine eme support`() = runBlocking {
        val executable = WebViewDrmBrowser.findExecutable()
        if (executable == null) {
            println("[WebViewDrmBrowserSmokeTest] 未发现 Edge/Chrome/WebView2 内核，跳过")
            return@runBlocking
        }
        println("[WebViewDrmBrowserSmokeTest] 使用内核: $executable")
        val s = WebViewDrmServer()
        s.start()
        server = s
        val profile = createTempDirectory("cpplayer-drm-smoke")
        process = WebViewDrmBrowser.launch(java.nio.file.Path.of(executable), s.pageUrl, profile)
        val ok = withTimeout(60_000) { s.awaitPageConnected(60_000) }
        assertTrue(ok, "浏览器内核未在超时内完成 EME 探测（Widevine CDM 缺失或页面未加载）")
    }
}
