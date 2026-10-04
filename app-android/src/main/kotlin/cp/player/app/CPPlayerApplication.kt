package cp.player.app

import android.app.Application
import cp.player.app.platform.installAndroidImageCacheLimit
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
        // 必须在 backend（可能触发首次图片请求）之前：Coil 单例 factory 首次
        // get() 后固化，晚了 setSafe 直接抛。见 AndroidImageCacheTuning 的说明。
        installAndroidImageCacheLimit()
        backend
    }
}
