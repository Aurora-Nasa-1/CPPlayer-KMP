package cp.player.app

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertNotNull

class AppWindowIconTest {

    @Test
    fun appWindowIcon_initializesWithoutPlatformGraphicsException() {
        val painter = AppWindowIcon.painter
        // 允许资源缺失返回 null，但不允许抛出 No Compose UI graphics implementation is registered
        // 如果能够成功获取或正常返回 null，说明未受未初始化注册表异常阻断
        val bitmap = ImageBitmap(16, 16)
        assertNotNull(bitmap)
    }
}
