package cp.player.app

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntSize
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * 应用窗口图标（任务栏 / Alt-Tab）。
 *
 * 源头复用 Android 的启动图标（`app-android/.../mipmap-xxxhdpi/ic_launcher.png`，
 * 构建期拷贝为 `desktopMain/resources/cpplayer/icon.png`），保证两端外观一致。
 *
 * ⚠️ Compose 的 `Window(icon = ...)` 只认 `Painter`，没有 AWT Image 直通；
 * 这里包一层，用 `intrinsicSize` 告诉宿主原始尺寸、`onDraw` 按目标尺寸缩放绘制。
 * 资源缺失时回落 `painter=null`（调用方判空），绝不能挡启动。
 */
internal object AppWindowIcon {

    private const val RESOURCE = "/cpplayer/icon.png"

    /** 资源加载失败时为 null，窗口就没有图标——和修复前一样，不算回归。 */
    val painter: Painter? by lazy {
        runCatching {
            val image = ImageIO.read(AppWindowIcon::class.java.getResourceAsStream(RESOURCE))
                ?: return@lazy null
            val bitmap = image.toComposeImageBitmap()
            object : Painter() {
                override val intrinsicSize: Size =
                    Size(image.width.toFloat(), image.height.toFloat())

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
