package cp.player.app.ui.theme

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import cp.player.app.platform.ctxOrNull

/**
 * Android 侧实现。
 *
 * `ctxOrNull` 由 `PlatformFilePicker.android.kt` 的 `provideAppContext` 在
 * Application 启动时注入；拿不到就放弃取色（返回 null，主题回退到固定种子色），
 * 不做任何降级兜底 —— 取色失败不该影响播放。
 */
internal actual suspend fun loadCoverBitmap(model: String, sizePx: Int): ImageBitmap? {
    val ctx = ctxOrNull ?: return null
    val request = ImageRequest.Builder(ctx)
        .data(model)
        .size(sizePx)
        .build()
    val result = runCatching { SingletonImageLoader.get(ctx).execute(request) }.getOrNull()
    val image = (result as? SuccessResult)?.image ?: return null
    return runCatching { image.toBitmap().asImageBitmap() }.getOrNull()
}
