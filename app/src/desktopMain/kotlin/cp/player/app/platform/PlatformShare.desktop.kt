package cp.player.app.platform

import cp.player.app.ui.util.UiEvents
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.SwingUtilities

actual fun shareText(text: String) {
    try {
        // 剪贴板写入必须在 EDT 上执行
        SwingUtilities.invokeLater {
            try {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                UiEvents.notify("已复制到剪贴板")
            } catch (_: Exception) {
                UiEvents.notify("复制失败")
            }
        }
    } catch (_: Exception) {
        UiEvents.notify("复制失败")
    }
}
