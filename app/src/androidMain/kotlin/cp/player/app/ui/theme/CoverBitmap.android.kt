package cp.player.app.ui.theme

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.SingletonImageLoader
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import cp.player.app.platform.ctxOrNull
import cp.player.app.ui.util.buildCoverImageRequest

/**
 * Android 侧实现：取色需要 `readPixels`（必须 `allowHardware(false)`），且仅读取已有内存缓存、不回写。
 */
internal actual suspend fun loadCoverBitmap(model: String, sizePx: Int): ImageBitmap? {
    val ctx = ctxOrNull ?: return null
    val baseRequest = buildCoverImageRequest(
        context = ctx,
        url = model,
        targetPx = sizePx,
        cropSquare = true,
        memoryCacheWrite = false,
    ) ?: return null
    val request = baseRequest.newBuilder()
        .allowHardware(false)
        .build()
    val result = runCatching { SingletonImageLoader.get(ctx).execute(request) }.getOrNull()
    val image = (result as? SuccessResult)?.image ?: return null
    return runCatching { image.toBitmap().asImageBitmap() }.getOrNull()
}
