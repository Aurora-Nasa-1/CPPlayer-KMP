package cp.player.app.ui.theme

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 以 [sizePx] 为目标边长解码封面位图；无封面 / 解码失败返回 null。
 *
 * **为什么必须 expect/actual**：Coil 3 的 `Image` → `ImageBitmap` 转换在两端返回类型
 * 不同（Android 是 `android.graphics.Bitmap`，JVM 是 `org.jetbrains.skia.Bitmap`），
 * commonMain 里无法统一。两端各自的 `actual` 都只有两三行。
 *
 * **为什么必须复用单例 ImageLoader**：自己 new 一个 `ImageLoader` 会再开一套线程池、
 * 再建一个指向同一目录的 `DiskCache` —— 两个实例指向同一目录会互相抢锁。走
 * `SingletonImageLoader` 顺带白拿 Coil 已有的内存 / 磁盘缓存，不会二次下载。
 *
 * @param model Coil 的 model，这里传封面 URL 字符串。
 * @param sizePx 目标边长。取色只需要 [CoverSampleSizePx]，**不要**为了「看清楚」调大：
 *   解码尺寸是这条链路上最贵的一步。
 */
internal expect suspend fun loadCoverBitmap(model: String, sizePx: Int): ImageBitmap?
