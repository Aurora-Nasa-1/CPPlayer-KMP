package cp.player.app.platform

import cp.player.app.ui.util.UiEvents

/**
 * 分享链接 = 写系统剪贴板，不经过 `java.awt.Toolkit` / `javax.swing.SwingUtilities`。
 *
 * Windows 走 [Win32Clipboard]（FFM 调 user32/kernel32），Linux 用 `xclip`，
 * macOS 用 `pbcopy`。Win32 剪贴板不要求 EDT，所以这里直接在调用线程里完成并提示。
 */
actual fun shareText(text: String) {
    val copied = if (Win32Clipboard.isSupported()) {
        Win32Clipboard.copy(text)
    } else {
        copyViaCommand(text)
    }
    UiEvents.notify(if (copied) "链接已复制到剪贴板，粘贴给朋友即可分享" else "复制失败")
}

private fun copyViaCommand(text: String): Boolean = runCatching {
    val os = System.getProperty("os.name").orEmpty()
    val command = if (os.startsWith("Mac OS", ignoreCase = true)) {
        listOf("pbcopy")
    } else {
        listOf("xclip", "-selection", "clipboard")
    }
    val process = ProcessBuilder(command).start()
    process.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    process.waitFor() == 0
}.getOrDefault(false)
