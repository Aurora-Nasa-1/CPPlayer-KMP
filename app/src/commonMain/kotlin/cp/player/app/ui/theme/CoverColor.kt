package cp.player.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score

/**
 * 解码与量化的目标边长。
 *
 * 112 是「量化统计需要多少像素才够」与「解码要多快」的折中：112×112 ≈ 1.2 万像素，
 * 已经足够让 Celebi 量化出稳定的色板。对比一张 900×900 的封面是 81 万像素 —— 差 66 倍，
 * 这就是「卡不卡」的分水岭。
 */
internal const val CoverSampleSizePx: Int = 112

/**
 * 量化色数上限。
 *
 * ⚠️ **必须与 materialkolor 的 `themeColorOrNull` 默认值一致（128）**，
 * 否则同一张图会量化出不同色板，取色结果与系统 / 其他 MD3 应用对不上。
 */
private const val QuantizeMaxColors = 128

/**
 * 只要 1 个结果。`Score.score` 返回的是**按分数降序**的前 N 个，
 * 因此 desired 取 1 与取 4 的**第一个元素完全相同** —— 我们只要主色。
 */
private const val DesiredColors = 1

/** 种子色缓存容量。歌单里来回切歌时避免重复量化。 */
private const val CacheCapacity = 16

/**
 * 从封面位图提取种子色。
 *
 * 成本控制三层，缺一层都会卡：
 * 1. **解码** —— 调用方用 `size(112)` 让 Coil 只解一张缩略图（见 [loadCoverBitmap]），
 *    而不是先解 900×900 再缩。
 * 2. **量化** —— 只对已经降到 [CoverSampleSizePx] 量级的数组做 Celebi 量化。
 * 3. **线程** —— 本函数是纯 CPU 计算，调用方必须放在 `Dispatchers.Default` 上。
 *    **绝不能跑在 `MusicBackend.backendScope`（Main / 桌面 EDT）里。**
 *
 * @return 种子色；位图为空、量化失败、或图里没有任何达标的彩色时返回 null。
 */
internal fun extractSeedColor(image: ImageBitmap): Color? {
    if (image.width <= 0 || image.height <= 0) return null
    val pixels = IntArray(image.width * image.height)
    image.readPixels(pixels)
    return extractSeedColor(pixels, image.width, image.height)
}

/**
 * 从裸 ARGB 像素数组提取种子色。
 *
 * 单独抽出来是为了让**非 `ImageBitmap`** 的来源复用同一套逻辑：桌面壁纸走
 * `ImageIO` 降采样解码（见 `PlatformSeed.desktop.kt`），拿到的本来就是 `IntArray`，
 * 没必要先绕成一个 `ImageBitmap` 再拆回数组。
 *
 * ⚠️ 调用方必须保证数组**已经降采样过**（约 [CoverSampleSizePx] 量级）：
 * 直接丢一张 4K 壁纸的原始像素进来，光 `getRGB` 就已经分配了 3300 万个 int。
 *
 * ⚠️ **算法必须与 Material You 一致** —— `QuantizerCelebi` 量化 + `Score` 打分，
 * 这正是 Android Monet 干的事（也是 materialkolor `themeColorOrNull` 的内部实现）。
 * **不要**改成「优先取最鲜艳的色块」：在 Win11 默认壁纸（大面积蓝底 + 中间一朵花）上，
 * 那样会取到花的玫红 `#C03058`，而系统和其他 MD3 应用取的是底色的蓝。
 * 取色「和别人不一样」比「取得更鲜艳」严重得多。
 */
internal fun extractSeedColor(pixels: IntArray, width: Int, height: Int): Color? {
    if (width <= 0 || height <= 0 || pixels.size < width * height) return null
    val quantized = QuantizerCelebi.quantize(pixels, QuantizeMaxColors)
    // filter = true：剔除彩度极低（灰）的色块。灰阶图会得到空列表 ⇒ 返回 null ⇒ 主题回退。
    val ranked = Score.score(quantized, DesiredColors, null, true)
    return ranked.firstOrNull()?.let { Color(it) }
}

/**
 * 封面种子色缓存（key = 封面 URL）。
 *
 * 只缓存**成功**的结果：失败（无封面 / 网络抖动）不写缓存，下次切回该曲目会重试，
 * 避免一次瞬时失败把整首歌的配色永久钉死在回退色上。
 *
 * ⚠️ **无锁，靠调用方串行**：只在 AppModel 的取色协程（单条 `collect`）里访问 ——
 * 一条协程不会与自己并发，因此不需要额外同步。**不要从别处调用它。**
 */
internal object CoverSeedCache {
    private val entries = object : LinkedHashMap<String, Color>(CacheCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>?): Boolean =
            size > CacheCapacity
    }

    /** 命中返回缓存色；未命中返回 null（调用方自己去解码 + 量化，然后 [put]）。 */
    fun get(url: String): Color? = entries[url]

    /** 写入成功结果。传 null 无意义 —— 失败不缓存，见类 KDoc。 */
    fun put(url: String, color: Color) {
        entries[url] = color
    }

    /** 仅供测试与「清空缓存」设置项。 */
    fun clear() = entries.clear()

    internal fun cachedCount(): Int = entries.size
}
