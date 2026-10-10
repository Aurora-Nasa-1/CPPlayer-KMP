package cp.player.app

import android.app.Application
import cp.player.app.platform.installAndroidImageCacheLimit
import cp.player.core.MusicBackend
import cp.player.core.lyricpush.HiddenApiExemptions
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
        // 隐藏 API 豁免是**进程级、非永久**的，必须在主进程入口装一次
        // （ROM 更新 / 进程重建 / Shizuku binder 重建都会让它失效；防火墙调用前还会再装一次）。
        // 未使用超级岛的用户不受影响：这里只放宽本进程的反射策略，不触发任何 IPC。
        HiddenApiExemptions.install()
        // 必须在 backend（可能触发首次图片请求）之前：Coil 单例 factory 首次
        // get() 后固化，晚了 setSafe 直接抛。见 AndroidImageCacheTuning 的说明。
        installAndroidImageCacheLimit()
        backend
    }
}
