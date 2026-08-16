package cp.player.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import cp.player.app.version.AppVersion
import cp.player.kmp.MusicBackend
import cp.player.kmp.util.PlatformContext
import cp.player.kmp.util.defaultSettingsStorage

fun main() = application {
    ensureBackendInitialized()
    Window(
        onCloseRequest = ::exitApplication,
        title = "CPPlayer (KMP)",
    ) {
        App()
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
