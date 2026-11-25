package cp.player.app.ui.theme

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.SuccessResult
import coil3.toBitmap
import cp.player.app.ui.util.buildCoverImageRequest

/**
 * 桌面侧实现：使用 READ_ONLY 内存缓存策略 + 收敛分桶，避免取色位图额外占用 Coil MemoryCache。
 */
internal actual suspend fun loadCoverBitmap(model: String, sizePx: Int): ImageBitmap? {
    val ctx = PlatformContext.INSTANCE
    val request = buildCoverImageRequest(
        context = ctx,
        url = model,
        targetPx = sizePx,
        cropSquare = true,
        memoryCacheWrite = false,
    ) ?: return null
    val result = runCatching { SingletonImageLoader.get(ctx).execute(request) }.getOrNull()
    val image = (result as? SuccessResult)?.image ?: return null
    return runCatching { image.toBitmap().asComposeImageBitmap() }.getOrNull()
}
