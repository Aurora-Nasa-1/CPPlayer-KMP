package cp.player.app.platform

import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JbrFileDialogBridgeTest {

    @Test
    fun `ensureModernDialogProperty sets system property on Windows`() {
        val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
        JbrFileDialogBridge.ensureModernDialogProperty()
        if (isWindows) {
            assertEquals("true", System.getProperty("sun.awt.windows.useCommonItemDialog"))
        }
    }

    @Test
    fun `configureZipFilter and configureDirectoryPicker run safely on FileDialog`() {
        if (GraphicsEnvironment.isHeadless()) {
            println("[skip] FileDialog construction requires a graphics environment")
            return
        }
        val dialog = FileDialog(null as Frame?, "Test Dialog", FileDialog.LOAD)
        // Ensure no exception is thrown
        JbrFileDialogBridge.configureZipFilter(dialog, "音源模块 (*.zip)", arrayOf("zip"))
        JbrFileDialogBridge.configureDirectoryPicker(dialog)

        val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
        val hasJbr = runCatching { Class.forName("com.jetbrains.desktop.JBRFileDialog") }.isSuccess
        if (isWindows && hasJbr) {
            assertTrue(JbrFileDialogBridge.isFolderPickerSupported, "在 Windows JBR 环境下应支持原生文件夹选择")
        }
    }
}
