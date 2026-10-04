package cp.player.app.platform

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache

/**
 * 桌面端图片内存缓存上限：64MB。
 *
 * CoverThumbnail 用 [cp.player.app.ui.theme.loadCoverBitmap] 请求时已按目标像素
 * 降采样，常驻屏幕上的封面（列表 48~64px 缩略图 + 播放页大图）远用不完 64MB。
 */
private const val DesktopImageMemoryCacheBytes = 64L * 1024 * 1024

/**
 * 给 Coil 单例装上**固定上限**的内存缓存。
 *
 * 为什么要装：Coil 3 的默认内存缓存按「可用系统内存的百分比」给配额 —— 在
 * 大内存 Windows 机器上上限动辄几个 GB，缓存只进不出直到配额耗尽，是 release 包
 * 内存占用（~780MB）里 JVM 堆之外的第二大来源。且桌面端缓存的是解码后的
 * skia 位图，**像素在 native 内存里**，不受 JVM `-Xmx` 约束 —— 堆参数管不到它，
 * 必须在缓存层收口。
 *
 * ⚠️ 时序：必须在**任何一次图片请求之前**调用（`main()` 最开头）。
 * `SingletonImageLoader` 首次 `get()` 后 factory 即固化，此时再 `setSafe` 会直接抛
 * IllegalStateException —— 晚了就静默维持默认配置，且只在第二次启动后才可能暴露。
 *
 * 只覆盖 `memoryCache`，磁盘缓存与其余默认行为保持 Coil 默认不动；
 * 设置页「清除图片缓存」（`PlatformActions.clearImageCache`）走 `SingletonImageLoader.get`
 * 拿到的就是这个实例，无需任何适配。
 *
 * 安卓端不调这里：Coil 在安卓上的默认配额跟随 App 堆（系统会按 `onTrimMemory`
 * 驱逐），现有默认行为已足够，刻意不与桌面共一套数值。
 */
fun installDesktopImageCacheLimit() {
    SingletonImageLoader.setSafe { ctx: PlatformContext ->
        ImageLoader.Builder(ctx)
            .memoryCache(
                // coil 3.x 的 MemoryCache.Builder 是无参构造，maxSizeBytes 走链式方法
                //（javap 核对过 coil-core-jvm 的 Builder 签名），不需要传 context。
                MemoryCache.Builder()
                    .maxSizeBytes(DesktopImageMemoryCacheBytes)
                    .build(),
            )
            .build()
    }
}
