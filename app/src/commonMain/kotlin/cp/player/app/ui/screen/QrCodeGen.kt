package cp.player.app.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.qrose.ImageFormat
import io.github.alexzhirkevich.qrose.QrCodePainter
import io.github.alexzhirkevich.qrose.toByteArray
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

@Composable
fun QrCodeImage(url: String, modifier: Modifier = Modifier) {
    val painter = rememberQrCodePainter(url)
    Canvas(
        modifier
            .size(220.dp)
            .background(Color.White),
    ) {
        drawRect(Color.White, topLeft = androidx.compose.ui.geometry.Offset.Zero, size = size)
        with(painter) { draw(size) }
    }
}

/**
 * 把二维码 URL 在本地渲染成 PNG 字节，供「保存二维码」使用。
 *
 * 界面上的二维码本来就是 qrose 用 URL 本地出图（不依赖服务端 `qrimg`），所以保存
 * 也走同一条渲染路径：服务端没回图、或回图尺寸不一致时，保存下来的仍是用户屏幕上
 * 看到的那一张。渲染失败返回 null，由调用方按「保存失败」处理。
 *
 * @param sizePx 输出边长（像素）。默认 720，足够手机相册查看与二次扫描。
 */
fun qrCodePngBytes(url: String, sizePx: Int = 720): ByteArray? {
    if (url.isBlank()) return null
    val edge = sizePx.coerceIn(128, 2048)
    return runCatching {
        val painter = QrCodePainter(url)
        val bitmap = ImageBitmap(edge, edge)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = GraphicsCanvas(bitmap),
            size = Size(edge.toFloat(), edge.toFloat()),
        ) {
            drawRect(Color.White)
            with(painter) { draw(size) }
        }
        bitmap.toByteArray(ImageFormat.PNG)
    }.getOrNull()
}
