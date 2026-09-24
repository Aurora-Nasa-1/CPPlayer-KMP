package cp.player.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import cp.player.app.platform.DesktopRenderTuning
import cp.player.app.version.AppVersion
import cp.player.kmp.MusicBackend
import cp.player.kmp.util.PlatformContext
import cp.player.kmp.util.defaultSettingsStorage

fun main() {
    // 必须最先执行：Skiko 在创建渲染器时首次读取 skiko.* 属性并固化，
    // 晚于这一步再写就不生效了。见 DesktopRenderTuning 的时序约束说明。
    DesktopRenderTuning.applyBeforeSkikoInit()

    application {
        ensureBackendInitialized()
        // 放在其它初始化之后立字据：这样音频原生库加载失败之类的无关崩溃
        // 不会被算到渲染后端头上，导致下次启动无端回退用户的设置。
        DesktopRenderTuning.beginStartupProbe()
        Window(
            onCloseRequest = ::exitApplication,
            title = "CPPlayer (KMP)",
        ) {
            StartupHealthProbe()
            App()
        }
    }
}

/**
 * 渲染后端安全模式的「成功出帧」信号。
 *
 * 能进到这个 composable，就说明 SkiaLayer 已创建、Composition 已跑起来——Skiko 初始化没炸。
 * 再等满 [DesktopRenderTuning.HEALTHY_FRAME_COUNT] 帧，确认渲染循环真的在转
 * （覆盖「后端能初始化但呈现循环卡死」这种更隐蔽的失败）。
 *
 * 只有走到这里才会删掉探测文件。若后端不可用，本协程要么永远等不到帧、
 * 要么整个进程直接崩掉，两种情况探测文件都会留到下次启动 → 自动回退为「自动」。
 */
@Composable
private fun StartupHealthProbe() {
    LaunchedEffect(Unit) {
        // 即使数帧失败也要宣告健康：走到这里已证明 Composition 起来了，
        // 此时若还留着探测文件，会把 Compose 自身的异常误记成「后端不可用」。
        runCatching { repeat(DesktopRenderTuning.HEALTHY_FRAME_COUNT) { withFrameNanos { } } }
        DesktopRenderTuning.markStartupHealthy()
    }
}

@Volatile private var backendReady = false

private fun ensureBackendInitialized() {
    if (backendReady) return
    synchronized(Any()) {
        if (backendReady) return
        MusicBackend.init(
            context = PlatformContext(),
            settings = defaultSettingsStorage(),
        )
        AppModel.markInitialized()
        AppVersion.init(
            versionName = BuildInfo.VERSION_NAME,
            versionCode = BuildInfo.VERSION_CODE,
            gitSha = BuildInfo.GIT_SHA,
            isDesktop = true,
            releaseChannel = System.getProperty("cp.player.releaseChannel", "stable"),
        )
        backendReady = true
    }
}
