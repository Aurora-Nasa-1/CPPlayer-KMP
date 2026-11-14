package cp.player.app.ui.screen

import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 二维码保存的底层产物：必须是可解码的 PNG，且尺寸与调用方约定一致。
 *
 * 这个函数不读 `AppModel`，所以能在桌面测试里直接跑；真正写文件 / 相册的
 * 平台分支由 `saveImageToLocal` 自己负责。
 */
class QrCodePngTest {

    @Test
    fun `qr code renders to a valid png`() {
        val bytes = assertNotNull(qrCodePngBytes("https://example.com/cpplayer-login"))
        assertTrue(bytes.size > 100, "PNG 不应为空，实际 ${bytes.size} bytes")
        assertEquals(
            listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            bytes.take(8).map { it.toInt() and 0xFF },
            "保存内容必须是 PNG",
        )
        val image = Image.makeFromEncoded(bytes)
        assertEquals(720, image.width)
        assertEquals(720, image.height)
    }

    @Test
    fun `blank url does not produce an image`() {
        assertEquals(null, qrCodePngBytes("  "))
    }
}
