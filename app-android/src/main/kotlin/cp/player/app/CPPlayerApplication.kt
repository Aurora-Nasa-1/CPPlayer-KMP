package cp.player.app

import android.app.Application
import cp.player.kmp.MusicBackend
import cp.player.kmp.util.defaultSettingsStorage
import cp.player.kmp.util.initKmpAndroidContext
import cp.player.kmp.util.toPlatformContext

class CPPlayerApplication : Application() {
    val backend: MusicBackend by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        initKmpAndroidContext(this)
        MusicBackend.init(
            context = toPlatformContext(),
            settings = defaultSettingsStorage(),
        )
    }

    override fun onCreate() {
        super.onCreate()
        backend
    }
}
