package cp.player.app.ui.theme

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap

/**
 * 桌面侧实现。
 *
 * `PlatformContext.INSTANCE` 是 Coil 3 在非 Android 平台上的平台上下文单例；
 * `coil3.toBitmap()` 在 JVM 上返回 `org.jetbrains.skia.Bitmap`，
 * 再经 `asComposeImageBitmap()` 转成 Compose 的 `ImageBitmap`。
 */
internal actual suspend fun loadCoverBitmap(model: String, sizePx: Int): ImageBitmap? {
    val ctx = PlatformContext.INSTANCE
    val request = ImageRequest.Builder(ctx)
        .data(model)
        .size(sizePx)
        .build()
    val result = runCatching { SingletonImageLoader.get(ctx).execute(request) }.getOrNull()
    val image = (result as? SuccessResult)?.image ?: return null
    return runCatching { image.toBitmap().asComposeImageBitmap() }.getOrNull()
}
