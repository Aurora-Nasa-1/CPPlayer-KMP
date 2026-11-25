package cp.player.app.platform

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import cp.player.app.ui.theme.loadCoverBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 托盘菜单左上角那张封面。
 *
 * ## 为什么不是每次重组都新解码
 *
 * 菜单项的 `Painter` 会参与 ComposeNativeTray 的**结构指纹**（`icon.hashCode()`），
 * 指纹一变就把整份原生菜单重建一遍 —— 而重建要跑 `ImageComposeScene`、编码 ICO、
 * 再走一次 `InsertMenuItemW`。所以这里的 `Painter` 必须按曲目**只实例化一次**，
 * 换歌才换实例；`equals` 也按 [trackId] 定义，避免同封面被判定成「变了」。
 *
 * ## 为什么同步拿不到就返回 null
 *
 * `Item(label = ..., icon = painter)` 是**组合期同步**取值，而封面走 Coil 是挂起的。
 * 因此这里采用「缓存命中就同步返回，未命中先返回 null、解码完再回调触发一次重组」
 * 的写法：首次打开菜单时若还没解码完，这一项暂时没有图标；解码完成会重建原生菜单，
 * 已经弹出的那一份弹窗要重新打开才会换成新菜单。解码只在**换歌**时发生一次，
 * 不随菜单开合重复。
 *
 * 尺寸按 [COVER_SCENE_PX] 取：菜单图标场景是 64px（`IconRenderProperties.forMenuItem()`），
 * 这里多给一些，确保 200% DPI 下 `64×64` 那一档 ICO 帧仍是原生分辨率、不被放大。
 */
internal object TrayCoverPainter {

    /** 场景 64px × 2 倍 DPI 上取整；与 `IconRenderProperties.forMenuItem()` 的 master 对齐。 */
    private const val COVER_SCENE_PX = 128

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 取 [trackId] 对应的封面；尚未解码完成时返回 `null`（调用方回落图标）。
     *
     * 结果按 [trackId] 缓存，重复调用不再产生 IO。
     */
    fun painterFor(trackId: String?, coverUrl: String?, onReady: () -> Unit): Painter? {
        if (trackId == null || coverUrl.isNullOrBlank()) return null
        synchronized(lock) {
            cache[trackId]?.let { return it }
            // 同一首歌只允许一条解码任务：菜单重组、封面 revision 变化都可能重复调用。
            if (!inFlight.add(trackId)) return null
        }
        scope.launch {
            val bitmap = runCatching { loadCoverBitmap(coverUrl, COVER_SCENE_PX) }.getOrNull()
            synchronized(lock) {
                if (bitmap != null) {
                    cache[trackId] = BitmapPainter(trackId, bitmap)
                    while (cache.size > MAX_CACHED_COVERS) {
                        cache.remove(cache.keys.first())
                    }
                }
                inFlight.remove(trackId)
            }
            if (bitmap != null) withContext(Dispatchers.Main) { onReady() }
        }
        return null
    }

    /**
     * 只保留最近几首：封面是整张位图，留太多等于把 Coil 的内存缓存又复制一份。
     * 换歌才写入，正常情况下条目数不会超过一首歌的生命周期。
     */
    private const val MAX_CACHED_COVERS = 4

    private val lock = Any()
    private val cache = LinkedHashMap<String, BitmapPainter>()
    private val inFlight = HashSet<String>()

    /** 保留最近 1 条托盘封面，释放其余位图缓存以配合最小化到托盘后的内存压缩。 */
    fun trimToCurrent() {
        synchronized(lock) {
            while (cache.size > 1) {
                cache.remove(cache.keys.first())
            }
        }
    }

    /**
     * 只承载一张已解码位图的 `Painter`。
     *
     * `equals` / `hashCode` 只认 [trackId]：菜单结构指纹读的是 `icon.hashCode()`，
     * 用位图内容算哈希既慢又会让「同一首歌重新解码出等价位图」被判成结构变化。
     */
    private class BitmapPainter(
        private val trackId: String,
        private val bitmap: ImageBitmap,
    ) : Painter() {

        override val intrinsicSize: Size =
            Size(bitmap.width.toFloat(), bitmap.height.toFloat())

        override fun DrawScope.onDraw() {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return
            // 等比铺满再居中裁切：封面不一定是正方形，直接拉伸会把人脸压扁。
            val scale = max(w / bitmap.width, h / bitmap.height)
            val dstW = (bitmap.width * scale).roundToInt()
            val dstH = (bitmap.height * scale).roundToInt()
            // Windows 11 的菜单图标是直角小方块；给封面一点圆角，和系统圆角语言一致。
            val clip = Path().apply {
                addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(min(w, h) * 0.18f)))
            }
            clipPath(clip) {
                drawImage(
                    image = bitmap,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(bitmap.width, bitmap.height),
                    dstOffset = IntOffset(
                        ((w - dstW) / 2f).roundToInt(),
                        ((h - dstH) / 2f).roundToInt(),
                    ),
                    dstSize = IntSize(dstW, dstH),
                )
            }
        }

        override fun equals(other: Any?): Boolean =
            other is BitmapPainter && other.trackId == trackId

        override fun hashCode(): Int = trackId.hashCode()
    }
}
