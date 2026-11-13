package cp.player.app

import android.app.Application
import cp.player.app.monitoring.AppMonitoring
import cp.player.app.platform.installAndroidImageCacheLimit
import cp.player.core.MusicBackend
import cp.player.core.lyricpush.HiddenApiExemptions
import cp.player.core.provider.typed.QuickJsWarmup
import cp.player.core.util.defaultSettingsStorage
import cp.player.core.util.initKmpAndroidContext
import cp.player.core.util.toPlatformContext
import cp.player.engine.RustEngine

class CPPlayerApplication : Application() {
    val backend: MusicBackend by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        initKmpAndroidContext(this)
        MusicBackend.init(
            context = toPlatformContext(),
            settings = defaultSettingsStorage(),
            applicationVersionName = BuildConfig.VERSION_NAME,
        )
    }

    override fun onCreate() {
        super.onCreate()
        AppMonitoring.init(this)
        // 隐藏 API 豁免是**进程级、非永久**的，必须在主进程入口装一次
        // （ROM 更新 / 进程重建 / Shizuku binder 重建都会让它失效；防火墙调用前还会再装一次）。
        // 未使用超级岛的用户不受影响：这里只放宽本进程的反射策略，不触发任何 IPC。
        HiddenApiExemptions.install()
        // 必须在 backend（可能触发首次图片请求）之前：Coil 单例 factory 首次
        // get() 后固化，晚了 setSafe 直接抛。见 AndroidImageCacheTuning 的说明。
        installAndroidImageCacheLimit()

        // JVM 启动后即刻派发多核并行加载：
        // 1. JNI 原生音频库提前完成 dlopen 与 context 绑定
        RustEngine.preloadAsync(this)
        // 2. JS QuickJS 原生运行库与环境预热
        QuickJsWarmup.warmupAsync()
        // 3. Provider 与后端曲库异步预热（避免主线程 Application.onCreate 阻塞，MainActivity 启动时安全衔接）
        Thread {
            backend
        }.apply {
            isDaemon = true
            name = "Backend-Prewarm"
        }.start()
    }
}
