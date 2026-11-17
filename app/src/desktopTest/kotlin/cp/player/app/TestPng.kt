package cp.player.app

import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import org.jetbrains.skia.Image

/**
 * 截图断言用的 PNG 读取器，替代 `javax.imageio.ImageIO` / `BufferedImage`。
 *
 * 与生产代码一样走 Skia 解码（同一个解码器、同一套像素语义），保持
 * `getRGB` 的 0xAARRGGBB 排列与原先的 `BufferedImage.TYPE_INT_ARGB` 一致。
 * 这样 desktopTest 也不再引用 `java.desktop` 里的 AWT/ImageIO 类。
 */
class PngImage(val width: Int, val height: Int, private val pixels: IntArray) {

    fun getRGB(x: Int, y: Int): Int = pixels[y * width + x]

    /** 与 `BufferedImage.getRGB(x, y, w, h, out, offset, stride)` 同签名。 */
    fun getRGB(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        out: IntArray?,
        offset: Int,
        stride: Int,
    ): IntArray {
        val result = out ?: IntArray(offset + (h - 1) * stride + w)
        for (row in 0 until h) {
            System.arraycopy(pixels, (y + row) * width + x, result, offset + row * stride, w)
        }
        return result
    }
}

fun readPng(bytes: ByteArray): PngImage {
    val bitmap = Image.makeFromEncoded(bytes).toComposeImageBitmap()
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.readPixels(pixels)
    return PngImage(bitmap.width, bitmap.height, pixels)
}

fun readPng(file: File): PngImage = readPng(file.readBytes())
