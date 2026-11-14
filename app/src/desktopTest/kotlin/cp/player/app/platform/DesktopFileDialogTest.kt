package cp.player.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 桌面文件选择器回退路径的纯逻辑测试。
 *
 * Windows 的真对话框是模态 GUI，不能进单测；这里覆盖非 Windows 的命令拼装，
 * 顺带保证选择器链路不会再退回 `FileDialog` / `JFileChooser` 那套 AWT 实现。
 */
class DesktopFileDialogTest {

    @Test
    fun `linux directory picker asks zenity for a directory`() {
        val command = DesktopFileDialog.linuxCommand(
            DesktopFileDialog.ExternalKind.PICK_FOLDER, "选择目录", null,
        )
        assertEquals("zenity", command.first())
        assertTrue(command.contains("--directory"))
        assertTrue(command.contains("--title=选择目录"))
        assertTrue(command.none { it.contains("JFileChooser") || it.contains("FileDialog") })
    }

    @Test
    fun `linux zip picker carries the zip filter`() {
        val command = DesktopFileDialog.linuxCommand(
            DesktopFileDialog.ExternalKind.OPEN_FILE, "选择音源模块 (.zip)", null,
        )
        assertTrue(command.any { it.startsWith("--file-filter=") && it.endsWith("*.zip") })
    }

    @Test
    fun `linux save dialog keeps the default file name`() {
        val command = DesktopFileDialog.linuxCommand(
            DesktopFileDialog.ExternalKind.SAVE_FILE, "导出音源模块 (.zip)", "voice.zip",
        )
        assertTrue(command.contains("--save"))
        assertTrue(command.contains("--filename=voice.zip"))
    }

    @Test
    fun `mac picker escapes quotes in the AppleScript prompt`() {
        val command = DesktopFileDialog.macCommand(
            DesktopFileDialog.ExternalKind.PICK_FOLDER, "say \"hi\"", null,
        )
        assertEquals(listOf("osascript", "-e").size + 1, command.size)
        assertEquals("osascript", command[0])
        assertTrue(command[2].contains("choose folder"))
        assertTrue(command[2].contains("\\\"hi\\\""))
    }

    @Test
    fun `mac save dialog passes the default name`() {
        val command = DesktopFileDialog.macCommand(
            DesktopFileDialog.ExternalKind.SAVE_FILE, "导出", "voice.zip",
        )
        assertTrue(command[2].contains("choose file name"))
        assertTrue(command[2].contains("default name \"voice.zip\""))
    }
}
