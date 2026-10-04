package cp.player.app.platform

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache

/**
 * 安卓端图片内存缓存上限：64MB。
 *
 * 与桌面端 [cp.player.app.platform.installDesktopImageCacheLimit] 同一套收口逻辑、
 * 同一个数值：Coil 的默认配额是「堆的百分比」，随设备内存浮动（低端机 ~48MB、
 * 大内存机 ~128MB+），行为不可预期；固定 64MB 与典型 App 堆（~256MB）的默认比例
 * 接近，又给「封面瀑布流 + 大图预览」留了足够余量。
 *
 * 系统的 `onTrimMemory` 驱逐仍照常生效（Coil 内建），这是**上限**收紧而不是替换。
 */
private const val ImageMemoryCacheBytes = 64L * 1024 * 1024

/**
 * 给 Coil 单例装上**固定上限**的内存缓存。
 *
 * ⚠️ 时序：必须在**任何一次图片请求之前**调用（`CPPlayerApplication.onCreate`）。
 * `SingletonImageLoader` 首次 `get()` 后 factory 即固化，此时再 `setSafe` 会直接抛
 * IllegalStateException。Application.onCreate 早于任何 Activity 渲染，是安全锚点。
 *
 * ⚠️ 位置：放在 `:app` 的 androidMain 而不是 `:app-android` 模块 —— coil 依赖
 * 声明在 `:app`（`implementation`，不传递），app-android 模块解析不了 coil3 类，
 * 而 app 的 androidMain 本来就在用它（`PlatformActions.android.kt`）。
 *
 * 安卓侧另外两个既有入口不受影响：设置页「清除图片缓存」与封面取色
 * （`CoverBitmap.android.kt`）都走 `SingletonImageLoader.get`，拿到的就是这个实例。
 */
fun installAndroidImageCacheLimit() {
    SingletonImageLoader.setSafe { ctx: PlatformContext ->
        ImageLoader.Builder(ctx)
            .memoryCache(
                // Coil 3 的 MemoryCache.Builder 是无参构造（上下文在
                // ImageLoader.Builder 上），与桌面端同款写法，见桌面侧注释。
                MemoryCache.Builder()
                    .maxSizeBytes(ImageMemoryCacheBytes)
                    .build(),
            )
            .build()
    }
}
