package cp.player.app

import android.app.Application
import cp.player.core.MusicBackend
import cp.player.core.util.defaultSettingsStorage
import cp.player.core.util.initKmpAndroidContext
import cp.player.core.util.toPlatformContext

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
