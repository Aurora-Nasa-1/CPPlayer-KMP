package cp.player.app

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.Image

/**
 * 应用窗口图标（任务栏 / Alt-Tab）。
 *
 * 源头复用 Android 的启动图标（`app-android/.../mipmap-xxxhdpi/ic_launcher.png`，
 * 构建期拷贝为 `desktopMain/resources/cpplayer/icon.png`），保证两端外观一致。
 *
 * 采用 Skia 原生解码，摆脱对 AWT BufferedImage / ImageIO 的依赖，为 GraalVM Native Image
 * 与 Nucleus Tao 无 AWT 运行环境提供秒级直接呈现。
 * 资源缺失时回落 `painter=null`（调用方判空），绝不能挡启动。
 */
internal object AppWindowIcon {

    private const val RESOURCE = "/cpplayer/icon.png"

    /** 资源加载失败时为 null，窗口就没有图标——和修复前一样，不算回归。 */
    val painter: Painter? by lazy {
        runCatching {
            val bytes = AppWindowIcon::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() }
                ?: return@lazy null
            val skiaImage = Image.makeFromEncoded(bytes)
            val bitmap = skiaImage.toComposeImageBitmap()
            object : Painter() {
                override val intrinsicSize: Size =
                    Size(bitmap.width.toFloat(), bitmap.height.toFloat())

                override fun DrawScope.onDraw() {
                    drawImage(
                        bitmap,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    )
                }
            }
        }.getOrNull()
    }
}
