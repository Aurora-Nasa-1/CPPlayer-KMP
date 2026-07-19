package cp.player.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cp.player.app.version.AppVersion
import cp.player.kmp.MusicBackend
import cp.player.app.platform.provideAppContext
import cp.player.kmp.util.initKmpAndroidContext
import cp.player.kmp.util.toPlatformContext
import cp.player.kmp.util.defaultSettingsStorage

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        initKmpAndroidContext(this)
        provideAppContext(this)
        MusicBackend.init(
            context = toPlatformContext(),
            settings = defaultSettingsStorage(),
        )
        AppModel.markInitialized()

        runCatching {
            val pkgInfo = packageManager.getPackageInfo(packageName, 0)
            AppVersion.init(
                versionName = pkgInfo.versionName ?: "1.0",
                versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                    pkgInfo.longVersionCode.toInt() else pkgInfo.versionCode,
                gitSha = BuildConfig.GIT_SHA,
            )
        }

        setContent { App() }
    }
}
